```java
package com.privatevcam.fms;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class FmsHook implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.gfd.fms.binhthuan"; 
    private static final String FAKE_IMAGE_PATH = "/sdcard/FMS_Fake/fake.jpg";
    private static final String TAG = "FMS_AUTO_RESIZE_HOOK";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        XposedBridge.log("[" + TAG + "] Đã liên kết thành công hệ thống Auto-Resize ảnh với FMS!");

        // 1. HOOK THƯ VIỆN "com.otaliastudios.cameraview" (Chặn luồng xử lý ảnh chụp)
        try {
            Class<?> pictureResultClass = XposedHelpers.findClass(
                "com.otaliastudios.cameraview.PictureResult", 
                lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(
                pictureResultClass, 
                "getData", 
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        byte[] originalBytes = (byte[]) param.getResult();
                        if (originalBytes != null) {
                            // Đo kích thước ảnh gốc mà app mong muốn
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.length, options);
                            int targetW = options.outWidth;
                            int targetH = options.outHeight;

                            XposedBridge.log("[" + TAG + "] Cameraview yêu cầu ảnh kích thước: " + targetW + "x" + targetH);

                            // Resize ảnh fake về đúng kích thước chuẩn của app
                            byte[] fakeData = getFakeImageBytes(targetW, targetH);
                            if (fakeData != null) {
                                param.setResult(fakeData);
                                XposedBridge.log("[" + TAG + "] Đã tráo và tự động scale ảnh thành công!");
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Không thể cài đặt hook Cameraview: " + t.getMessage());
        }

        // 2. HOOK BỘ GIẢI MÃ MẢNG BYTE HỆ THỐNG (Dành cho việc dựng ảnh xem trước và lưu trữ)
        try {
            XposedHelpers.findAndHookMethod(
                BitmapFactory.class, 
                "decodeByteArray", 
                byte[].class, 
                int.class, 
                int.class, 
                BitmapFactory.Options.class, 
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        byte[] data = (byte[]) param.args[0];
                        if (data != null && data.length > 20480) { // Chỉ xử lý các file ảnh lớn hơn 20KB
                            // Đo kích thước ảnh thật
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(data, 0, data.length, options);
                            int targetW = options.outWidth;
                            int targetH = options.outHeight;

                            byte[] fakeData = getFakeImageBytes(targetW, targetH);
                            if (fakeData != null) {
                                param.args[0] = fakeData;
                                param.args[1] = 0;
                                param.args[2] = fakeData.length;
                                XposedBridge.log("[" + TAG + "] Đã ép nạp ảnh fake khớp tỉ lệ " + targetW + "x" + targetH);
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Lỗi thiết lập giải mã byte: " + t.getMessage());
        }

        // 3. HOOK CAMERA 1 API TRUYỀN THỐNG (Dự phòng)
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.Camera", 
                lpparam.classLoader, 
                "takePicture", 
                Camera.ShutterCallback.class, 
                Camera.PictureCallback.class, 
                Camera.PictureCallback.class, 
                Camera.PictureCallback.class, 
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        final Camera.PictureCallback originalJpegCallback = (Camera.PictureCallback) param.args[3];
                        if (originalJpegCallback != null) {
                            param.args[3] = new Camera.PictureCallback() {
                                @Override
                                public void onPictureTaken(byte[] data, Camera camera) {
                                    int targetW = 1280;
                                    int targetH = 960;
                                    try {
                                        Camera.Parameters parameters = camera.getParameters();
                                        Camera.Size size = parameters.getPictureSize();
                                        targetW = size.width;
                                        targetH = size.height;
                                    } catch (Exception ignored) {}

                                    byte[] fakeData = getFakeImageBytes(targetW, targetH);
                                    if (fakeData != null) {
                                        originalJpegCallback.onPictureTaken(fakeData, camera);
                                    } else {
                                        originalJpegCallback.onPictureTaken(data, camera);
                                    }
                                }
                            };
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] App không sử dụng Camera 1: " + t.getMessage());
        }
    }

    /**
     * Đọc ảnh fake.jpg và tự động co giãn về kích thước chuẩn của ứng dụng yêu cầu
     */
    private byte[] getFakeImageBytes(int targetWidth, int targetHeight) {
        File file = new File(FAKE_IMAGE_PATH);
        if (!file.exists()) {
            return null;
        }
        
        try {
            // Nạp ảnh fake gốc từ bộ nhớ máy
            Bitmap originalBitmap = BitmapFactory.decodeFile(FAKE_IMAGE_PATH);
            if (originalBitmap == null) {
                return null;
            }

            // Nếu kích thước mục tiêu hợp lệ, tiến hành co giãn (Resize) ảnh
            Bitmap finalBitmap;
            if (targetWidth > 0 && targetHeight > 0) {
                finalBitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true);
            } else {
                finalBitmap = originalBitmap;
            }

            // Nén ảnh lại thành định dạng JPG để trả về cho ứng dụng
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos); // Giữ chất lượng ảnh cao 95%
            byte[] resultBytes = baos.toByteArray();

            // Thu hồi bộ nhớ RAM tạm thời để tránh tràn RAM
            if (finalBitmap != originalBitmap) {
                finalBitmap.recycle();
            }
            originalBitmap.recycle();

            return resultBytes;
        } catch (Exception e) {
            XposedBridge.log("[" + TAG + "] Lỗi trong quá trình xử lý Resize ảnh: " + e.getMessage());
            return null;
        }
    }
}

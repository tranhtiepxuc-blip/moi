package com.privatevcam.fms;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class FmsHook implements IXposedHookLoadPackage {

    // PHÁT HIỆN TỪ DECOMPILE: Tên gói chính xác của FMS Bình Thuận
    private static final String TARGET_PACKAGE = "com.gfd.fms.binhthuan"; 
    private static final String FAKE_IMAGE_PATH = "/sdcard/FMS_Fake/fake.jpg";
    private static final String TAG = "FMS_DECOMPILED_HOOK";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        XposedBridge.log("[" + TAG + "] Đã liên kết thành công với FMS Bình Thuận mục tiêu: " + TARGET_PACKAGE);

        // 1. HOOK THƯ VIỆN "com.otaliastudios.cameraview" (Phát hiện từ ảnh decompile)
        try {
            Class<?> pictureResultClass = XposedHelpers.findClass(
                "com.otaliastudios.cameraview.PictureResult", 
                lpparam.classLoader
            );

            // Hook vào hàm lấy dữ liệu ảnh thô (byte[]) của thư viện Cameraview
            XposedHelpers.findAndHookMethod(
                pictureResultClass, 
                "getData", 
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("[" + TAG + "] Cameraview đang xuất dữ liệu ảnh chụp!");
                        byte[] fakeData = getFakeImageBytes();
                        if (fakeData != null) {
                            param.setResult(fakeData); // Ép trả về mảng byte của ảnh fake.jpg
                            XposedBridge.log("[" + TAG + "] Đã tráo đổi dữ liệu PictureResult thành công!");
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Không tìm thấy class Cameraview hoặc lỗi hook: " + t.getMessage());
        }

        // 2. HOOK BỘ GIẢI MÃ MẢNG BYTE HỆ THỐNG (BitmapFactory.decodeByteArray)
        // Đây là chốt chặn vạn năng phòng hờ cho cả CameraX lẫn Camera2
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
                        // Lọc bỏ các icon/giao diện nhỏ bằng cách chỉ bẫy các file dữ liệu lớn hơn 20KB (20480 bytes)
                        if (data != null && data.length > 20480) {
                            XposedBridge.log("[" + TAG + "] Phát hiện app đang dựng ảnh chụp từ mảng byte (Size: " + data.length + " bytes)");
                            byte[] fakeData = getFakeImageBytes();
                            if (fakeData != null) {
                                param.args[0] = fakeData;          // Thay thế dữ liệu thô bằng ảnh fake
                                param.args[1] = 0;                 // Thiết lập lại điểm bắt đầu
                                param.args[2] = fakeData.length;   // Thiết lập lại độ dài dữ liệu mới
                                XposedBridge.log("[" + TAG + "] Đã ép nạp dữ liệu ảnh fake vào bộ giải mã!");
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Lỗi thiết lập chốt chặn decodeByteArray: " + t.getMessage());
        }

        // 3. HOOK CAMERA 1 API TRUYỀN THỐNG (Dùng dự phòng)
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
                        XposedBridge.log("[" + TAG + "] Camera 1 API truyền thống đang được gọi!");
                        final Camera.PictureCallback originalJpegCallback = (Camera.PictureCallback) param.args[3];
                        if (originalJpegCallback != null) {
                            param.args[3] = new Camera.PictureCallback() {
                                @Override
                                public void onPictureTaken(byte[] data, Camera camera) {
                                    byte[] fakeData = getFakeImageBytes();
                                    if (fakeData != null) {
                                        originalJpegCallback.onPictureTaken(fakeData, camera);
                                        XposedBridge.log("[" + TAG + "] Đã ép luồng Camera 1 trả về ảnh fake!");
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
            XposedBridge.log("[" + TAG + "] Ứng dụng không sử dụng Camera 1 API: " + t.getMessage());
        }

        // 4. HOOK ĐƯỜNG DẪN ĐỌC FILE LƯU TẠM
        try {
            XposedHelpers.findAndHookMethod(
                BitmapFactory.class, 
                "decodeFile", 
                String.class, 
                BitmapFactory.Options.class, 
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String filePath = (String) param.args[0];
                        if (filePath != null) {
                            String lowerPath = filePath.toLowerCase();
                            if (lowerPath.contains("fms") || lowerPath.contains("camera") || lowerPath.contains("cache") || lowerPath.contains(".jpg") || lowerPath.contains(".jpeg")) {
                                File fakeFile = new File(FAKE_IMAGE_PATH);
                                if (fakeFile.exists() && !filePath.equals(FAKE_IMAGE_PATH)) {
                                    param.args[0] = FAKE_IMAGE_PATH;
                                    XposedBridge.log("[" + TAG + "] Đã chuyển hướng nạp file ảnh chụp sang fake.jpg!");
                                }
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Lỗi thiết lập chốt chặn decodeFile: " + t.getMessage());
        }
    }

    private byte[] getFakeImageBytes() {
        File file = new File(FAKE_IMAGE_PATH);
        if (!file.exists()) {
            return null;
        }
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            int offset = 0;
            int numRead;
            while (offset < bytes.length && (numRead = fis.read(bytes, offset, bytes.length - offset)) >= 0) {
                offset += numRead;
            }
        } catch (IOException e) {
            return null;
        }
        return bytes;
    }
}

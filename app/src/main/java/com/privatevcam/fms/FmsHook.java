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

    // Tên gói của ứng dụng FMS Bình Thuận mục tiêu
    private static final String TARGET_PACKAGE = "com.fms.binhthuan"; 
    
    // Đường dẫn chứa ảnh giả lập trên điện thoại của bạn
    private static final String FAKE_IMAGE_PATH = "/sdcard/FMS_Fake/fake.jpg";
    private static final String TAG = "FMS_HOOK_PRIVATE";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        XposedBridge.log("[" + TAG + "] Đã nhắm mục tiêu ứng dụng FMS Bình Thuận thành công!");

        // HOOK CAMERA DỰA TRÊN CAMERA 1 API
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
                        XposedBridge.log("[" + TAG + "] Phát hiện ứng dụng đang chụp ảnh!");
                        final Camera.PictureCallback originalJpegCallback = (Camera.PictureCallback) param.args[3];
                        
                        if (originalJpegCallback != null) {
                            param.args[3] = new Camera.PictureCallback() {
                                @Override
                                public void onPictureTaken(byte[] data, Camera camera) {
                                    byte[] fakeData = getFakeImageBytes();
                                    if (fakeData != null) {
                                        XposedBridge.log("[" + TAG + "] Thay ảnh chụp thực địa bằng ảnh giả lập!");
                                        originalJpegCallback.onPictureTaken(fakeData, camera);
                                    } else {
                                        XposedBridge.log("[" + TAG + "] Thư mục rỗng hoặc lỗi đọc file, dùng ảnh thật!");
                                        originalJpegCallback.onPictureTaken(data, camera);
                                    }
                                }
                            };
                        }
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log("[" + TAG + "] Không thể Hook Camera API: " + e.getMessage());
        }

        // HOOK BITMAP FACTORY (Bẫy nạp ảnh từ đường dẫn tạm thời)
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
                        if (filePath != null && (filePath.contains("FMS") || filePath.contains("cache") || filePath.contains("Camera"))) {
                            File fakeFile = new File(FAKE_IMAGE_PATH);
                            if (fakeFile.exists()) {
                                param.args[0] = FAKE_IMAGE_PATH;
                                XposedBridge.log("[" + TAG + "] Chuyển hướng luồng giải nén ảnh sang ảnh fake!");
                            }
                        }
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log("[" + TAG + "] Không thể Hook BitmapFactory: " + e.getMessage());
        }
    }

    private byte[] getFakeImageBytes() {
        File file = new File(FAKE_IMAGE_PATH);
        if (!file.exists()) {
            Log.e(TAG, "Không tìm thấy file ảnh tại: " + FAKE_IMAGE_PATH);
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

package com.example.singlevm.engine;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/* JADX INFO: loaded from: classes3.dex */
public final class Android7GuestEngineAdapter implements EngineAdapter {
    public static final String CORE_LIBRARY_NAME = "libqemu-system-aarch64.so";
    public static final String ID = "android7-qemu";
    public static final String IMAGE_DIRECTORY = "android7";
    public static final String[] REQUIRED_IMAGES = {"kernel", "ramdisk.img", "system.img"};

    @Override // com.example.singlevm.engine.EngineAdapter
    public String id() {
        return ID;
    }

    @Override // com.example.singlevm.engine.EngineAdapter
    public String displayName() {
        return "Android 7.0 ARM32 게스트";
    }

    @Override // com.example.singlevm.engine.EngineAdapter
    public EngineReadiness inspect(Context context, File vmRoot) {
        String summary;
        File imageDir = new File(vmRoot, IMAGE_DIRECTORY);
        List<String> missing = new ArrayList<>();
        StringBuilder details = new StringBuilder();
        details.append("이미지 폴더: ").append(imageDir.getAbsolutePath()).append('\n');
        String[] strArr = REQUIRED_IMAGES;
        int length = strArr.length;
        int i = 0;
        while (true) {
            if (i >= length) {
                break;
            }
            String name = strArr[i];
            File image = new File(imageDir, name);
            boolean present = image.isFile() && image.length() > 0;
            details.append("- ").append(name).append(": ").append(present ? humanSize(image.length()) : "없음").append('\n');
            if (!present) {
                missing.add(name);
            }
            i++;
        }
        File userdata = new File(imageDir, "userdata.img");
        details.append("- userdata.img: ").append((!userdata.isFile() || userdata.length() <= 0) ? "없음 (첫 부팅 시 생성 예정)" : humanSize(userdata.length())).append('\n');
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File qemuBridge = new File(nativeDir, "libsinglevm_qemu.so");
        boolean bridgeReady = qemuBridge.isFile() && qemuBridge.length() > 0;
        details.append("- QEMU JNI 브리지: ").append(bridgeReady ? "내장됨" : "없음").append('\n');
        if (!bridgeReady) {
            missing.add("libsinglevm_qemu.so");
        }
        File qemuCore = new File(nativeDir, CORE_LIBRARY_NAME);
        boolean coreReady = qemuCore.isFile() && qemuCore.length() > 0;
        details.append("- ARM QEMU/TCG 코어: ").append(coreReady ? "내장됨" : "없음").append('\n');
        details.append("- 실행 방식: ARM32 전체 시스템 소프트웨어 에뮬레이션");
        if (!coreReady) {
            missing.add(CORE_LIBRARY_NAME);
        }
        boolean ready = missing.isEmpty();
        if (ready) {
            summary = "부팅 준비 완료";
        } else {
            summary = "준비 필요: " + join(missing);
        }
        return new EngineReadiness(ready, summary, details.toString());
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(value);
        }
        return out.toString();
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1073741824) {
            return String.format(Locale.US, "%.1f GB", Double.valueOf(bytes / 1.073741824E9d));
        }
        if (bytes >= 1048576) {
            return String.format(Locale.US, "%.1f MB", Double.valueOf(bytes / 1048576.0d));
        }
        return bytes + " bytes";
    }
}

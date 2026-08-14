package com.example.singlevm.engine;

import android.content.Context;
import java.io.File;

/* JADX INFO: loaded from: classes3.dex */
public final class LegacyArmEngineAdapter implements EngineAdapter {
    public static final String ID = "legacy-arm32";

    @Override // com.example.singlevm.engine.EngineAdapter
    public String id() {
        return ID;
    }

    @Override // com.example.singlevm.engine.EngineAdapter
    public String displayName() {
        return "기존 ARM32 시험 런타임";
    }

    @Override // com.example.singlevm.engine.EngineAdapter
    public EngineReadiness inspect(Context context, File vmRoot) {
        boolean ready;
        try {
            System.loadLibrary("singlevm_runtime");
            ready = true;
        } catch (UnsatisfiedLinkError e) {
            ready = false;
        }
        return new EngineReadiness(ready, ready ? "진단 실행 가능" : "네이티브 런타임 없음", "개별 ARM32 ELF/JNI 호출을 분석하는 기존 시험 엔진입니다.\n완전한 Android 프레임워크나 Android 7 시스템을 제공하지 않습니다.");
    }
}

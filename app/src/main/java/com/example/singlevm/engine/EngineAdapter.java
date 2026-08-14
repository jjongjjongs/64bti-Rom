package com.example.singlevm.engine;

import android.content.Context;
import java.io.File;

/* JADX INFO: loaded from: classes3.dex */
public interface EngineAdapter {
    String displayName();

    String id();

    EngineReadiness inspect(Context context, File file);
}

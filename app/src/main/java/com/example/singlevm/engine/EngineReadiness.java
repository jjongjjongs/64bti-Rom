package com.example.singlevm.engine;

/* JADX INFO: loaded from: classes3.dex */
public final class EngineReadiness {
    public final String details;
    public final boolean ready;
    public final String summary;

    public EngineReadiness(boolean ready, String summary, String details) {
        this.ready = ready;
        this.summary = summary;
        this.details = details;
    }
}

package com.example.singlevm.engine;

/* JADX INFO: loaded from: classes3.dex */
public final class EngineRegistry {
    private static final EngineAdapter ANDROID7 = new Android7GuestEngineAdapter();
    private static final EngineAdapter LEGACY = new LegacyArmEngineAdapter();

    private EngineRegistry() {
    }

    public static EngineAdapter primary() {
        return ANDROID7;
    }

    public static EngineAdapter legacy() {
        return LEGACY;
    }

    public static EngineAdapter byId(String id) {
        return LegacyArmEngineAdapter.ID.equals(id) ? LEGACY : ANDROID7;
    }
}

package org.libsdl.app;

/* JADX INFO: compiled from: SDLActivity.java */
/* JADX INFO: loaded from: classes4.dex */
interface SDLClipboardHandler {
    String clipboardGetText();

    boolean clipboardHasText();

    void clipboardSetText(String str);
}

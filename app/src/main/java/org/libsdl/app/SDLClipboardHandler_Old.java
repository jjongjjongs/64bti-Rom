package org.libsdl.app;

import android.text.ClipboardManager;

/* JADX INFO: compiled from: SDLActivity.java */
/* JADX INFO: loaded from: classes4.dex */
class SDLClipboardHandler_Old implements SDLClipboardHandler {
    protected ClipboardManager mClipMgrOld = (ClipboardManager) SDL.getContext().getSystemService("clipboard");

    SDLClipboardHandler_Old() {
    }

    @Override // org.libsdl.app.SDLClipboardHandler
    public boolean clipboardHasText() {
        return this.mClipMgrOld.hasText();
    }

    @Override // org.libsdl.app.SDLClipboardHandler
    public String clipboardGetText() {
        CharSequence text = this.mClipMgrOld.getText();
        if (text != null) {
            return text.toString();
        }
        return null;
    }

    @Override // org.libsdl.app.SDLClipboardHandler
    public void clipboardSetText(String string) {
        this.mClipMgrOld.setText(string);
    }
}

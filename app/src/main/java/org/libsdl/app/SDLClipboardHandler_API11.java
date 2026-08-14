package org.libsdl.app;

import android.content.ClipboardManager;

/* JADX INFO: compiled from: SDLActivity.java */
/* JADX INFO: loaded from: classes4.dex */
class SDLClipboardHandler_API11 implements SDLClipboardHandler, ClipboardManager.OnPrimaryClipChangedListener {
    protected ClipboardManager mClipMgr = (ClipboardManager) SDL.getContext().getSystemService("clipboard");

    SDLClipboardHandler_API11() {
        this.mClipMgr.addPrimaryClipChangedListener(this);
    }

    @Override // org.libsdl.app.SDLClipboardHandler
    public boolean clipboardHasText() {
        return this.mClipMgr.hasText();
    }

    @Override // org.libsdl.app.SDLClipboardHandler
    public String clipboardGetText() {
        CharSequence text = this.mClipMgr.getText();
        if (text != null) {
            return text.toString();
        }
        return null;
    }

    @Override // org.libsdl.app.SDLClipboardHandler
    public void clipboardSetText(String string) {
        this.mClipMgr.removePrimaryClipChangedListener(this);
        this.mClipMgr.setText(string);
        this.mClipMgr.addPrimaryClipChangedListener(this);
    }

    @Override // android.content.ClipboardManager.OnPrimaryClipChangedListener
    public void onPrimaryClipChanged() {
        SDLActivity.onNativeClipboardChanged();
    }
}

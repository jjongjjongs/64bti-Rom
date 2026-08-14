package org.libsdl.app;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;

/* JADX INFO: compiled from: SDLActivity.java */
/* JADX INFO: loaded from: classes4.dex */
class SDLInputConnection extends BaseInputConnection {
    public static native void nativeCommitText(String str, int i);

    public native void nativeGenerateScancodeForUnichar(char c);

    public native void nativeSetComposingText(String str, int i);

    public SDLInputConnection(View targetView, boolean fullEditor) {
        super(targetView, fullEditor);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean sendKeyEvent(KeyEvent event) {
        String imeHide;
        if (event.getKeyCode() == 66 && (imeHide = SDLActivity.nativeGetHint("SDL_RETURN_KEY_HIDES_IME")) != null && imeHide.equals("1")) {
            Context c = SDL.getContext();
            if (c instanceof SDLActivity) {
                SDLActivity activity = (SDLActivity) c;
                activity.sendCommand(3, null);
                return true;
            }
        }
        return super.sendKeyEvent(event);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean commitText(CharSequence text, int newCursorPosition) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            nativeGenerateScancodeForUnichar(c);
        }
        nativeCommitText(text.toString(), newCursorPosition);
        return super.commitText(text, newCursorPosition);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        nativeSetComposingText(text.toString(), newCursorPosition);
        return super.setComposingText(text, newCursorPosition);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        if (beforeLength > 0 && afterLength == 0) {
            boolean ret = true;
            while (true) {
                int beforeLength2 = beforeLength - 1;
                if (beforeLength > 0) {
                    boolean z = false;
                    boolean ret_key = sendKeyEvent(new KeyEvent(0, 67)) && sendKeyEvent(new KeyEvent(1, 67));
                    if (ret && ret_key) {
                        z = true;
                    }
                    ret = z;
                    beforeLength = beforeLength2;
                } else {
                    return ret;
                }
            }
        } else {
            boolean ret2 = super.deleteSurroundingText(beforeLength, afterLength);
            return ret2;
        }
    }
}

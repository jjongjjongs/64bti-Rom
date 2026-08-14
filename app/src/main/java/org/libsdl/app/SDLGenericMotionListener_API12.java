package org.libsdl.app;

import android.view.MotionEvent;
import android.view.View;

/* JADX INFO: compiled from: SDLControllerManager.java */
/* JADX INFO: loaded from: classes4.dex */
class SDLGenericMotionListener_API12 implements View.OnGenericMotionListener {
    SDLGenericMotionListener_API12() {
    }

    @Override // android.view.View.OnGenericMotionListener
    public boolean onGenericMotion(View v, MotionEvent event) {
        switch (event.getSource()) {
            case 513:
            case 1025:
            case 16777232:
                return SDLControllerManager.handleJoystickMotionEvent(event);
            case 8194:
                if (SDLActivity.mSeparateMouseAndTouch) {
                    int action = event.getActionMasked();
                    switch (action) {
                        case 7:
                            float x = event.getX(0);
                            float y = event.getY(0);
                            SDLActivity.onNativeMouse(0, action, x, y);
                            return true;
                        case 8:
                            float x2 = event.getAxisValue(10, 0);
                            float y2 = event.getAxisValue(9, 0);
                            SDLActivity.onNativeMouse(0, action, x2, y2);
                            return true;
                    }
                }
            default:
                return false;
        }
    }
}

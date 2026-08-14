package org.libsdl.app;

import android.view.InputDevice;

/* JADX INFO: compiled from: SDLControllerManager.java */
/* JADX INFO: loaded from: classes4.dex */
class SDLJoystickHandler_API16 extends SDLJoystickHandler_API12 {
    SDLJoystickHandler_API16() {
    }

    @Override // org.libsdl.app.SDLJoystickHandler_API12
    public String getJoystickDescriptor(InputDevice joystickDevice) {
        String desc = joystickDevice.getDescriptor();
        if (desc != null && !desc.isEmpty()) {
            return desc;
        }
        return super.getJoystickDescriptor(joystickDevice);
    }
}

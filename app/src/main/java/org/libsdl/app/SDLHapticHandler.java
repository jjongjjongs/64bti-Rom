package org.libsdl.app;

import android.os.Vibrator;
import android.view.InputDevice;
import java.util.ArrayList;

/* JADX INFO: compiled from: SDLControllerManager.java */
/* JADX INFO: loaded from: classes4.dex */
class SDLHapticHandler {
    private ArrayList<SDLHaptic> mHaptics = new ArrayList<>();

    /* JADX INFO: compiled from: SDLControllerManager.java */
    class SDLHaptic {
        public int device_id;
        public String name;
        public Vibrator vib;

        SDLHaptic() {
        }
    }

    public void run(int device_id, int length) {
        SDLHaptic haptic = getHaptic(device_id);
        if (haptic != null) {
            haptic.vib.vibrate(length);
        }
    }

    public void pollHapticDevices() {
        boolean hasVibratorService = false;
        int[] deviceIds = InputDevice.getDeviceIds();
        int i = deviceIds.length;
        while (true) {
            i--;
            if (i <= -1) {
                break;
            }
            if (getHaptic(deviceIds[i]) == null) {
                InputDevice device = InputDevice.getDevice(deviceIds[i]);
                Vibrator vib = device.getVibrator();
                if (vib.hasVibrator()) {
                    SDLHaptic haptic = new SDLHaptic();
                    haptic.device_id = deviceIds[i];
                    haptic.name = device.getName();
                    haptic.vib = vib;
                    this.mHaptics.add(haptic);
                    SDLControllerManager.nativeAddHaptic(haptic.device_id, haptic.name);
                }
            }
        }
        Vibrator vib2 = (Vibrator) SDL.getContext().getSystemService("vibrator");
        if (vib2 != null && (hasVibratorService = vib2.hasVibrator()) && getHaptic(999999) == null) {
            SDLHaptic haptic2 = new SDLHaptic();
            haptic2.device_id = 999999;
            haptic2.name = "VIBRATOR_SERVICE";
            haptic2.vib = vib2;
            this.mHaptics.add(haptic2);
            SDLControllerManager.nativeAddHaptic(haptic2.device_id, haptic2.name);
        }
        ArrayList<Integer> removedDevices = new ArrayList<>();
        for (int i2 = 0; i2 < this.mHaptics.size(); i2++) {
            int device_id = this.mHaptics.get(i2).device_id;
            int j = 0;
            while (j < deviceIds.length && device_id != deviceIds[j]) {
                j++;
            }
            if ((device_id != 999999 || !hasVibratorService) && j == deviceIds.length) {
                removedDevices.add(Integer.valueOf(device_id));
            }
        }
        for (int i3 = 0; i3 < removedDevices.size(); i3++) {
            int device_id2 = removedDevices.get(i3).intValue();
            SDLControllerManager.nativeRemoveHaptic(device_id2);
            for (int j2 = 0; j2 < this.mHaptics.size(); j2++) {
                if (this.mHaptics.get(j2).device_id == device_id2) {
                    this.mHaptics.remove(j2);
                    break;
                }
            }
        }
    }

    protected SDLHaptic getHaptic(int device_id) {
        for (int i = 0; i < this.mHaptics.size(); i++) {
            if (this.mHaptics.get(i).device_id == device_id) {
                return this.mHaptics.get(i);
            }
        }
        return null;
    }
}

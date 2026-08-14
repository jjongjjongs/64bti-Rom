package org.libsdl.app;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

/* JADX INFO: compiled from: SDLActivity.java */
/* JADX INFO: loaded from: classes4.dex */
class SDLSurface extends SurfaceView implements SurfaceHolder.Callback, View.OnKeyListener, View.OnTouchListener, SensorEventListener {
    protected static Display mDisplay;
    protected static float mHeight;
    protected static SensorManager mSensorManager;
    protected static float mWidth;

    public SDLSurface(Context context) {
        super(context);
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setOnKeyListener(this);
        setOnTouchListener(this);
        mDisplay = ((WindowManager) context.getSystemService("window")).getDefaultDisplay();
        mSensorManager = (SensorManager) context.getSystemService("sensor");
        setOnGenericMotionListener(new SDLGenericMotionListener_API12());
        mWidth = 1.0f;
        mHeight = 1.0f;
    }

    public void handlePause() {
        enableSensor(1, false);
    }

    public void handleResume() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setOnKeyListener(this);
        setOnTouchListener(this);
        enableSensor(1, true);
    }

    public Surface getNativeSurface() {
        return getHolder().getSurface();
    }

    public void surfaceCreated(SurfaceHolder holder) {
        Log.v("SDL", "surfaceCreated()");
        holder.setType(2);
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.v("SDL", "surfaceDestroyed()");
        SDLActivity.mNextNativeState = SDLActivity.NativeState.PAUSED;
        SDLActivity.handleNativeState();
        SDLActivity.mIsSurfaceReady = false;
        SDLActivity.onNativeSurfaceDestroyed();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.v("SDL", "surfaceChanged()");
        int sdlFormat = 353701890;
        switch (format) {
            case 1:
                Log.v("SDL", "pixel format RGBA_8888");
                sdlFormat = 373694468;
                break;
            case 2:
                Log.v("SDL", "pixel format RGBX_8888");
                sdlFormat = 371595268;
                break;
            case 3:
                Log.v("SDL", "pixel format RGB_888");
                sdlFormat = 370546692;
                break;
            case 4:
                Log.v("SDL", "pixel format RGB_565");
                sdlFormat = 353701890;
                break;
            case 5:
            default:
                Log.v("SDL", "pixel format unknown " + format);
                break;
            case 6:
                Log.v("SDL", "pixel format RGBA_5551");
                sdlFormat = 356782082;
                break;
            case 7:
                Log.v("SDL", "pixel format RGBA_4444");
                sdlFormat = 356651010;
                break;
            case 8:
                Log.v("SDL", "pixel format A_8");
                break;
            case 9:
                Log.v("SDL", "pixel format L_8");
                break;
            case 10:
                Log.v("SDL", "pixel format LA_88");
                break;
            case 11:
                Log.v("SDL", "pixel format RGB_332");
                sdlFormat = 336660481;
                break;
        }
        mWidth = width;
        mHeight = height;
        SDLActivity.onNativeResize(width, height, sdlFormat, mDisplay.getRefreshRate());
        Log.v("SDL", "Window size: " + width + "x" + height);
        boolean skip = false;
        int requestedOrientation = SDLActivity.mSingleton.getRequestedOrientation();
        if (requestedOrientation != -1) {
            if (requestedOrientation == 1 || requestedOrientation == 7) {
                if (mWidth > mHeight) {
                    skip = true;
                }
            } else if ((requestedOrientation == 0 || requestedOrientation == 6) && mWidth < mHeight) {
                skip = true;
            }
        }
        if (skip) {
            double min = Math.min(mWidth, mHeight);
            double max = Math.max(mWidth, mHeight);
            if (max / min < 1.2d) {
                Log.v("SDL", "Don't skip on such aspect-ratio. Could be a square resolution.");
                skip = false;
            }
        }
        if (skip) {
            Log.v("SDL", "Skip .. Surface is not ready.");
            SDLActivity.mIsSurfaceReady = false;
        } else {
            SDLActivity.mIsSurfaceReady = true;
            SDLActivity.onNativeSurfaceChanged();
            SDLActivity.handleNativeState();
        }
    }

    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (SDLControllerManager.isDeviceSDLJoystick(event.getDeviceId())) {
            if (event.getAction() == 0) {
                if (SDLControllerManager.onNativePadDown(event.getDeviceId(), keyCode) == 0) {
                    return true;
                }
            } else if (event.getAction() == 1 && SDLControllerManager.onNativePadUp(event.getDeviceId(), keyCode) == 0) {
                return true;
            }
        }
        if ((event.getSource() & 257) != 0) {
            if (event.getAction() == 0) {
                if (SDLActivity.isTextInputEvent(event)) {
                    SDLInputConnection.nativeCommitText(String.valueOf((char) event.getUnicodeChar()), 1);
                }
                SDLActivity.onNativeKeyDown(keyCode);
                return true;
            }
            if (event.getAction() == 1) {
                SDLActivity.onNativeKeyUp(keyCode);
                return true;
            }
        }
        if ((event.getSource() & 8194) != 0) {
            if (keyCode == 4 || keyCode == 125) {
                switch (event.getAction()) {
                    case 0:
                    case 1:
                        return true;
                    default:
                        return false;
                }
            }
            return false;
        }
        return false;
    }

    public boolean onTouch(View v, MotionEvent event) {
        int i;
        float p;
        int mouseButton;
        int touchDevId = event.getDeviceId();
        int pointerCount = event.getPointerCount();
        int action = event.getActionMasked();
        int i2 = -1;
        if (event.getSource() == 8194 && SDLActivity.mSeparateMouseAndTouch) {
            try {
                mouseButton = ((Integer) event.getClass().getMethod("getButtonState", new Class[0]).invoke(event, new Object[0])).intValue();
            } catch (Exception e) {
                mouseButton = 1;
            }
            SDLActivity.onNativeMouse(mouseButton, action, event.getX(0), event.getY(0));
            return true;
        }
        switch (action) {
            case 0:
            case 1:
                i2 = 0;
                break;
            case 2:
                for (int i3 = 0; i3 < pointerCount; i3++) {
                    int pointerFingerId = event.getPointerId(i3);
                    float x = event.getX(i3) / mWidth;
                    float y = event.getY(i3) / mHeight;
                    float p2 = event.getPressure(i3);
                    if (p2 > 1.0f) {
                        p2 = 1.0f;
                    }
                    SDLActivity.onNativeTouch(touchDevId, pointerFingerId, action, x, y, p2);
                }
                return true;
            case 3:
                for (int i4 = 0; i4 < pointerCount; i4++) {
                    int pointerFingerId2 = event.getPointerId(i4);
                    float x2 = event.getX(i4) / mWidth;
                    float y2 = event.getY(i4) / mHeight;
                    float p3 = event.getPressure(i4);
                    if (p3 > 1.0f) {
                        p3 = 1.0f;
                    }
                    SDLActivity.onNativeTouch(touchDevId, pointerFingerId2, 1, x2, y2, p3);
                }
                return true;
            case 4:
            default:
                return true;
            case 5:
            case 6:
                break;
        }
        if (i2 != -1) {
            i = i2;
        } else {
            int i5 = event.getActionIndex();
            i = i5;
        }
        int pointerFingerId3 = event.getPointerId(i);
        float x3 = event.getX(i) / mWidth;
        float y3 = event.getY(i) / mHeight;
        float p4 = event.getPressure(i);
        if (p4 <= 1.0f) {
            p = p4;
        } else {
            p = 1.0f;
        }
        SDLActivity.onNativeTouch(touchDevId, pointerFingerId3, action, x3, y3, p);
        return true;
    }

    public void enableSensor(int sensortype, boolean enabled) {
        if (enabled) {
            mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(sensortype), 1, (Handler) null);
        } else {
            mSensorManager.unregisterListener(this, mSensorManager.getDefaultSensor(sensortype));
        }
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    public void onSensorChanged(SensorEvent event) {
        float x;
        float y;
        if (event.sensor.getType() == 1) {
            switch (mDisplay.getRotation()) {
                case 1:
                    x = -event.values[1];
                    y = event.values[0];
                    break;
                case 2:
                    x = -event.values[1];
                    y = -event.values[0];
                    break;
                case 3:
                    x = event.values[1];
                    y = -event.values[0];
                    break;
                default:
                    x = event.values[0];
                    y = event.values[1];
                    break;
            }
            SDLActivity.onNativeAccel((-x) / 9.80665f, y / 9.80665f, event.values[2] / 9.80665f);
        }
    }
}

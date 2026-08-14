package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.UiModeManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;

/* JADX INFO: loaded from: classes4.dex */
public class SDLActivity extends Activity {
    static final int COMMAND_CHANGE_TITLE = 1;
    static final int COMMAND_CHANGE_WINDOW_STYLE = 2;
    static final int COMMAND_SET_KEEP_SCREEN_ON = 5;
    static final int COMMAND_TEXTEDIT_HIDE = 3;
    protected static final int COMMAND_USER = 32768;
    private static final String TAG = "SDL";
    private static Object expansionFile;
    private static Method expansionFileMethod;
    public static boolean mBrokenLibraries;
    protected static SDLClipboardHandler mClipboardHandler;
    public static NativeState mCurrentNativeState;
    public static boolean mExitCalledFromJava;
    public static boolean mHasFocus;
    public static boolean mIsResumedCalled;
    public static boolean mIsSurfaceReady;
    protected static ViewGroup mLayout;
    public static NativeState mNextNativeState;
    protected static Thread mSDLThread;
    protected static boolean mScreenKeyboardShown;
    public static boolean mSeparateMouseAndTouch;
    protected static SDLActivity mSingleton;
    protected static SDLSurface mSurface;
    public static boolean mSuspendOnly;
    protected static View mTextEdit;
    Handler commandHandler = new SDLCommandHandler();
    protected final int[] messageboxSelection = new int[COMMAND_CHANGE_TITLE];
    protected int dialogs = 0;

    public enum NativeState {
        INIT,
        RESUMED,
        PAUSED
    }

    public static native String nativeGetHint(String str);

    public static native void nativeLowMemory();

    public static native void nativePause();

    public static native void nativeQuit();

    public static native void nativeResume();

    public static native int nativeRunMain(String str, String str2, Object obj);

    public static native void nativeSetenv(String str, String str2);

    public static native int nativeSetupJNI();

    public static native void onNativeAccel(float f, float f2, float f3);

    public static native void onNativeClipboardChanged();

    public static native void onNativeDropFile(String str);

    public static native void onNativeKeyDown(int i);

    public static native void onNativeKeyUp(int i);

    public static native void onNativeKeyboardFocusLost();

    public static native void onNativeMouse(int i, int i2, float f, float f2);

    public static native void onNativeResize(int i, int i2, int i3, float f);

    public static native void onNativeSurfaceChanged();

    public static native void onNativeSurfaceDestroyed();

    public static native void onNativeTouch(int i, int i2, int i3, float f, float f2, float f3);

    protected String getMainSharedObject() {
        String[] libraries = mSingleton.getLibraries();
        if (libraries.length > 0) {
            String library = "lib" + libraries[libraries.length - 1] + ".so";
            return library;
        }
        return "libmain.so";
    }

    protected String getMainFunction() {
        return "SDL_main";
    }

    protected String[] getLibraries() {
        return new String[]{"SDL2", "main"};
    }

    public void loadLibraries() {
        String[] libraries = getLibraries();
        int length = libraries.length;
        for (int i = 0; i < length; i += COMMAND_CHANGE_TITLE) {
            String lib = libraries[i];
            System.loadLibrary(lib);
        }
    }

    protected String[] getArguments() {
        return new String[0];
    }

    public static void initialize() {
        mSingleton = null;
        mSurface = null;
        mTextEdit = null;
        mLayout = null;
        mClipboardHandler = null;
        mSDLThread = null;
        mExitCalledFromJava = false;
        mBrokenLibraries = false;
        mIsResumedCalled = false;
        mIsSurfaceReady = false;
        mHasFocus = true;
        mNextNativeState = NativeState.INIT;
        mCurrentNativeState = NativeState.INIT;
    }

    @Override // android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        String filename;
        Log.v(TAG, "Device: " + Build.DEVICE);
        Log.v(TAG, "Model: " + Build.MODEL);
        Log.v(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        String errorMsgBrokenLib = "";
        try {
            loadLibraries();
        } catch (Exception e) {
            System.err.println(e.getMessage());
            mBrokenLibraries = true;
            errorMsgBrokenLib = e.getMessage();
        } catch (UnsatisfiedLinkError e2) {
            System.err.println(e2.getMessage());
            mBrokenLibraries = true;
            errorMsgBrokenLib = e2.getMessage();
        }
        if (mBrokenLibraries) {
            mSingleton = this;
            AlertDialog.Builder dlgAlert = new AlertDialog.Builder(this);
            dlgAlert.setMessage("An error occurred while trying to start the application. Please try again and/or reinstall." + System.getProperty("line.separator") + System.getProperty("line.separator") + "Error: " + errorMsgBrokenLib);
            dlgAlert.setTitle("SDL Error");
            dlgAlert.setPositiveButton("Exit", new DialogInterface.OnClickListener() { // from class: org.libsdl.app.SDLActivity.1
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialog, int id) {
                    SDLActivity.mSingleton.finish();
                }
            });
            dlgAlert.setCancelable(false);
            dlgAlert.create().show();
            return;
        }
        SDL.setupJNI();
        SDL.initialize();
        mSingleton = this;
        SDL.setContext(this);
        mClipboardHandler = new SDLClipboardHandler_API11();
        setWindowStyle(false);
        Intent intent = getIntent();
        if (intent != null && intent.getData() != null && (filename = intent.getData().getPath()) != null) {
            Log.v(TAG, "Got filename: " + filename);
            onNativeDropFile(filename);
        }
    }

    @Override // android.app.Activity
    protected void onPause() {
        Log.v(TAG, "onPause()");
        super.onPause();
        mNextNativeState = NativeState.PAUSED;
        mIsResumedCalled = false;
        if (mBrokenLibraries) {
            return;
        }
        handleNativeState();
    }

    @Override // android.app.Activity
    protected void onResume() {
        Log.v(TAG, "onResume()");
        super.onResume();
        mNextNativeState = NativeState.RESUMED;
        mIsResumedCalled = true;
        if (mBrokenLibraries) {
            return;
        }
        handleNativeState();
    }

    @Override // android.app.Activity, android.view.Window.Callback
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        Log.v(TAG, "onWindowFocusChanged(): " + hasFocus);
        if (mBrokenLibraries) {
            return;
        }
        mHasFocus = hasFocus;
        if (hasFocus) {
            mNextNativeState = NativeState.RESUMED;
        } else {
            mNextNativeState = NativeState.PAUSED;
        }
        handleNativeState();
    }

    @Override // android.app.Activity, android.content.ComponentCallbacks
    public void onLowMemory() {
        Log.v(TAG, "onLowMemory()");
        super.onLowMemory();
        if (mBrokenLibraries) {
            return;
        }
        nativeLowMemory();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        Log.v(TAG, "onDestroy()");
        if (mSuspendOnly) {
            Thread currSDLThread = mSDLThread;
            initialize();
            mExitCalledFromJava = true;
            if (currSDLThread != null) {
                currSDLThread.interrupt();
            }
            mSuspendOnly = false;
            super.onDestroy();
            return;
        }
        if (mBrokenLibraries) {
            super.onDestroy();
            initialize();
            return;
        }
        mNextNativeState = NativeState.PAUSED;
        handleNativeState();
        mExitCalledFromJava = true;
        nativeQuit();
        if (mSDLThread != null) {
            try {
                mSDLThread.join();
            } catch (Exception e) {
                Log.v(TAG, "Problem stopping thread: " + e);
            }
            mSDLThread = null;
        }
        super.onDestroy();
        initialize();
    }

    @Override // android.app.Activity, android.view.Window.Callback
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode;
        if (mBrokenLibraries || (keyCode = event.getKeyCode()) == 25 || keyCode == 24 || keyCode == 27 || keyCode == 168 || keyCode == 169) {
            return false;
        }
        return super.dispatchKeyEvent(event);
    }

    public static void handleNativeState() {
        if (mNextNativeState == mCurrentNativeState) {
            return;
        }
        if (mNextNativeState == NativeState.INIT) {
            mCurrentNativeState = mNextNativeState;
            return;
        }
        if (mNextNativeState == NativeState.PAUSED) {
            nativePause();
            if (mSurface != null) {
                mSurface.handlePause();
            }
            mCurrentNativeState = mNextNativeState;
            return;
        }
        if (mNextNativeState == NativeState.RESUMED && mIsSurfaceReady && mHasFocus && mIsResumedCalled) {
            if (mSDLThread == null) {
                mSDLThread = new Thread(new SDLMain(), "SDLThread");
                mSurface.enableSensor(COMMAND_CHANGE_TITLE, true);
                mSDLThread.start();
            }
            nativeResume();
            mSurface.handleResume();
            mCurrentNativeState = mNextNativeState;
        }
    }

    public static void handleNativeExit() {
        mSDLThread = null;
        mSingleton.finish();
    }

    protected boolean onUnhandledMessage(int command, Object param) {
        return false;
    }

    protected static class SDLCommandHandler extends Handler {
        protected SDLCommandHandler() {
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            Window window;
            Context context = SDL.getContext();
            if (context == null) {
                Log.e(SDLActivity.TAG, "error handling message, getContext() returned null");
            }
            switch (msg.arg1) {
                case SDLActivity.COMMAND_CHANGE_TITLE /* 1 */:
                    if (!(context instanceof Activity)) {
                        Log.e(SDLActivity.TAG, "error handling message, getContext() returned no Activity");
                    } else {
                        ((Activity) context).setTitle((String) msg.obj);
                    }
                    break;
                case SDLActivity.COMMAND_CHANGE_WINDOW_STYLE /* 2 */:
                    break;
                case SDLActivity.COMMAND_TEXTEDIT_HIDE /* 3 */:
                    if (SDLActivity.mTextEdit != null) {
                        SDLActivity.mTextEdit.setLayoutParams(new RelativeLayout.LayoutParams(0, 0));
                        InputMethodManager imm = (InputMethodManager) context.getSystemService("input_method");
                        imm.hideSoftInputFromWindow(SDLActivity.mTextEdit.getWindowToken(), 0);
                        SDLActivity.mScreenKeyboardShown = false;
                    }
                    break;
                case 4:
                default:
                    if ((context instanceof SDLActivity) && !((SDLActivity) context).onUnhandledMessage(msg.arg1, msg.obj)) {
                        Log.e(SDLActivity.TAG, "error handling message, command is " + msg.arg1);
                        break;
                    }
                    break;
                case SDLActivity.COMMAND_SET_KEEP_SCREEN_ON /* 5 */:
                    if ((context instanceof Activity) && (window = ((Activity) context).getWindow()) != null) {
                        if ((msg.obj instanceof Integer) && ((Integer) msg.obj).intValue() != 0) {
                            window.addFlags(128);
                        } else {
                            window.clearFlags(128);
                        }
                        break;
                    }
                    break;
            }
        }
    }

    boolean sendCommand(int command, Object data) {
        Message msg = this.commandHandler.obtainMessage();
        msg.arg1 = command;
        msg.obj = data;
        return this.commandHandler.sendMessage(msg);
    }

    public static boolean setActivityTitle(String title) {
        return mSingleton.sendCommand(COMMAND_CHANGE_TITLE, title);
    }

    public static void setWindowStyle(boolean z) {
        mSingleton.sendCommand(COMMAND_CHANGE_WINDOW_STYLE, Integer.valueOf(z ? 1 : 0));
    }

    public static void setOrientation(int w, int h, boolean resizable, String hint) {
        if (mSingleton != null) {
            mSingleton.setOrientationBis(w, h, resizable, hint);
        }
    }

    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        int orientation = -1;
        if (hint.contains("LandscapeRight") && hint.contains("LandscapeLeft")) {
            orientation = 6;
        } else if (hint.contains("LandscapeRight")) {
            orientation = 0;
        } else if (hint.contains("LandscapeLeft")) {
            orientation = 8;
        } else if (hint.contains("Portrait") && hint.contains("PortraitUpsideDown")) {
            orientation = 7;
        } else if (hint.contains("Portrait")) {
            orientation = COMMAND_CHANGE_TITLE;
        } else if (hint.contains("PortraitUpsideDown")) {
            orientation = 9;
        }
        if (orientation == -1 && !resizable) {
            if (w > h) {
                orientation = 6;
            } else {
                orientation = 7;
            }
        }
        Log.v(TAG, "setOrientation() orientation=" + orientation + " width=" + w + " height=" + h + " resizable=" + resizable + " hint=" + hint);
        if (orientation != -1) {
            mSingleton.setRequestedOrientation(orientation);
        }
    }

    public static boolean isScreenKeyboardShown() {
        if (mTextEdit == null || !mScreenKeyboardShown) {
            return false;
        }
        InputMethodManager imm = (InputMethodManager) SDL.getContext().getSystemService("input_method");
        return imm.isAcceptingText();
    }

    public static boolean sendMessage(int command, int param) {
        if (mSingleton == null) {
            return false;
        }
        return mSingleton.sendCommand(command, Integer.valueOf(param));
    }

    public static Context getContext() {
        return SDL.getContext();
    }

    public static boolean isAndroidTV() {
        UiModeManager uiModeManager = (UiModeManager) getContext().getSystemService("uimode");
        return uiModeManager.getCurrentModeType() == 4;
    }

    public static DisplayMetrics getDisplayDPI() {
        return getContext().getResources().getDisplayMetrics();
    }

    public static boolean getManifestEnvironmentVariables() {
        try {
            ApplicationInfo applicationInfo = getContext().getPackageManager().getApplicationInfo(getContext().getPackageName(), 128);
            Bundle bundle = applicationInfo.metaData;
            if (bundle == null) {
                return false;
            }
            int trimLength = "SDL_ENV.".length();
            for (String key : bundle.keySet()) {
                if (key.startsWith("SDL_ENV.")) {
                    String name = key.substring(trimLength);
                    String value = bundle.get(key).toString();
                    nativeSetenv(name, value);
                }
            }
            return true;
        } catch (Exception e) {
            Log.v(TAG, "exception " + e.toString());
            return false;
        }
    }

    static class ShowTextInputTask implements Runnable {
        static final int HEIGHT_PADDING = 15;
        public int h;
        public int w;
        public int x;
        public int y;

        public ShowTextInputTask(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        @Override // java.lang.Runnable
        public void run() {
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(this.w, this.h + HEIGHT_PADDING);
            params.leftMargin = this.x;
            params.topMargin = this.y;
            if (SDLActivity.mTextEdit == null) {
                SDLActivity.mTextEdit = new DummyEdit(SDL.getContext());
                SDLActivity.mLayout.addView(SDLActivity.mTextEdit, params);
            } else {
                SDLActivity.mTextEdit.setLayoutParams(params);
            }
            SDLActivity.mTextEdit.setVisibility(0);
            SDLActivity.mTextEdit.requestFocus();
            InputMethodManager imm = (InputMethodManager) SDL.getContext().getSystemService("input_method");
            imm.showSoftInput(SDLActivity.mTextEdit, 0);
            SDLActivity.mScreenKeyboardShown = true;
        }
    }

    public static boolean showTextInput(int x, int y, int w, int h) {
        return mSingleton.commandHandler.post(new ShowTextInputTask(x, y, w, h));
    }

    public static boolean isTextInputEvent(KeyEvent event) {
        if (event.isCtrlPressed()) {
            return false;
        }
        return event.isPrintingKey() || event.getKeyCode() == 62;
    }

    public static Surface getNativeSurface() {
        if (mSurface == null) {
            return null;
        }
        return mSurface.getNativeSurface();
    }

    public static int[] inputGetInputDeviceIds(int sources) {
        int[] ids = InputDevice.getDeviceIds();
        int[] filtered = new int[ids.length];
        int used = 0;
        for (int i = 0; i < ids.length; i += COMMAND_CHANGE_TITLE) {
            InputDevice device = InputDevice.getDevice(ids[i]);
            if (device != null && (device.getSources() & sources) != 0) {
                int used2 = used + COMMAND_CHANGE_TITLE;
                filtered[used] = device.getId();
                used = used2;
            }
        }
        return Arrays.copyOf(filtered, used);
    }

    public static InputStream openAPKExpansionInputStream(String fileName) throws IOException {
        String patchHint;
        if (expansionFile == null) {
            String mainHint = nativeGetHint("SDL_ANDROID_APK_EXPANSION_MAIN_FILE_VERSION");
            if (mainHint == null || (patchHint = nativeGetHint("SDL_ANDROID_APK_EXPANSION_PATCH_FILE_VERSION")) == null) {
                return null;
            }
            try {
                Integer mainVersion = Integer.valueOf(mainHint);
                Integer patchVersion = Integer.valueOf(patchHint);
                try {
                    expansionFile = Class.forName("com.android.vending.expansion.zipfile.APKExpansionSupport").getMethod("getAPKExpansionZipFile", Context.class, Integer.TYPE, Integer.TYPE).invoke(null, SDL.getContext(), mainVersion, patchVersion);
                    expansionFileMethod = expansionFile.getClass().getMethod("getInputStream", String.class);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    expansionFile = null;
                    expansionFileMethod = null;
                    throw new IOException("Could not access APK expansion support library", ex);
                }
            } catch (NumberFormatException ex2) {
                ex2.printStackTrace();
                throw new IOException("No valid file versions set for APK expansion files", ex2);
            }
        }
        try {
            InputStream fileStream = (InputStream) expansionFileMethod.invoke(expansionFile, fileName);
            if (fileStream == null) {
                throw new IOException("Could not find path in APK expansion file");
            }
            return fileStream;
        } catch (Exception ex3) {
            ex3.printStackTrace();
            throw new IOException("Could not open stream from APK expansion file", ex3);
        }
    }

    public int messageboxShowMessageBox(int flags, String title, String message, int[] buttonFlags, int[] buttonIds, String[] buttonTexts, int[] colors) {
        this.messageboxSelection[0] = -1;
        if (buttonFlags.length != buttonIds.length && buttonIds.length != buttonTexts.length) {
            return -1;
        }
        final Bundle args = new Bundle();
        args.putInt("flags", flags);
        args.putString("title", title);
        args.putString("message", message);
        args.putIntArray("buttonFlags", buttonFlags);
        args.putIntArray("buttonIds", buttonIds);
        args.putStringArray("buttonTexts", buttonTexts);
        args.putIntArray("colors", colors);
        runOnUiThread(new Runnable() { // from class: org.libsdl.app.SDLActivity.2
            @Override // java.lang.Runnable
            public void run() {
                SDLActivity sDLActivity = SDLActivity.this;
                SDLActivity sDLActivity2 = SDLActivity.this;
                int i = sDLActivity2.dialogs;
                sDLActivity2.dialogs = i + SDLActivity.COMMAND_CHANGE_TITLE;
                sDLActivity.showDialog(i, args);
            }
        });
        synchronized (this.messageboxSelection) {
            try {
                this.messageboxSelection.wait();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                return -1;
            }
        }
        return this.messageboxSelection[0];
    }

    @Override // android.app.Activity
    protected Dialog onCreateDialog(int ignore, Bundle args) {
        int backgroundColor;
        int textColor;
        int buttonBackgroundColor;
        int i;
        SparseArray<Button> mapping;
        int[] colors = args.getIntArray("colors");
        if (colors != null) {
            int i2 = (-1) + COMMAND_CHANGE_TITLE;
            backgroundColor = colors[i2];
            int i3 = i2 + COMMAND_CHANGE_TITLE;
            textColor = colors[i3];
            int i4 = i3 + COMMAND_CHANGE_TITLE;
            int i5 = colors[i4];
            int i6 = i4 + COMMAND_CHANGE_TITLE;
            buttonBackgroundColor = colors[i6];
            i = colors[i6 + COMMAND_CHANGE_TITLE];
        } else {
            backgroundColor = 0;
            textColor = 0;
            buttonBackgroundColor = 0;
            i = 0;
        }
        final Dialog dialog = new Dialog(this);
        dialog.setTitle(args.getString("title"));
        dialog.setCancelable(false);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() { // from class: org.libsdl.app.SDLActivity.3
            @Override // android.content.DialogInterface.OnDismissListener
            public void onDismiss(DialogInterface unused) {
                synchronized (SDLActivity.this.messageboxSelection) {
                    SDLActivity.this.messageboxSelection.notify();
                }
            }
        });
        TextView message = new TextView(this);
        message.setGravity(17);
        message.setText(args.getString("message"));
        if (textColor != 0) {
            message.setTextColor(textColor);
        }
        int[] buttonFlags = args.getIntArray("buttonFlags");
        int[] buttonIds = args.getIntArray("buttonIds");
        String[] buttonTexts = args.getStringArray("buttonTexts");
        SparseArray<Button> mapping2 = new SparseArray<>();
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(0);
        buttons.setGravity(17);
        int i7 = 0;
        while (i7 < buttonTexts.length) {
            Button button = new Button(this);
            final int id = buttonIds[i7];
            int[] colors2 = colors;
            button.setOnClickListener(new View.OnClickListener() { // from class: org.libsdl.app.SDLActivity.4
                @Override // android.view.View.OnClickListener
                public void onClick(View v) {
                    SDLActivity.this.messageboxSelection[0] = id;
                    dialog.dismiss();
                }
            });
            if (buttonFlags[i7] != 0) {
                if ((buttonFlags[i7] & COMMAND_CHANGE_TITLE) != 0) {
                    mapping = mapping2;
                    mapping.put(66, button);
                } else {
                    mapping = mapping2;
                }
                if ((buttonFlags[i7] & COMMAND_CHANGE_WINDOW_STYLE) != 0) {
                    mapping.put(111, button);
                }
            } else {
                mapping = mapping2;
            }
            button.setText(buttonTexts[i7]);
            if (textColor != 0) {
                button.setTextColor(textColor);
            }
            if (buttonBackgroundColor != 0) {
                Drawable drawable = button.getBackground();
                if (drawable == null) {
                    button.setBackgroundColor(buttonBackgroundColor);
                } else {
                    drawable.setColorFilter(buttonBackgroundColor, PorterDuff.Mode.MULTIPLY);
                }
            }
            buttons.addView(button);
            i7 += COMMAND_CHANGE_TITLE;
            i = i;
            colors = colors2;
            mapping2 = mapping;
        }
        final SparseArray<Button> mapping3 = mapping2;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(COMMAND_CHANGE_TITLE);
        content.addView(message);
        content.addView(buttons);
        if (backgroundColor != 0) {
            content.setBackgroundColor(backgroundColor);
        }
        dialog.setContentView(content);
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() { // from class: org.libsdl.app.SDLActivity.5
            @Override // android.content.DialogInterface.OnKeyListener
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                Button button2 = (Button) mapping3.get(keyCode);
                if (button2 != null) {
                    if (event.getAction() == SDLActivity.COMMAND_CHANGE_TITLE) {
                        button2.performClick();
                    }
                    return true;
                }
                return false;
            }
        });
        return dialog;
    }

    public static boolean clipboardHasText() {
        return mClipboardHandler.clipboardHasText();
    }

    public static String clipboardGetText() {
        return mClipboardHandler.clipboardGetText();
    }

    public static void clipboardSetText(String string) {
        mClipboardHandler.clipboardSetText(string);
    }

    public static class ExSDLSurface extends SDLSurface {
        @Override // org.libsdl.app.SDLSurface
        public /* bridge */ /* synthetic */ void enableSensor(int i, boolean z) {
            super.enableSensor(i, z);
        }

        @Override // org.libsdl.app.SDLSurface
        public /* bridge */ /* synthetic */ Surface getNativeSurface() {
            return super.getNativeSurface();
        }

        @Override // org.libsdl.app.SDLSurface
        public /* bridge */ /* synthetic */ void handlePause() {
            super.handlePause();
        }

        @Override // org.libsdl.app.SDLSurface
        public /* bridge */ /* synthetic */ void handleResume() {
            super.handleResume();
        }

        @Override // org.libsdl.app.SDLSurface, android.hardware.SensorEventListener
        public /* bridge */ /* synthetic */ void onAccuracyChanged(Sensor sensor, int i) {
            super.onAccuracyChanged(sensor, i);
        }

        @Override // org.libsdl.app.SDLSurface, android.view.View.OnKeyListener
        public /* bridge */ /* synthetic */ boolean onKey(View view, int i, KeyEvent keyEvent) {
            return super.onKey(view, i, keyEvent);
        }

        @Override // org.libsdl.app.SDLSurface, android.hardware.SensorEventListener
        public /* bridge */ /* synthetic */ void onSensorChanged(SensorEvent sensorEvent) {
            super.onSensorChanged(sensorEvent);
        }

        @Override // org.libsdl.app.SDLSurface, android.view.View.OnTouchListener
        public /* bridge */ /* synthetic */ boolean onTouch(View view, MotionEvent motionEvent) {
            return super.onTouch(view, motionEvent);
        }

        @Override // org.libsdl.app.SDLSurface, android.view.SurfaceHolder.Callback
        public /* bridge */ /* synthetic */ void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i2, int i3) {
            super.surfaceChanged(surfaceHolder, i, i2, i3);
        }

        @Override // org.libsdl.app.SDLSurface, android.view.SurfaceHolder.Callback
        public /* bridge */ /* synthetic */ void surfaceCreated(SurfaceHolder surfaceHolder) {
            super.surfaceCreated(surfaceHolder);
        }

        @Override // org.libsdl.app.SDLSurface, android.view.SurfaceHolder.Callback
        public /* bridge */ /* synthetic */ void surfaceDestroyed(SurfaceHolder surfaceHolder) {
            super.surfaceDestroyed(surfaceHolder);
        }

        public ExSDLSurface(Context context) {
            super(context);
        }
    }

    protected void runSDLMain() {
    }
}

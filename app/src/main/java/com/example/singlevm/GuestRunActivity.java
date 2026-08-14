package com.example.singlevm;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.example.singlevm.engine.Android7GuestEngineAdapter;
import com.example.singlevm.engine.EngineAdapter;
import com.example.singlevm.engine.EngineReadiness;
import com.example.singlevm.engine.EngineRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/* JADX INFO: loaded from: classes2.dex */
public class GuestRunActivity extends Activity implements SurfaceHolder.Callback {
    private static final boolean EMUGL_PROBE_LOADED;
    public static final String EXTRA_ABIS = "abis";
    public static final String EXTRA_APK_PATH = "apk_path";
    public static final String EXTRA_ARM32_LIB_COUNT = "arm32_lib_count";
    public static final String EXTRA_DATA_DIR = "data_dir";
    public static final String EXTRA_ENGINE_ID = "engine_id";
    public static final String EXTRA_INSTALL_ID = "install_id";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_LAUNCHER = "launcher";
    public static final String EXTRA_LIB_DIR = "lib_dir";
    public static final String EXTRA_PACKAGE = "package";
    private static final long FRAME_TICK_DELAY_MS = 700;
    private static final int FRAME_TICK_LIMIT = 8;
    private static final boolean NATIVE_RUNTIME_LOADED;
    private static final String READY_TEXT = "런타임 준비 중...";
    private static volatile Process activeQemuProcess;
    private EngineAdapter engineAdapter;
    private String engineId;
    private TextView logView;
    private volatile Process qemuProcess;
    private String runtimeLog = "";
    private boolean runtimeStarted = false;
    private boolean surfaceAttached = false;
    private boolean frameLoopRunning = false;
    private int frameTicks = 0;
    private final Handler frameHandler = new Handler(Looper.getMainLooper());
    private final Runnable frameTicker = new Runnable() { // from class: com.example.singlevm.GuestRunActivity.1
        @Override // java.lang.Runnable
        public void run() {
            GuestRunActivity.this.runFrameTick();
        }
    };

    private native String nativeAttachEmuglSurface(Surface surface);

    private native String nativeAttachSurface(Surface surface);

    private native void nativeDetachEmuglSurface();

    private native String nativeDetachSurface();

    private native String nativeProbeEmugl();

    /* JADX INFO: Access modifiers changed from: private */
    public native String nativeRunFrame();

    /* JADX INFO: Access modifiers changed from: private */
    public native String nativeStartEmuglBridge(String str, boolean z);

    private native String nativeStartGuest(String str, String str2);

    static {
        boolean loaded;
        boolean emuglProbeLoaded;
        try {
            System.loadLibrary("singlevm_runtime");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            loaded = false;
        }
        NATIVE_RUNTIME_LOADED = loaded;
        try {
            System.loadLibrary("emugl_probe");
            emuglProbeLoaded = true;
        } catch (UnsatisfiedLinkError e2) {
            emuglProbeLoaded = false;
        }
        EMUGL_PROBE_LOADED = emuglProbeLoaded;
    }

    /* JADX WARN: Code duplicated, block: B:11:0x002f A[DONT_INVERT] */
    /* JADX WARN: Code duplicated, block: B:12:0x0031 A[Catch: Exception -> 0x002d, TryCatch #0 {Exception -> 0x002d, blocks: (B:4:0x0004, B:6:0x000c, B:8:0x001d, B:15:0x0040, B:12:0x0031), top: B:22:0x0004 }] */
    public int get_fd(String path) {
        ParcelFileDescriptor descriptor = null;
        if (path == null) {
            if (path != null) {
                descriptor = ParcelFileDescriptor.open(new File(path), 805306368);
            }
        } else {
            try {
                if (path.startsWith("content://")) {
                    descriptor = getContentResolver().openFileDescriptor(Uri.parse(path), "rw");
                    if (descriptor == null) {
                        descriptor = getContentResolver().openFileDescriptor(Uri.parse(path), "r");
                    }
                } else if (path != null) {
                    descriptor = ParcelFileDescriptor.open(new File(path), 805306368);
                }
            } catch (Exception e) {
                if (descriptor != null) {
                    try {
                        descriptor.close();
                    } catch (IOException e2) {
                    }
                }
                return -1;
            }
        }
        if (descriptor == null) {
            return -1;
        }
        return descriptor.detachFd();
    }

    public int close_fd(int fd) {
        if (fd < 0) {
            return -1;
        }
        try {
            ParcelFileDescriptor.adoptFd(fd).close();
            return 0;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    @Override // android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.engineId = getExtra(EXTRA_ENGINE_ID, Android7GuestEngineAdapter.ID);
        this.engineAdapter = EngineRegistry.byId(this.engineId);
        buildUi();
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        if (this.runtimeStarted && this.surfaceAttached) {
            startFrameLoop();
        }
    }

    @Override // android.app.Activity
    protected void onPause() {
        stopFrameLoop();
        super.onPause();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(18, 18, 18));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setPadding(dp(18), dp(22), dp(18), dp(18));
        scrollView.addView(root, new FrameLayout.LayoutParams(-1, -2));
        TextView title = new TextView(this);
        title.setText(getExtra(EXTRA_LABEL, "게임"));
        title.setTextSize(24.0f);
        title.setTypeface(Typeface.DEFAULT, 1);
        title.setTextColor(-1);
        title.setGravity(1);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = new TextView(this);
        subtitle.setText(this.engineAdapter.displayName());
        subtitle.setTextSize(14.0f);
        subtitle.setTextColor(Color.rgb(155, 155, 155));
        subtitle.setGravity(1);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        SurfaceView surfaceView = new SurfaceView(this);
        surfaceView.setBackgroundColor(-16777216);
        surfaceView.setZOrderOnTop(true);
        surfaceView.getHolder().setFormat(1);
        surfaceView.getHolder().addCallback(this);
        LinearLayout.LayoutParams surfaceParams = new LinearLayout.LayoutParams(-1, dp(320));
        surfaceParams.setMargins(0, 0, 0, dp(14));
        root.addView(surfaceView, surfaceParams);
        this.logView = new TextView(this);
        this.logView.setTextSize(13.0f);
        this.logView.setTextColor(Color.rgb(222, 222, 222));
        this.logView.setTypeface(Typeface.MONOSPACE);
        this.logView.setText(READY_TEXT);
        this.logView.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(this.logView, new LinearLayout.LayoutParams(-1, -2));
        setContentView(scrollView);
    }

    private void startGuestRuntime() {
        String installId = getExtra(EXTRA_INSTALL_ID, "");
        StringBuilder out = new StringBuilder();
        out.append("실행 요청\n");
        out.append("- 게임: ").append(getExtra(EXTRA_LABEL, installId)).append('\n');
        out.append("- 패키지: ").append(getExtra(EXTRA_PACKAGE, "")).append('\n');
        out.append("- 런처: ").append(getExtra(EXTRA_LAUNCHER, "")).append('\n');
        out.append("- ABI: ").append(getExtra(EXTRA_ABIS, "")).append('\n');
        out.append("- ARM32 라이브러리: ").append(getIntent().getIntExtra(EXTRA_ARM32_LIB_COUNT, 0)).append("개\n");
        out.append("- APK: ").append(getExtra(EXTRA_APK_PATH, "")).append('\n');
        out.append("- lib: ").append(getExtra(EXTRA_LIB_DIR, "")).append('\n');
        out.append("- data: ").append(getExtra(EXTRA_DATA_DIR, "")).append("\n\n");
        if (!NATIVE_RUNTIME_LOADED) {
            out.append("native runtime load failed\n");
            appendRuntimeBlock(out.toString());
            return;
        }
        try {
            out.append(nativeStartGuest(getFilesDir().getAbsolutePath() + "/single_vm", installId));
        } catch (RuntimeException e) {
            out.append("nativeStartGuest failed: ").append(e.getMessage());
        } catch (UnsatisfiedLinkError e2) {
            out.append("nativeStartGuest JNI missing: ").append(e2.getMessage());
        }
        appendRuntimeBlock(out.toString());
    }

    private void startFrameLoop() {
        if (!NATIVE_RUNTIME_LOADED || this.frameLoopRunning || !this.surfaceAttached) {
            return;
        }
        this.frameLoopRunning = true;
        this.frameTicks = 0;
        appendRuntimeLog("\n\nframe loop: persistent NativeRender tick start");
        this.frameHandler.postDelayed(this.frameTicker, FRAME_TICK_DELAY_MS);
    }

    private void stopFrameLoop() {
        if (!this.frameLoopRunning) {
            return;
        }
        this.frameLoopRunning = false;
        this.frameHandler.removeCallbacks(this.frameTicker);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void runFrameTick() {
        if (!this.frameLoopRunning || !NATIVE_RUNTIME_LOADED) {
            return;
        }
        if (this.frameTicks >= FRAME_TICK_LIMIT) {
            this.frameLoopRunning = false;
            appendRuntimeLog("\n\nframe loop: stopped after 8 ticks");
        } else {
            this.frameTicks++;
            new Thread(new Runnable() { // from class: com.example.singlevm.GuestRunActivity.2
                @Override // java.lang.Runnable
                public void run() {
                    String result;
                    try {
                        result = GuestRunActivity.this.nativeRunFrame();
                    } catch (RuntimeException e) {
                        result = "nativeRunFrame failed: " + e.getMessage();
                    } catch (UnsatisfiedLinkError e2) {
                        result = "nativeRunFrame JNI missing: " + e2.getMessage();
                    }
                    final String output = "\n\n" + result;
                    GuestRunActivity.this.runOnUiThread(new Runnable() { // from class: com.example.singlevm.GuestRunActivity.2.1
                        @Override // java.lang.Runnable
                        public void run() {
                            if (GuestRunActivity.this.frameLoopRunning) {
                                GuestRunActivity.this.appendRuntimeLog(output);
                                GuestRunActivity.this.frameHandler.postDelayed(GuestRunActivity.this.frameTicker, GuestRunActivity.FRAME_TICK_DELAY_MS);
                            }
                        }
                    });
                }
            }, "single-vm-frame-tick").start();
        }
    }

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceCreated(SurfaceHolder holder) {
        this.surfaceAttached = true;
        if (Android7GuestEngineAdapter.ID.equals(this.engineId)) {
            if (EMUGL_PROBE_LOADED) {
                try {
                    appendRuntimeLog("\n\nAOSP emugl host probe\n" + nativeProbeEmugl());
                    appendRuntimeLog("\n" + nativeAttachEmuglSurface(holder.getSurface()));
                } catch (RuntimeException | UnsatisfiedLinkError e) {
                    appendRuntimeLog("\n\nAOSP emugl surface attach failed: " + e.getMessage());
                }
            }
            Process existing = activeQemuProcess;
            if (existing != null && existing.isAlive()) {
                this.runtimeStarted = true;
                this.qemuProcess = existing;
                appendRuntimeLog("\n\nsurface bridge: reattached to running Android 7 guest");
                return;
            }
            if (this.runtimeStarted) {
                appendRuntimeLog("\n\nsurface bridge: Android 7 guest already running");
                return;
            }
            this.runtimeStarted = true;
            File vmRoot = new File(getFilesDir(), "single_vm");
            EngineReadiness readiness = this.engineAdapter.inspect(this, vmRoot);
            appendRuntimeLog("\n\nAndroid 7.0 게스트 부팅 사전 검사\n" + readiness.summary + "\n" + readiness.details);
            String nativeDir = getApplicationInfo().nativeLibraryDir;
            String corePath = new File(nativeDir, Android7GuestEngineAdapter.CORE_LIBRARY_NAME).getAbsolutePath();
            List<String> command = buildModernQemuCommand(corePath, vmRoot.getAbsolutePath());
            appendRuntimeLog("\n\nQEMU 11 실행 파일: " + corePath);
            appendRuntimeLog("\n\nQEMU_BOOT_ARGS\n" + renderCommand(command));
            if (!EMUGL_PROBE_LOADED) {
                appendRuntimeLog("\n\nAOSP emugl host probe library load failed");
            }
            if (readiness.ready) {
                startAndroid7Guest(command, vmRoot.getAbsolutePath());
                return;
            }
            return;
        }
        if (!NATIVE_RUNTIME_LOADED) {
            appendRuntimeLog("\n\nsurface bridge: native runtime not loaded");
            return;
        }
        try {
            appendRuntimeLog("\n\n" + nativeAttachSurface(holder.getSurface()));
        } catch (RuntimeException e2) {
            appendRuntimeLog("\n\nnativeAttachSurface failed: " + e2.getMessage());
        } catch (UnsatisfiedLinkError e3) {
            appendRuntimeLog("\n\nnativeAttachSurface JNI missing: " + e3.getMessage());
        }
        if (!this.runtimeStarted) {
            this.runtimeStarted = true;
            startGuestRuntime();
        }
        startFrameLoop();
    }

    private List<String> buildModernQemuCommand(String corePath, String vmRootPath) {
        File selectedKernel;
        File selectedRamdisk;
        File imageRoot = new File(vmRootPath, Android7GuestEngineAdapter.IMAGE_DIRECTORY);
        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        File launcher = new File(nativeDir, "libpodroid-launcher.so");
        List<String> command = new ArrayList<>();
        if (launcher.isFile()) {
            command.add(launcher.getAbsolutePath());
        }
        command.add(corePath);
        File android54Kernel = new File(imageRoot, "kernel-android54");
        File virtKernel = new File(imageRoot, "kernel-virt");
        if (android54Kernel.isFile()) {
            selectedKernel = android54Kernel;
        } else {
            selectedKernel = virtKernel.isFile() ? virtKernel : new File(imageRoot, "kernel");
        }
        File android54Ramdisk = new File(imageRoot, "ramdisk-android54.img");
        if (android54Ramdisk.isFile()) {
            selectedRamdisk = android54Ramdisk;
        } else {
            selectedRamdisk = new File(imageRoot, "ramdisk.img");
        }
        command.add("-machine");
        command.add("virt,gic-version=3");
        command.add("-cpu");
        command.add(selectedKernel.equals(new File(imageRoot, "kernel")) ? "cortex-a15" : "cortex-a53");
        command.add("-accel");
        command.add("tcg,thread=multi,tb-size=256");
        command.add("-smp");
        command.add("2");
        command.add("-m");
        command.add("1024");
        command.add("-display");
        command.add("none");
        command.add("-device");
        command.add("virtio-gpu-pci,xres=480,yres=800");
        File transportDir = new File(vmRootPath, "transport");
        if (!transportDir.exists()) {
            transportDir.mkdirs();
        }
        command.add("-device");
        command.add("virtio-serial-pci,disable-legacy=on");
        int pipeIndex = 0;
        while (pipeIndex < FRAME_TICK_LIMIT) {
            File nativeDir2 = nativeDir;
            File pipeSocket = new File(transportDir, "pipe" + pipeIndex + ".sock");
            if (pipeSocket.exists()) {
                pipeSocket.delete();
            }
            String pipeId = "singlevmpipe" + pipeIndex;
            command.add("-chardev");
            command.add("socket,id=" + pipeId + ",path=" + pipeSocket.getAbsolutePath() + ",server=on,wait=off");
            command.add("-device");
            command.add("virtserialport,chardev=" + pipeId + ",name=org.singlevm.pipe." + pipeIndex);
            pipeIndex++;
            nativeDir = nativeDir2;
            launcher = launcher;
        }
        command.add("-monitor");
        command.add("none");
        command.add("-nic");
        command.add("none");
        command.add("-serial");
        command.add("stdio");
        command.add("-kernel");
        command.add(selectedKernel.getAbsolutePath());
        command.add("-initrd");
        command.add(selectedRamdisk.getAbsolutePath());
        File userdata = new File(imageRoot, "userdata.img");
        if (userdata.isFile()) {
            command.add("-drive");
            command.add("if=none,id=userdata,format=raw,file=" + userdata.getAbsolutePath());
            command.add("-device");
            command.add("virtio-blk-device,drive=userdata");
        }
        File cache = new File(imageRoot, "cache.img");
        if (cache.isFile()) {
            command.add("-drive");
            command.add("if=none,id=cache,format=raw,file=" + cache.getAbsolutePath());
            command.add("-device");
            command.add("virtio-blk-device,drive=cache");
        }
        command.add("-drive");
        command.add("if=none,id=system,format=raw,readonly=on,file=" + new File(imageRoot, "system.img").getAbsolutePath());
        command.add("-device");
        command.add("virtio-blk-device,drive=system");
        command.add("-append");
        command.add("console=ttyAMA0 androidboot.hardware=ranchu androidboot.selinux=permissive binder.devices=binder,hwbinder,vndbinder rdinit=/init.wrapper root=/dev/ram0 rw");
        return command;
    }

    private String renderCommand(List<String> command) {
        StringBuilder output = new StringBuilder();
        for (String arg : command) {
            if (output.length() > 0) {
                output.append(' ');
            }
            output.append('\"').append(arg).append('\"');
        }
        return output.toString();
    }

    private void startAndroid7Guest(final List<String> command, final String vmRootPath) {
        appendRuntimeLog("\n\nQEMU_SESSION_START_REQUESTED");
        new Thread(new Runnable() { // from class: com.example.singlevm.GuestRunActivity.3
            @Override // java.lang.Runnable
            public void run() {
                String result;
                try {
                    File logDir = new File(vmRootPath, "logs");
                    if (!logDir.exists()) {
                        logDir.mkdirs();
                    }
                    File outputLog = new File(logDir, "qemu-modern.log");
                    ProcessBuilder builder = new ProcessBuilder((List<String>) command);
                    builder.directory(GuestRunActivity.this.getFilesDir());
                    builder.environment().put("LD_LIBRARY_PATH", GuestRunActivity.this.getApplicationInfo().nativeLibraryDir + ":" + GuestRunActivity.this.getFilesDir().getAbsolutePath());
                    builder.redirectErrorStream(true);
                    builder.redirectOutput(outputLog);
                    GuestRunActivity.this.qemuProcess = builder.start();
                    Process unused = GuestRunActivity.activeQemuProcess = GuestRunActivity.this.qemuProcess;
                    if (GuestRunActivity.EMUGL_PROBE_LOADED) {
                        StringBuilder bridgeStatus = new StringBuilder();
                        int pipeIndex = 0;
                        while (pipeIndex < GuestRunActivity.FRAME_TICK_LIMIT) {
                            String status = GuestRunActivity.this.nativeStartEmuglBridge(new File(vmRootPath, "transport/pipe" + pipeIndex + ".sock").getAbsolutePath(), pipeIndex == 0);
                            if (bridgeStatus.length() > 0) {
                                bridgeStatus.append('\n');
                            }
                            bridgeStatus.append(status);
                            pipeIndex++;
                        }
                        final String bridgeResult = bridgeStatus.toString();
                        GuestRunActivity.this.runOnUiThread(new Runnable() { // from class: com.example.singlevm.GuestRunActivity.3.1
                            @Override // java.lang.Runnable
                            public void run() {
                                GuestRunActivity.this.appendRuntimeLog("\n" + bridgeResult);
                            }
                        });
                    }
                    GuestRunActivity.this.scheduleGuestDiagnostics(GuestRunActivity.this.qemuProcess);
                    int exitCode = GuestRunActivity.this.qemuProcess.waitFor();
                    if (GuestRunActivity.activeQemuProcess == GuestRunActivity.this.qemuProcess) {
                        Process unused2 = GuestRunActivity.activeQemuProcess = null;
                    }
                    result = "QEMU_PROCESS_EXIT=" + exitCode + "\n로그: " + outputLog.getAbsolutePath();
                } catch (IOException | InterruptedException | RuntimeException e) {
                    Thread.currentThread().interrupt();
                    result = "QEMU_SESSION_FAILED: " + e.getMessage();
                }
                final String output = result;
                GuestRunActivity.this.runOnUiThread(new Runnable() { // from class: com.example.singlevm.GuestRunActivity.3.2
                    @Override // java.lang.Runnable
                    public void run() {
                        GuestRunActivity.this.appendRuntimeLog("\n\n" + output);
                    }
                });
            }
        }, "single-vm-qemu").start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void scheduleGuestDiagnostics(final Process process) {
        new Thread(new Runnable() { // from class: com.example.singlevm.GuestRunActivity.4
            @Override // java.lang.Runnable
            public void run() {
                try {
                    Thread.sleep(60000L);
                    if (process.isAlive()) {
                        process.getOutputStream().write("echo VM_DIAG_FILE_BEGIN\rcat /data/local/tmp/bootdiag.log 2>&1\recho VM_DIAG_FILE_END\r".getBytes(StandardCharsets.US_ASCII));
                        process.getOutputStream().flush();
                    }
                } catch (IOException | InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "single-vm-guest-diagnostics").start();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        Process process = this.qemuProcess;
        if (!isChangingConfigurations() && process != null && process.isAlive()) {
            process.destroy();
            if (activeQemuProcess == process) {
                activeQemuProcess = null;
            }
        }
        super.onDestroy();
    }

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        appendRuntimeLog("\nsurface changed: " + width + "x" + height);
    }

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceDestroyed(SurfaceHolder holder) {
        this.surfaceAttached = false;
        stopFrameLoop();
        if (Android7GuestEngineAdapter.ID.equals(this.engineId) && EMUGL_PROBE_LOADED) {
            try {
                nativeDetachEmuglSurface();
            } catch (RuntimeException e) {
            } catch (UnsatisfiedLinkError e2) {
            }
        }
        if (!Android7GuestEngineAdapter.ID.equals(this.engineId) && NATIVE_RUNTIME_LOADED) {
            try {
                appendRuntimeLog("\n" + nativeDetachSurface());
                return;
            } catch (RuntimeException e3) {
                appendRuntimeLog("\nnativeDetachSurface failed: " + e3.getMessage());
                return;
            } catch (UnsatisfiedLinkError e4) {
                appendRuntimeLog("\nnativeDetachSurface JNI missing: " + e4.getMessage());
                return;
            }
        }
        appendRuntimeLog("\nsurface bridge: destroyed");
    }

    private void setRuntimeLog(String value) {
        this.runtimeLog = value;
        if (this.logView != null) {
            this.logView.setText(this.runtimeLog);
        }
        writeRuntimeLog(this.runtimeLog);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void appendRuntimeLog(String value) {
        setRuntimeLog(this.runtimeLog + value);
    }

    private void appendRuntimeBlock(String value) {
        if (this.runtimeLog == null || this.runtimeLog.isEmpty() || READY_TEXT.contentEquals(this.runtimeLog)) {
            setRuntimeLog(value);
        } else {
            appendRuntimeLog("\n\n" + value);
        }
    }

    private String getExtra(String key, String fallback) {
        String value = getIntent().getStringExtra(key);
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    private int dp(int value) {
        return (int) ((value * getResources().getDisplayMetrics().density) + 0.5f);
    }

    private void writeRuntimeLog(String value) {
        File logDir = new File(getFilesDir(), "single_vm/logs");
        if (!logDir.exists() && !logDir.mkdirs()) {
            return;
        }
        File logFile = new File(logDir, "last_run.txt");
        try {
            FileOutputStream output = new FileOutputStream(logFile, false);
            try {
                output.write(value.getBytes(StandardCharsets.UTF_8));
                output.close();
            } catch (Throwable th) {
                try {
                    output.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (IOException e) {
        }
    }
}

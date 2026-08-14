package com.example.singlevm;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Gravity;
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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
    /**
     * Number of virtio-serial pipes wired between host and guest. jadx had collapsed this into
     * FRAME_TICK_LIMIT because both happen to be 8; they are unrelated.
     */
    private static final int TRANSPORT_PIPE_COUNT = 8;
    private static final long GUEST_DIAGNOSTICS_DELAY_MS = 60_000L;
    /** Drop a one-line kernel cmdline here to override the default without rebuilding the app. */
    private static final String KERNEL_CMDLINE_FILE = "kernel_cmdline.txt";
    /**
     * earlycon is what makes a silent boot diagnosable. console=ttyAMA0 only produces output once
     * the PL011 driver is up, and a ranchu kernel consoles on goldfish_tty instead — so a kernel
     * missing CONFIG_SERIAL_AMBA_PL011 boots and dies without printing a single character.
     * earlycon writes straight to the virt machine's UART at 0x09000000 from very early on,
     * before driver probing, and keep_bootcon stops it being handed off and silenced.
     */
    private static final String DEFAULT_KERNEL_CMDLINE =
            "console=ttyAMA0 earlycon=pl011,0x09000000 keep_bootcon ignore_loglevel"
                    + " androidboot.hardware=ranchu androidboot.selinux=permissive"
                    + " binder.devices=binder,hwbinder,vndbinder"
                    + " rdinit=/init.wrapper root=/dev/ram0 rw";
    /** How many trailing QEMU output lines are kept on screen. */
    private static final int QEMU_LOG_TAIL_LINES = 120;
    /** Minimum gap between on-screen refreshes while QEMU output is streaming in. */
    private static final long QEMU_LOG_UI_FLUSH_MS = 400L;
    /** How often the running log is mirrored somewhere a file manager can open. */
    private static final long QEMU_LOG_MIRROR_MS = 3_000L;
    /** A guest that dies faster than this never really booted; say so loudly. */
    private static final long QEMU_EARLY_EXIT_MS = 5_000L;
    private static final String GUEST_DIAGNOSTICS_COMMAND =
            "echo VM_DIAG_FILE_BEGIN\rcat /data/local/tmp/bootdiag.log 2>&1\recho VM_DIAG_FILE_END\r";
    private static final boolean NATIVE_RUNTIME_LOADED;
    private static final String READY_TEXT = "런타임 준비 중...";
    private static volatile Process activeQemuProcess;
    private EngineAdapter engineAdapter;
    private String engineId;
    private TextView logView;
    private volatile Process qemuProcess;
    private final Deque<String> qemuTail = new ArrayDeque<>();
    private volatile boolean qemuTerminatedByHost = false;
    private String runtimeLog = "";
    private boolean runtimeStarted = false;
    private boolean surfaceAttached = false;
    private boolean frameLoopRunning = false;
    private int frameTicks = 0;
    private final Handler frameHandler = new Handler(Looper.getMainLooper());
    private final Runnable frameTicker = this::runFrameTick;

    private native String nativeAttachEmuglSurface(Surface surface);

    private native String nativeAttachSurface(Surface surface);

    private native void nativeDetachEmuglSurface();

    private native String nativeDetachSurface();

    private native String nativeProbeEmugl();

    /* JADX INFO: Access modifiers changed from: private */
    public native String nativeRunFrame();

    /* JADX INFO: Access modifiers changed from: private */
    public native String nativeStartEmuglBridge(String pipeSocketPath, boolean primaryPipe);

    private native String nativeStartGuest(String vmRootPath, String installId);

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

    /**
     * Called from native code: opens {@code path} (a filesystem path or a {@code content://} URI)
     * and hands the raw file descriptor over to the caller, which then owns it.
     *
     * @return the detached fd, or -1 if the path could not be opened.
     */
    public int get_fd(String path) {
        if (path == null) {
            return -1;
        }
        ParcelFileDescriptor descriptor = null;
        try {
            if (path.startsWith("content://")) {
                descriptor = getContentResolver().openFileDescriptor(Uri.parse(path), "rw");
                if (descriptor == null) {
                    descriptor = getContentResolver().openFileDescriptor(Uri.parse(path), "r");
                }
            } else {
                descriptor = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_WRITE);
            }
        } catch (Exception e) {
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                }
            }
            return -1;
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
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(18));
        scrollView.addView(root, new FrameLayout.LayoutParams(-1, -2));
        TextView title = new TextView(this);
        title.setText(getExtra(EXTRA_LABEL, "게임"));
        title.setTextSize(24.0f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = new TextView(this);
        subtitle.setText(this.engineAdapter.displayName());
        subtitle.setTextSize(14.0f);
        subtitle.setTextColor(Color.rgb(155, 155, 155));
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        SurfaceView surfaceView = new SurfaceView(this);
        surfaceView.setBackgroundColor(Color.BLACK);
        surfaceView.setZOrderOnTop(true);
        surfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);
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

    private void runFrameTick() {
        if (!this.frameLoopRunning || !NATIVE_RUNTIME_LOADED) {
            return;
        }
        if (this.frameTicks >= FRAME_TICK_LIMIT) {
            this.frameLoopRunning = false;
            appendRuntimeLog("\n\nframe loop: stopped after " + FRAME_TICK_LIMIT + " ticks");
            return;
        }
        this.frameTicks++;
        new Thread(() -> {
            String result;
            try {
                result = nativeRunFrame();
            } catch (RuntimeException e) {
                result = "nativeRunFrame failed: " + e.getMessage();
            } catch (UnsatisfiedLinkError e) {
                result = "nativeRunFrame JNI missing: " + e.getMessage();
            }
            final String output = "\n\n" + result;
            runOnUiThread(() -> {
                if (this.frameLoopRunning) {
                    appendRuntimeLog(output);
                    this.frameHandler.postDelayed(this.frameTicker, FRAME_TICK_DELAY_MS);
                }
            });
        }, "single-vm-frame-tick").start();
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
        File imageRoot = new File(vmRootPath, Android7GuestEngineAdapter.IMAGE_DIRECTORY);
        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        File launcher = new File(nativeDir, "libpodroid-launcher.so");
        List<String> command = new ArrayList<>();
        if (launcher.isFile()) {
            command.add(launcher.getAbsolutePath());
        }
        command.add(corePath);
        // Selection lives in the engine adapter so the readiness check and the boot path can
        // never disagree about which kernel/ramdisk names are acceptable.
        File selectedKernel = Android7GuestEngineAdapter.selectKernel(imageRoot);
        File selectedRamdisk = Android7GuestEngineAdapter.selectRamdisk(imageRoot);
        command.add("-machine");
        command.add("virt,gic-version=3");
        command.add("-cpu");
        // A plain "kernel" is the legacy ARM32 (ranchu/goldfish) image; the android54 and virt
        // kernels are the newer 64-bit-capable ones.
        command.add("kernel".equals(selectedKernel.getName()) ? "cortex-a15" : "cortex-a53");
        command.add("-accel");
        command.add("tcg,thread=multi,tb-size=256");
        command.add("-smp");
        command.add("2");
        command.add("-m");
        command.add("1024");
        command.add("-display");
        command.add("none");
        command.add("-device");
        command.add("virtio-gpu-device,xres=480,yres=800");
        File transportDir = new File(vmRootPath, "transport");
        if (!transportDir.exists()) {
            transportDir.mkdirs();
        }
        // virtio-mmio throughout, matching the virtio-blk-device disks below. The guest kernel
        // then needs only CONFIG_VIRTIO_MMIO and no PCIe host bridge — goldfish/ranchu derived
        // ARM kernels are commonly built mmio-only, and on those a -pci transport would leave
        // /dev/vport0p* missing, which silently kills the emugl channel even when the guest boots.
        //
        // disable-legacy is deliberately absent: it is a virtio-pci property and QEMU rejects it
        // outright on the mmio device ("Property 'virtio-serial-device.disable-legacy' not found").
        command.add("-device");
        command.add("virtio-serial-device");
        for (int pipeIndex = 0; pipeIndex < TRANSPORT_PIPE_COUNT; pipeIndex++) {
            File pipeSocket = new File(transportDir, "pipe" + pipeIndex + ".sock");
            if (pipeSocket.exists()) {
                pipeSocket.delete();
            }
            String pipeId = "singlevmpipe" + pipeIndex;
            command.add("-chardev");
            command.add("socket,id=" + pipeId + ",path=" + pipeSocket.getAbsolutePath() + ",server=on,wait=off");
            command.add("-device");
            command.add("virtserialport,chardev=" + pipeId + ",name=org.singlevm.pipe." + pipeIndex);
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
        command.add(resolveKernelCmdline());
        return command;
    }

    /**
     * The kernel command line, overridable at runtime from a plain text file in external storage.
     *
     * <p>Each change here otherwise costs a CI rebuild, a download and a reinstall, which is a
     * brutal loop for tuning boot arguments. The override file sits somewhere a file manager can
     * reach, so a line can be edited and the guest relaunched in seconds.
     */
    private String resolveKernelCmdline() {
        File override = kernelCmdlineOverrideFile();
        if (override != null && override.isFile()) {
            String text = readSmallFile(override);
            if (text != null && !text.trim().isEmpty()) {
                // Editors love to wrap; a kernel command line has to be one line.
                String cmdline = text.replace('\n', ' ').replace('\r', ' ').trim();
                appendRuntimeLog("\n\n커널 cmdline 재정의 사용: " + override.getAbsolutePath()
                        + "\n" + cmdline);
                return cmdline;
            }
        }
        return DEFAULT_KERNEL_CMDLINE;
    }

    private File kernelCmdlineOverrideFile() {
        File dir = getExternalFilesDir("import");
        return dir == null ? null : new File(dir, KERNEL_CMDLINE_FILE);
    }

    private String readSmallFile(File file) {
        if (file.length() > 8192) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int read = 0;
            while (read < buffer.length) {
                int n = in.read(buffer, read, buffer.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
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
        new Thread(() -> {
            String result;
            try {
                File logDir = new File(vmRootPath, "logs");
                if (!logDir.exists()) {
                    logDir.mkdirs();
                }
                File outputLog = new File(logDir, "qemu-modern.log");
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(getFilesDir());
                builder.environment().put("LD_LIBRARY_PATH",
                        getApplicationInfo().nativeLibraryDir + ":" + getFilesDir().getAbsolutePath());
                builder.redirectErrorStream(true);
                // Deliberately NOT redirectOutput(outputLog): that buries the kernel's own panic
                // message in an app-private file the user cannot open without adb or root, and
                // leaves the screen blank between launch and exit. Stream it instead, so a guest
                // that dies during boot says why on screen.
                long startedAt = SystemClock.elapsedRealtime();
                this.qemuProcess = builder.start();
                activeQemuProcess = this.qemuProcess;
                if (EMUGL_PROBE_LOADED) {
                    StringBuilder bridgeStatus = new StringBuilder();
                    for (int pipeIndex = 0; pipeIndex < TRANSPORT_PIPE_COUNT; pipeIndex++) {
                        String socketPath =
                                new File(vmRootPath, "transport/pipe" + pipeIndex + ".sock").getAbsolutePath();
                        String status = nativeStartEmuglBridge(socketPath, pipeIndex == 0);
                        if (bridgeStatus.length() > 0) {
                            bridgeStatus.append('\n');
                        }
                        bridgeStatus.append(status);
                    }
                    final String bridgeResult = bridgeStatus.toString();
                    runOnUiThread(() -> appendRuntimeLog("\n" + bridgeResult));
                }
                scheduleGuestDiagnostics(this.qemuProcess);
                streamQemuOutput(this.qemuProcess, outputLog);
                int exitCode = this.qemuProcess.waitFor();
                long ranForMs = SystemClock.elapsedRealtime() - startedAt;
                if (activeQemuProcess == this.qemuProcess) {
                    activeQemuProcess = null;
                }
                result = describeGuestExit(exitCode, ranForMs, outputLog);
            } catch (IOException | InterruptedException | RuntimeException e) {
                Thread.currentThread().interrupt();
                result = "QEMU_SESSION_FAILED: " + e.getMessage();
            }
            final String output = result;
            runOnUiThread(() -> appendRuntimeLog("\n\n" + output));
        }, "single-vm-qemu").start();
    }

    /**
     * Pumps QEMU's combined stdout/stderr to {@code outputLog} while mirroring a bounded tail to
     * the screen. Returns when the guest closes the stream, i.e. when the process is on its way
     * out. The stream must be drained regardless: if nobody reads it, QEMU blocks once the pipe
     * buffer fills and the guest wedges partway through boot.
     */
    private void streamQemuOutput(Process process, File outputLog) {
        long[] lastFlush = {0L};
        long[] lastMirror = {0L};
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             Writer logWriter =
                     new OutputStreamWriter(new FileOutputStream(outputLog, false), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                logWriter.write(line);
                logWriter.write('\n');
                logWriter.flush();
                synchronized (this.qemuTail) {
                    this.qemuTail.addLast(line);
                    while (this.qemuTail.size() > QEMU_LOG_TAIL_LINES) {
                        this.qemuTail.removeFirst();
                    }
                }
                long now = SystemClock.elapsedRealtime();
                if (now - lastFlush[0] >= QEMU_LOG_UI_FLUSH_MS) {
                    lastFlush[0] = now;
                    runOnUiThread(this::renderLog);
                }
                // Mirror to external storage while the guest is still running. Copying only at
                // exit meant the boot log could not be read without killing the guest first, and
                // leaving the activity to go read it is exactly what kills it.
                if (now - lastMirror[0] >= QEMU_LOG_MIRROR_MS) {
                    lastMirror[0] = now;
                    copyLogToSharedDir(outputLog);
                }
            }
        } catch (IOException e) {
            synchronized (this.qemuTail) {
                this.qemuTail.addLast("[로그 스트림 중단: " + e.getMessage() + "]");
            }
        }
        runOnUiThread(this::renderLog);
    }

    /**
     * Turns the raw exit code into something the user can act on. An immediate exit means the
     * command line or the images are wrong rather than the guest having shut down, so the tail of
     * QEMU's own output is the useful part and gets pulled to the front.
     */
    private String describeGuestExit(int exitCode, long ranForMs, File outputLog) {
        StringBuilder out = new StringBuilder();
        out.append("QEMU_PROCESS_EXIT=").append(exitCode)
                .append(" (").append(ranForMs).append("ms 실행)\n");
        if (this.qemuTerminatedByHost) {
            out.append("\n※ 앱이 종료를 요청했습니다 (액티비티 종료). 게스트가 스스로 끝난 게 아닙니다.\n");
        }
        boolean silent;
        synchronized (this.qemuTail) {
            silent = this.qemuTail.isEmpty();
        }
        if (silent) {
            out.append("\n⚠ QEMU 시리얼 출력이 한 줄도 없습니다.\n")
                    .append("커널이 콘솔에 아무것도 쓰지 못했다는 뜻입니다. 흔한 원인:\n")
                    .append("- 커널에 CONFIG_SERIAL_AMBA_PL011 이 없음 (ranchu 커널은 goldfish_tty 를 씀)\n")
                    .append("- 커널이 이 머신/CPU 조합에서 아예 시작하지 못함\n")
                    .append("cmdline 의 earlycon 으로도 안 나오면 커널을 바꿔야 합니다.\n")
                    .append("재정의 파일: Android/data/" + getPackageName() + "/files/import/"
                            + KERNEL_CMDLINE_FILE + "\n");
        }
        if (ranForMs < QEMU_EARLY_EXIT_MS) {
            out.append("\n⚠ 게스트가 부팅하지 못하고 즉시 종료했습니다.\n")
                    .append("커널/램디스크/시스템 이미지가 이 QEMU 구성(-machine virt)과 맞는지,\n")
                    .append("램디스크에 커널 cmdline이 요구하는 /init.wrapper 가 있는지 확인하세요.\n");
            String tail;
            synchronized (this.qemuTail) {
                tail = String.join("\n", this.qemuTail);
            }
            if (!tail.isEmpty()) {
                out.append("\n--- QEMU 마지막 출력 ---\n").append(tail).append('\n');
            } else {
                out.append("\nQEMU가 아무 출력도 남기지 않았습니다 (실행 파일 자체가 기동 실패했을 가능성).\n");
            }
        }
        out.append("\n로그: ").append(outputLog.getAbsolutePath());
        File shared = copyLogToSharedDir(outputLog);
        if (shared != null) {
            out.append("\n복사본(파일 관리자로 접근 가능): ").append(shared.getAbsolutePath());
        }
        return out.toString();
    }

    /**
     * Mirrors the QEMU log into external app storage. getFilesDir() is unreadable without adb or
     * root, which makes the single most useful diagnostic artifact effectively invisible.
     */
    private File copyLogToSharedDir(File outputLog) {
        File sharedDir = getExternalFilesDir("logs");
        if (sharedDir == null || !outputLog.isFile()) {
            return null;
        }
        if (!sharedDir.exists() && !sharedDir.mkdirs()) {
            return null;
        }
        File shared = new File(sharedDir, outputLog.getName());
        try (InputStream in = new FileInputStream(outputLog);
             OutputStream out = new FileOutputStream(shared, false)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return shared;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * One minute after boot, pokes the guest's serial console to dump its boot diagnostics into
     * the QEMU log. Fire-and-forget: failures are swallowed on purpose.
     */
    private void scheduleGuestDiagnostics(final Process process) {
        new Thread(() -> {
            try {
                Thread.sleep(GUEST_DIAGNOSTICS_DELAY_MS);
                if (process.isAlive()) {
                    process.getOutputStream().write(GUEST_DIAGNOSTICS_COMMAND.getBytes(StandardCharsets.US_ASCII));
                    process.getOutputStream().flush();
                }
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "single-vm-guest-diagnostics").start();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        Process process = this.qemuProcess;
        if (!isChangingConfigurations() && process != null && process.isAlive()) {
            // QEMU handles SIGTERM by shutting the VM down cleanly and exiting 0, which is
            // indistinguishable in the log from the guest having powered itself off. Record that
            // we asked for it so the exit report can say which happened.
            this.qemuTerminatedByHost = true;
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
        renderLog();
        // Persist exactly what the screen shows. Writing only runtimeLog left last_run.txt
        // without any of the guest's serial output, so sharing that file looked like the guest
        // had printed nothing at all when in fact it had.
        writeRuntimeLog(composeLog());
    }

    /** The full transcript: setup messages plus whatever the guest has printed so far. */
    private String composeLog() {
        String tail;
        synchronized (this.qemuTail) {
            tail = this.qemuTail.isEmpty() ? "" : String.join("\n", this.qemuTail);
        }
        return tail.isEmpty() ? this.runtimeLog : this.runtimeLog + "\n\n--- QEMU ---\n" + tail;
    }

    /**
     * Repaints the log view from the setup transcript plus the streamed QEMU tail. Kept separate
     * from {@link #setRuntimeLog} so streaming output can refresh the screen without rewriting
     * last_run.txt on every line.
     */
    private void renderLog() {
        if (this.logView != null) {
            this.logView.setText(composeLog());
        }
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

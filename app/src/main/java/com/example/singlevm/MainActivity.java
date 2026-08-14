package com.example.singlevm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.example.singlevm.engine.Android7GuestEngineAdapter;
import com.example.singlevm.engine.EngineAdapter;
import com.example.singlevm.engine.EngineReadiness;
import com.example.singlevm.engine.EngineRegistry;
import com.example.singlevm.engine.LegacyArmEngineAdapter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes2.dex */
public class MainActivity extends Activity {
    private static final String ACTION_IMPORT_ADB_GUEST = "com.example.singlevm.IMPORT_ADB_GUEST";
    private static final String KEY_ACTIVE_PACKAGE = "active_package";
    private static final String KEY_INSTALLED_PACKAGES = "installed_packages";
    private static final int MANIFEST_VERSION = 1;
    private static final boolean NATIVE_RUNTIME_LOADED;
    private static final String PREFS = "single_vm_state";
    private static final int REQUEST_IMPORT_ANDROID7_IMAGE = 7002;
    private static final int REQUEST_IMPORT_APK = 7001;
    private File android7ImageDir;
    private File apkDir;
    private GridLayout appGrid;
    private File appsDir;
    private GridLayout commandGrid;
    private File dataDir;
    private TextView detailsView;
    private File externalGuestApk;
    private File externalImportDir;
    private File logsDir;
    private File nativeLibDir;
    private SharedPreferences prefs;
    private TextView statusView;
    private File vmRoot;

    private native String nativeProbeRuntime(String str, String str2);

    static {
        boolean loaded;
        try {
            System.loadLibrary("singlevm_runtime");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            loaded = false;
        }
        NATIVE_RUNTIME_LOADED = loaded;
    }

    @Override // android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.prefs = getSharedPreferences(PREFS, 0);
        initPaths();
        buildUi();
        ensureVmDirs();
        refreshState();
        handleStartupIntent(getIntent());
    }

    @Override // android.app.Activity
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleStartupIntent(intent);
    }

    private void handleStartupIntent(Intent intent) {
        if (intent != null && ACTION_IMPORT_ADB_GUEST.equals(intent.getAction())) {
            importAdbGuestApk(false);
        }
    }

    private void initPaths() {
        this.vmRoot = new File(getFilesDir(), "single_vm");
        this.apkDir = new File(this.vmRoot, "apks");
        this.appsDir = new File(this.vmRoot, "apps");
        this.dataDir = new File(this.vmRoot, "data");
        this.nativeLibDir = new File(this.vmRoot, "native_libs");
        this.logsDir = new File(this.vmRoot, "logs");
        this.externalImportDir = getExternalFilesDir("import");
        if (this.externalImportDir == null) {
            this.externalImportDir = new File(this.vmRoot, "adb_import");
        }
        this.externalGuestApk = new File(this.externalImportDir, "guest.apk");
        this.android7ImageDir = new File(this.vmRoot, Android7GuestEngineAdapter.IMAGE_DIRECTORY);
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(245, 245, 245));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(MANIFEST_VERSION);
        root.setPadding(dp(14), dp(22), dp(14), dp(18));
        scrollView.addView(root, new FrameLayout.LayoutParams(-1, -2));
        TextView search = new TextView(this);
        search.setText("⌕  앱 검색");
        search.setTextSize(20.0f);
        search.setGravity(17);
        search.setTextColor(Color.rgb(0, 150, 136));
        search.setPadding(0, dp(18), 0, dp(18));
        root.addView(search, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(0, 150, 136));
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(MANIFEST_VERSION)));
        this.statusView = makePanelText(15, true);
        this.statusView.setBackgroundColor(0);
        this.statusView.setTextColor(Color.rgb(72, 72, 72));
        this.statusView.setPadding(dp(4), dp(12), dp(4), dp(2));
        root.addView(this.statusView, panelParams(dp(0)));
        this.commandGrid = new GridLayout(this);
        this.commandGrid.setColumnCount(4);
        this.commandGrid.setPadding(0, dp(8), 0, dp(0));
        root.addView(this.commandGrid, new LinearLayout.LayoutParams(-1, -2));
        this.appGrid = new GridLayout(this);
        this.appGrid.setColumnCount(5);
        this.appGrid.setPadding(0, dp(6), 0, dp(10));
        root.addView(this.appGrid, new LinearLayout.LayoutParams(-1, -2));
        this.detailsView = makePanelText(14, false);
        this.detailsView.setPadding(dp(14), dp(12), dp(14), dp(14));
        root.addView(this.detailsView, panelParams(dp(10)));
        this.detailsView.setVisibility(8);
        setContentView(scrollView);
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(16.0f);
        button.setTextColor(-1);
        button.setBackgroundColor(Color.rgb(45, 42, 43));
        button.setGravity(17);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.setMargins(0, dp(4), 0, dp(4));
        button.setLayoutParams(params);
        return button;
    }

    private Button makeSecondaryButton(String text) {
        Button button = makeButton(text);
        button.setTextColor(Color.rgb(45, 42, 43));
        button.setBackgroundColor(Color.rgb(248, 245, 239));
        return button;
    }

    private TextView makePanelText(int size, boolean bold) {
        TextView text = new TextView(this);
        text.setTextSize(size);
        text.setTextColor(Color.rgb(42, 39, 40));
        text.setBackgroundColor(Color.rgb(255, 252, 247));
        if (bold) {
            text.setTypeface(Typeface.DEFAULT, MANIFEST_VERSION);
        }
        return text;
    }

    private LinearLayout.LayoutParams panelParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, topMargin, 0, 0);
        return params;
    }

    private void refreshAppGrid() {
        if (this.appGrid == null || this.commandGrid == null) {
            return;
        }
        this.commandGrid.removeAllViews();
        this.commandGrid.addView(makeCommandTile("APK 설치", "↓", Color.rgb(3, 169, 244), new View.OnClickListener() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda1
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m9lambda$refreshAppGrid$0$comexamplesinglevmMainActivity(view);
            }
        }));
        this.commandGrid.addView(makeCommandTile("ADB 설치", "↓", Color.rgb(0, 150, 136), new View.OnClickListener() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda2
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m10lambda$refreshAppGrid$1$comexamplesinglevmMainActivity(view);
            }
        }));
        this.commandGrid.addView(makeCommandTile("실행 전 스캔", "✓", Color.rgb(96, 125, 139), new View.OnClickListener() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda3
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m11lambda$refreshAppGrid$2$comexamplesinglevmMainActivity(view);
            }
        }));
        this.commandGrid.addView(makeCommandTile("설정", "⚙", Color.rgb(117, 133, 140), new View.OnClickListener() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda4
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m12lambda$refreshAppGrid$3$comexamplesinglevmMainActivity(view);
            }
        }));
        this.appGrid.removeAllViews();
        Set<String> installed = getInstalledPackages();
        for (String installId : installed) {
            this.appGrid.addView(makeInstalledAppTile(installId));
        }
    }

    /* JADX INFO: renamed from: lambda$refreshAppGrid$0$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m9lambda$refreshAppGrid$0$comexamplesinglevmMainActivity(View v) {
        openApkPicker();
    }

    /* JADX INFO: renamed from: lambda$refreshAppGrid$1$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m10lambda$refreshAppGrid$1$comexamplesinglevmMainActivity(View v) {
        importAdbGuestApk(true);
    }

    /* JADX INFO: renamed from: lambda$refreshAppGrid$2$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m11lambda$refreshAppGrid$2$comexamplesinglevmMainActivity(View v) {
        showRuntimeProbe();
    }

    /* JADX INFO: renamed from: lambda$refreshAppGrid$3$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m12lambda$refreshAppGrid$3$comexamplesinglevmMainActivity(View v) {
        showVmSettings();
    }

    private View makeInstalledAppTile(final String str) {
        View viewMakeGlyphIcon;
        String string = this.prefs.getString(("app." + str + ".") + GuestRunActivity.EXTRA_LABEL, str);
        Drawable drawableLoadInstalledAppIcon = loadInstalledAppIcon(str);
        LinearLayout linearLayoutMakeBaseTile = makeBaseTile(string);
        if (drawableLoadInstalledAppIcon != null) {
            ImageView imageView = new ImageView(this);
            imageView.setImageDrawable(drawableLoadInstalledAppIcon);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            viewMakeGlyphIcon = imageView;
        } else {
            viewMakeGlyphIcon = makeGlyphIcon(firstLetter(string), Color.rgb(63, 81, 181), false);
        }
        linearLayoutMakeBaseTile.addView(viewMakeGlyphIcon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        linearLayoutMakeBaseTile.addView(makeTileLabel(string));
        linearLayoutMakeBaseTile.setOnClickListener(new View.OnClickListener() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda5
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m7lambda$makeInstalledAppTile$4$comexamplesinglevmMainActivity(str, view);
            }
        });
        linearLayoutMakeBaseTile.setOnLongClickListener(new View.OnLongClickListener() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda6
            @Override // android.view.View.OnLongClickListener
            public final boolean onLongClick(View view) {
                return this.f$0.m8lambda$makeInstalledAppTile$5$comexamplesinglevmMainActivity(str, view);
            }
        });
        return linearLayoutMakeBaseTile;
    }

    /* JADX INFO: renamed from: lambda$makeInstalledAppTile$4$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m7lambda$makeInstalledAppTile$4$comexamplesinglevmMainActivity(String installId, View v) {
        showInstalledAppActions(installId);
    }

    /* JADX INFO: renamed from: lambda$makeInstalledAppTile$5$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ boolean m8lambda$makeInstalledAppTile$5$comexamplesinglevmMainActivity(String installId, View v) {
        showInstalledAppActions(installId);
        return true;
    }

    private View makeCommandTile(String label, String glyph, int color, View.OnClickListener listener) {
        LinearLayout tile = makeBaseTile(label);
        tile.addView(makeGlyphIcon(glyph, color, true), new LinearLayout.LayoutParams(dp(56), dp(56)));
        tile.addView(makeTileLabel(label));
        tile.setOnClickListener(listener);
        return tile;
    }

    private LinearLayout makeBaseTile(String label) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(MANIFEST_VERSION);
        tile.setGravity(17);
        tile.setPadding(0, 0, 0, 0);
        tile.setContentDescription(label);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = dp(76);
        params.height = dp(112);
        params.setMargins(dp(2), dp(10), dp(2), dp(10));
        tile.setLayoutParams(params);
        return tile;
    }

    private TextView makeGlyphIcon(String str, int i, boolean z) {
        TextView textView = new TextView(this);
        textView.setText(str);
        textView.setTextSize(30.0f);
        textView.setTypeface(Typeface.DEFAULT, MANIFEST_VERSION);
        textView.setGravity(17);
        textView.setTextColor(-1);
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setColor(i);
        gradientDrawable.setShape(z ? 1 : 0);
        gradientDrawable.setCornerRadius(dp(12));
        textView.setBackground(gradientDrawable);
        return textView;
    }

    private TextView makeTileLabel(String label) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(13.0f);
        text.setTextColor(Color.rgb(85, 85, 85));
        text.setGravity(17);
        text.setMaxLines(2);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(0, dp(8), 0, 0);
        return text;
    }

    private Drawable loadInstalledAppIcon(String installId) {
        String prefix = "app." + installId + ".";
        String apkPath = this.prefs.getString(prefix + GuestRunActivity.EXTRA_APK_PATH, "");
        if (apkPath.isEmpty()) {
            return null;
        }
        try {
            PackageInfo info = getPackageManager().getPackageArchiveInfo(apkPath, 0);
            if (info != null && info.applicationInfo != null) {
                ApplicationInfo appInfo = info.applicationInfo;
                appInfo.sourceDir = apkPath;
                appInfo.publicSourceDir = apkPath;
                return appInfo.loadIcon(getPackageManager());
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String firstLetter(String label) {
        if (label == null || label.trim().isEmpty()) {
            return "?";
        }
        return label.trim().substring(0, MANIFEST_VERSION).toUpperCase(Locale.KOREA);
    }

    private void showInstalledAppActions(final String installId) {
        this.prefs.edit().putString(KEY_ACTIVE_PACKAGE, installId).apply();
        String prefix = "app." + installId + ".";
        String label = this.prefs.getString(prefix + GuestRunActivity.EXTRA_LABEL, installId);
        String[] items = {"실행", "실행 전 APK 스캔", "앱 정보", "삭제"};
        new AlertDialog.Builder(this).setTitle(label).setItems(items, new DialogInterface.OnClickListener() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda8
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                this.f$0.m13xfb40c3b1(installId, dialogInterface, i);
            }
        }).show();
    }

    /* JADX INFO: renamed from: lambda$showInstalledAppActions$6$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m13xfb40c3b1(String installId, DialogInterface dialog, int which) {
        if (which == 0) {
            launchInstalledApp(installId);
            return;
        }
        if (which == MANIFEST_VERSION) {
            showRuntimeProbe();
        } else if (which == 2) {
            showInstalledAppInfo(installId);
        } else if (which == 3) {
            confirmDeleteInstalledApp(installId);
        }
    }

    private void launchInstalledApp(final String installId) {
        EngineAdapter engine = EngineRegistry.primary();
        EngineReadiness readiness = engine.inspect(this, this.vmRoot);
        if (!readiness.ready) {
            new AlertDialog.Builder(this).setTitle(engine.displayName()).setMessage(readiness.summary + "\n\n" + readiness.details).setPositiveButton("구성 파일 가져오기", new DialogInterface.OnClickListener() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda15
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int i) {
                    this.f$0.m5lambda$launchInstalledApp$7$comexamplesinglevmMainActivity(dialogInterface, i);
                }
            }).setNegativeButton("기존 시험 엔진", new DialogInterface.OnClickListener() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda16
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int i) {
                    this.f$0.m6lambda$launchInstalledApp$8$comexamplesinglevmMainActivity(installId, dialogInterface, i);
                }
            }).show();
        } else {
            launchGuestWithEngine(installId, engine.id());
        }
    }

    /* JADX INFO: renamed from: lambda$launchInstalledApp$7$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m5lambda$launchInstalledApp$7$comexamplesinglevmMainActivity(DialogInterface dialog, int which) {
        openAndroid7ImagePicker();
    }

    /* JADX INFO: renamed from: lambda$launchInstalledApp$8$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m6lambda$launchInstalledApp$8$comexamplesinglevmMainActivity(String installId, DialogInterface dialog, int which) {
        launchGuestWithEngine(installId, LegacyArmEngineAdapter.ID);
    }

    private void launchGuestWithEngine(String installId, String engineId) {
        String prefix = "app." + installId + ".";
        String packageName = this.prefs.getString(prefix + GuestRunActivity.EXTRA_PACKAGE, "");
        Intent intent = new Intent(this, (Class<?>) GuestRunActivity.class);
        intent.putExtra(GuestRunActivity.EXTRA_ENGINE_ID, engineId);
        intent.putExtra(GuestRunActivity.EXTRA_INSTALL_ID, installId);
        intent.putExtra(GuestRunActivity.EXTRA_LABEL, this.prefs.getString(prefix + GuestRunActivity.EXTRA_LABEL, installId));
        intent.putExtra(GuestRunActivity.EXTRA_PACKAGE, packageName);
        intent.putExtra(GuestRunActivity.EXTRA_LAUNCHER, this.prefs.getString(prefix + "first_activity", ""));
        intent.putExtra(GuestRunActivity.EXTRA_APK_PATH, this.prefs.getString(prefix + GuestRunActivity.EXTRA_APK_PATH, ""));
        intent.putExtra(GuestRunActivity.EXTRA_LIB_DIR, this.prefs.getString(prefix + GuestRunActivity.EXTRA_LIB_DIR, ""));
        intent.putExtra(GuestRunActivity.EXTRA_DATA_DIR, this.prefs.getString(prefix + GuestRunActivity.EXTRA_DATA_DIR, ""));
        intent.putExtra(GuestRunActivity.EXTRA_ABIS, this.prefs.getString(prefix + GuestRunActivity.EXTRA_ABIS, ""));
        intent.putExtra(GuestRunActivity.EXTRA_ARM32_LIB_COUNT, this.prefs.getInt(prefix + GuestRunActivity.EXTRA_ARM32_LIB_COUNT, 0));
        startActivity(intent);
    }

    private void showRunNotReady(String label) {
        new AlertDialog.Builder(this).setTitle(label).setMessage("아직 실행 엔진이 준비되지 않았습니다.\n\n'실행 전 APK 스캔'에서 필요한 보완 요소를 확인할 수 있습니다.").setPositiveButton("스캔", new DialogInterface.OnClickListener() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda7
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                this.f$0.m14lambda$showRunNotReady$9$comexamplesinglevmMainActivity(dialogInterface, i);
            }
        }).setNegativeButton("확인", (DialogInterface.OnClickListener) null).show();
    }

    /* JADX INFO: renamed from: lambda$showRunNotReady$9$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m14lambda$showRunNotReady$9$comexamplesinglevmMainActivity(DialogInterface dialog, int which) {
        showRuntimeProbe();
    }

    private void showInstalledAppInfo(String installId) {
        String prefix = "app." + installId + ".";
        String message = "게임 이름: " + this.prefs.getString(prefix + GuestRunActivity.EXTRA_LABEL, installId) + "\n패키지: " + this.prefs.getString(prefix + GuestRunActivity.EXTRA_PACKAGE, installId) + "\n버전: " + this.prefs.getString(prefix + "version_name", "") + " (" + this.prefs.getLong(prefix + "version_code", 0L) + ")\n대표 Activity: " + this.prefs.getString(prefix + "first_activity", "알 수 없음") + "\nABI: " + this.prefs.getString(prefix + GuestRunActivity.EXTRA_ABIS, "없음") + "\nARM32 라이브러리: " + this.prefs.getInt(prefix + GuestRunActivity.EXTRA_ARM32_LIB_COUNT, 0) + "\n설치 시간: " + this.prefs.getString(prefix + "installed_at", "") + "\n\n앱 폴더:\n" + this.prefs.getString(prefix + "app_dir", "") + "\n\n데이터 폴더:\n" + this.prefs.getString(prefix + GuestRunActivity.EXTRA_DATA_DIR, "");
        new AlertDialog.Builder(this).setTitle("앱 정보").setMessage(message).setPositiveButton("확인", (DialogInterface.OnClickListener) null).show();
    }

    private void confirmDeleteInstalledApp(final String installId) {
        String prefix = "app." + installId + ".";
        String label = this.prefs.getString(prefix + GuestRunActivity.EXTRA_LABEL, installId);
        new AlertDialog.Builder(this).setTitle("삭제").setMessage(label + "을(를) 가상폰에서 삭제할까요?").setPositiveButton("삭제", new DialogInterface.OnClickListener() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda11
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                this.f$0.m0x56bded1(installId, dialogInterface, i);
            }
        }).setNegativeButton("취소", (DialogInterface.OnClickListener) null).show();
    }

    /* JADX INFO: renamed from: lambda$confirmDeleteInstalledApp$10$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m0x56bded1(String installId, DialogInterface dialog, int which) {
        deleteInstalledApp(installId);
    }

    private void deleteInstalledApp(String installId) {
        String prefix = "app." + installId + ".";
        clearVmChildPath(this.prefs.getString(prefix + "app_dir", ""));
        clearVmChildPath(this.prefs.getString(prefix + GuestRunActivity.EXTRA_DATA_DIR, ""));
        Set<String> installed = getInstalledPackages();
        installed.remove(installId);
        SharedPreferences.Editor editor = this.prefs.edit();
        editor.putStringSet(KEY_INSTALLED_PACKAGES, installed);
        if (installId.equals(this.prefs.getString(KEY_ACTIVE_PACKAGE, ""))) {
            editor.putString(KEY_ACTIVE_PACKAGE, installed.isEmpty() ? "" : installed.iterator().next());
        }
        String[] keys = {"display_name", GuestRunActivity.EXTRA_LABEL, GuestRunActivity.EXTRA_PACKAGE, "version_name", "version_code", "first_activity", "activity_count", "permission_count", GuestRunActivity.EXTRA_ABIS, "dex_count", "native_lib_count", GuestRunActivity.EXTRA_ARM32_LIB_COUNT, "arm64_lib_count", "extracted_lib_count", "asset_count", "archive_size", GuestRunActivity.EXTRA_APK_PATH, "app_dir", GuestRunActivity.EXTRA_LIB_DIR, "assets_dir", GuestRunActivity.EXTRA_DATA_DIR, "installed_at"};
        int length = keys.length;
        for (int i = 0; i < length; i += MANIFEST_VERSION) {
            String key = keys[i];
            editor.remove(prefix + key);
        }
        editor.apply();
        try {
            writeVmManifest(installed);
        } catch (IOException e) {
            showError("manifest update failed", e.getMessage());
        }
        refreshState();
    }

    private void clearVmChildPath(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        File target = new File(path);
        String rootPath = this.vmRoot.getAbsolutePath();
        String targetPath = target.getAbsolutePath();
        if (!targetPath.startsWith(rootPath + File.separator)) {
            return;
        }
        clearDirectory(target);
    }

    private void showVmSettings() {
        EngineAdapter primaryEngine = EngineRegistry.primary();
        EngineReadiness primaryState = primaryEngine.inspect(this, this.vmRoot);
        EngineReadiness legacyState = EngineRegistry.legacy().inspect(this, this.vmRoot);
        String message = "가상롬 루트:\n" + this.vmRoot.getAbsolutePath() + "\n\nADB 설치 경로:\n" + this.externalGuestApk.getAbsolutePath() + "\n\n기본 엔진: " + primaryEngine.displayName() + "\n상태: " + primaryState.summary + "\n\n" + primaryState.details + "\n\n기존 시험 엔진: " + legacyState.summary + "\n\n설치된 게임 수: " + getInstalledPackages().size();
        new AlertDialog.Builder(this).setTitle("설정").setMessage(message).setPositiveButton("확인", (DialogInterface.OnClickListener) null).setNeutralButton("Android 7 구성 가져오기", new DialogInterface.OnClickListener() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda12
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                this.f$0.m15lambda$showVmSettings$11$comexamplesinglevmMainActivity(dialogInterface, i);
            }
        }).setNegativeButton("가상롬 초기화", new DialogInterface.OnClickListener() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda13
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                this.f$0.m16lambda$showVmSettings$12$comexamplesinglevmMainActivity(dialogInterface, i);
            }
        }).show();
    }

    /* JADX INFO: renamed from: lambda$showVmSettings$11$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m15lambda$showVmSettings$11$comexamplesinglevmMainActivity(DialogInterface dialog, int which) {
        openAndroid7ImagePicker();
    }

    /* JADX INFO: renamed from: lambda$showVmSettings$12$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m16lambda$showVmSettings$12$comexamplesinglevmMainActivity(DialogInterface dialog, int which) {
        confirmReset();
    }

    private void openAndroid7ImagePicker() {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        intent.putExtra("android.intent.extra.MIME_TYPES", new String[]{"application/zip", "application/octet-stream"});
        intent.addFlags(MANIFEST_VERSION);
        startActivityForResult(intent, REQUEST_IMPORT_ANDROID7_IMAGE);
    }

    private void openApkPicker() {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        intent.putExtra("android.intent.extra.MIME_TYPES", new String[]{"application/vnd.android.package-archive", "application/octet-stream"});
        intent.addFlags(MANIFEST_VERSION);
        startActivityForResult(intent, REQUEST_IMPORT_APK);
    }

    @Override // android.app.Activity
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_APK && resultCode == -1 && data != null) {
            Uri uri = data.getData();
            if (uri == null) {
                m2x16efb73e("APK 파일을 읽을 수 없습니다.");
                return;
            }
            try {
                importApk(uri);
                return;
            } catch (IOException e) {
                showError("APK 가져오기 실패", e.getMessage());
                return;
            }
        }
        if (requestCode == REQUEST_IMPORT_ANDROID7_IMAGE && resultCode == -1 && data != null) {
            Uri uri2 = data.getData();
            if (uri2 == null) {
                m2x16efb73e("구성 파일을 읽을 수 없습니다.");
            } else {
                importAndroid7ImageAsync(uri2);
            }
        }
    }

    private void importAndroid7ImageAsync(final Uri uri) {
        m2x16efb73e("Android 7 구성 파일을 확인하는 중입니다.");
        new Thread(new Runnable() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda14
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.m4xe4a136c0(uri);
            }
        }, "android7-image-import").start();
    }

    /* JADX INFO: renamed from: lambda$importAndroid7ImageAsync$15$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m4xe4a136c0(Uri uri) {
        try {
            final String result = importAndroid7Image(uri);
            runOnUiThread(new Runnable() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda9
                @Override // java.lang.Runnable
                public final void run() {
                    this.f$0.m2x16efb73e(result);
                }
            });
        } catch (IOException e) {
            final String message = e.getMessage();
            runOnUiThread(new Runnable() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda10
                @Override // java.lang.Runnable
                public final void run() {
                    this.f$0.m3x7dc876ff(message);
                }
            });
        }
    }

    /* JADX INFO: renamed from: lambda$importAndroid7ImageAsync$14$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m3x7dc876ff(String message) {
        showError("Android 7 구성 가져오기 실패", message);
    }

    private String importAndroid7Image(Uri uri) throws IOException {
        String sourceName = getDisplayName(uri);
        if (sourceName != null && sourceName.toLowerCase(Locale.US).endsWith(".zip")) {
            return importAndroid7Bundle(uri);
        }
        String normalized = normalizeAndroid7ImageName(sourceName);
        if (normalized == null) {
            throw new IOException("지원 파일: Android 시스템 이미지 ZIP 또는 kernel, ramdisk.img, system.img, userdata.img");
        }
        if (!this.android7ImageDir.exists() && !this.android7ImageDir.mkdirs()) {
            throw new IOException("Android 7 이미지 폴더를 만들 수 없습니다.");
        }
        File destination = new File(this.android7ImageDir, normalized);
        copyUriToFile(uri, destination);
        return normalized + " 가져오기 완료";
    }

    private String importAndroid7Bundle(Uri uri) throws IOException {
        ensureVmDirs();
        if (!this.android7ImageDir.exists() && !this.android7ImageDir.mkdirs()) {
            throw new IOException("Android 7 이미지 폴더를 만들 수 없습니다.");
        }
        File bundle = new File(this.vmRoot, "android7-import.zip");
        copyUriToFile(uri, bundle);
        try {
            ZipFile zip = new ZipFile(bundle);
            try {
                ZipEntry kernel = findZipEntry(zip, "kernel-ranchu");
                if (kernel == null) {
                    kernel = findZipEntry(zip, "kernel-qemu");
                }
                if (kernel == null) {
                    kernel = findZipEntry(zip, "kernel");
                }
                ZipEntry ramdisk = findZipEntry(zip, "ramdisk.img");
                ZipEntry system = findZipEntry(zip, "system.img");
                ZipEntry userdata = findZipEntry(zip, "userdata.img");
                if (kernel == null || ramdisk == null || system == null) {
                    throw new IOException("ZIP에 kernel-ranchu/kernel-qemu, ramdisk.img, system.img가 필요합니다.");
                }
                long required = positiveSize(kernel) + positiveSize(ramdisk) + positiveSize(system) + positiveSize(userdata) + 268435456;
                if (required > 0 && this.android7ImageDir.getUsableSpace() < required) {
                    throw new IOException("압축 해제 공간이 부족합니다. 최소 " + humanSize(required) + "가 필요합니다.");
                }
                extractZipEntry(zip, kernel, new File(this.android7ImageDir, "kernel"));
                extractZipEntry(zip, ramdisk, new File(this.android7ImageDir, "ramdisk.img"));
                extractZipEntry(zip, system, new File(this.android7ImageDir, "system.img"));
                if (userdata != null) {
                    extractZipEntry(zip, userdata, new File(this.android7ImageDir, "userdata.img"));
                }
                zip.close();
                if (bundle.exists() && !bundle.delete()) {
                    bundle.deleteOnExit();
                }
                return "Android 7 ARM32 구성 가져오기 완료";
            } catch (Throwable th) {
                try {
                    zip.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (Throwable th3) {
            if (bundle.exists() && !bundle.delete()) {
                bundle.deleteOnExit();
            }
            throw th3;
        }
    }

    private ZipEntry findZipEntry(ZipFile zip, String fileName) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                String name = entry.getName().replace('\\', '/');
                int slash = name.lastIndexOf(47);
                String baseName = slash >= 0 ? name.substring(slash + MANIFEST_VERSION) : name;
                if (baseName.equalsIgnoreCase(fileName)) {
                    return entry;
                }
            }
        }
        return null;
    }

    private long positiveSize(ZipEntry entry) {
        if (entry == null) {
            return 0L;
        }
        return Math.max(0L, entry.getSize());
    }

    private String humanSize(long bytes) {
        if (bytes >= 1073741824) {
            return String.format(Locale.US, "%.1f GB", Double.valueOf(bytes / 1.073741824E9d));
        }
        if (bytes >= 1048576) {
            return String.format(Locale.US, "%.1f MB", Double.valueOf(bytes / 1048576.0d));
        }
        return bytes + " bytes";
    }

    /* JADX WARN: Code duplicated, block: B:50:0x00ce A[EXC_TOP_SPLITTER, SYNTHETIC] */
    private void extractZipEntry(ZipFile zip, ZipEntry entry, File destination) throws IOException {
        File partial = new File(destination.getParentFile(), destination.getName() + ".part");
        if (partial.exists() && !partial.delete()) {
            throw new IOException(partial.getName() + " 임시 파일을 정리할 수 없습니다.");
        }
        try {
            InputStream input = zip.getInputStream(entry);
            try {
                OutputStream output = new FileOutputStream(partial);
                try {
                    byte[] buffer = new byte[1048576];
                    while (true) {
                        int read = input.read(buffer);
                        if (read == -1) {
                            break;
                        } else {
                            output.write(buffer, 0, read);
                        }
                        if (input != null) {
                            try {
                                input.close();
                            } catch (Throwable th) {
                                th.addSuppressed(th);
                            }
                        }
                        throw th;
                    }
                    output.close();
                    if (input != null) {
                        input.close();
                    }
                    if (destination.exists() && !destination.delete()) {
                        partial.delete();
                        throw new IOException(destination.getName() + " 기존 파일을 교체할 수 없습니다.");
                    }
                    if (!partial.renameTo(destination)) {
                        partial.delete();
                        throw new IOException(destination.getName() + " 파일을 확정할 수 없습니다.");
                    }
                } catch (Throwable th2) {
                    try {
                        output.close();
                    } catch (Throwable th3) {
                        th2.addSuppressed(th3);
                    }
                    throw th2;
                }
            } catch (Throwable th4) {
                if (input != null) {
                    input.close();
                }
                throw th4;
            }
        } catch (IOException e) {
            partial.delete();
            throw e;
        }
    }

    private String normalizeAndroid7ImageName(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.trim().toLowerCase(Locale.US);
        if (lower.equals("kernel-android54")) {
            return "kernel-android54";
        }
        if (lower.equals("ramdisk-android54.img")) {
            return "ramdisk-android54.img";
        }
        if (lower.equals("kernel") || lower.equals("zimage") || lower.equals("kernel-ranchu") || lower.equals("kernel-qemu")) {
            return "kernel";
        }
        if (lower.equals("ramdisk.img")) {
            return "ramdisk.img";
        }
        if (lower.equals("system.img")) {
            return "system.img";
        }
        if (!lower.equals("userdata.img")) {
            return null;
        }
        return "userdata.img";
    }

    private void importApk(Uri uri) throws IOException {
        ensureVmDirs();
        String displayName = getDisplayName(uri);
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "guest.apk";
        }
        File apkFile = new File(this.apkDir, "guest.apk");
        copyUriToFile(uri, apkFile);
        finishImportApk(apkFile, displayName);
        m2x16efb73e("APK를 단일 가상롬 저장소에 가져왔습니다.");
    }

    private void importAdbGuestApk(boolean showMissingDialog) {
        ensureVmDirs();
        if (!this.externalGuestApk.exists()) {
            if (showMissingDialog) {
                showError("ADB guest.apk 없음", "다음 경로에 APK를 넣은 뒤 다시 누르세요.\n\n" + this.externalGuestApk.getAbsolutePath());
                return;
            }
            return;
        }
        try {
            File apkFile = new File(this.apkDir, "guest.apk");
            copyFileToFile(this.externalGuestApk, apkFile);
            finishImportApk(apkFile, this.externalGuestApk.getName());
            m2x16efb73e("ADB guest.apk를 가져왔습니다.");
        } catch (IOException e) {
            showError("ADB APK 가져오기 실패", e.getMessage());
        }
    }

    private void finishImportApk(File apkFile, String displayName) throws IOException {
        String installId;
        if (!displayName.toLowerCase(Locale.US).endsWith(".apk")) {
            apkFile.delete();
            showError("지원하지 않는 파일", ".apk 파일을 선택해야 합니다.");
            return;
        }
        ApkReport report = inspectApk(apkFile);
        if (report.validApk) {
            String installId2 = sanitizePackageName(report.packageName);
            if (installId2.isEmpty()) {
                installId2 = sanitizePackageName(displayName.replace(".apk", ""));
            }
            if (!installId2.isEmpty()) {
                installId = installId2;
            } else {
                installId = "unknown_app";
            }
            File appDir = new File(this.appsDir, installId);
            File appApkFile = new File(appDir, "base.apk");
            File appLibDir = new File(appDir, "lib");
            File appAssetsDir = new File(appDir, "assets");
            File appDataDir = new File(this.dataDir, installId);
            clearDirectory(appDir);
            if (!appDir.exists() && !appDir.mkdirs()) {
                throw new IOException("설치 폴더를 만들 수 없습니다: " + appDir);
            }
            if (!appLibDir.exists() && !appLibDir.mkdirs()) {
                throw new IOException("라이브러리 폴더를 만들 수 없습니다: " + appLibDir);
            }
            if (!appDataDir.exists() && !appDataDir.mkdirs()) {
                throw new IOException("데이터 폴더를 만들 수 없습니다: " + appDataDir);
            }
            appAssetsDir.mkdirs();
            copyFileToFile(apkFile, appApkFile);
            int extractedLibs = extractArm32Libraries(appApkFile, appLibDir);
            int extractedAssets = extractApkAssets(appApkFile, appAssetsDir);
            clearDirectory(this.nativeLibDir);
            this.nativeLibDir.mkdirs();
            saveInstalledState(report, displayName, installId, appApkFile, appDir, appLibDir, appAssetsDir, appDataDir, extractedLibs, extractedAssets);
            refreshState();
            return;
        }
        apkFile.delete();
        showError("APK 분석 실패", "AndroidManifest.xml 또는 패키지 정보를 찾지 못했습니다.");
    }

    private ApkReport inspectApk(File apkFile) throws IOException {
        ApkReport report = new ApkReport();
        report.archiveSize = apkFile.length();
        ZipFile zip = new ZipFile(apkFile);
        try {
            report.validApk = zip.getEntry("AndroidManifest.xml") != null ? MANIFEST_VERSION : false;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.equals("classes.dex") || name.matches("classes\\d+\\.dex")) {
                    report.dexCount += MANIFEST_VERSION;
                }
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    String[] parts = name.split("/");
                    if (parts.length >= 3) {
                        report.abis.add(parts[MANIFEST_VERSION]);
                        report.nativeLibCount += MANIFEST_VERSION;
                        if ("armeabi".equals(parts[MANIFEST_VERSION]) || "armeabi-v7a".equals(parts[MANIFEST_VERSION])) {
                            report.arm32LibCount += MANIFEST_VERSION;
                        }
                        if ("arm64-v8a".equals(parts[MANIFEST_VERSION])) {
                            report.arm64LibCount += MANIFEST_VERSION;
                        }
                    }
                }
            }
            zip.close();
            PackageManager pm = getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 4225);
            if (info == null) {
                report.validApk = false;
                return report;
            }
            report.packageName = info.packageName == null ? "" : info.packageName;
            report.versionName = info.versionName == null ? "" : info.versionName;
            report.versionCode = getVersionCode(info);
            if (info.activities != null && info.activities.length > 0) {
                ActivityInfo first = info.activities[0];
                report.firstActivity = first.name == null ? "" : first.name;
                report.activityCount = info.activities.length;
            }
            if (info.requestedPermissions != null) {
                report.permissionCount = info.requestedPermissions.length;
            }
            ApplicationInfo appInfo = info.applicationInfo;
            if (appInfo != null) {
                appInfo.sourceDir = apkFile.getAbsolutePath();
                appInfo.publicSourceDir = apkFile.getAbsolutePath();
                CharSequence label = appInfo.loadLabel(pm);
                report.appLabel = label != null ? label.toString() : "";
            }
            return report;
        } catch (Throwable th) {
            try {
                zip.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    private int extractArm32Libraries(File apkFile, File targetLibDir) throws IOException {
        int count = 0;
        ZipFile zip = new ZipFile(apkFile);
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith("lib/") && name.endsWith(".so")) {
                    String[] parts = name.split("/");
                    if (parts.length >= 3) {
                        String abi = parts[MANIFEST_VERSION];
                        if ("armeabi".equals(abi) || "armeabi-v7a".equals(abi)) {
                            File abiDir = new File(targetLibDir, abi);
                            if (!abiDir.exists() && !abiDir.mkdirs()) {
                                throw new IOException("네이티브 라이브러리 폴더를 만들 수 없습니다: " + abiDir);
                            }
                            File out = new File(abiDir, new File(name).getName());
                            InputStream in = zip.getInputStream(entry);
                            try {
                                OutputStream output = new FileOutputStream(out);
                                try {
                                    copy(in, output);
                                    output.close();
                                    if (in != null) {
                                        in.close();
                                    }
                                    count += MANIFEST_VERSION;
                                } catch (Throwable th) {
                                    try {
                                        output.close();
                                    } catch (Throwable th2) {
                                        th.addSuppressed(th2);
                                    }
                                    throw th;
                                }
                            } catch (Throwable th3) {
                                if (in != null) {
                                    try {
                                        in.close();
                                    } catch (Throwable th4) {
                                        th3.addSuppressed(th4);
                                    }
                                }
                                throw th3;
                            }
                        }
                    }
                }
            }
            zip.close();
            return count;
        } catch (Throwable th5) {
            try {
                zip.close();
            } catch (Throwable th6) {
                th5.addSuppressed(th6);
            }
            throw th5;
        }
    }

    private int extractApkAssets(File apkFile, File targetAssetsDir) throws IOException {
        int count = 0;
        ZipFile zip = new ZipFile(apkFile);
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith("assets/")) {
                    String relative = name.substring("assets/".length());
                    if (!relative.isEmpty()) {
                        File out = new File(targetAssetsDir, relative);
                        File parent = out.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw new IOException("Cannot create assets directory: " + parent);
                        }
                        InputStream in = zip.getInputStream(entry);
                        try {
                            OutputStream output = new FileOutputStream(out);
                            try {
                                copy(in, output);
                                output.close();
                                if (in != null) {
                                    in.close();
                                }
                                count += MANIFEST_VERSION;
                            } catch (Throwable th) {
                                try {
                                    output.close();
                                } catch (Throwable th2) {
                                    th.addSuppressed(th2);
                                }
                                throw th;
                            }
                        } catch (Throwable th3) {
                            if (in != null) {
                                try {
                                    in.close();
                                } catch (Throwable th4) {
                                    th3.addSuppressed(th4);
                                }
                            }
                            throw th3;
                        }
                    }
                }
            }
            zip.close();
            return count;
        } catch (Throwable th5) {
            try {
                zip.close();
            } catch (Throwable th6) {
                th5.addSuppressed(th6);
            }
            throw th5;
        }
    }

    private void saveInstalledState(ApkReport report, String displayName, String installId, File apkFile, File appDir, File appLibDir, File appAssetsDir, File appDataDir, int extractedLibs, int extractedAssets) throws IOException {
        Set<String> installed = getInstalledPackages();
        installed.add(installId);
        String installedAt = now();
        String prefix = "app." + installId + ".";
        this.prefs.edit().putBoolean("has_apk", true).putStringSet(KEY_INSTALLED_PACKAGES, installed).putString(KEY_ACTIVE_PACKAGE, installId).putString("display_name", displayName).putString(GuestRunActivity.EXTRA_LABEL, emptyToFallback(report.appLabel, displayName)).putString(GuestRunActivity.EXTRA_PACKAGE, report.packageName).putString("version_name", report.versionName).putLong("version_code", report.versionCode).putString("first_activity", report.firstActivity).putInt("activity_count", report.activityCount).putInt("permission_count", report.permissionCount).putString(GuestRunActivity.EXTRA_ABIS, join(report.abis)).putInt("dex_count", report.dexCount).putInt("native_lib_count", report.nativeLibCount).putInt(GuestRunActivity.EXTRA_ARM32_LIB_COUNT, report.arm32LibCount).putInt("arm64_lib_count", report.arm64LibCount).putInt("extracted_lib_count", extractedLibs).putInt("asset_count", extractedAssets).putLong("archive_size", report.archiveSize).putString(GuestRunActivity.EXTRA_APK_PATH, apkFile.getAbsolutePath()).putString("imported_at", installedAt).putString(prefix + "display_name", displayName).putString(prefix + GuestRunActivity.EXTRA_LABEL, emptyToFallback(report.appLabel, displayName)).putString(prefix + GuestRunActivity.EXTRA_PACKAGE, report.packageName).putString(prefix + "version_name", report.versionName).putLong(prefix + "version_code", report.versionCode).putString(prefix + "first_activity", report.firstActivity).putInt(prefix + "activity_count", report.activityCount).putInt(prefix + "permission_count", report.permissionCount).putString(prefix + GuestRunActivity.EXTRA_ABIS, join(report.abis)).putInt(prefix + "dex_count", report.dexCount).putInt(prefix + "native_lib_count", report.nativeLibCount).putInt(prefix + GuestRunActivity.EXTRA_ARM32_LIB_COUNT, report.arm32LibCount).putInt(prefix + "arm64_lib_count", report.arm64LibCount).putInt(prefix + "extracted_lib_count", extractedLibs).putInt(prefix + "asset_count", extractedAssets).putLong(prefix + "archive_size", report.archiveSize).putString(prefix + GuestRunActivity.EXTRA_APK_PATH, apkFile.getAbsolutePath()).putString(prefix + "app_dir", appDir.getAbsolutePath()).putString(prefix + GuestRunActivity.EXTRA_LIB_DIR, appLibDir.getAbsolutePath()).putString(prefix + "assets_dir", appAssetsDir.getAbsolutePath()).putString(prefix + GuestRunActivity.EXTRA_DATA_DIR, appDataDir.getAbsolutePath()).putString(prefix + "installed_at", installedAt).apply();
        writeAppManifest(report, displayName, installId, apkFile, appDir, appLibDir, appAssetsDir, appDataDir, extractedLibs, extractedAssets, installedAt);
        writeVmManifest(installed);
    }

    private void refreshState() {
        ensureVmDirs();
        refreshAppGrid();
        Set<String> installed = getInstalledPackages();
        if (installed.isEmpty()) {
            this.statusView.setText("설치된 게임이 없습니다.");
            this.detailsView.setText("");
            return;
        }
        String active = this.prefs.getString(KEY_ACTIVE_PACKAGE, "");
        if (active.isEmpty() || !installed.contains(active)) {
            active = installed.iterator().next();
        }
        String str = "app." + active + ".";
        this.statusView.setText("설치된 게임 " + installed.size() + "개");
        this.detailsView.setText("");
    }

    private void showRuntimeProbe() {
        Set<String> installed = getInstalledPackages();
        String active = this.prefs.getString(KEY_ACTIVE_PACKAGE, "");
        if ((active.isEmpty() || !installed.contains(active)) && !installed.isEmpty()) {
            active = installed.iterator().next();
        }
        String activePrefix = "app." + active + ".";
        String imported = installed.isEmpty() ? "없음" : "있음";
        String activeLabel = active.isEmpty() ? "없음" : this.prefs.getString(activePrefix + GuestRunActivity.EXTRA_LABEL, active);
        int arm32Libs = active.isEmpty() ? 0 : this.prefs.getInt(activePrefix + GuestRunActivity.EXTRA_ARM32_LIB_COUNT, 0);
        String message = "실행 전 스캔 결과\n\n현재 구현됨\n- 단일 가상롬 저장소 생성\n- APK 가져오기\n- 패키지/ABI 분석\n- armeabi / armeabi-v7a 라이브러리 추출\n- ARM32 ELF 구조 분석\n- 의존 라이브러리 / relocation / undefined symbol 일부 분석\n\n현재 상태\n- 설치된 게임: " + installed.size() + "개\n- 선택된 게임: " + activeLabel + "\n- APK 설치 상태: " + imported + "\n- 선택된 게임 ARM32 라이브러리 수: " + arm32Libs + "\n\n실행 불가 시 보완해야 하는 핵심 엔진\n- ARM32 ELF 로더\n- ARM32 to arm64 실행기 또는 인터프리터\n- 32비트 bionic/linker 호환 계층\n- Android framework/Binder/Surface/Audio bridge\n\n따라서 지금 단계에서는 실행 가능 여부를 미리 스캔하고, 부족한 실행 계층을 구체적으로 파악합니다.";
        String nativeReport = getNativeProbeReport(active);
        EngineAdapter primaryEngine = EngineRegistry.primary();
        EngineReadiness primaryState = primaryEngine.inspect(this, this.vmRoot);
        new AlertDialog.Builder(this).setTitle("실행 전 APK 스캔").setMessage(message + "\n\n기본 실행 엔진\n" + primaryEngine.displayName() + "\n" + primaryState.summary + "\n" + primaryState.details + "\n\n기존 네이티브 진단 브릿지\n" + nativeReport).setPositiveButton("확인", (DialogInterface.OnClickListener) null).show();
    }

    private String getNativeProbeReport(String installId) {
        if (!NATIVE_RUNTIME_LOADED) {
            return "Native bridge: load failed";
        }
        try {
            return nativeProbeRuntime(this.vmRoot.getAbsolutePath(), installId == null ? "" : installId);
        } catch (RuntimeException e) {
            return "Native bridge: probe failed - " + e.getMessage();
        } catch (UnsatisfiedLinkError e2) {
            return "Native bridge: JNI missing - " + e2.getMessage();
        }
    }

    private void confirmReset() {
        new AlertDialog.Builder(this).setTitle("가상롬 초기화").setMessage("가져온 APK와 추출된 라이브러리, 단일 가상롬 데이터 영역을 삭제합니다.").setPositiveButton("초기화", new DialogInterface.OnClickListener() { // from class: com.example.singlevm.MainActivity$$ExternalSyntheticLambda0
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                this.f$0.m1lambda$confirmReset$16$comexamplesinglevmMainActivity(dialogInterface, i);
            }
        }).setNegativeButton("취소", (DialogInterface.OnClickListener) null).show();
    }

    /* JADX INFO: renamed from: lambda$confirmReset$16$com-example-singlevm-MainActivity, reason: not valid java name */
    /* synthetic */ void m1lambda$confirmReset$16$comexamplesinglevmMainActivity(DialogInterface dialog, int which) {
        clearDirectory(this.vmRoot);
        this.prefs.edit().clear().apply();
        ensureVmDirs();
        refreshState();
        m2x16efb73e("가상롬을 초기화했습니다.");
    }

    private void ensureVmDirs() {
        List<File> dirs = new ArrayList<>();
        dirs.add(this.vmRoot);
        dirs.add(this.apkDir);
        dirs.add(this.appsDir);
        dirs.add(this.dataDir);
        dirs.add(this.nativeLibDir);
        dirs.add(this.logsDir);
        dirs.add(this.externalImportDir);
        dirs.add(this.android7ImageDir);
        for (File dir : dirs) {
            if (!dir.exists()) {
                dir.mkdirs();
            }
        }
        syncManifestFilesQuietly();
    }

    private void copyUriToFile(Uri uri, File outFile) throws IOException {
        InputStream input = getContentResolver().openInputStream(uri);
        try {
            OutputStream output = new FileOutputStream(outFile);
            try {
                if (input == null) {
                    throw new IOException("선택한 파일을 열 수 없습니다.");
                }
                copy(input, output);
                output.close();
                if (input != null) {
                    input.close();
                }
            } catch (Throwable th) {
                try {
                    output.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (Throwable th3) {
            if (input != null) {
                try {
                    input.close();
                } catch (Throwable th4) {
                    th3.addSuppressed(th4);
                }
            }
            throw th3;
        }
    }

    private void copyFileToFile(File inputFile, File outFile) throws IOException {
        InputStream input = new FileInputStream(inputFile);
        try {
            OutputStream output = new FileOutputStream(outFile);
            try {
                copy(input, output);
                output.close();
                input.close();
            } catch (Throwable th) {
                try {
                    output.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (Throwable th3) {
            try {
                input.close();
            } catch (Throwable th4) {
                th3.addSuppressed(th4);
            }
            throw th3;
        }
    }

    private void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[65536];
        while (true) {
            int read = input.read(buffer);
            if (read != -1) {
                output.write(buffer, 0, read);
            } else {
                return;
            }
        }
    }

    private String getDisplayName(Uri uri) {
        int index;
        try {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst() && (index = cursor.getColumnIndex("_display_name")) >= 0) {
                        String string = cursor.getString(index);
                        if (cursor != null) {
                            cursor.close();
                        }
                        return string;
                    }
                } catch (Throwable th) {
                    if (cursor != null) {
                        try {
                            cursor.close();
                        } catch (Throwable th2) {
                            th.addSuppressed(th2);
                        }
                    }
                    throw th;
                }
            }
            if (cursor != null) {
                cursor.close();
            }
        } catch (Exception e) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "guest.apk" : last;
    }

    private void clearDirectory(File file) {
        File[] children;
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory() && (children = file.listFiles()) != null) {
            int length = children.length;
            for (int i = 0; i < length; i += MANIFEST_VERSION) {
                File child = children[i];
                clearDirectory(child);
            }
        }
        if (!file.equals(this.vmRoot)) {
            file.delete();
        }
    }

    private long getVersionCode(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            return info.getLongVersionCode();
        }
        return info.versionCode;
    }

    private String join(Set<String> values) {
        if (values.isEmpty()) {
            return "없음";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private void writeVmManifest(Set<String> installed) throws IOException {
        try {
            JSONObject manifest = new JSONObject();
            JSONArray apps = new JSONArray();
            for (String installId : installed) {
                apps.put(installId);
            }
            manifest.put("schema", "single-vm");
            manifest.put("version", MANIFEST_VERSION);
            manifest.put("updated_at", now());
            manifest.put("vm_root", this.vmRoot.getAbsolutePath());
            manifest.put("apps_dir", this.appsDir.getAbsolutePath());
            manifest.put(GuestRunActivity.EXTRA_DATA_DIR, this.dataDir.getAbsolutePath());
            manifest.put(KEY_ACTIVE_PACKAGE, this.prefs.getString(KEY_ACTIVE_PACKAGE, ""));
            manifest.put(KEY_INSTALLED_PACKAGES, apps);
            writeJsonFile(new File(this.vmRoot, "config.json"), manifest);
        } catch (JSONException e) {
            throw new IOException("VM manifest JSON creation failed", e);
        }
    }

    private void writeAppManifest(ApkReport report, String displayName, String installId, File apkFile, File appDir, File appLibDir, File appAssetsDir, File appDataDir, int extractedLibs, int extractedAssets, String installedAt) throws IOException {
        try {
            JSONArray abis = new JSONArray();
            for (String abi : report.abis) {
                abis.put(abi);
            }
            JSONObject manifest = new JSONObject();
            manifest.put("schema", "single-vm-app");
            manifest.put("version", MANIFEST_VERSION);
            try {
                manifest.put(GuestRunActivity.EXTRA_INSTALL_ID, installId);
                manifest.put("display_name", displayName);
                manifest.put(GuestRunActivity.EXTRA_LABEL, emptyToFallback(report.appLabel, displayName));
                manifest.put(GuestRunActivity.EXTRA_PACKAGE, report.packageName);
                manifest.put("version_name", report.versionName);
                manifest.put("version_code", report.versionCode);
                manifest.put("first_activity", report.firstActivity);
                manifest.put("activity_count", report.activityCount);
                manifest.put("permission_count", report.permissionCount);
                manifest.put("dex_count", report.dexCount);
                manifest.put("native_lib_count", report.nativeLibCount);
                manifest.put(GuestRunActivity.EXTRA_ARM32_LIB_COUNT, report.arm32LibCount);
                manifest.put("arm64_lib_count", report.arm64LibCount);
                try {
                    manifest.put("extracted_lib_count", extractedLibs);
                    try {
                        manifest.put("asset_count", extractedAssets);
                        manifest.put("archive_size", report.archiveSize);
                        manifest.put(GuestRunActivity.EXTRA_ABIS, abis);
                        manifest.put(GuestRunActivity.EXTRA_APK_PATH, apkFile.getAbsolutePath());
                        manifest.put("app_dir", appDir.getAbsolutePath());
                        manifest.put(GuestRunActivity.EXTRA_LIB_DIR, appLibDir.getAbsolutePath());
                        manifest.put("assets_dir", appAssetsDir.getAbsolutePath());
                        manifest.put(GuestRunActivity.EXTRA_DATA_DIR, appDataDir.getAbsolutePath());
                        try {
                            manifest.put("installed_at", installedAt);
                            try {
                                writeJsonFile(new File(appDir, "manifest.json"), manifest);
                            } catch (JSONException e) {
                                e = e;
                                throw new IOException("App manifest JSON creation failed", e);
                            }
                        } catch (JSONException e2) {
                            e = e2;
                        }
                    } catch (JSONException e3) {
                        e = e3;
                        throw new IOException("App manifest JSON creation failed", e);
                    }
                } catch (JSONException e4) {
                    e = e4;
                    throw new IOException("App manifest JSON creation failed", e);
                }
            } catch (JSONException e5) {
                e = e5;
                throw new IOException("App manifest JSON creation failed", e);
            }
        } catch (JSONException e6) {
            e = e6;
        }
    }

    private void writeJsonFile(File file, JSONObject json) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create manifest directory: " + parent);
        }
        try {
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
                out.write(10);
                out.close();
            } catch (Throwable th) {
                try {
                    out.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (JSONException e) {
            throw new IOException("JSON formatting failed", e);
        }
    }

    private void syncManifestFilesQuietly() {
        if (this.prefs == null || this.vmRoot == null) {
            return;
        }
        try {
            Set<String> installed = getInstalledPackages();
            writeVmManifest(installed);
            for (String installId : installed) {
                writePrefsBackedAppManifest(installId);
            }
        } catch (IOException e) {
        }
    }

    private void writePrefsBackedAppManifest(String installId) throws IOException {
        String prefix = "app." + installId + ".";
        String appDirPath = this.prefs.getString(prefix + "app_dir", "");
        if (appDirPath.isEmpty()) {
            return;
        }
        try {
            JSONObject manifest = new JSONObject();
            try {
                manifest.put("schema", "single-vm-app");
                manifest.put("version", MANIFEST_VERSION);
                manifest.put(GuestRunActivity.EXTRA_INSTALL_ID, installId);
                manifest.put("display_name", this.prefs.getString(prefix + "display_name", installId));
                manifest.put(GuestRunActivity.EXTRA_LABEL, this.prefs.getString(prefix + GuestRunActivity.EXTRA_LABEL, installId));
                manifest.put(GuestRunActivity.EXTRA_PACKAGE, this.prefs.getString(prefix + GuestRunActivity.EXTRA_PACKAGE, ""));
                manifest.put("version_name", this.prefs.getString(prefix + "version_name", ""));
                manifest.put("version_code", this.prefs.getLong(prefix + "version_code", 0L));
                manifest.put("first_activity", this.prefs.getString(prefix + "first_activity", ""));
                manifest.put("activity_count", this.prefs.getInt(prefix + "activity_count", 0));
                manifest.put("permission_count", this.prefs.getInt(prefix + "permission_count", 0));
                manifest.put("dex_count", this.prefs.getInt(prefix + "dex_count", 0));
                manifest.put("native_lib_count", this.prefs.getInt(prefix + "native_lib_count", 0));
                manifest.put(GuestRunActivity.EXTRA_ARM32_LIB_COUNT, this.prefs.getInt(prefix + GuestRunActivity.EXTRA_ARM32_LIB_COUNT, 0));
                manifest.put("arm64_lib_count", this.prefs.getInt(prefix + "arm64_lib_count", 0));
                manifest.put("extracted_lib_count", this.prefs.getInt(prefix + "extracted_lib_count", 0));
                manifest.put("asset_count", this.prefs.getInt(prefix + "asset_count", 0));
                manifest.put("archive_size", this.prefs.getLong(prefix + "archive_size", 0L));
                manifest.put(GuestRunActivity.EXTRA_ABIS, new JSONArray((Collection) splitCommaList(this.prefs.getString(prefix + GuestRunActivity.EXTRA_ABIS, ""))));
                manifest.put(GuestRunActivity.EXTRA_APK_PATH, this.prefs.getString(prefix + GuestRunActivity.EXTRA_APK_PATH, ""));
                try {
                    manifest.put("app_dir", appDirPath);
                    manifest.put(GuestRunActivity.EXTRA_LIB_DIR, this.prefs.getString(prefix + GuestRunActivity.EXTRA_LIB_DIR, ""));
                    manifest.put("assets_dir", this.prefs.getString(prefix + "assets_dir", ""));
                    manifest.put(GuestRunActivity.EXTRA_DATA_DIR, this.prefs.getString(prefix + GuestRunActivity.EXTRA_DATA_DIR, ""));
                    manifest.put("installed_at", this.prefs.getString(prefix + "installed_at", ""));
                    writeJsonFile(new File(appDirPath, "manifest.json"), manifest);
                } catch (JSONException e) {
                    e = e;
                    throw new IOException("App manifest JSON creation failed", e);
                }
            } catch (JSONException e2) {
                e = e2;
            }
        } catch (JSONException e3) {
            e = e3;
        }
    }

    private List<String> splitCommaList(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.trim().isEmpty() || value.contains("?")) {
            return out;
        }
        String[] parts = value.split(",");
        int length = parts.length;
        for (int i = 0; i < length; i += MANIFEST_VERSION) {
            String part = parts[i];
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private Set<String> getInstalledPackages() {
        Set<String> stored = this.prefs.getStringSet(KEY_INSTALLED_PACKAGES, null);
        if (stored == null) {
            return new LinkedHashSet();
        }
        return new LinkedHashSet(stored);
    }

    private String sanitizePackageName(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i += MANIFEST_VERSION) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || ((c >= 'A' && c <= 'Z') || ((c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-'))) {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }

    private String emptyToFallback(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value;
    }

    private String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(new Date());
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX INFO: renamed from: showToast, reason: merged with bridge method [inline-methods] */
    public void m2x16efb73e(String message) {
        Toast.makeText(this, message, MANIFEST_VERSION).show();
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message == null ? "알 수 없는 오류" : message).setPositiveButton("확인", (DialogInterface.OnClickListener) null).show();
    }

    private int dp(int value) {
        return (int) ((value * getResources().getDisplayMetrics().density) + 0.5f);
    }

    private static final class ApkReport {
        final LinkedHashSet<String> abis;
        int activityCount;
        String appLabel;
        long archiveSize;
        int arm32LibCount;
        int arm64LibCount;
        int dexCount;
        String firstActivity;
        int nativeLibCount;
        String packageName;
        int permissionCount;
        boolean validApk;
        long versionCode;
        String versionName;

        private ApkReport() {
            this.appLabel = "";
            this.packageName = "";
            this.versionName = "";
            this.firstActivity = "";
            this.abis = new LinkedHashSet<>();
        }
    }
}

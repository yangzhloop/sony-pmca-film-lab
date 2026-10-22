package com.filmlab.diagnostics;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Original, API-10 diagnostic launcher. Camera ownership stays with Sony. */
public final class DiagnosticActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(14, 15, 17);
    private static final int PANEL = Color.rgb(34, 35, 38);
    private static final int AMBER = Color.rgb(255, 177, 44);
    private static final int WHITE = Color.rgb(240, 240, 240);
    private static final int MUTED = Color.rgb(180, 182, 186);
    private static final Object REPORT_LOCK = new Object();

    /* A pending snapshot survives Activity recreation, but never process death.
       Export completion updates this session first. UI callbacks merely redraw
       the current Activity, so a destroyed Activity cannot replace new state. */
    private static String pendingSnapshot;
    private static String exportState = "ready";
    private static String exportFilename = "";
    private static String exportError = "";
    private static int reportGeneration;
    private static WeakReference<DiagnosticActivity> resumedActivity =
            new WeakReference<DiagnosticActivity>(null);

    private final Handler handler = new Handler();
    private final ArrayList<Button> buttons = new ArrayList<Button>();
    private final ArrayList<Boolean> rightOpens = new ArrayList<Boolean>();
    private final HashMap<String, Integer> selectedRows = new HashMap<String, Integer>();
    private final ArrayList<String> keyLines = new ArrayList<String>();
    private String language = "en";
    private String screen = "index";
    private String message = "";
    private LinearLayout content;
    private ScrollView scroll;
    private TextView keyDisplay;
    private boolean destroyed;
    private boolean resumed;
    private int consumedBackScan = -1;

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        DiagnosticsBridge.init(this);
        language = getPreferences(MODE_PRIVATE).getString("language", "en");
        if (!"zh".equals(language)) language = "en";
        if (savedState != null) {
            String previous = savedState.getString("screen");
            if (knownScreen(previous)) screen = previous;
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        synchronized (REPORT_LOCK) {
            resumedActivity = new WeakReference<DiagnosticActivity>(this);
        }
        DiagnosticsBridge.record("lifecycle.resume", "screen=" + screen + ";language=" + language);
        /* Sony's application manager needs the foreground component identity.
           This does not disable auto power-off or alter camera/display settings. */
        try {
            Intent info = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
            info.putExtra("package_name", getPackageName());
            info.putExtra("class_name", getClass().getName());
            sendBroadcast(info);
        } catch (SecurityException denied) {
            DiagnosticsBridge.record("ui.action", "stage=resume;status=unavailable");
        }
        render();
    }

    @Override
    protected void onPause() {
        resumed = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        synchronized (REPORT_LOCK) {
            if (resumedActivity.get() == this) resumedActivity.clear();
        }
        DiagnosticsBridge.record("lifecycle.pause", "screen=" + screen);
        super.onPause();
    }

    @Override
    protected void onStop() {
        DiagnosticsBridge.record("lifecycle.stop", "screen=" + screen);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("screen", screen);
        super.onSaveInstanceState(state);
    }

    private static boolean knownScreen(String value) {
        return "index".equals(value) || "report".equals(value) || "language".equals(value)
                || "about".equals(value) || "developer".equals(value) || "device".equals(value)
                || "processing".equals(value) || "events".equals(value) || "keys".equals(value);
    }

    private String t(String english, String chinese) {
        return "zh".equals(language) ? chinese : english;
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }

    private TextView text(String value, int size, int color) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setPadding(dp(12), dp(6), dp(12), dp(6));
        result.setFocusable(false);
        return result;
    }

    private String title() {
        if ("report".equals(screen)) return t("Save issue report", "保存问题报告");
        if ("language".equals(screen)) return t("Language", "语言");
        if ("about".equals(screen)) return t("About", "关于");
        if ("developer".equals(screen)) return t("Developer tools", "开发者工具");
        if ("device".equals(screen)) return t("Device and capabilities", "设备与能力");
        if ("processing".equals(screen)) return t("Processing status", "图像处理状态");
        if ("events".equals(screen)) return t("Recent app events", "最近应用事件");
        if ("keys".equals(screen)) return t("Key viewer", "按键查看器");
        return t("Film Lab Diagnostics", "Film Lab 诊断");
    }

    private void render() {
        if (destroyed || isFinishing()) return;
        buttons.clear();
        rightOpens.clear();
        keyDisplay = null;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BACKGROUND);
        TextView heading = text(title(), 22, AMBER);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(heading);

        if ("index".equals(screen)) {
            page.addView(text(t("A7R II test build · Hardware validation pending",
                    "A7R II 测试版本 · 尚待实机验证"), 13, MUTED));
        }
        if (message.length() > 0) page.addView(text(message, 14, AMBER));

        scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), 0, dp(8), dp(6));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        if ("index".equals(screen)) drawIndex();
        else if ("language".equals(screen)) drawLanguage();
        else if ("report".equals(screen)) drawReport();
        else if ("about".equals(screen)) drawAbout();
        else if ("developer".equals(screen)) drawDeveloper();
        else if ("device".equals(screen)) drawDevice();
        else if ("processing".equals(screen)) drawProcessing();
        else if ("events".equals(screen)) drawEvents();
        else if ("keys".equals(screen)) drawKeys();

        String hint;
        if ("keys".equals(screen)) {
            hint = t("Observe keys only · MENU: exit viewer", "仅观察按键 · MENU：退出查看器");
        } else if ("index".equals(screen) || "developer".equals(screen)) {
            hint = t("Up/Down: move  Center: select  Right: submenu  MENU: back",
                    "上/下：移动  中央键：选择  右：子菜单  左/MENU：返回");
        } else {
            hint = t("Up/Down: move  Center: select  Left/MENU: back",
                    "上/下：移动  中央键：选择  左/MENU：返回");
        }
        TextView footer = text(hint, 13, MUTED);
        footer.setBackgroundColor(PANEL);
        page.addView(footer);
        com.filmlab.app.FilmFonts.applyTree(page);
        setContentView(page);
        if (!buttons.isEmpty()) {
            Integer saved = selectedRows.get(screen);
            int index = saved == null ? 0 : Math.min(saved.intValue(), buttons.size() - 1);
            while (index > 0 && !buttons.get(index).isEnabled()) index--;
            buttons.get(index).requestFocus();
        } else {
            scroll.setFocusable(true);
            scroll.requestFocus();
        }
    }

    private GradientDrawable fill(int color, int stroke) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(2));
        result.setStroke(dp(1), stroke);
        return result;
    }

    private void row(String label, boolean enabled, final Runnable action) {
        final int index = buttons.size();
        final Button button = new Button(this);
        button.setText(label);
        button.setTextSize(18);
        button.setTextColor(enabled ? WHITE : MUTED);
        button.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        button.setMinHeight(dp(46));
        button.setMinimumHeight(dp(46));
        button.setFocusable(true);
        button.setEnabled(enabled);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] { android.R.attr.state_pressed }, fill(Color.rgb(103, 68, 14), AMBER));
        background.addState(new int[] { android.R.attr.state_focused }, fill(Color.rgb(62, 45, 19), AMBER));
        background.addState(new int[] {}, fill(PANEL, Color.rgb(64, 65, 68)));
        button.setBackgroundDrawable(background);
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus) selectedRows.put(screen, Integer.valueOf(index));
            }
        });
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                selectedRows.put(screen, Integer.valueOf(index));
                action.run();
            }
        });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.bottomMargin = dp(5);
        content.addView(button, layout);
        buttons.add(button);
        rightOpens.add(Boolean.FALSE);
    }

    private void row(String label, Runnable action) { row(label, true, action); }

    private void submenu(String label, Runnable action) {
        row(label, action);
        rightOpens.set(rightOpens.size() - 1, Boolean.TRUE);
    }

    private void note(String value) { content.addView(text(value, 15, MUTED)); }

    private void open(String destination) {
        screen = destination;
        message = "";
        DiagnosticsBridge.record("ui.action", "screen=" + screen + ";stage=open");
        render();
    }

    private void drawIndex() {
        row(t("Open Sony shooting", "打开 Sony 拍摄"), new Runnable() {
            public void run() { launchShooting(); }
        });
        submenu(t("Save issue report", "保存问题报告"), new Runnable() {
            public void run() { openReport(); }
        });
        submenu(t("Language", "语言"), new Runnable() {
            public void run() { open("language"); }
        });
        submenu(t("About", "关于"), new Runnable() {
            public void run() { open("about"); }
        });
        submenu(t("Developer tools", "开发者工具"), new Runnable() {
            public void run() { open("developer"); }
        });
    }

    private void launchShooting() {
        DiagnosticsBridge.record("ui.action", "screen=shoot;stage=launch;status=requested");
        try {
            Intent shooting = new Intent();
            shooting.setClassName(getPackageName(), BuildInfo.NATIVE_ACTIVITY);
            startActivity(shooting);
        } catch (ActivityNotFoundException absent) {
            launchFailure("NATIVE_ACTIVITY_MISSING");
        } catch (SecurityException denied) {
            launchFailure("NATIVE_ACTIVITY_DENIED");
        }
    }

    private void launchFailure(String code) {
        DiagnosticsBridge.record("ui.action", "screen=shoot;stage=launch;status=failed");
        message = t("Could not open Sony shooting: ", "无法打开 Sony 拍摄：") + code;
        render();
    }

    private void drawLanguage() {
        note(t("This preference applies to the diagnostic menus. Inherited Sony screens keep their own language support.",
                "此设置用于诊断菜单。继承的 Sony 页面仍使用其自身支持的语言。"));
        row(("en".equals(language) ? "[x] " : "[ ] ") + "English", new Runnable() {
            public void run() { setLanguage("en"); }
        });
        row(("zh".equals(language) ? "[x] " : "[ ] ") + "简体中文", new Runnable() {
            public void run() { setLanguage("zh"); }
        });
    }

    private void setLanguage(String selected) {
        language = selected;
        boolean saved = getPreferences(MODE_PRIVATE).edit().putString("language", language).commit();
        DiagnosticsBridge.record("ui.action", "screen=language;language=" + language
                + ";status=" + (saved ? "success" : "failed"));
        message = saved ? "" : t("Language changed for this session; preference could not be saved.",
                "已更改本次会话语言，但无法保存语言偏好。 ");
        render();
    }

    private void openReport() {
        boolean missing;
        synchronized (REPORT_LOCK) { missing = pendingSnapshot == null; }
        if (missing) createSnapshot();
        open("report");
    }

    private void createSnapshot() {
        synchronized (REPORT_LOCK) {
            if ("busy".equals(exportState)) return;
        }
        String snapshot;
        try {
            snapshot = DiagnosticsBridge.report(screen, language);
        } catch (RuntimeException failure) {
            synchronized (REPORT_LOCK) {
                exportState = "snapshot-error";
                exportError = "SNAPSHOT_FAILED";
            }
            return;
        }
        synchronized (REPORT_LOCK) {
            pendingSnapshot = snapshot;
            reportGeneration++;
            exportState = "ready";
            exportFilename = "";
            exportError = "";
        }
    }

    private void drawReport() {
        String status;
        String filename;
        String error;
        boolean exists;
        synchronized (REPORT_LOCK) {
            status = exportState;
            filename = exportFilename;
            error = exportError;
            exists = pendingSnapshot != null;
        }
        note(t("App state and bounded app events only. Photos, recipe names and device serial numbers are excluded. Attach the TXT to an issue yourself.",
                "仅包含应用状态和有限的应用事件，不包含照片、配方名称或设备序列号。请自行将 TXT 附加到问题反馈。"));
        if ("busy".equals(status)) {
            note(t("Saving the existing snapshot... You may leave this page.", "正在保存已有快照……您可以离开此页。"));
        } else if ("saved".equals(status)) {
            note(t("External-storage file verified: FILMLAB/", "外部存储文件已校验：FILMLAB/") + filename);
            note(t("Verify the file with a card reader. Memory-card mapping and USB visibility still need testing on this body.",
                    "请使用读卡器确认文件。本机的存储卡路径和 USB 可见性仍需验证。"));
        } else if ("error".equals(status) || "snapshot-error".equals(status)) {
            note(t("Not saved. Error: ", "未保存。错误：") + error);
            if (exists) note(t("The same snapshot is retained for retry.", "已保留同一快照，可重试保存。"));
        } else if (exists) {
            note(t("Snapshot ready. Save writes a new TXT without replacing earlier reports.",
                    "快照已就绪。保存将创建新的 TXT，不会覆盖之前的报告。"));
        } else {
            note(t("No pending snapshot in this session. Create one before saving.",
                    "本次会话中没有待保存的快照。请先创建快照。"));
        }
        final boolean busy = "busy".equals(status);
        String saveLabel = "error".equals(status) ? t("Retry saving this snapshot", "重试保存此快照")
                : "saved".equals(status) ? t("Save another copy", "再保存一份")
                : t("Save this snapshot", "保存此快照");
        row(saveLabel, !busy && exists, new Runnable() {
            public void run() { saveSnapshot(); }
        });
        row(t("Create a new snapshot", "创建新快照"), !busy, new Runnable() {
            public void run() { createSnapshot(); render(); }
        });
    }

    private void saveSnapshot() {
        final String snapshot;
        final int generation;
        synchronized (REPORT_LOCK) {
            if (pendingSnapshot == null || "busy".equals(exportState)) return;
            snapshot = pendingSnapshot;
            generation = reportGeneration;
            exportState = "busy";
            exportFilename = "";
            exportError = "";
        }
        render();
        try {
            new Thread(new ExportJob(snapshot, generation), "FilmLabReportExport").start();
        } catch (RuntimeException unavailable) {
            finishExport(generation, "", "EXPORT_WORKER_UNAVAILABLE");
        }
    }

    /** Does not retain an Activity while storage I/O is pending. */
    private static final class ExportJob implements Runnable {
        private final String snapshot;
        private final int generation;
        ExportJob(String snapshot, int generation) {
            this.snapshot = snapshot;
            this.generation = generation;
        }
        public void run() {
            String filename = "";
            String error = "";
            try {
                File file = DiagnosticsBridge.export(snapshot);
                filename = file.getName();
            } catch (ReportStore.ExportException failure) {
                error = failure.getCode();
            } catch (IOException failure) {
                error = "EXPORT_IO_FAILED";
            } catch (SecurityException failure) {
                error = "EXPORT_ACCESS_DENIED";
            } catch (RuntimeException failure) {
                error = "EXPORT_FAILED";
            }
            finishExport(generation, filename, error);
        }
    }

    private static void finishExport(int generation, String filename, String error) {
        synchronized (REPORT_LOCK) {
            if (generation != reportGeneration) return;
            exportFilename = filename;
            exportError = error;
            exportState = error.length() == 0 ? "saved" : "error";
        }
        refreshReportPage();
    }

    private static void refreshReportPage() {
        final DiagnosticActivity activity;
        synchronized (REPORT_LOCK) { activity = resumedActivity.get(); }
        if (activity == null) return;
        activity.handler.post(new Runnable() {
            public void run() {
                if (activity.resumed && !activity.destroyed && !activity.isFinishing()
                        && "report".equals(activity.screen)) {
                    activity.render();
                }
            }
        });
    }

    private Map<String, String> facts() { return DiagnosticsBridge.facts(screen, language); }

    private String fact(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null || value.length() == 0 ? "unknown" : value;
    }

    private void factLine(Map<String, String> values, String key, String english, String chinese) {
        note(t(english, chinese) + ": " + fact(values, key));
    }

    private void drawAbout() {
        note("Film Lab Diagnostics " + BuildInfo.VERSION);
        note(t("Build: ", "构建：") + BuildInfo.BUILD_ID);
        note(t("Target: Sony A7R II / ILCE-7RM2. This diagnostic build has not yet been validated on that camera.",
                "目标机型：Sony A7R II / ILCE-7RM2。本诊断版本尚未完成该机型的实机验证。"));
        Map<String, String> values = facts();
        factLine(values, "model", "Reported model", "读取到的机型");
        factLine(values, "firmware", "Reported firmware", "读取到的固件");
        note(t("Project and issue reports:\ngithub.com/yangzhloop/sony-pmca-film-lab\ngithub.com/yangzhloop/sony-pmca-film-lab/issues",
                "项目与问题反馈：\ngithub.com/yangzhloop/sony-pmca-film-lab\ngithub.com/yangzhloop/sony-pmca-film-lab/issues"));
        note(t("Original diagnostic code: MIT. Film Studio additions: PolyForm Noncommercial; relevant Ricoh/signer portions: Apache-2.0. Sony and other third-party materials keep their own rights. Full notices and pinned sources accompany this build. Personal/noncommercial evaluation only.",
                "原创诊断代码采用 MIT 许可。Film Studio 新增内容采用 PolyForm 非商业许可，相关 Ricoh/签名代码采用 Apache-2.0 许可。Sony 及其他第三方材料保留各自权利。完整声明和固定版本源代码信息随构建提供。仅用于个人/非商业测试。"));
        note(t("The native shooting screen owns DISP, capture and available Sony flash settings. This launcher does not add unsupported flash controls or claim to verify saved-image processing.",
                "原生拍摄页面负责 DISP、拍摄及可用的 Sony 闪光灯设置。本启动页不会添加未经支持的闪光灯控制，也不会声称已验证成片处理效果。"));
    }

    private void drawDeveloper() {
        note(t("Read-only diagnostic views. No firmware, shell or camera-parameter writes.",
                "只读诊断页面，不写入固件、不执行命令行，也不修改相机参数。"));
        submenu(t("Device and capabilities", "设备与能力"), new Runnable() {
            public void run() { open("device"); }
        });
        submenu(t("Processing status", "图像处理状态"), new Runnable() {
            public void run() { open("processing"); }
        });
        submenu(t("Key viewer", "按键查看器"), new Runnable() {
            public void run() { keyLines.clear(); open("keys"); }
        });
        submenu(t("Recent app events", "最近应用事件"), new Runnable() {
            public void run() { open("events"); }
        });
    }

    private void drawDevice() {
        Map<String, String> values = facts();
        factLine(values, "model", "Reported model", "读取到的机型");
        factLine(values, "firmware", "Firmware", "固件");
        factLine(values, "platformApi", "Sony API", "Sony API");
        factLine(values, "androidApi", "Android API", "Android API");
        factLine(values, "readbackSource", "Capability evidence source", "能力证据来源");
        factLine(values, "rgbSupport", "Matrix support", "矩阵支持");
        factLine(values, "gammaSupport", "Curve support", "曲线支持");
        factLine(values, "captureCapability", "Capture support", "拍摄支持");
        factLine(values, "storageStatus", "External storage", "外部存储");
        factLine(values, "storageFreeBytes", "Available bytes", "可用字节数");
        note(t("Unknown and not-probed mean no verified observation. This page does not open a camera or infer support from the target model.",
                "unknown 和 not-probed 表示尚无验证结果。此页不会打开相机，也不会根据目标机型推断支持情况。"));
    }

    private void drawProcessing() {
        Map<String, String> values = facts();
        factLine(values, "readbackStatus", "Camera readback", "相机参数读回");
        factLine(values, "readbackSource", "Evidence source", "证据来源");
        factLine(values, "readbackAgeMs", "Readback age (ms)", "读回距今（毫秒）");
        factLine(values, "requestedId", "Requested look ID", "请求的效果 ID");
        factLine(values, "requestedStrength", "Requested strength", "请求的强度");
        factLine(values, "applyStatus", "Apply call result", "应用调用结果");
        factLine(values, "activeId", "Active look ID", "当前效果 ID");
        factLine(values, "requestedMatrix", "Requested matrix", "请求的矩阵");
        factLine(values, "observedMatrix", "Observed matrix", "实际读取的矩阵");
        factLine(values, "observedAfterRequest", "Readback after request", "读回发生在请求之后");
        factLine(values, "matrixMatchesRequest", "Matrix matches request", "矩阵与请求一致");
        factLine(values, "curveHash", "Requested curve hash", "请求的曲线哈希");
        factLine(values, "observedCurveHash", "Observed curve hash", "实际读取的曲线哈希");
        factLine(values, "savedImageVerification", "Saved-image verification", "成片验证");
        factLine(values, "captureStatus", "Capture state", "拍摄状态");
        factLine(values, "displayState", "Native DISP state", "原生 DISP 状态");
        factLine(values, "flashMode", "Flash mode", "闪光灯模式");
        factLine(values, "flashAccessory", "Flash accessory", "闪光灯附件");
        note(t("Requested values are not proof of camera acceptance or saved-image output. Use native shooting for the hardware test; unsupported observations stay unknown.",
                "请求值并不能证明相机已接受设置或成片已生效。请在原生拍摄中完成实机测试；无法获取的结果保持未知。"));
    }

    private void drawEvents() {
        note(t("App-owned, bounded events. No OS logcat or text-key content. Reopen to refresh.",
                "仅显示有限的应用自身事件，不读取系统 logcat 或按键文字。重新打开此页可刷新。"));
        String events = DiagnosticsBridge.recentEvents();
        TextView log = text(events.length() == 0 ? t("No events yet.", "暂无事件。") : events, 13, WHITE);
        log.setTypeface(Typeface.MONOSPACE);
        content.addView(log);
    }

    private void drawKeys() {
        note(t("Press camera buttons to inspect numeric key and scan codes. MENU exits; other keys are observed only. No characters are recorded.",
                "按下相机按键以查看按键码和扫描码。MENU 退出，其他按键仅用于观察。不记录字符内容。"));
        note(CameraKeys.usesFallback()
                ? t("Mapping: fallback constants in use; validate on this body.", "映射：正在使用备用常量，请在本机验证。")
                : t("Mapping: ScalarInput constants loaded; physical behavior still needs validation.", "映射：已读取 ScalarInput 常量，实际按键行为仍需验证。"));
        keyDisplay = text(keyText(), 16, WHITE);
        keyDisplay.setTypeface(Typeface.MONOSPACE);
        content.addView(keyDisplay);
    }

    private String keyText() {
        if (keyLines.isEmpty()) return t("Waiting for a key...", "等待按键……");
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < keyLines.size(); index++) {
            if (index > 0) result.append('\n');
            result.append(keyLines.get(index));
        }
        return result.toString();
    }

    private void observeKey(KeyEvent event) {
        String action = event.getAction() == KeyEvent.ACTION_DOWN ? "down"
                : event.getAction() == KeyEvent.ACTION_UP ? "up" : "other";
        String line = action + " key=" + event.getKeyCode() + " scan=" + event.getScanCode()
                + " repeat=" + event.getRepeatCount();
        keyLines.add(0, line);
        while (keyLines.size() > 12) keyLines.remove(keyLines.size() - 1);
        DiagnosticsBridge.record("key.event", "code=" + event.getKeyCode()
                + ";number=" + event.getScanCode() + ";stage=" + action
                + ";repeat=" + (event.getRepeatCount() > 0 ? "repeat" : "press"));
        if (keyDisplay != null) keyDisplay.setText(keyText());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int direction = CameraKeys.navigation(event);
        if (event.getAction() == KeyEvent.ACTION_UP && event.getScanCode() == consumedBackScan) {
            consumedBackScan = -1;
            return true;
        }
        if ("keys".equals(screen)) {
            observeKey(event);
            if (direction == CameraKeys.MENU && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                consumedBackScan = event.getScanCode();
                goBack();
            }
            return true;
        }
        if (direction == CameraKeys.NONE) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (direction == CameraKeys.UP || direction == CameraKeys.DOWN) {
            move(direction == CameraKeys.UP ? -1 : 1);
        } else if (event.getRepeatCount() == 0) {
            if (direction == CameraKeys.LEFT || direction == CameraKeys.MENU) {
                consumedBackScan = event.getScanCode();
                goBack();
            } else if (direction == CameraKeys.CENTER || direction == CameraKeys.RIGHT) {
                View focused = getCurrentFocus();
                int index = buttons.indexOf(focused);
                boolean allowed = direction == CameraKeys.CENTER
                        || (index >= 0 && rightOpens.get(index).booleanValue());
                if (allowed && focused instanceof Button && focused.isEnabled()) focused.performClick();
            }
            /* Right can enter a submenu, but never exports a report, launches
               shooting, or changes a preference. Those require Center. */
        }
        return true;
    }

    private void move(int delta) {
        if (buttons.isEmpty()) {
            scroll.smoothScrollBy(0, dp(72) * delta);
            return;
        }
        int current = -1;
        for (int index = 0; index < buttons.size(); index++) {
            if (buttons.get(index).hasFocus()) { current = index; break; }
        }
        int next = current < 0 ? (delta > 0 ? 0 : buttons.size() - 1) : current + delta;
        while (next >= 0 && next < buttons.size()) {
            if (buttons.get(next).isEnabled()) { buttons.get(next).requestFocus(); return; }
            next += delta;
        }
        /* At the ends, allow reading explanatory text outside the rows. */
        scroll.smoothScrollBy(0, dp(72) * delta);
    }

    @Override
    public void onBackPressed() { goBack(); }

    private void goBack() {
        if ("index".equals(screen)) {
            finish();
        } else if ("device".equals(screen) || "processing".equals(screen)
                || "keys".equals(screen) || "events".equals(screen)) {
            open("developer");
        } else {
            open("index");
        }
    }
}

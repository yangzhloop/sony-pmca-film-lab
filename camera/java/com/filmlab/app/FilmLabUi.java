// SPDX-License-Identifier: MIT
package com.filmlab.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.filmlab.diagnostics.BuildInfo;
import com.filmlab.diagnostics.CameraKeys;
import com.filmlab.diagnostics.DiagnosticsBridge;
import com.filmlab.diagnostics.ReportStore;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Film Lab's own menus overlay Sony's live shooting Activity. All input is physical-button capable. */
public final class FilmLabUi {
    private static final int WHITE = 0xffeeeeee, MUTED = 0xffb9b9b4, AMBER = 0xffffae42;
    private static final int PANEL = 0xf0212222, ROW = 0xe82a2b2b, FOCUS = 0xef66543a;
    private static final String[] LIBRARY_TABS = { "favorites", "builtin", "user" };
    private static FilmLabUi current;
    private static final Object REPORT_LOCK = new Object();
    private static final ReportState REPORT = new ReportState();
    private static final Handler REPORT_HANDLER = new Handler(Looper.getMainLooper());
    private final Activity activity;
    private final Handler handler = new Handler();
    private final SharedPreferences preferences;
    private final FrameLayout overlay;
    private RecipeStore store;
    private String language;
    private String screen = "library";
    private String tab = "favorites";
    private String notice = "";
    private Recipe selectedRecipe;
    private Recipe draft;
    private boolean draftDirty;
    private boolean renameOnly;
    private NameKeyboard keyboard;
    private String keyboardReturn;
    private String confirmation = "";
    private String confirmationReturn = "";
    private int index;
    private int libraryIndex;
    private float scale = 1f;
    private LinearLayout page;
    private LinearLayout list;
    private LinearLayout right;
    private ScrollView scroll;
    private final ArrayList<Row> rows = new ArrayList<Row>();
    private final ArrayList<String> keyLines = new ArrayList<String>();
    private TextView keysView;
    private TextView noticeView;
    private TextView previewHeading, previewName, previewHint, previewColor, previewTone;
    private TextView keyboardValue, keyboardMode, keyboardError;
    private LinearLayout keyboardGrid;
    private final ArrayList<TextView> keyboardCells = new ArrayList<TextView>();
    private boolean firstLayoutRecorded;
    private boolean previewAccepted;
    private Runnable pendingPreview;
    private int previewGeneration;
    private boolean closed;
    private boolean exitPending;

    /** No Activity references. Retained through Activity recreation, not process death. */
    private static final class ReportState {
        String snapshot;
        boolean busy;
        String result = "";
        String error = "";
        int generation;
    }

    private static final class Row {
        String label; String value; Recipe recipe; boolean enabled; Runnable action; Adjust adjust;
        LinearLayout view; View marker; TextView nameView, valueView;
        Row(String label, String value, boolean enabled, Runnable action, Adjust adjust) {
            this.label = label; this.value = value; this.enabled = enabled; this.action = action; this.adjust = adjust;
        }
    }
    private interface Adjust { void change(int delta); }

    private FilmLabUi(Activity owner) {
        activity = owner;
        preferences = owner.getSharedPreferences("film_lab", 0);
        String previousLanguage = owner.getSharedPreferences("com.filmlab.diagnostics.DiagnosticActivity", 0).getString("language",
                owner.getSharedPreferences("DiagnosticActivity", 0).getString("language", "en"));
        language = preferences.getString("language", previousLanguage);
        if (!preferences.contains("language")) preferences.edit().putString("language", language).commit();
        if (!"zh".equals(language)) language = "en";
        AppLanguage.apply(owner, language);
        try { store = new RecipeStore(new File(owner.getFilesDir(), "recipes")); }
        catch (IOException failed) { notice = t("Recipes could not be opened. Restart the app.", "无法读取配方，请重新启动应用。"); }
        overlay = new FrameLayout(owner);
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(true);
        overlay.setVisibility(View.GONE);
        View decor = owner.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) throw new IllegalStateException("decor_not_group");
        ((ViewGroup) decor).addView(overlay, new ViewGroup.LayoutParams(-1, -1));
    }

    public static void attach(Activity owner) {
        if (current != null && current.activity == owner) return;
        if (current != null) {
            current.stopPendingPreview();
            current.closed = true;
            ViewGroup parent = (ViewGroup) current.overlay.getParent();
            if (parent != null) parent.removeView(current.overlay);
        }
        current = new FilmLabUi(owner);
        DiagnosticsBridge.milestone("ui_attached");
    }
    public static void detach(Activity owner) {
        if (current == null || current.activity != owner) return;
        current.stopPendingPreview(); current.closed = true; current.handler.removeCallbacksAndMessages(null);
        ViewGroup parent = (ViewGroup) current.overlay.getParent();
        if (parent != null) parent.removeView(current.overlay);
        current = null;
    }
    public static void cancelPendingPreview() { if (current != null) current.stopPendingPreview(); }
    public static RecipeStore getStore() { return current == null ? null : current.store; }
    public static boolean isOpen() { return current != null && current.overlay.getVisibility() == View.VISIBLE; }
    public static void open(String destination) {
        if (current == null || current.exitPending) return;
        current.notice = "";
        current.screen = "settings".equals(destination) ? "settings" : "library";
        current.index = 0;
        current.overlay.setVisibility(View.VISIBLE);
        current.overlay.bringToFront();
        if ("library".equals(current.screen)) current.enterLibrary();
        current.render();
        current.overlay.requestFocus();
    }
    /** Lifecycle/committed-use close. User cancellation goes through leave() to restore preview. */
    public static void close() {
        if (current == null) return;
        current.stopPendingPreview();
        current.overlay.setVisibility(View.GONE);
        current.previewAccepted = false;
        current.keyboard = null;
        current.draft = null;
        current.draftDirty = false;
    }
    public static boolean dispatch(KeyEvent event) {
        if (!isOpen()) return false;
        long started = SystemClock.elapsedRealtime();
        try { return current.key(event); }
        finally { if (event.getAction() == KeyEvent.ACTION_DOWN) DiagnosticsBridge.timing("ui_navigation", SystemClock.elapsedRealtime() - started); }
    }

    private String t(String en, String zh) { return "zh".equals(language) ? zh : en; }
    private int px(int value) { return Math.max(1, Math.round(value * scale)); }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value); view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        view.setGravity(Gravity.CENTER_VERTICAL);
        FilmFonts.apply(view, bold);
        return view;
    }
    private TextView padded(String value, int size, int color) {
        TextView v = text(value, size, color, false); v.setPadding(px(12), px(5), px(10), px(5)); return v;
    }
    private void note(String value) { list.addView(padded(value, 16, MUTED)); }
    private void add(String label, String value, boolean enabled, Runnable action, Adjust adjust) {
        rows.add(new Row(label, value, enabled, action, adjust));
    }
    private void add(String label, Runnable action) { add(label, "→", true, action, null); }
    private void go(String destination) { screen = destination; index = 0; notice = ""; render(); }

    private String title() {
        if ("library".equals(screen)) return t("Film presets", "胶片预设");
        if ("detail".equals(screen)) return selectedRecipe == null ? "Film Lab" : selectedRecipe.displayName(language);
        if ("editor".equals(screen)) return t("Editing", "编辑配方");
        if ("keyboard".equals(screen)) return renameOnly ? t("Rename recipe", "重命名配方") : t("Recipe name", "配方名称");
        if ("settings".equals(screen)) return t("Settings", "设置");
        if ("language".equals(screen)) return t("Language", "语言");
        if ("flash".equals(screen)) return t("Flash", "闪光灯");
        if ("camera".equals(screen)) return t("Camera settings", "相机设置");
        if ("report".equals(screen)) return t("Save issue report", "保存问题报告");
        if ("about".equals(screen)) return t("About Film Lab", "关于 Film Lab");
        if ("developer".equals(screen)) return t("Developer tools", "开发者工具");
        if ("device".equals(screen)) return t("Device and capabilities", "设备与能力");
        if ("glyphs".equals(screen)) return t("Language display check", "语言显示检查");
        if ("processing".equals(screen)) return t("Processing status", "图像处理状态");
        if ("keys".equals(screen)) return t("Key viewer", "按键查看器");
        if ("events".equals(screen)) return t("Recent events", "最近事件");
        if ("confirm".equals(screen)) return t("Confirm", "确认");
        return "Film Lab";
    }

    private void render() {
        long started = SystemClock.elapsedRealtime();
        try { renderPage(); }
        finally { DiagnosticsBridge.timing("ui_render", SystemClock.elapsedRealtime() - started); }
    }
    /** Build only when the screen or its collection changes. Navigation retains these views. */
    private void renderPage() {
        if (closed || activity.isFinishing()) return;
        int height = overlay.getHeight();
        if (height <= 0) height = activity.getResources().getDisplayMetrics().heightPixels;
        scale = Math.max(0.65f, height / 480f);
        overlay.removeAllViews(); rows.clear(); keysView = null;
        scroll = null; list = null; right = null;
        previewHeading = null; previewName = null; previewHint = null; previewColor = null; previewTone = null;
        keyboardCells.clear(); keyboardGrid = null;
        page = new LinearLayout(activity); page.setOrientation(LinearLayout.VERTICAL);
        // The camera image outside the compact preset panel must remain clear.
        page.setBackgroundColor("library".equals(screen) ? 0x00000000 : 0x99151717);
        overlay.addView(page, new FrameLayout.LayoutParams(-1, -1));
        if (!firstLayoutRecorded) {
            final LinearLayout renderedPage = page;
            final ViewTreeObserver layoutObserver = renderedPage.getViewTreeObserver();
            layoutObserver.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                public void onGlobalLayout() {
                    // A rebuilt page may already be detached. Use its original observer,
                    // or the stable overlay's observer after an initial attach merged it.
                    ViewTreeObserver observer = layoutObserver.isAlive() ? layoutObserver : overlay.getViewTreeObserver();
                    if (observer.isAlive()) observer.removeGlobalOnLayoutListener(this);
                    if (!closed && page == renderedPage && !firstLayoutRecorded) {
                        firstLayoutRecorded = true; DiagnosticsBridge.milestone("first_menu_layout");
                    }
                }
            });
        }
        LinearLayout head = new LinearLayout(activity); head.setGravity(Gravity.CENTER_VERTICAL); head.setBackgroundColor(PANEL);
        head.addView(new FilmIcon(null, AMBER), new LinearLayout.LayoutParams(px(48), px(40)));
        head.addView(text(title(), 22, WHITE, false), new LinearLayout.LayoutParams(0, -1, 1));
        TextView back = padded(t("← Back", "← 返回"), 16, MUTED); head.addView(back);
        page.addView(head, new LinearLayout.LayoutParams(-1, px(44)));
        View line = new View(activity); line.setBackgroundColor(AMBER); page.addView(line, new LinearLayout.LayoutParams(-1, px(2)));
        if ("library".equals(screen)) drawTabs();
        noticeView = padded(notice, 14, AMBER); noticeView.setBackgroundColor(PANEL); page.addView(noticeView);
        updateNotice();
        if ("keyboard".equals(screen)) { drawKeyboard(); drawFooter(); return; }
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll = new ScrollView(activity); scroll.setFillViewport(false); scroll.setFocusable(false);
        list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL); list.setBackgroundColor(PANEL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        boolean library = "library".equals(screen);
        boolean split = "detail".equals(screen) || "editor".equals(screen);
        body.addView(scroll, new LinearLayout.LayoutParams(0, -1, split ? 0.55f : 1f));
        if (library) {
            // Equal-width tabs use one share each. Keep the list under the left
            // share for all categories so changing tabs never shifts the view.
            body.addView(new View(activity), new LinearLayout.LayoutParams(0, -1, 2f));
        } else if (split) {
            right = new LinearLayout(activity); right.setOrientation(LinearLayout.VERTICAL); right.setGravity(Gravity.BOTTOM);
            body.addView(right, new LinearLayout.LayoutParams(0, -1, 0.45f));
        } else right = null;
        if ("library".equals(screen)) drawLibrary();
        else if ("detail".equals(screen)) drawDetail();
        else if ("editor".equals(screen)) drawEditor();
        else if ("settings".equals(screen)) drawSettings();
        else if ("language".equals(screen)) drawLanguage();
        else if ("flash".equals(screen)) drawFlash();
        else if ("camera".equals(screen)) drawCamera();
        else if ("report".equals(screen)) drawReport();
        else if ("about".equals(screen)) drawAbout();
        else if ("developer".equals(screen)) drawDeveloper();
        else if ("device".equals(screen) || "processing".equals(screen)) drawFacts();
        else if ("events".equals(screen)) note(DiagnosticsBridge.recentEvents());
        else if ("keys".equals(screen)) drawKeys();
        else if ("glyphs".equals(screen)) { note("语言  语 言  简体中文  語言  English"); note("配方  胶片  色彩  阴影  高光  白平衡"); note(t("Font: ", "字体：") + FilmFonts.status()); }
        else if ("confirm".equals(screen)) drawConfirm();
        index = Math.max(0, Math.min(index, rows.size() - 1));
        for (int i = 0; i < rows.size(); i++) drawRow(rows.get(i), i);
        drawFooter();
        final ScrollView renderedScroll = scroll;
        scroll.post(new Runnable() { public void run() {
            if (!closed && scroll == renderedScroll) {
                scrollToSelection();
            }
        }});
    }
    private void updateNotice() {
        if (noticeView == null) return;
        setTextIfChanged(noticeView, notice);
        noticeView.setVisibility(notice.length() == 0 ? View.GONE : View.VISIBLE);
    }
    private static void setTextIfChanged(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }
    private void scrollToSelection() {
        if (scroll == null || index < 0 || index >= rows.size()) return;
        View target = rows.get(index).view;
        if (target == null) return;
        int top = target.getTop(), bottom = top + target.getHeight();
        if (top < scroll.getScrollY()) scroll.scrollTo(0, top);
        else if (bottom > scroll.getScrollY() + scroll.getHeight()) scroll.scrollTo(0, Math.max(0, bottom - scroll.getHeight()));
    }
    private void updateFocus(int previous) {
        if (previous >= 0 && previous < rows.size()) updateRow(rows.get(previous), previous);
        if (index >= 0 && index < rows.size()) updateRow(rows.get(index), index);
        scrollToSelection();
    }
    private void updateRow(Row row, int at) {
        if (row.view == null) return;
        boolean focused = at == index;
        row.view.setBackgroundColor(focused ? FOCUS : ROW);
        row.marker.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
        setTextIfChanged(row.nameView, row.label); row.nameView.setTextColor(row.enabled ? WHITE : MUTED);
        setTextIfChanged(row.valueView, row.value == null ? "" : row.value);
        row.valueView.setTextColor(row.enabled ? (focused ? AMBER : WHITE) : MUTED);
        row.valueView.setVisibility(row.value == null || row.value.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void drawTabs() {
        LinearLayout tabs = new LinearLayout(activity); tabs.setBackgroundColor(PANEL);
        String[] labels = { t("Favorites", "收藏"), t("Built-in", "内置预设"), t("My recipes", "我的配方") };
        for (int i = 0; i < LIBRARY_TABS.length; i++) {
            TextView v = text(labels[i], 16, tab.equals(LIBRARY_TABS[i]) ? AMBER : MUTED, false);
            v.setGravity(Gravity.CENTER); if (tab.equals(LIBRARY_TABS[i])) v.setBackgroundColor(0xff493b28);
            tabs.addView(v, new LinearLayout.LayoutParams(0, px(36), 1));
        }
        page.addView(tabs);
    }

    private void drawRow(Row row, final int rowIndex) {
        boolean compact = "library".equals(screen);
        LinearLayout item = new LinearLayout(activity); item.setGravity(Gravity.CENTER_VERTICAL);
        row.view = item;
        row.marker = new View(activity); row.marker.setBackgroundColor(AMBER);
        item.addView(row.marker, new LinearLayout.LayoutParams(px(3), -1));
        if (row.recipe != null) item.addView(new FilmIcon(row.recipe.code(), row.enabled ? WHITE : MUTED), new LinearLayout.LayoutParams(px(compact ? 30 : 50), px(compact ? 40 : 52)));
        TextView name = padded(row.label, compact ? 16 : 18, row.enabled ? WHITE : MUTED);
        if (compact) name.setPadding(px(5), px(5), px(3), px(5));
        name.setMaxLines(compact || row.recipe != null ? 1 : 2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        item.addView(name, new LinearLayout.LayoutParams(0, -1, 1));
        row.nameView = name;
        TextView val = padded(row.value == null ? "" : row.value, 16, WHITE);
        if (compact) val.setPadding(px(2), px(5), px(5), px(5));
        val.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        val.setMaxWidth(px("editor".equals(screen) || "library".equals(screen) || "detail".equals(screen) ? 155 : 230));
        val.setMaxLines(1);
        val.setEllipsize(TextUtils.TruncateAt.END);
        item.addView(val);
        row.valueView = val;
        updateRow(row, rowIndex);
        item.setOnClickListener(new View.OnClickListener() { public void onClick(View view) {
            int previous = index; index = rowIndex; updateFocus(previous); activateRow();
        }});
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(compact || row.recipe == null ? 46 : 57));
        lp.bottomMargin = px(1); list.addView(item, lp);
    }

    private void drawFooter() {
        String hint;
        if ("library".equals(screen)) hint = t("MENU Back   ↑↓ View   ←→ Tabs   ● Details", "MENU 返回   ↑↓ 浏览   ←→ 分类   ● 详情");
        else if ("editor".equals(screen)) hint = t("MENU Back   ↑↓ Fields   ←→ Adjust   ● Select", "MENU 返回   ↑↓ 字段   ←→ 调整   ● 选择");
        else if ("keyboard".equals(screen)) hint = t("MENU Cancel   ↑↓←→ Move   ● Enter", "MENU 取消   ↑↓←→ 移动   ● 输入");
        else if ("keys".equals(screen)) hint = t("MENU Exit key viewer", "MENU 退出按键查看器");
        else hint = t("MENU / ← Back   ↑↓ Move   ● Select   Half shutter: shooting", "MENU / ← 返回   ↑↓ 移动   ● 选择   半按快门：拍摄");
        TextView footer = padded(hint, 13, MUTED); footer.setBackgroundColor(0xff252626);
        page.addView(footer, new LinearLayout.LayoutParams(-1, px(30)));
    }

    private List<Recipe> visible() { return store == null ? new ArrayList<Recipe>() : store.list(tab); }
    private void enterLibrary() {
        tab = LIBRARY_TABS[0]; libraryIndex = 0; index = 0; notice = "";
        // A failed restoration can survive a native lifecycle close. Keep its
        // error visible on Settings instead of hiding the active preview behind
        // an empty Favorites list (or returning to a discarded editor/keyboard).
        screen = selectLibrary() ? "library" : "settings";
    }
    private boolean selectLibrary() {
        List<Recipe> recipes = visible();
        if (recipes.isEmpty()) {
            if (!cancelPreview()) return false;
            libraryIndex = 0; index = 0; selectedRecipe = null; return true;
        }
        libraryIndex = Math.max(0, Math.min(libraryIndex, recipes.size() - 1));
        index = libraryIndex;
        selectedRecipe = recipes.get(libraryIndex); preview(selectedRecipe);
        return true;
    }
    private void drawLibrary() {
        if (store == null) { note(t("Recipe storage unavailable", "配方存储不可用")); return; }
        List<Recipe> recipes = visible();
        Recipe active = store.getActive();
        for (int i = 0; i < recipes.size(); i++) {
            final Recipe recipe = recipes.get(i);
            String value = store.isFavorite(recipe.id) ? "★" : "";
            if (active != null && recipe.id.equals(active.id) && recipe.revision == active.revision) value += " ✓";
            add(recipe.displayName(language), value, true, new Runnable() { public void run() {
                selectedRecipe = recipe; go("detail");
            }}, null);
            rows.get(rows.size() - 1).recipe = recipe;
        }
        if (recipes.isEmpty()) note("favorites".equals(tab) ? t("No favorites\nRight: Built-in", "暂无收藏\n右键：内置预设") : t("Create a recipe, or duplicate a preset.", "创建配方，或复制预设。"));
        if ("user".equals(tab)) add(t("+ Create recipe", "+ 创建配方"), new Runnable() { public void run() {
            List<Recipe> bases = RecipeStore.builtins();
            draft = store.createDraft(bases.get(0).baseId);
            draft.name = t("My recipe", "我的配方"); startEditor();
        }});
    }

    private void drawPreviewCard(Recipe recipe, boolean details) {
        if (right == null) return;
        previewHeading = padded("", 13, WHITE);
        previewHeading.setBackgroundColor(0xb0202222);
        right.addView(previewHeading, new LinearLayout.LayoutParams(-1, px(29)));
        View space = new View(activity); right.addView(space, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL); card.setBackgroundColor(PANEL);
        previewName = padded("", 20, WHITE); card.addView(previewName);
        previewHint = padded("", 13, MUTED); card.addView(previewHint);
        previewColor = padded("", 15, MUTED); card.addView(previewColor);
        previewTone = padded("", 15, MUTED); card.addView(previewTone);
        if (details) card.addView(padded(t("● Details", "● 查看详情"), 17, WHITE));
        right.addView(card);
        updatePreviewCard(recipe);
    }
    private void updatePreviewCard(Recipe recipe) {
        if (previewHeading == null || recipe == null) return;
        setTextIfChanged(previewHeading, previewAccepted ? t("Live preview", "实时预览") : t("Current camera view", "当前取景"));
        setTextIfChanged(previewName, recipe.displayName(language));
        setTextIfChanged(previewHint, previewAccepted ? t("Previewing · Use keeps this look", "预览中 · 使用后保留此效果") : t("Select Use to apply this recipe", "选择“使用”应用此配方"));
        setTextIfChanged(previewColor, t("Color ", "色彩 ") + recipe.color + "%  " + t("Tone ", "影调 ") + recipe.tone + "%");
        setTextIfChanged(previewTone, t("Shadows ", "阴影 ") + signed(recipe.shadows) + "  " + t("Highlights ", "高光 ") + signed(recipe.highlights));
    }

    private void drawDetail() {
        if (selectedRecipe == null) { go("library"); return; }
        final Recipe recipe = selectedRecipe;
        add(t("Use recipe", "使用配方"), new Runnable() { public void run() { use(recipe); }});
        add(t("Duplicate", "复制配方"), new Runnable() { public void run() {
            draft = store.duplicate(recipe.id); draft.name = recipe.displayName(language) + t(" copy", " 副本");
            if (draft.name.codePointCount(0, draft.name.length()) > 32) draft.name = t("My recipe", "我的配方");
            startEditor();
        }});
        add(store.isFavorite(recipe.id) ? t("Remove favorite", "取消收藏") : t("Add to favorites", "加入收藏"), new Runnable() { public void run() {
            try { store.toggleFavorite(recipe.id); render(); } catch (IOException failure) { saveFailure(); }
        }});
        if (recipe.user) {
            add(t("Edit recipe", "编辑配方"), new Runnable() { public void run() { draft = recipe.copy(); startEditor(); }});
            add(t("Rename", "重命名"), new Runnable() { public void run() {
                renameOnly = true; keyboardReturn = "detail"; keyboard = new NameKeyboard(activity, recipe.name, "zh".equals(language)); go("keyboard");
            }});
            add(t("Delete", "删除配方"), new Runnable() { public void run() { confirm("delete", "detail"); }});
        }
        drawPreviewCard(recipe, false);
    }

    private void startEditor() {
        renameOnly = false; draftDirty = draft.id == null;
        preview(draft); go("editor");
    }
    private void drawEditor() {
        if (draft == null) { go("library"); return; }
        add(t("Recipe name", "配方名称"), draft.name, true, new Runnable() { public void run() {
            renameOnly = false; keyboardReturn = "editor";
            keyboard = new NameKeyboard(activity, draft.name, "zh".equals(language)); go("keyboard");
        }}, null);
        add(t("Base film", "基础胶片"), RecipeStore.baseName(draft.baseId, language), true, null, new Adjust() { public void change(int delta) {
            List<Recipe> bases = RecipeStore.builtins(); int selected = 0;
            for (int i = 0; i < bases.size(); i++) if (bases.get(i).baseId.equals(draft.baseId)) selected = i;
            selected = (selected + delta + bases.size()) % bases.size(); draft.baseId = bases.get(selected).baseId;
            if (draft.isMonochrome()) draft.color = 100; changed();
        }});
        number(t("Color", "色彩"), "color", draft.color, 0, 100, 10, !draft.isMonochrome(), "%");
        number(t("Tone", "影调"), "tone", draft.tone, 0, 100, 10, true, "%");
        number(t("Shadows", "阴影"), "shadows", draft.shadows, -3, 3, 1, true, "");
        number(t("Highlights", "高光"), "highlights", draft.highlights, -3, 3, 1, true, "");
        add(t("White balance", "白平衡"), "kelvin".equals(draft.wb) ? t("Color temperature", "色温") : t("Auto", "自动"), true, null, new Adjust() { public void change(int delta) {
            draft.wb = "auto".equals(draft.wb) ? "kelvin" : "auto"; changed();
        }});
        number(t("Temperature", "色温"), "kelvin", draft.kelvin, 2500, 10000, 100, "kelvin".equals(draft.wb), "K");
        number(t("A/B shift", "A/B 偏移"), "ab", draft.ab, -7, 7, 1, true, "");
        number(t("G/M shift", "G/M 偏移"), "gm", draft.gm, -7, 7, 1, true, "");
        add(t("Save", "保存"), new Runnable() { public void run() { saveDraft(false); }});
        add(t("Save & use", "保存并使用"), new Runnable() { public void run() { saveDraft(true); }});
        drawPreviewCard(draft, false);
        right.addView(padded(t("Save keeps the recipe without changing the shooting look.", "保存配方不会改变当前拍摄效果。"), 13, MUTED));
    }
    private void number(String label, final String field, int value, final int min, final int max, final int step, boolean enabled, String suffix) {
        add(label, numberText(field, value, min, enabled, suffix), enabled, null, new Adjust() { public void change(int delta) {
            int current = fieldValue(field); int next = Math.max(min, Math.min(max, current + delta * step));
            if (next != current) { setField(field, next); changed(); }
        }});
    }
    private static String numberText(String field, int value, int min, boolean enabled, String suffix) {
        String display = (min < 0 ? signed(value) : String.valueOf(value)) + suffix;
        if ("ab".equals(field)) display = value > 0 ? "A" + value : value < 0 ? "B" + (-value) : "0";
        if ("gm".equals(field)) display = value > 0 ? "M" + value : value < 0 ? "G" + (-value) : "0";
        return (enabled ? "← " : "") + display + (enabled ? " →" : "");
    }
    private int fieldValue(String field) {
        if ("color".equals(field)) return draft.color; if ("tone".equals(field)) return draft.tone;
        if ("shadows".equals(field)) return draft.shadows; if ("highlights".equals(field)) return draft.highlights;
        if ("kelvin".equals(field)) return draft.kelvin; if ("ab".equals(field)) return draft.ab; return draft.gm;
    }
    private void setField(String field, int value) {
        if ("color".equals(field)) draft.color = value; else if ("tone".equals(field)) draft.tone = value;
        else if ("shadows".equals(field)) draft.shadows = value; else if ("highlights".equals(field)) draft.highlights = value;
        else if ("kelvin".equals(field)) draft.kelvin = value; else if ("ab".equals(field)) draft.ab = value; else draft.gm = value;
    }
    private void changed() {
        draftDirty = true; notice = ""; preview(draft);
        updateNotice(); updateEditor(); updatePreviewCard(draft);
    }
    private void updateEditor() {
        if (!"editor".equals(screen) || draft == null || rows.size() < 12) return;
        rows.get(0).value = draft.name;
        rows.get(1).value = RecipeStore.baseName(draft.baseId, language);
        String[] fields = { "color", "tone", "shadows", "highlights", "kelvin", "ab", "gm" };
        int[] positions = { 2, 3, 4, 5, 7, 8, 9 };
        for (int i = 0; i < fields.length; i++) {
            Row row = rows.get(positions[i]);
            row.enabled = "color".equals(fields[i]) ? !draft.isMonochrome() : !"kelvin".equals(fields[i]) || "kelvin".equals(draft.wb);
            row.value = numberText(fields[i], fieldValue(fields[i]), i == 0 || i == 1 || i == 4 ? 0 : -1,
                    row.enabled, i == 0 || i == 1 ? "%" : i == 4 ? "K" : "");
        }
        rows.get(6).value = "kelvin".equals(draft.wb) ? t("Color temperature", "色温") : t("Auto", "自动");
        for (int i = 0; i < 10; i++) updateRow(rows.get(i), i);
    }
    private static String signed(int value) { return value > 0 ? "+" + value : String.valueOf(value); }

    private void saveDraft(boolean useAfter) {
        try {
            Recipe saved = store.save(draft);
            selectedRecipe = saved; draft = null; draftDirty = false;
            if (useAfter) { use(saved); if (isOpen()) { screen = "detail"; index = 0; notice = t("Recipe saved. ", "配方已保存。") + notice; render(); } }
            else {
                boolean restored = cancelPreview(); tab = "user";
                notice = restored ? t("Recipe saved", "配方已保存") : t("Recipe saved. ", "配方已保存。") + notice;
                screen = "detail"; index = 0; render();
            }
        } catch (IOException failure) { saveFailure(); }
        catch (IllegalArgumentException invalid) { notice = t("Check the name and recipe settings.", "请检查名称与配方设置。"); render(); }
    }
    private void use(Recipe recipe) {
        stopPendingPreview();
        String error = ShootingBridge.apply(activity, recipe);
        if (error != null) { notice = t("Could not apply this recipe: ", "无法应用此配方：") + error; render(); return; }
        try { store.activate(recipe); }
        catch (IOException failure) {
            String rollbackError = ShootingBridge.rollbackLastApply();
            previewAccepted = false;
            notice = rollbackError == null ? t("Could not save the selection. The previous look was restored; retry.", "无法保存选择。已恢复先前效果，请重试。")
                    : t("Could not save or restore the look: ", "无法保存或恢复效果：") + rollbackError;
            render(); return;
        }
        close();
    }
    private void preview(Recipe recipe) {
        stopPendingPreview();
        previewAccepted = false;
        final int generation = previewGeneration;
        final Recipe candidate = recipe.copy();
        final long queued = SystemClock.elapsedRealtime();
        pendingPreview = new Runnable() { public void run() {
            if (closed || current != FilmLabUi.this || generation != previewGeneration || !isOpen()) return;
            pendingPreview = null;
            DiagnosticsBridge.timing("preview_queue", SystemClock.elapsedRealtime() - queued);
            String error = ShootingBridge.preview(activity, candidate);
            if (generation != previewGeneration) return;
            previewAccepted = error == null;
            if (error != null) notice = t("Preview unavailable: ", "预览不可用：") + error;
            updateNotice();
            updatePreviewCard("editor".equals(screen) ? draft : selectedRecipe);
        }};
        handler.postDelayed(pendingPreview, 120);
    }
    private void stopPendingPreview() {
        previewGeneration++;
        if (pendingPreview != null) handler.removeCallbacks(pendingPreview);
        pendingPreview = null;
    }
    private boolean cancelPreview() {
        stopPendingPreview();
        String error = ShootingBridge.cancelPreview(); previewAccepted = false;
        if (error != null) { notice = t("Could not restore the previous look: ", "无法恢复先前效果：") + error; return false; }
        return true;
    }
    private void saveFailure() { notice = t("Could not save. Your changes are still here; retry.", "无法保存。更改仍然保留，请重试。"); render(); }

    private void drawSettings() {
        add(t("Film presets", "胶片预设"), new Runnable() { public void run() { enterLibrary(); render(); }});
        final Recipe active = store == null ? null : store.getActive();
        if (active != null && active.id == null) add(t("Save current look", "保存当前效果"), new Runnable() { public void run() {
            draft = active.copy(); draft.user = true; draft.id = null; draft.revision = 1;
            draft.name = t("My recipe", "我的配方"); startEditor();
        }});
        add(t("Camera settings", "相机设置"), new Runnable() { public void run() { go("camera"); }});
        add(t("Flash", "闪光灯"), new Runnable() { public void run() { go("flash"); }});
        add(t("Language", "语言"), "zh".equals(language) ? "简体中文" : "English", true, new Runnable() { public void run() { go("language"); }}, null);
        add(t("Save issue report", "保存问题报告"), new Runnable() { public void run() { go("report"); }});
        add(t("About", "关于"), new Runnable() { public void run() { go("about"); }});
        add(t("Developer tools", "开发者工具"), new Runnable() { public void run() { go("developer"); }});
        add(t("Return to shooting", "返回拍摄"), new Runnable() { public void run() { leave(); }});
        add(t("Exit app", "退出应用"), new Runnable() { public void run() { requestExit(); }});
    }
    private void drawLanguage() {
        add("English", "en".equals(language) ? "✓" : "", true, new Runnable() { public void run() { language("en"); }}, null);
        add("简体中文", "zh".equals(language) ? "✓" : "", true, new Runnable() { public void run() { language("zh"); }}, null);
    }
    private void language(String value) {
        language = value;
        if (!preferences.edit().putString("language", value).commit()) notice = t("Language changed for this session; unable to save.", "已更改本次会话语言，但无法保存。");
        AppLanguage.apply(activity, language);
        render();
    }
    private void drawCamera() {
        nativeRow(t("Exposure mode", "曝光模式"), "ExposureMode");
        nativeRow(t("ISO", "ISO"), "Iso");
        nativeRow(t("Exposure compensation", "曝光补偿"), "ExposureCompensation");
        nativeRow(t("White balance", "白平衡"), "WhiteBalance");
        nativeRow(t("Focus mode", "对焦模式"), "FocusSelection");
        nativeRow(t("Focus area", "对焦区域"), "FocusArea");
        nativeRow(t("Image quality", "影像质量"), "setPictureStorageFormat");
        nativeRow(t("Image size", "影像尺寸"), "Still_ImageSize");
        nativeRow(t("Aspect ratio", "宽高比"), "Still_AspectRatio");
        nativeRow(t("Drive mode", "拍摄模式"), "drivemode");
    }
    private void nativeRow(String label, final String id) {
        add(label, new Runnable() { public void run() { nativeMenu(id); }});
    }
    private void drawFlash() {
        add(t("Flash mode", "闪光灯模式"), new Runnable() { public void run() { nativeMenu("FlashMode"); }});
        add(t("Flash compensation", "闪光补偿"), new Runnable() { public void run() { nativeMenu("FlashCompensation"); }});
        note(t("Available options follow the attached flash and shooting mode.", "可用选项取决于外接闪光灯与拍摄模式。"));
    }
    private void nativeMenu(String id) {
        if (!cancelPreview()) { render(); return; }
        String error = ShootingBridge.openNativeMenu(id);
        if (error == null) close(); else { notice = t("This setting is unavailable: ", "此设置不可用：") + error; render(); }
    }

    private void drawAbout() {
        note("Film Lab " + BuildInfo.VERSION);
        note(t("Film recipes for Sony cameras", "为 Sony 相机创作胶片配方"));
        note("github.com/yangzhloop/sony-pmca-film-lab");
        note(t("Film Lab code: MIT. Film Studio additions: PolyForm Noncommercial. Ricoh portions: Apache-2.0. Font and input dictionary notices are included with the app. Other inherited materials retain their own rights.", "Film Lab 代码采用 MIT 许可；Film Studio 新增内容采用 PolyForm 非商业许可；Ricoh 部分采用 Apache-2.0 许可。字体及输入字典声明随应用附带。其他继承材料保留各自权利。"));
        note(t("Personal / noncommercial use", "仅限个人／非商业使用"));
        note(t("Build ", "构建 ") + BuildInfo.BUILD_ID);
    }
    private void drawDeveloper() {
        add(t("Device and capabilities", "设备与能力"), new Runnable() { public void run() { go("device"); }});
        add(t("Processing status", "图像处理状态"), new Runnable() { public void run() { go("processing"); }});
        add(t("Language display check", "语言显示检查"), new Runnable() { public void run() { go("glyphs"); }});
        add(t("Key viewer", "按键查看器"), new Runnable() { public void run() { keyLines.clear(); go("keys"); }});
        add(t("Recent events", "最近事件"), new Runnable() { public void run() { go("events"); }});
    }
    private void drawFacts() {
        Map<String, String> facts = DiagnosticsBridge.facts(screen, language);
        String[] fields = "device".equals(screen) ? new String[] { "model", "firmware", "androidApi", "platformApi", "rgbSupport", "gammaSupport", "storageStatus" }
                : new String[] { "requestedId", "requestedStrength", "applyStatus", "readbackStatus", "readbackSource", "readbackAgeMs", "activeId", "requestedMatrix", "observedMatrix", "matrixMatchesRequest", "captureStatus", "savedImageVerification", "flashMode" };
        for (int i = 0; i < fields.length; i++) { String value = facts.get(fields[i]); note(fields[i] + ": " + (value == null ? "unknown" : value)); }
        note(t("Font: ", "字体：") + FilmFonts.status());
        Map<String, String> previous = DiagnosticsBridge.lastCameraState();
        note(t("Last saved camera state — historical checkpoint", "上次保存的相机状态——历史快照"));
        note(t("These values are not a fresh camera readback. Age across app restarts is unknown.", "这些数值并非实时相机读回结果。跨应用重启的经过时间未知。"));
        String[] history = "device".equals(screen)
                ? new String[] { "lastStateStatus", "lastStateSession", "lastStateAgeMs", "model", "firmware", "cameraTime", "cameraTimeSource" }
                : new String[] { "lastStateStatus", "lastStateSession", "lastStateAgeMs", "activeId", "requestedId", "applyStatus", "readbackStatus", "readbackSource", "readbackAgeAtCheckpointMs", "observedMatrix", "captureStatus", "savedImageVerification" };
        for (int i = 0; i < history.length; i++) { String value = previous.get(history[i]); note(history[i] + ": " + (value == null ? "unknown" : value)); }
    }
    private void drawKeys() {
        note(t("Numeric key and scan codes only. MENU exits.", "仅显示按键码与扫描码。MENU 退出。"));
        keysView = padded(keyText(), 16, WHITE); list.addView(keysView);
    }
    private String keyText() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < keyLines.size(); i++) value.append(keyLines.get(i)).append('\n');
        return value.length() == 0 ? t("Waiting for a key…", "等待按键……") : value.toString();
    }
    private void observe(KeyEvent event) {
        keyLines.add(0, "key=" + event.getKeyCode() + " scan=" + event.getScanCode() + " action=" + event.getAction() + " repeat=" + event.getRepeatCount());
        while (keyLines.size() > 10) keyLines.remove(keyLines.size() - 1);
        if (keysView != null) keysView.setText(keyText());
        DiagnosticsBridge.record("key.event", "key_code=" + event.getKeyCode() + ";scan_code=" + event.getScanCode() + ";repeat_count=" + event.getRepeatCount());
    }

    private void newReport() {
        synchronized (REPORT_LOCK) { if (REPORT.busy) return; }
        try {
            String snapshot = DiagnosticsBridge.report(screen, language);
            synchronized (REPORT_LOCK) {
                REPORT.snapshot = snapshot; REPORT.result = ""; REPORT.error = ""; REPORT.generation++;
            }
        } catch (RuntimeException failure) {
            synchronized (REPORT_LOCK) { REPORT.error = "SNAPSHOT_FAILED"; }
        }
    }
    private static boolean hasReportSnapshot() { synchronized (REPORT_LOCK) { return REPORT.snapshot != null; } }
    private void drawReport() {
        final boolean busy, exists;
        final String result, error;
        synchronized (REPORT_LOCK) {
            busy = REPORT.busy; exists = REPORT.snapshot != null; result = REPORT.result; error = REPORT.error;
        }
        note(t("Save a TXT containing app status and recent events. Photos, recipe names and device serial numbers are excluded.", "保存包含应用状态与最近事件的 TXT 文件。不包含照片、配方名称或设备序列号。"));
        if (busy) note(t("Saving… You may leave this page.", "正在保存……您可以离开此页。"));
        else if (error.length() > 0) {
            note(t("Not saved: ", "未保存：") + error);
            if (exists) note(t("The same snapshot is retained for retry.", "已保留同一快照，可重试保存。"));
        } else if (result.length() > 0) note(t("Saved and read back: FILMLAB/", "已保存并读回校验：FILMLAB/") + result);
        add(error.length() > 0 && exists ? t("Retry saving", "重试保存") : t("Save report", "保存报告"), "", !busy, new Runnable() { public void run() { exportReport(); }}, null);
        add(t("New report snapshot", "创建新报告快照"), "", !busy, new Runnable() { public void run() { newReport(); render(); }}, null);
        note(t("Find the file in FILMLAB on the camera storage and attach it to a GitHub issue.", "在相机存储的 FILMLAB 文件夹中找到报告，并自行附加到 GitHub 问题反馈。"));
    }
    private void exportReport() {
        synchronized (REPORT_LOCK) { if (REPORT.busy) return; }
        if (!hasReportSnapshot()) newReport();
        if (!hasReportSnapshot()) { render(); return; }
        final String snapshot;
        final int generation;
        synchronized (REPORT_LOCK) {
            if (REPORT.busy || REPORT.snapshot == null) return;
            snapshot = REPORT.snapshot; generation = REPORT.generation;
            REPORT.busy = true; REPORT.error = ""; REPORT.result = "";
        }
        render();
        try { new Thread(new ReportExport(snapshot, generation), "FilmLabReportExport").start(); }
        catch (RuntimeException failure) { finishExport(generation, "", "EXPORT_WORKER_UNAVAILABLE"); }
    }
    /** Static worker has no implicit reference to a destroyed UI or Activity. */
    private static final class ReportExport implements Runnable {
        private final String snapshot;
        private final int generation;
        ReportExport(String snapshot, int generation) { this.snapshot = snapshot; this.generation = generation; }
        public void run() {
            String file = "", error = "";
            try { file = DiagnosticsBridge.export(snapshot).getName(); }
            catch (ReportStore.ExportException failure) { error = failure.getCode(); }
            catch (IOException failure) { error = "EXPORT_IO_FAILED"; }
            catch (SecurityException failure) { error = "EXPORT_ACCESS_DENIED"; }
            catch (RuntimeException failure) { error = "EXPORT_FAILED"; }
            finishExport(generation, file, error);
        }
    }
    private static void finishExport(int generation, String file, String error) {
        synchronized (REPORT_LOCK) {
            if (generation != REPORT.generation) return;
            REPORT.busy = false; REPORT.result = file; REPORT.error = error;
            // A failed export retries the exact snapshot; the next successful Save is fresh.
            if (error.length() == 0) REPORT.snapshot = null;
        }
        REPORT_HANDLER.post(new RefreshReport());
    }
    /** Resolve the newest instance only after returning to the main thread. */
    private static final class RefreshReport implements Runnable {
        public void run() {
            FilmLabUi ui = current;
            if (ui != null && !ui.closed && !ui.activity.isFinishing() && isOpen() && "report".equals(ui.screen)) ui.render();
        }
    }

    private void drawKeyboard() {
        LinearLayout area = new LinearLayout(activity); area.setOrientation(LinearLayout.VERTICAL); area.setBackgroundColor(PANEL);
        page.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        keyboardValue = padded("", 23, WHITE); keyboardValue.setMaxLines(2); area.addView(keyboardValue);
        keyboardMode = padded("", 15, AMBER); area.addView(keyboardMode);
        keyboardError = padded("", 14, AMBER); area.addView(keyboardError);
        keyboardGrid = new LinearLayout(activity); keyboardGrid.setOrientation(LinearLayout.VERTICAL);
        area.addView(keyboardGrid, new LinearLayout.LayoutParams(-1, 0, 1));
        updateKeyboard();
    }
    private void updateKeyboard() {
        if (keyboard == null || keyboardGrid == null) return;
        keyboardValue.setText(keyboard.value() + "▏");
        String mode = keyboard.isPinyin() ? t("Pinyin: ", "拼音：") + keyboard.syllable() : t("English / numbers", "英文／数字");
        if (keyboard.isCandidateMode()) mode += "  " + (keyboard.candidatePage() + 1) + "/" + keyboard.pageCount();
        keyboardMode.setText(mode);
        keyboardError.setText(keyboard.error()); keyboardError.setVisibility(keyboard.error().length() > 0 ? View.VISIBLE : View.GONE);
        List<String> cells = keyboard.cells();
        int rowCount = (cells.size() + NameKeyboard.COLUMNS - 1) / NameKeyboard.COLUMNS;
        if (keyboardCells.size() != rowCount * NameKeyboard.COLUMNS) {
            keyboardGrid.removeAllViews(); keyboardCells.clear();
            for (int r = 0; r < rowCount; r++) {
                LinearLayout row = new LinearLayout(activity);
                keyboardGrid.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
                for (int c = 0; c < NameKeyboard.COLUMNS; c++) {
                    TextView cell = text("", 18, WHITE, false); cell.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); lp.setMargins(px(2), px(2), px(2), px(2)); row.addView(cell, lp);
                    keyboardCells.add(cell);
                }
            }
        }
        for (int at = 0; at < keyboardCells.size(); at++) {
            TextView cell = keyboardCells.get(at);
            cell.setText(at < cells.size() ? cells.get(at) : "");
            cell.setBackgroundColor(at == keyboard.selected() ? FOCUS : ROW);
        }
    }
    private void moveKeyboard(int horizontal, int vertical) {
        int previous = keyboard.selected(); keyboard.move(horizontal, vertical);
        if (previous == keyboard.selected()) return;
        keyboardCells.get(previous).setBackgroundColor(ROW);
        keyboardCells.get(keyboard.selected()).setBackgroundColor(FOCUS);
    }
    private void keyboardChoose() {
        int result = keyboard.choose();
        if (result == 1) {
            String name = keyboard.value().trim();
            if (renameOnly) {
                try { selectedRecipe = store.rename(selectedRecipe.id, name); ShootingBridge.syncActiveMetadata(store.getActive()); }
                catch (IOException failure) { saveFailure(); return; }
                catch (IllegalArgumentException invalid) { notice = t("Invalid recipe name", "配方名称无效"); render(); return; }
            } else { draft.name = name; draftDirty = true; }
            keyboard = null; go(keyboardReturn);
        } else if (result == 2) { keyboard = null; go(keyboardReturn); }
        else updateKeyboard();
    }

    private void confirm(String action, String returnTo) {
        confirmation = action; confirmationReturn = returnTo; go("confirm");
    }
    private void drawConfirm() {
        boolean deleting = "delete".equals(confirmation);
        note(deleting ? t("Delete this user recipe? This cannot be undone.", "删除此用户配方？此操作无法撤销。")
                : t("Discard your unsaved changes?", "放弃尚未保存的更改？"));
        add(t("Cancel", "取消"), new Runnable() { public void run() { go(confirmationReturn); }});
        add(deleting ? t("Delete recipe", "删除配方") : t("Discard changes", "放弃更改"), new Runnable() { public void run() {
            if ("delete".equals(confirmation)) {
                try {
                    if (!cancelPreview()) { render(); return; }
                    store.delete(selectedRecipe.id); ShootingBridge.syncActiveMetadata(store.getActive()); selectedRecipe = null;
                    screen = "library"; tab = "user"; libraryIndex = 0; selectLibrary(); render();
                } catch (IOException failure) { saveFailure(); }
            } else {
                boolean shoot = "discard-shoot".equals(confirmation);
                boolean exiting = "discard-exit".equals(confirmation);
                if (exiting && reportBusy()) { exitBusy(); return; }
                if (!cancelPreview()) { render(); return; }
                draft = null; draftDirty = false; keyboard = null;
                if (exiting) beginExit();
                else if (shoot) close(); else { screen = "library"; selectLibrary(); render(); }
            }
        }});
    }

    private boolean key(KeyEvent event) {
        if (exitPending) return true;
        int nav = navigation(event);
        if ("keys".equals(screen)) {
            observe(event);
            if (nav == 6 && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) go("developer");
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (event.getScanCode() == 516 || event.getScanCode() == 518 || event.getKeyCode() == KeyEvent.KEYCODE_CAMERA || event.getKeyCode() == KeyEvent.KEYCODE_FOCUS) {
            if (event.getRepeatCount() == 0) leave(); return true;
        }
        if ("keyboard".equals(screen)) {
            if (nav == 1) moveKeyboard(0, -1); else if (nav == 2) moveKeyboard(0, 1);
            else if (nav == 3) moveKeyboard(-1, 0); else if (nav == 4) moveKeyboard(1, 0);
            else if (nav == 5 && event.getRepeatCount() == 0) { keyboardChoose(); return true; }
            else if (nav == 6 && event.getRepeatCount() == 0) { keyboard = null; go(keyboardReturn); return true; }
            return true;
        }
        if (nav == 1 || nav == 2) {
            int delta = nav == 1 ? -1 : 1;
            if (rows.isEmpty()) { if (scroll != null) scroll.smoothScrollBy(0, px(80) * delta); return true; }
            int next = (index + delta + rows.size()) % rows.size();
            if (next != index) {
                int previous = index;
                index = next;
                if ("library".equals(screen)) {
                    libraryIndex = index;
                    Recipe recipe = rows.get(index).recipe;
                    if (recipe != null) { selectedRecipe = recipe; notice = ""; preview(selectedRecipe); }
                    else stopPendingPreview();
                    updateNotice(); updatePreviewCard(selectedRecipe);
                }
                updateFocus(previous);
            }
        } else if (nav == 3 || nav == 4) {
            if ("library".equals(screen)) {
                String previousTab = tab; int previousLibraryIndex = libraryIndex;
                int at = 0;
                for (int i = 0; i < LIBRARY_TABS.length; i++) if (LIBRARY_TABS[i].equals(tab)) at = i;
                tab = LIBRARY_TABS[(at + (nav == 3 ? LIBRARY_TABS.length - 1 : 1)) % LIBRARY_TABS.length];
                libraryIndex = 0; notice = "";
                if (!selectLibrary()) {
                    tab = previousTab; libraryIndex = previousLibraryIndex; updateNotice(); return true;
                }
                render();
            } else if ("editor".equals(screen) && index < rows.size() && rows.get(index).adjust != null) {
                Row row = rows.get(index); if (row.enabled) row.adjust.change(nav == 3 ? -1 : 1);
            } else if (nav == 3 && event.getRepeatCount() == 0) back();
            else if (nav == 4 && event.getRepeatCount() == 0 && isSubmenuScreen()) activateRow();
        } else if (nav == 5 && event.getRepeatCount() == 0) activateRow();
        else if (nav == 6 && event.getRepeatCount() == 0) back();
        return true;
    }
    private boolean isSubmenuScreen() { return "settings".equals(screen) || "developer".equals(screen); }
    private void activateRow() {
        if (index >= 0 && index < rows.size()) { Row row = rows.get(index); if (row.enabled && row.action != null) row.action.run(); }
    }
    private void back() {
        if ("library".equals(screen) || "settings".equals(screen)) { leave(); return; }
        if ("editor".equals(screen)) {
            if (draftDirty) confirm("discard", "editor");
            else { draft = null; screen = "library"; selectLibrary(); render(); }
        } else if ("detail".equals(screen)) {
            if (restoreLibrarySelection()) screen = "library";
            render();
        }
        else if ("confirm".equals(screen)) go(confirmationReturn);
        else if ("device".equals(screen) || "processing".equals(screen) || "events".equals(screen) || "keys".equals(screen) || "glyphs".equals(screen)) go("developer");
        else go("settings");
    }
    private boolean restoreLibrarySelection() {
        List<Recipe> recipes = visible();
        for (int i = 0; i < recipes.size(); i++) if (selectedRecipe != null && recipes.get(i).id.equals(selectedRecipe.id)) { libraryIndex = i; break; }
        return selectLibrary();
    }
    private void leave() {
        boolean unsaved = (draft != null && draftDirty) || (keyboard != null && keyboard.hasChanges());
        if (unsaved) {
            if ("confirm".equals(screen)) { confirmation = "discard-shoot"; render(); }
            else confirm("discard-shoot", screen);
            return;
        }
        if (cancelPreview()) close(); else render();
    }
    private static boolean reportBusy() { synchronized (REPORT_LOCK) { return REPORT.busy; } }
    private void exitBusy() {
        notice = t("A report is still saving. Please try Exit app when it finishes.", "报告仍在保存，请完成后再选择退出应用。");
        updateNotice();
    }
    private void requestExit() {
        if (exitPending) return;
        if (reportBusy()) { exitBusy(); return; }
        if ((draft != null && draftDirty) || (keyboard != null && keyboard.hasChanges())) {
            confirm("discard-exit", "confirm".equals(screen) ? confirmationReturn : screen); return;
        }
        if (!cancelPreview()) { updateNotice(); return; }
        beginExit();
    }
    private void beginExit() {
        exitPending = true;
        notice = t("Closing Film Lab…", "正在退出 Film Lab……"); updateNotice();
        DiagnosticsBridge.flushAsync();
        final FilmLabUi owner = this;
        try { new Thread(new Runnable() { public void run() {
            try { DiagnosticsBridge.awaitFlush(1500); }
            catch (RuntimeException unavailable) { /* Exit remains available if persistence failed. */ }
            finally { REPORT_HANDLER.post(new Runnable() { public void run() { owner.finishExit(); }}); }
        }}, "FilmLabExit").start(); }
        catch (RuntimeException failure) { finishExit(); }
    }
    private void finishExit() {
        if (closed || current != this || !exitPending) return;
        exitPending = false;
        close();
        // Sony BaseApp.finish() supplies its normal confirmation and connection cleanup.
        // If the user cancels that confirmation, native Fn/MENU can reopen this overlay.
        activity.finish();
    }
    private static int navigation(KeyEvent event) { return CameraKeys.navigation(event); }

    /** Drawn vectors remain crisp on the camera LCD and do not depend on symbol fonts. */
    private final class FilmIcon extends View {
        private final String code; private final int color; private final Paint paint = new Paint(3);
        FilmIcon(String code, int color) { super(activity); this.code = code; this.color = color; }
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.save();
            float unit = Math.min(getWidth() / 48f, getHeight() / 48f); canvas.scale(unit, unit);
            paint.setColor(color); paint.setStrokeWidth(1.5f); paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(10, 9, 28, 39, paint); canvas.drawRect(8, 6, 30, 10, paint); canvas.drawRect(8, 38, 30, 41, paint);
            canvas.drawRect(29, 15, 43, 33, paint);
            for (int x = 32; x < 43; x += 4) { canvas.drawLine(x, 17, x, 20, paint); canvas.drawLine(x, 28, x, 31, paint); }
            if (code != null) { paint.setStyle(Paint.Style.FILL); paint.setTextSize(10); paint.setTextAlign(Paint.Align.CENTER); canvas.drawText(code, 19, 28, paint); }
            canvas.restore();
        }
    }
}

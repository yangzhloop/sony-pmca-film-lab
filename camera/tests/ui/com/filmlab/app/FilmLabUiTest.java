package com.filmlab.app;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.filmlab.diagnostics.DiagnosticsBridge;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import testsupport.Probe;

/** Drives real key dispatch and async callbacks; reflection only inspects state or unavailable host entry points. */
public final class FilmLabUiTest {
    private static int assertions;
    private static Activity activity;
    private static Object ui;

    public static void main(String[] arguments) throws Exception {
        Context.assetDirectory = new File(arguments[0]);
        favoritesAndCompactPreview();
        wrapMenusAndPreview();
        nativeFocusRoutes();
        emptyFavoritesRestoreGuard();
        navigationAndLatestPreview();
        cancellationAndUse();
        editorAndKeyboard();
        pinyinGridChanges();
        reportsFreshRetryAndRecreation();
        exitGuardsAndOrdering();
        dirtyNameExitAndCancel();
        staleExitCannotFinishReplacement();
        detachedPreviewAndFlushTimeout();
        canceledNativeExitCanReopen();
        cleanup();
        System.out.println("FilmLabUiTest: " + assertions + " assertions passed");
    }

    private static void setup(String destination) throws Exception {
        cleanup();
        Handler.reset(); ShootingBridge.reset(); DiagnosticsBridge.reset(); Probe.events.clear();
        Object report = get(null, FilmLabUi.class, "REPORT");
        put(report, "snapshot", null); put(report, "busy", Boolean.FALSE);
        put(report, "result", ""); put(report, "error", ""); put(report, "generation", Integer.valueOf(0));
        activity = new Activity();
        FilmLabUi.attach(activity); ui = get(null, FilmLabUi.class, "current");
        FilmLabUi.open(destination); Handler.drain();
    }

    private static void setupBuiltinLibrary() throws Exception {
        setup("library");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); Handler.drain();
        // Transaction assertions below count actions after opening the requested collection.
        ShootingBridge.cancels = 0;
    }

    private static void favoritesAndCompactPreview() throws Exception {
        setup("library");
        check("favorites".equals(get(ui, "tab")), "normal library entry starts Favorites");
        check(rows().isEmpty() && get(ui, "selectedRecipe") == null, "empty Favorites has no phantom selection");
        key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
        Handler.advance(120);
        check(index() == 0 && "library".equals(get(ui, "screen")) && ShootingBridge.previews.isEmpty(),
                "empty list arrows and Center cause no recipe application or index error");
        ViewGroup page = (ViewGroup) get(ui, "page");
        ViewGroup tabs = (ViewGroup) page.getChildAt(2);
        check("Favorites".equals(((TextView) tabs.getChildAt(0)).getText())
                && "Built-in".equals(((TextView) tabs.getChildAt(1)).getText())
                && "My recipes".equals(((TextView) tabs.getChildAt(2)).getText()), "visible tabs follow requested order");
        ScrollView scroll = (ScrollView) get(ui, "scroll");
        ViewGroup body = scroll.getParent();
        LinearLayout.LayoutParams column = (LinearLayout.LayoutParams) scroll.getLayoutParams();
        LinearLayout.LayoutParams free = (LinearLayout.LayoutParams) body.getChildAt(1).getLayoutParams();
        float totalTabs = 0;
        for (int i = 0; i < tabs.getChildCount(); i++) totalTabs += ((LinearLayout.LayoutParams) tabs.getChildAt(i).getLayoutParams()).weight;
        float tabWidth = ((LinearLayout.LayoutParams) tabs.getChildAt(0).getLayoutParams()).weight / totalTabs;
        check(column.width == 0 && free.width == 0 && column.weight / (column.weight + free.weight) == tabWidth,
                "preset list width matches one of the three equal tabs");
        check(body.getChildAt(0) == scroll && body.getChildCount() == 2 && !(body.getChildAt(1) instanceof ViewGroup),
                "list stays left with an empty preview area on the right");
        check((page.backgroundColor >>> 24) == 0 && (body.backgroundColor >>> 24) == 0
                && (body.getChildAt(1).backgroundColor >>> 24) == 0,
                "library page and space beside the list have no dark scrim");
        check(get(ui, "right") == null && get(ui, "previewName") == null && get(ui, "previewHeading") == null,
                "library has no inline details or preview heading overlay");
        check(((View) get(ui, "list")).backgroundColor != 0, "menu column retains readable contrast");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        check("builtin".equals(get(ui, "tab")) && rows().size() == RecipeStore.builtins().size(), "Right reaches Built-in");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        check("user".equals(get(ui, "tab")) && rows().size() == 1, "Right reaches empty My recipes with Create");
        int before = View.creations, previews = ShootingBridge.previews.size();
        key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_DOWN); Handler.advance(120);
        check(index() == 0 && View.creations == before && ShootingBridge.previews.size() == previews,
                "single-row Create list wraps without rebuilding or requesting preview");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        check("favorites".equals(get(ui, "tab")), "category cycling returns to Favorites");
        Recipe draft = FilmLabUi.getStore().createDraft("pop-color"); draft.name = "Evening lights 雨后东京胶片";
        Recipe saved = FilmLabUi.getStore().save(draft); FilmLabUi.getStore().setFavorite(saved.id, true);
        FilmLabUi.open("library"); Handler.advance(120);
        TextView name = (TextView) get(rows().get(0), "nameView");
        check(name.maxLines == 1 && name.ellipsize == android.text.TextUtils.TruncateAt.END
                && saved.name.equals(name.getText().toString()), "long mixed-language names use display ellipsis without changing saved text");
        before = View.creations; previews = ShootingBridge.previews.size();
        key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_DOWN); Handler.advance(120);
        check(index() == 0 && View.creations == before && ShootingBridge.previews.size() == previews,
                "single favorite arrows do not reapply the same look");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        check("detail".equals(get(ui, "screen")) && saved.name.equals(((TextView) get(ui, "previewName")).getText().toString()),
                "Center retains full recipe name and detail/actions page");
        check((((View) get(ui, "page")).backgroundColor >>> 24) != 0, "detail screen keeps its own readable background");
        key(KeyEvent.KEYCODE_MENU);
        check("favorites".equals(get(ui, "tab")) && ((View) get(ui, "page")).backgroundColor == 0,
                "return from details restores compact transparent Favorites");
        FilmLabUi.open("settings"); choose("Language"); choose("简体中文");
        FilmLabUi.open("library");
        page = (ViewGroup) get(ui, "page"); tabs = (ViewGroup) page.getChildAt(2);
        check("收藏".equals(((TextView) tabs.getChildAt(0)).getText())
                && "内置预设".equals(((TextView) tabs.getChildAt(1)).getText())
                && "我的配方".equals(((TextView) tabs.getChildAt(2)).getText()), "Chinese tabs use the same Favorites-first order");
    }

    private static void wrapMenusAndPreview() throws Exception {
        setupBuiltinLibrary();
        Object page = get(ui, "page"); int before = View.creations;
        int last = rows().size() - 1;
        key(KeyEvent.KEYCODE_DPAD_UP);
        check(index() == last, "Up at first preset wraps to last");
        ScrollView scroll = (ScrollView) get(ui, "scroll");
        View selected = (View) get(rows().get(last), "view");
        check(scroll.getScrollY() > 0 && selected.getTop() + selected.getHeight() <= scroll.getScrollY() + scroll.getHeight(),
                "wrapped last preset is scrolled into view");
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        check(index() == 0 && scroll.getScrollY() == 0, "Down at last preset wraps and scrolls to first");
        FilmLabUi.dispatch(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 3));
        Handler.advance(120);
        check(index() == last && ShootingBridge.previews.size() == 1
                && ShootingBridge.previews.get(0).id.equals(RecipeStore.builtins().get(last).id),
                "held arrow wraps and applies only the latest queued selection");
        check(page == get(ui, "page") && before == View.creations, "wrap reuses rows and page views");
        key(KeyEvent.KEYCODE_DPAD_CENTER); assertWrap("detail");
        choose("Duplicate"); assertWrap("editor");
        focus("Color");
        for (int i = 0; i < 12; i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        check(((Recipe) get(ui, "draft")).color == 100, "editor numeric upper limit does not wrap");
        setup("settings"); assertWrap("settings");
        choose("Camera settings"); assertWrap("camera");
        key(KeyEvent.KEYCODE_MENU); choose("Flash"); assertWrap("flash");
        key(KeyEvent.KEYCODE_MENU); choose("Language"); assertWrap("language");
        choose("简体中文"); assertWrap("language Chinese");
        key(KeyEvent.KEYCODE_MENU); assertWrap("settings Chinese");
        choose("开发者工具"); assertWrap("developer Chinese");
        key(KeyEvent.KEYCODE_MENU); choose("保存问题报告"); assertWrap("report Chinese");
        setupBuiltinLibrary(); key(KeyEvent.KEYCODE_DPAD_CENTER); choose("Duplicate");
        focus("Tone"); key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_MENU);
        check("confirm".equals(get(ui, "screen")), "dirty editor opens confirmation"); assertWrap("confirmation");
    }

    private static void assertWrap(String context) throws Exception {
        int size = rows().size(); check(size > 1, context + " has a selectable list");
        while (index() != 0) key(KeyEvent.KEYCODE_DPAD_UP);
        key(KeyEvent.KEYCODE_DPAD_UP); check(index() == size - 1, context + " wraps first Up to last");
        key(KeyEvent.KEYCODE_DPAD_DOWN); check(index() == 0, context + " wraps last Down to first");
    }

    private static void nativeFocusRoutes() throws Exception {
        setup("settings"); choose("Camera settings"); choose("Focus mode");
        check("FocusSelection".equals(ShootingBridge.nativeMenu) && !FilmLabUi.isOpen(), "Focus mode opens exact native Sony menu");
        FilmLabUi.open("settings"); choose("Language"); choose("简体中文"); key(KeyEvent.KEYCODE_MENU);
        choose("相机设置"); choose("对焦区域");
        check("FocusArea".equals(ShootingBridge.nativeMenu) && !FilmLabUi.isOpen(), "Chinese Focus area opens exact native Sony menu");
        FilmLabUi.open("settings"); choose("相机设置"); ShootingBridge.nativeMenuError = "menu_unavailable";
        choose("对焦模式");
        check(FilmLabUi.isOpen() && get(ui, "notice").toString().indexOf("menu_unavailable") >= 0,
                "native focus unavailability remains visible without closing the overlay");
    }

    private static void emptyFavoritesRestoreGuard() throws Exception {
        setupBuiltinLibrary(); Handler.advance(120);
        Recipe selected = (Recipe) get(ui, "selectedRecipe"); ShootingBridge.cancelError = "RESTORE_FAILED";
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        check("builtin".equals(get(ui, "tab")) && selected == get(ui, "selectedRecipe")
                && get(ui, "notice").toString().indexOf("RESTORE_FAILED") >= 0,
                "failed restore does not hide current preset behind an empty Favorites tab");
        ShootingBridge.cancelError = null; key(KeyEvent.KEYCODE_DPAD_LEFT);
        check("favorites".equals(get(ui, "tab")) && get(ui, "selectedRecipe") == null,
                "empty Favorites clears selection after successful restoration");
        FilmLabUi.getStore().setFavorite(selected.id, true); FilmLabUi.open("library"); Handler.advance(120);
        key(KeyEvent.KEYCODE_DPAD_CENTER); choose("Remove favorite"); ShootingBridge.cancelError = "RESTORE_FAILED";
        key(KeyEvent.KEYCODE_MENU);
        check("detail".equals(get(ui, "screen")), "removing last favorite keeps detail open if restore fails");
        ShootingBridge.cancelError = null; key(KeyEvent.KEYCODE_MENU);
        check("library".equals(get(ui, "screen")) && rows().isEmpty() && get(ui, "selectedRecipe") == null,
                "retry after removing last favorite returns to an empty restored library");
        setupBuiltinLibrary(); Handler.advance(120);
        FilmLabUi.close(); ShootingBridge.cancelError = "RESTORE_FAILED";
        FilmLabUi.open("library");
        check("settings".equals(get(ui, "screen")) && FilmLabUi.isOpen()
                && get(ui, "notice").toString().indexOf("RESTORE_FAILED") >= 0,
                "reopening after native failed restoration shows an actionable settings error instead of empty Favorites");
        choose("Film presets");
        check("settings".equals(get(ui, "screen")) && get(ui, "notice").toString().indexOf("RESTORE_FAILED") >= 0,
                "Settings preset entry also guards failed restoration");
        ShootingBridge.cancelError = null; choose("Film presets");
        check("library".equals(get(ui, "screen")) && "favorites".equals(get(ui, "tab"))
                && rows().isEmpty() && get(ui, "selectedRecipe") == null,
                "retrying entry after successful restoration reaches empty Favorites");
    }

    private static void navigationAndLatestPreview() throws Exception {
        setupBuiltinLibrary();
        Object originalPage = get(ui, "page");
        Object firstRowView = get(rows().get(0), "view");
        Object secondRowView = get(rows().get(1), "view");
        int views = View.creations;
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_UP);
        check(index() == 2, "physical arrows select the expected recipe");
        check(get(ui, "page") == originalPage, "arrow navigation retains page identity");
        check(get(rows().get(0), "view") == firstRowView && get(rows().get(1), "view") == secondRowView,
                "arrow navigation retains row identities");
        check(View.creations == views, "arrow navigation creates no replacement views");
        check(((View) get(rows().get(2), "marker")).getVisibility() == View.VISIBLE,
                "new focus marker is visible");
        check(((View) get(rows().get(1), "marker")).getVisibility() == View.INVISIBLE,
                "old focus marker is cleared");
        check(ShootingBridge.previews.isEmpty(), "rapid input does not synchronously invoke camera previews");
        Handler.advance(119);
        check(ShootingBridge.previews.isEmpty(), "settle delay is respected");
        Handler.advance(1);
        Recipe chosen = (Recipe) get(ui, "selectedRecipe");
        check(ShootingBridge.previews.size() == 1, "rapid input applies only one preview");
        check(ShootingBridge.previews.get(0).id.equals(chosen.id), "preview uses latest highlighted recipe");
        check(get(ui, "page") == originalPage && View.creations == views,
                "preview completion updates retained views");
        check(get(ui, "previewName") == null && get(ui, "previewHeading") == null,
                "preview completion never creates an inline detail card");
        check(((Boolean) get(ui, "previewAccepted")).booleanValue(), "successful callback marks preview accepted");
        FilmLabUi.dispatch(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
        check(index() == 2, "key-up does not move selection");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        check("user".equals(get(ui, "tab")), "right from Built-in opens My recipes");
        check(get(ui, "page") != originalPage, "collection changes rebuild the correct list");
    }

    private static void cancellationAndUse() throws Exception {
        setupBuiltinLibrary();
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_MENU);
        check(!FilmLabUi.isOpen() && ShootingBridge.cancels == 1, "MENU restores prior look before closing");
        Handler.advance(1000);
        check(ShootingBridge.previews.isEmpty(), "closing cancels queued preview");
        check(!FilmLabUi.dispatch(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)),
                "closed overlay passes shooting keys onward");
        setupBuiltinLibrary();
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        check("detail".equals(get(ui, "screen")), "center opens selected recipe details");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        check(ShootingBridge.applies == 1 && !FilmLabUi.isOpen(), "Use commits once and closes");
        Handler.advance(1000);
        check(ShootingBridge.previews.isEmpty(), "Use prevents stale delayed preview overwriting applied recipe");
        check(FilmLabUi.getStore().getActive().id.equals(RecipeStore.builtins().get(1).id),
                "Use persists actual selected recipe");
        setupBuiltinLibrary();
        ShootingBridge.previewError = "ENGINE_BUSY";
        Object page = get(ui, "page");
        Handler.advance(120);
        check(get(ui, "page") == page, "failed preview also keeps retained page");
        check(get(ui, "notice").toString().indexOf("ENGINE_BUSY") >= 0, "failed preview remains visible");
    }

    private static void editorAndKeyboard() throws Exception {
        setupBuiltinLibrary();
        key(KeyEvent.KEYCODE_DPAD_CENTER); choose("Duplicate");
        check("editor".equals(get(ui, "screen")), "duplicate opens editor");
        Object page = get(ui, "page"); int views = View.creations;
        focus("Color"); key(KeyEvent.KEYCODE_DPAD_LEFT);
        check(((Recipe) get(ui, "draft")).color == 90, "left adjusts current field");
        check(((TextView) get(rows().get(2), "valueView")).getText().toString().indexOf("90%") >= 0,
                "adjustment updates visible value");
        check(get(ui, "page") == page && View.creations == views, "editor navigation and adjustment reuse views");
        focus("White balance"); key(KeyEvent.KEYCODE_DPAD_RIGHT);
        check(((Boolean) get(rows().get(7), "enabled")).booleanValue(), "Kelvin mode enables temperature field");
        focus("Temperature"); key(KeyEvent.KEYCODE_DPAD_RIGHT);
        check(((Recipe) get(ui, "draft")).kelvin == 5600, "newly enabled temperature accepts adjustment");
        focus("Base film");
        for (int i = 0; i < RecipeStore.builtins().size() && !((Recipe) get(ui, "draft")).isMonochrome(); i++)
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
        check(((Recipe) get(ui, "draft")).isMonochrome(), "base-film control reaches monochrome");
        check(!((Boolean) get(rows().get(2), "enabled")).booleanValue(), "monochrome disables color control");
        focus("Color"); key(KeyEvent.KEYCODE_DPAD_LEFT);
        check(((Recipe) get(ui, "draft")).color == 100, "disabled color control does not mutate monochrome");
        choose("Recipe name");
        NameKeyboard keyboard = (NameKeyboard) get(ui, "keyboard");
        String before = keyboard.value();
        page = get(ui, "page"); views = View.creations;
        Object cell0 = ((List<?>) get(ui, "keyboardCells")).get(0);
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER);
        check(keyboard.value().equals(before + "b"), "physical keyboard commits selected letter");
        check(((TextView) get(ui, "keyboardValue")).getText().toString().equals(before + "b▏"),
                "typed name appears immediately");
        check(get(ui, "page") == page && View.creations == views, "keyboard focus and typing keep page and grid");
        check(((List<?>) get(ui, "keyboardCells")).get(0) == cell0, "typing retains key view identity");
        key(KeyEvent.KEYCODE_FOCUS);
        check("confirm".equals(get(ui, "screen")), "half shutter protects unsaved name");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        check("keyboard".equals(get(ui, "screen")) && get(ui, "keyboard") == keyboard,
                "cancel discard returns to same keyboard");
        check(keyboard.value().equals(before + "b"), "discard cancellation retains typed name");
    }

    private static void reportsFreshRetryAndRecreation() throws Exception {
        setup("settings");
        choose("Save issue report");
        check(DiagnosticsBridge.snapshots == 0, "opening report page does not take a stale eager snapshot");
        choose("Save report"); finishWorker("FilmLabReportExport");
        check(DiagnosticsBridge.snapshots == 1 && "snapshot-1".equals(DiagnosticsBridge.exported.get(0)),
                "first Save captures current snapshot");
        choose("Save report"); finishWorker("FilmLabReportExport");
        check(DiagnosticsBridge.snapshots == 2 && "snapshot-2".equals(DiagnosticsBridge.exported.get(1)),
                "next successful Save captures a fresh snapshot");
        DiagnosticsBridge.failExport = true;
        choose("Save report"); finishWorker("FilmLabReportExport");
        check(DiagnosticsBridge.snapshots == 3, "failed attempt starts with a new snapshot");
        Object report = get(null, FilmLabUi.class, "REPORT");
        check("snapshot-3".equals(get(report, "snapshot")), "failed export retains snapshot");
        DiagnosticsBridge.failExport = false;
        choose("Retry saving"); finishWorker("FilmLabReportExport");
        check(DiagnosticsBridge.snapshots == 3 && "snapshot-3".equals(DiagnosticsBridge.exported.get(3)),
                "Retry exports identical failed snapshot");
        check(get(report, "snapshot") == null, "successful retry releases stale snapshot");
        DiagnosticsBridge.blockExport = true;
        choose("Save report"); DiagnosticsBridge.awaitStarted(5); Handler.drain();
        Activity oldActivity = activity;
        FilmLabUi.detach(oldActivity);
        activity = new Activity(); FilmLabUi.attach(activity);
        ui = get(null, FilmLabUi.class, "current");
        FilmLabUi.open("settings"); choose("Save issue report"); Handler.drain();
        check(((Boolean) get(report, "busy")).booleanValue(), "export stays in progress across Activity replacement");
        check(!((Boolean) get(rows().get(0), "enabled")).booleanValue(), "new UI disables duplicate export while busy");
        DiagnosticsBridge.release(); finishWorker("FilmLabReportExport");
        check(get(report, "result").toString().length() > 0, "worker result reaches replacement UI state");
        check(((Boolean) get(rows().get(0), "enabled")).booleanValue(), "replacement UI refreshes after export");
        check(oldActivity.finishCalls == 0, "export completion never finishes destroyed Activity");
    }

    private static void pinyinGridChanges() throws Exception {
        setupBuiltinLibrary();
        key(KeyEvent.KEYCODE_DPAD_CENTER); choose("Duplicate"); choose("Recipe name");
        Object page = get(ui, "page");
        NameKeyboard keyboard = (NameKeyboard) get(ui, "keyboard");
        String previous = keyboard.value();
        keyboardChoose("Pinyin"); keyboardChoose("y"); keyboardChoose("u");
        check("yu".equals(keyboard.syllable()), "physical keys compose Pinyin syllable");
        Object lettersCell = ((List<?>) get(ui, "keyboardCells")).get(0);
        keyboardChoose("Choose");
        check(keyboard.isCandidateMode(), "Pinyin Choose enters real dictionary candidates");
        check(get(ui, "page") == page, "candidate mode retains the surrounding keyboard page");
        check(((List<?>) get(ui, "keyboardCells")).size() == 32, "candidate mode resizes grid for 24 characters and controls");
        check(((List<?>) get(ui, "keyboardCells")).get(0) != lettersCell, "grid replacement is limited to changed key count");
        int views = View.creations;
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        check(View.creations == views, "candidate focus movement allocates no views");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        check(!keyboard.isCandidateMode() && keyboard.value().length() == previous.length() + 1,
                "choosing candidate inserts a Chinese character and returns to letters");
        check(((List<?>) get(ui, "keyboardCells")).size() == 48, "letter grid returns after candidate selection");
        check(get(ui, "page") == page, "candidate choice keeps keyboard page identity");
    }

    private static void exitGuardsAndOrdering() throws Exception {
        setup("settings");
        choose("Save issue report");
        DiagnosticsBridge.blockExport = true;
        choose("Save report"); DiagnosticsBridge.awaitStarted(1); Handler.drain();
        key(KeyEvent.KEYCODE_MENU); choose("Exit app");
        check(activity.finishCalls == 0 && FilmLabUi.isOpen(), "Exit stays open while report export is busy");
        check(get(ui, "notice").toString().indexOf("still saving") >= 0, "busy Exit gives actionable notice");
        check(ShootingBridge.cancels == 0, "busy Exit has no camera cancellation side effect");
        DiagnosticsBridge.release(); finishWorker("FilmLabReportExport");
        ShootingBridge.cancelError = "RESTORE_FAILED";
        choose("Exit app");
        check(activity.finishCalls == 0 && FilmLabUi.isOpen(), "failed preview restoration blocks Exit");
        check(get(ui, "notice").toString().indexOf("RESTORE_FAILED") >= 0, "restore failure remains visible");
        ShootingBridge.cancelError = null; DiagnosticsBridge.blockFlush = true; Probe.events.clear();
        choose("Exit app"); DiagnosticsBridge.awaitFlushStarted();
        check(activity.finishCalls == 0 && FilmLabUi.isOpen(), "Exit waits off main thread for bounded diagnostics flush");
        int selected = index();
        key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_MENU);
        check(index() == selected && FilmLabUi.isOpen(), "keys are consumed while Exit is pending");
        DiagnosticsBridge.releaseFlush(); finishWorker("FilmLabExit");
        check(activity.finishCalls == 1 && !FilmLabUi.isOpen(), "Exit invokes normal Activity finish once");
        check(Probe.events.indexOf("cancel") < Probe.events.indexOf("flush")
                && Probe.events.indexOf("flush") < Probe.events.indexOf("finish"),
                "Exit restores preview and flushes before native finish");
        Handler.advance(1000);
        check(ShootingBridge.previews.isEmpty(), "Exit leaves no queued preview callbacks");
    }

    private static void dirtyNameExitAndCancel() throws Exception {
        setupBuiltinLibrary();
        key(KeyEvent.KEYCODE_DPAD_CENTER); choose("Duplicate"); choose("Recipe name");
        NameKeyboard keyboard = (NameKeyboard) get(ui, "keyboard");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        String typed = keyboard.value();
        // Exit is a Settings action; call its same entry point to cover unsaved-state guard
        // without inventing an impossible navigation path that silently drops the draft.
        invoke(ui, "requestExit");
        check("confirm".equals(get(ui, "screen")) && activity.finishCalls == 0, "Exit protects unsaved name");
        check("discard-exit".equals(get(ui, "confirmation")), "Exit requires an explicit discard decision");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        check("keyboard".equals(get(ui, "screen")) && keyboard.value().equals(typed),
                "cancel Exit preserves name input and keyboard screen");
        invoke(ui, "requestExit"); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
        finishWorker("FilmLabExit");
        check(activity.finishCalls == 1 && !FilmLabUi.isOpen(), "confirmed discard exits through normal finish");
        check(get(ui, "draft") == null && get(ui, "keyboard") == null, "confirmed Exit clears discarded edit state");
    }

    private static void staleExitCannotFinishReplacement() throws Exception {
        setup("settings");
        DiagnosticsBridge.blockFlush = true;
        choose("Exit app"); DiagnosticsBridge.awaitFlushStarted();
        Activity previous = activity;
        FilmLabUi.detach(previous);
        activity = new Activity(); FilmLabUi.attach(activity); ui = get(null, FilmLabUi.class, "current");
        FilmLabUi.open("settings"); Handler.drain();
        DiagnosticsBridge.releaseFlush(); finishWorker("FilmLabExit");
        check(previous.finishCalls == 0 && activity.finishCalls == 0,
                "stale Exit completion cannot finish replaced Activity");
        check(FilmLabUi.isOpen(), "stale Exit completion leaves new overlay open");
    }

    private static void detachedPreviewAndFlushTimeout() throws Exception {
        setupBuiltinLibrary();
        Runnable obsolete = (Runnable) get(ui, "pendingPreview");
        FilmLabUi.detach(activity);
        FilmLabUi.attach(activity); ui = get(null, FilmLabUi.class, "current");
        FilmLabUi.open("settings");
        obsolete.run();
        Handler.advance(1000);
        check(ShootingBridge.previews.isEmpty(), "late callback from detached UI cannot apply an obsolete preview");
        setup("settings");
        DiagnosticsBridge.blockFlush = true;
        choose("Exit app"); DiagnosticsBridge.awaitFlushStarted();
        finishWorker("FilmLabExit");
        check(activity.finishCalls == 1 && !FilmLabUi.isOpen(),
                "bounded flush timeout still hands exit to Sony finish");
        DiagnosticsBridge.releaseFlush();
    }

    private static void canceledNativeExitCanReopen() throws Exception {
        setup("settings");
        activity.finishAccepted = false;
        choose("Exit app"); finishWorker("FilmLabExit");
        check(activity.finishCalls == 1 && !activity.isFinishing() && !FilmLabUi.isOpen(),
                "native exit confirmation can return without finishing Activity");
        FilmLabUi.open("library");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_DOWN);
        check(FilmLabUi.isOpen() && index() == 1, "overlay is usable after native exit confirmation is canceled");
        key(KeyEvent.KEYCODE_MENU);
        check(!FilmLabUi.isOpen() && activity.finishCalls == 1, "reopened overlay returns to shooting without another exit");
    }

    private static void cleanup() throws Exception {
        DiagnosticsBridge.release(); DiagnosticsBridge.releaseFlush();
        finishWorker("FilmLabReportExport"); finishWorker("FilmLabExit");
        if (activity != null) {
            FilmLabUi.detach(activity);
            delete(activity.getFilesDir());
        }
        activity = null; ui = null;
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
    private static void finishWorker(String name) throws Exception {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().equals(name)) {
                thread.join(3000);
                if (thread.isAlive()) throw new AssertionError(name + " did not finish");
            }
        }
        Handler.drain();
    }
    private static void key(int code) {
        FilmLabUi.dispatch(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        FilmLabUi.dispatch(new KeyEvent(KeyEvent.ACTION_UP, code));
    }
    private static void focus(String label) throws Exception {
        int target = -1;
        List<?> collection = rows();
        for (int i = 0; i < collection.size(); i++)
            if (label.equals(get(collection.get(i), "label"))) { target = i; break; }
        if (target < 0) throw new AssertionError("missing row " + label + " on " + get(ui, "screen"));
        for (int guard = 0; index() != target && guard < 250; guard++)
            key(index() < target ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
        if (index() != target) throw new AssertionError("cannot focus " + label);
    }
    private static void choose(String label) throws Exception { focus(label); key(KeyEvent.KEYCODE_DPAD_CENTER); }
    private static void keyboardChoose(String label) throws Exception {
        NameKeyboard keyboard = (NameKeyboard) get(ui, "keyboard");
        int target = keyboard.cells().indexOf(label);
        if (target < 0) throw new AssertionError("missing keyboard cell " + label);
        while (keyboard.selected() / 8 < target / 8) key(KeyEvent.KEYCODE_DPAD_DOWN);
        while (keyboard.selected() / 8 > target / 8) key(KeyEvent.KEYCODE_DPAD_UP);
        while (keyboard.selected() < target) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        while (keyboard.selected() > target) key(KeyEvent.KEYCODE_DPAD_LEFT);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
    }
    private static int index() throws Exception { return ((Integer) get(ui, "index")).intValue(); }
    private static List<?> rows() throws Exception { return (List<?>) get(ui, "rows"); }
    private static Object get(Object object, String field) throws Exception { return get(object, object.getClass(), field); }
    private static Object get(Object object, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void put(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void invoke(Object object, String name) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(object);
    }
    private static void check(boolean result, String message) {
        assertions++; if (!result) throw new AssertionError(message);
    }
}

#!/usr/bin/env python3
"""Browser-only E2E checks for Film Lab settings and compact preset browsing.

Start a server, then run:
  python3 -m http.server 8080 --directory design/ui-preview
  python3 design/ui-preview/tests/verify_settings.py

Navigation uses desktop keys and the illustrated physical camera buttons.
The public state APIs are used for assertions and explicitly marked fixtures;
these checks do not exercise Sony hardware, flash, camera capture or card I/O.
"""

import argparse
import json
from pathlib import Path
import re
import unittest

from playwright.sync_api import sync_playwright


BASE_URL = "http://127.0.0.1:8080"
CHROMIUM = "/usr/bin/chromium"


class SettingsPreviewTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(
            executable_path=CHROMIUM, headless=True, args=["--no-sandbox"]
        )

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.context = self.browser.new_context(
            viewport={"width": 1440, "height": 1050}, accept_downloads=True
        )
        self.page = self.context.new_page()
        self.errors = []
        self.downloads = []
        self.page.on("pageerror", lambda error: self.errors.append(str(error)))
        self.page.on("download", lambda download: self.downloads.append(download))
        self.page.goto(BASE_URL, wait_until="networkidle")
        self.page.wait_for_function(
            "window.filmLabPreview && window.FilmLabSupport && window.FilmLabDisplay"
        )

    def tearDown(self):
        errors = list(self.errors)
        self.context.close()
        self.assertEqual(errors, [], "JavaScript errors occurred")

    def state(self):
        return self.page.evaluate("filmLabPreview.getState()")

    def support(self, method):
        return self.page.evaluate(f"FilmLabSupport.{method}()")

    def press(self, key, count=1):
        for _ in range(count):
            self.page.keyboard.press(key)

    def physical(self, selector):
        candidates = self.page.locator(
            f".hardware {selector}, .mobile-controls {selector}"
        )
        for index in range(candidates.count()):
            if candidates.nth(index).is_visible():
                candidates.nth(index).click()
                return
        self.fail(f"No visible physical control: {selector}")

    def expect_page(self, name):
        self.page.locator(f'#lcd[data-support-page="{name}"]').wait_for()
        self.assertEqual(self.support("getPage"), name)

    def focused_setting(self):
        return self.page.evaluate("document.activeElement?.dataset.setting || null")

    def focus_setting(self, key):
        # Move focus through the real key handler; no DOM .focus() shortcut.
        for _ in range(25):
            if self.focused_setting() == key:
                return
            self.press("ArrowDown")
        self.fail(f"Could not reach settings row {key!r}; focused {self.focused_setting()!r}")

    def open_row(self, key, page=None):
        self.focus_setting(key)
        self.press("Enter")
        self.expect_page(page or key)

    def to_shoot(self):
        for _ in range(8):
            if self.state()["screen"] == "shoot":
                return
            self.press("Escape")
        self.fail("MENU did not return to shooting")

    def open_settings(self):
        self.to_shoot()
        self.physical('[data-ui="menu"]')
        self.expect_page("index")

    def invariant(self):
        state = self.state()
        active = dict(state["active"])
        # Built-in labels translate; the resolved shooting values must not change.
        if active["id"] and not active["id"].startswith("user-"):
            active.pop("name", None)
        return {
            "active": active,
            "selectedId": state["selectedId"],
            "draft": state["draft"],
            "records": state["records"],
            "favorites": state["favorites"],
        }

    def test_01_all_settings_pages_bilingual_hierarchy_and_focus(self):
        titles = {
            "en": {
                "flash": "Flash", "language": "Language", "report": "report",
                "about": "About", "licenses": "Licens", "developer": "Developer",
                "device": "Device", "processing": "Processing", "keys": "Key", "events": "events",
            },
            "zh": {
                "flash": "闪光", "language": "语言", "report": "报告", "about": "关于",
                "licenses": "许可", "developer": "开发者", "device": "设备", "processing": "处理",
                "keys": "按键", "events": "事件",
            },
        }
        for lang in ("en", "zh"):
            with self.subTest(language=lang):
                self.page.locator(".language-switch button").nth(0 if lang == "en" else 1).click()
                self.open_settings()
                before = self.invariant()
                for root in ("flash", "language", "report", "about", "developer"):
                    self.open_row(root)
                    self.assertRegex(self.page.locator("#lcd .head-title").inner_text(),
                                     re.compile(titles[lang][root], re.I))
                    if root == "about":
                        self.open_row("licenses")
                        self.assertIn(titles[lang]["licenses"], self.page.locator("#lcd").inner_text())
                        self.press("ArrowLeft")
                        self.expect_page("about")
                        self.assertEqual(self.focused_setting(), "licenses")
                    if root == "developer":
                        for child in ("device", "processing", "keys", "events"):
                            self.open_row(child)
                            self.assertRegex(self.page.locator("#lcd .head-title").inner_text(),
                                             re.compile(titles[lang][child], re.I))
                            if child == "keys":
                                self.physical('[data-ui="menu"]')
                            else:
                                self.press("ArrowLeft")
                            self.expect_page("developer")
                            self.assertEqual(self.focused_setting(), child)
                    if root == "flash":
                        self.physical('[data-ui="menu"]')
                    else:
                        self.press("ArrowLeft")
                    self.expect_page("index")
                    self.assertEqual(self.focused_setting(), root)
                self.assertEqual(self.invariant(), before)
                self.assertEqual(self.downloads, [])
                self.physical('[data-ui="menu"]')
                self.assertEqual(self.state()["screen"], "shoot")

    def test_02_direction_keys_never_export_or_choose_language(self):
        self.open_settings()
        self.open_row("language")
        original_language = self.state()["lang"]
        for key in ("ArrowDown", "ArrowRight", "ArrowUp", "ArrowRight"):
            self.press(key)
            self.assertEqual(self.state()["lang"], original_language)
        self.press("ArrowLeft")
        self.expect_page("index")
        self.open_row("report")
        self.focus_setting("download")
        for key in ("ArrowUp", "ArrowDown", "ArrowRight", "ArrowDown", "ArrowUp"):
            self.press(key)
            self.assertEqual(self.downloads, [])
        self.press("ArrowLeft")
        self.expect_page("index")
        self.assertEqual(self.downloads, [])

    def test_03_flash_simulation_is_separate_from_recipe_preview_and_use(self):
        self.open_settings()
        self.open_row("flash")
        before = self.invariant()
        self.focus_setting("flash-mode")
        self.press("ArrowRight")
        self.focus_setting("flash-compensation")
        self.press("ArrowRight", 3)
        flash = self.support("getFlash")
        self.assertEqual(self.invariant(), before)
        self.assertRegex(self.page.locator("#lcd").inner_text(), r"(?i)simulat|illustrat|示意|模拟")
        self.physical('[data-ui="menu"]')
        self.expect_page("index")
        self.physical('[data-ui="menu"]')
        self.press("f")
        self.assertEqual(self.state()["screen"], "library")
        active_before = self.state()["active"]
        self.press("ArrowDown")
        self.assertEqual(self.state()["active"], active_before)
        self.assertEqual(self.support("getFlash"), flash)
        self.press("Enter")
        self.assertEqual(self.state()["screen"], "detail")
        self.press("Enter")
        self.assertEqual(self.state()["screen"], "shoot")
        self.assertNotEqual(self.state()["active"]["id"], active_before["id"])
        self.assertEqual(self.support("getFlash"), flash)

    def test_04_report_download_has_stable_fields_and_redacts_user_names(self):
        private_name = "PRIVATE_雨后街角_Weekend Gold"
        # Deterministic state fixture; report navigation/export below uses buttons.
        self.page.evaluate("""name => {
          filmLabPreview.dispatch('duplicate', {id:'negative'});
          filmLabPreview.dispatch('field', {key:'name',value:name});
          filmLabPreview.dispatch('save', {use:true});
        }""", private_name)
        self.open_settings()
        self.open_row("report")
        self.focus_setting("download")
        with self.page.expect_download() as event:
            self.press("Enter")
        download = event.value
        self.assertRegex(download.suggested_filename, r"^FLR\d{5}\.TXT$")
        text = Path(download.path()).read_text(encoding="utf-8")
        self.assertTrue(text.startswith("BROWSER PROTOTYPE — NOT A CAMERA REPORT"))
        self.assertNotIn(private_name, text)
        self.assertNotIn("雨后街角", text)
        self.assertNotIn("Weekend Gold", text)
        for field in ("schema", "browser", "active", "highlighted", "draft", "flash", "events"):
            self.assertIn(field, text.lower())
        self.assertRegex(text, r"(?i)camera.*(?:unavailable|unsupported|not|unknown)|(?:unavailable|unsupported|not).*camera")
        self.assertNotIn("mailto:", text)
        self.assertEqual(self.state()["active"]["name"], private_name)
        report = json.loads(text[text.index("{"):])
        self.assertEqual(report["schemaVersion"], 1)
        self.assertEqual(report["report"]["filename"], download.suggested_filename)
        self.assertEqual(report["report"]["encoding"], "UTF-8")
        self.assertEqual(report["snapshot"]["scope"], "browser-prototype")
        self.assertEqual(report["snapshot"]["ui"]["language"], "en")
        self.assertIsNone(report["snapshot"]["device"]["actualModel"])
        self.assertIsNone(report["snapshot"]["device"]["firmware"])
        self.assertEqual(report["snapshot"]["camera"]["readback"], "unavailable")
        self.assertEqual(report["snapshot"]["recipes"]["active"]["settings"], self.state()["active"]["settings"])
        self.assertNotIn("name", report["snapshot"]["recipes"]["active"])
        self.assertEqual(report["reproduction"], {"steps": "", "expected": "", "actual": ""})
        self.page.locator(".language-switch button").nth(1).click()
        self.focus_setting("new-snapshot")
        self.press("Enter")
        self.focus_setting("download")
        with self.page.expect_download() as translated_event:
            self.press("Enter")
        translated_text = Path(translated_event.value.path()).read_text(encoding="utf-8")
        translated = json.loads(translated_text[translated_text.index("{"):])
        self.assertEqual(set(translated), set(report))
        self.assertEqual(set(translated["snapshot"]), set(report["snapshot"]))
        self.assertEqual(translated["snapshot"]["ui"]["language"], "zh")
        self.assertNotIn(private_name, translated_text)
        self.assertEqual(self.state()["active"]["name"], private_name)

    def test_05_failed_download_retry_retains_exact_snapshot(self):
        self.open_settings()
        self.open_row("report")
        self.focus_setting("download")
        self.page.evaluate("""() => {
          window.__originalObjectURL = URL.createObjectURL;
          URL.createObjectURL = blob => {
            window.__failedReportBlob = blob;
            throw new Error('QA simulated browser download failure');
          };
        }""")
        self.press("Enter")
        self.page.wait_for_function("FilmLabSupport.getReportStatus().state === 'error'")
        failed = self.support("getReportStatus")
        self.assertIsNotNone(failed["snapshotId"])
        failed_text = self.page.evaluate("window.__failedReportBlob.text()")
        self.assertEqual(self.downloads, [])
        # A later UI change must not quietly replace the failed report snapshot.
        self.page.locator(".language-switch button").nth(1).click()
        self.assertEqual(self.state()["lang"], "zh")
        self.expect_page("report")
        self.press("ArrowDown")
        self.press("ArrowUp")
        self.page.evaluate("() => { URL.createObjectURL = window.__originalObjectURL; }")
        self.focus_setting("download")
        with self.page.expect_download() as event:
            self.press("Enter")
        download = event.value
        self.assertEqual(download.suggested_filename, failed["filename"])
        self.assertEqual(Path(download.path()).read_text(encoding="utf-8"), failed_text)

    def test_06_key_viewer_is_isolated_and_recent_events_scroll(self):
        self.open_settings()
        self.open_row("developer")
        self.open_row("keys")
        before = self.invariant()
        flash = self.support("getFlash")
        display = self.page.evaluate("FilmLabDisplay.getMode()")
        for key in ("f", "h", "Space", "d", "ArrowLeft", "ArrowRight"):
            self.press(key)
            self.expect_page("keys")
            self.assertEqual(self.invariant(), before)
            self.assertEqual(self.support("getFlash"), flash)
            self.assertEqual(self.page.evaluate("FilmLabDisplay.getMode()"), display)
            self.assertEqual(self.page.locator("#lcd .flash").count(), 0)
        for button in ('[data-ui="fn"]', '[data-ui="capture"]', '[data-action="halfShutter"]',
                       '[data-nav="up"]', '[data-nav="center"]'):
            self.physical(button)
            self.expect_page("keys")
            self.assertEqual(self.invariant(), before)
            self.assertEqual(self.page.evaluate("FilmLabDisplay.getMode()"), display)
            self.assertEqual(self.page.locator("#lcd .flash").count(), 0)
        self.page.keyboard.type("PRIVATE_INPUT_DO_NOT_LOG")
        self.assertNotIn("PRIVATE_INPUT_DO_NOT_LOG", json.dumps(self.support("getEvents"), ensure_ascii=False))
        self.press("ArrowRight", 75)
        self.physical('[data-ui="menu"]')
        self.expect_page("developer")
        self.assertEqual(self.focused_setting(), "keys")
        self.open_row("events")
        events = self.page.locator('#lcd [data-scroll="events"]')
        self.assertTrue(events.evaluate("e => e.scrollHeight > e.clientHeight"), "Recent events did not overflow")
        self.press("ArrowDown", 8)
        self.assertGreater(events.evaluate("e => e.scrollTop"), 0)
        self.assertLessEqual(len(self.support("getEvents")), 128)
        self.press("Escape")
        self.expect_page("developer")

    def test_07_diagnostics_and_language_keep_pending_draft_and_builtin_protection(self):
        # Pending-draft fixture models recoverable app state available to diagnostics.
        self.page.evaluate("""() => {
          filmLabPreview.dispatch('duplicate', {id:'classic-chrome'});
          filmLabPreview.dispatch('field', {key:'name',value:'保留草稿 Draft'});
          filmLabPreview.dispatch('field', {key:'shadows',value:2});
          filmLabPreview.dispatch('settings');
        }""")
        self.expect_page("index")
        before = self.invariant()
        self.open_row("language")
        self.focus_setting("zh")
        self.press("Enter")
        self.assertEqual(self.state()["lang"], "zh")
        self.assertEqual(self.invariant(), before)
        self.press("ArrowLeft")
        self.open_row("developer")
        for child in ("device", "processing", "events"):
            self.open_row(child)
            self.assertEqual(self.invariant(), before)
            self.press("ArrowLeft")
        self.assertEqual(self.state()["draft"]["name"], "保留草稿 Draft")
        # Independently invoke protected actions; hiding buttons is insufficient.
        self.page.evaluate("""() => {
          filmLabPreview.dispatch('discard');
          filmLabPreview.dispatch('tab',{tab:'builtin'});
          filmLabPreview.dispatch('detail',{id:'classic-chrome'});
        }""")
        self.assertEqual(self.page.locator('#lcd [data-action="rename"], #lcd [data-action="delete"], #lcd [data-action="edit"]').count(), 0)
        builtins = [r for r in self.state()["records"] if r["origin"] == "builtin"]
        self.page.evaluate("""() => {
          ['rename','delete','edit'].forEach(type => filmLabPreview.dispatch(type,{id:'classic-chrome'}));
          filmLabPreview.dispatch('confirmDelete');
        }""")
        self.assertEqual([r for r in self.state()["records"] if r["origin"] == "builtin"], builtins)
        self.assertIsNone(self.state()["modal"])
        self.assertIsNone(self.state()["draft"])

    def test_08_clean_disp_menu_recovery_and_mobile_overflow(self):
        self.to_shoot()
        before = self.invariant()
        self.press("d")
        self.assertEqual(self.page.evaluate("FilmLabDisplay.getMode()"), "clean")
        self.assertEqual(self.page.locator("#lcd .hud-top, #lcd .shoot-recipe, #lcd .shoot-menu").count(), 0)
        self.physical('[data-ui="menu"]')
        self.expect_page("index")
        self.open_row("about")
        self.press("Escape")
        self.expect_page("index")
        self.press("Escape")
        self.assertEqual(self.state()["screen"], "shoot")
        self.assertEqual(self.page.evaluate("FilmLabDisplay.getMode()"), "clean")
        self.physical('[data-ui="fn"]')
        self.assertEqual(self.state()["screen"], "library")
        self.press("Escape")
        self.assertEqual(self.page.evaluate("FilmLabDisplay.getMode()"), "clean")
        self.assertEqual(self.invariant(), before)
        self.page.set_viewport_size({"width": 390, "height": 844})
        self.open_settings()
        for lang in ("en", "zh"):
            self.page.locator(".language-switch button").nth(0 if lang == "en" else 1).click()
            for root in ("flash", "language", "report", "about", "developer"):
                self.open_row(root)
                self.assertLessEqual(self.page.evaluate("document.documentElement.scrollWidth"), 390)
                self.assertLessEqual(self.page.evaluate("document.body.scrollWidth"), 390)
                children = ("licenses",) if root == "about" else (
                    ("device", "processing", "keys", "events") if root == "developer" else ()
                )
                for child in children:
                    self.open_row(child)
                    self.assertLessEqual(self.page.evaluate("document.documentElement.scrollWidth"), 390)
                    self.assertLessEqual(self.page.evaluate("document.body.scrollWidth"), 390)
                    self.physical('[data-ui="menu"]')
                    self.expect_page(root)
                if root == "flash":
                    self.physical('[data-ui="menu"]')
                else:
                    self.press("ArrowLeft")
                self.expect_page("index")
        self.assertEqual(self.downloads, [])

    def test_09_favorites_first_compact_undimmed_library_bilingual(self):
        self.assertEqual(self.state()["screen"], "library")
        self.assertEqual(self.state()["tab"], "favorites")
        for lang in ("en", "zh"):
            with self.subTest(language=lang):
                self.page.locator(".language-switch button").nth(0 if lang == "en" else 1).click()
                self.page.evaluate("filmLabPreview.dispatch('tab', {tab:'favorites'})")
                self.assertEqual(self.page.locator("#lcd .library-tabs button").evaluate_all(
                    "buttons => buttons.map(b => JSON.parse(b.dataset.payload).tab)"
                ), ["favorites", "builtin", "user"])
                first_left = None
                for tab in ("favorites", "builtin", "user"):
                    self.assertEqual(self.state()["tab"], tab)
                    geometry = self.page.evaluate("""() => {
                      const list = document.querySelector('#lcd .recipe-list').getBoundingClientRect();
                      const tab = document.querySelector('#lcd .library-tabs button').getBoundingClientRect();
                      const shade = getComputedStyle(document.querySelector('#lcd .scene'), '::after');
                      const body = getComputedStyle(document.querySelector('#lcd .library-layout'));
                      return {listWidth:list.width, tabWidth:tab.width, left:list.left, tabLeft:tab.left,
                        shadeHidden:shade.content === 'none' || shade.content === 'normal' ||
                          (shade.backgroundImage === 'none' && shade.backgroundColor === 'rgba(0, 0, 0, 0)'),
                        bodyImage:body.backgroundImage, bodyColor:body.backgroundColor};
                    }""")
                    self.assertAlmostEqual(geometry["listWidth"], geometry["tabWidth"], delta=1)
                    self.assertAlmostEqual(geometry["left"], geometry["tabLeft"], delta=1)
                    if first_left is None:
                        first_left = geometry["left"]
                    self.assertAlmostEqual(geometry["left"], first_left, delta=1)
                    self.assertTrue(geometry["shadeHidden"], "Library still dims the live scene")
                    self.assertEqual(geometry["bodyImage"], "none")
                    self.assertEqual(geometry["bodyColor"], "rgba(0, 0, 0, 0)")
                    self.assertEqual(self.page.locator("#lcd .library-preview, #lcd .preview-card").count(), 0)
                    self.press("ArrowRight")
                self.assertEqual(self.state()["tab"], "favorites")
                self.press("ArrowLeft")
                self.assertEqual(self.state()["tab"], "user")

        long_name = "Long recipe name 雨后街角 123456789"
        self.page.evaluate("""name => {
          filmLabPreview.dispatch('duplicate', {id:'classic-chrome'});
          filmLabPreview.dispatch('field', {key:'name',value:name});
          filmLabPreview.dispatch('save', {use:false});
        }""", long_name)
        row = self.page.locator("#lcd .recipe-row.selected")
        name_style = row.locator("b").evaluate("""element => {
          const style = getComputedStyle(element);
          return {text:element.textContent, overflow:style.overflowX,
            ellipsis:style.textOverflow, whiteSpace:style.whiteSpace,
            truncated:element.scrollWidth > element.clientWidth};
        }""")
        self.assertEqual(name_style["text"], long_name)
        self.assertEqual(name_style["overflow"], "hidden")
        self.assertEqual(name_style["ellipsis"], "ellipsis")
        self.assertEqual(name_style["whiteSpace"], "nowrap")
        self.assertTrue(name_style["truncated"])
        active = self.state()["active"]
        self.physical('[data-nav="center"]')
        self.assertEqual(self.state()["screen"], "detail")
        self.assertEqual(self.page.locator("#lcd .detail-identity h2").inner_text(), long_name)
        self.assertGreater(self.page.locator('#lcd [data-action="use"]').count(), 0)
        self.assertEqual(self.state()["active"], active)

    def test_10_library_wrap_empty_single_and_long_user_list(self):
        active = self.state()["active"]
        initial_filter = self.page.locator("#lcd .scene").evaluate("element => element.style.filter")
        self.page.locator("#lcd .recipe-row").first.click()
        first_id = self.state()["selectedId"]
        last_id = self.page.locator("#lcd .recipe-row").last.get_attribute("data-id")
        self.press("ArrowUp")
        self.assertEqual(self.state()["selectedId"], last_id)
        self.press("ArrowDown")
        self.assertEqual(self.state()["selectedId"], first_id)
        # Explicit fixtures adjust collection size; navigation still uses real keys.
        self.page.evaluate("""() => {
          filmLabPreview.getState().favorites.forEach(id => filmLabPreview.dispatch('favorite', {id}));
        }""")
        self.assertEqual(self.page.locator("#lcd .recipe-row").count(), 0)
        self.assertIsNone(self.state()["selectedId"])
        for key in ("ArrowUp", "ArrowDown", "Enter"):
            self.press(key)
            self.assertEqual(self.state()["screen"], "library")
            self.assertIsNone(self.state()["selectedId"])
            self.assertEqual(self.state()["active"], active)
        self.assertEqual(self.page.locator("#lcd .scene").evaluate("element => element.style.filter"), initial_filter)
        self.assertRegex(self.page.locator("#lcd .empty").inner_text(), r"(?i)right|右")
        self.press("ArrowRight")
        self.assertEqual(self.state()["tab"], "builtin")
        self.page.evaluate("""() => {
          filmLabPreview.dispatch('favorite', {id:'classic-chrome'});
          filmLabPreview.dispatch('tab', {tab:'favorites'});
        }""")
        self.assertEqual(self.page.locator("#lcd .recipe-row").count(), 1)
        for key in ("ArrowUp", "ArrowDown"):
            self.press(key, 3)
            self.assertEqual(self.state()["selectedId"], "classic-chrome")
            self.assertEqual(self.state()["active"], active)
        self.page.evaluate("""() => {
          for (let i=0; i<10; i++) {
            filmLabPreview.dispatch('duplicate', {id:'classic-chrome'});
            filmLabPreview.dispatch('field', {key:'name',value:'Wrap row '+i});
            filmLabPreview.dispatch('save', {use:false});
          }
        }""")
        self.assertEqual(self.state()["tab"], "user")
        self.page.locator("#lcd .recipe-row").first.click()
        first_id = self.state()["selectedId"]
        last_id = self.page.locator("#lcd .recipe-row").last.get_attribute("data-id")
        self.press("ArrowUp")
        self.assertTrue(self.page.evaluate("document.activeElement.matches('#lcd .new-row')"))
        self.assertTrue(self.page.locator("#lcd .new-row").evaluate("""element => {
          const row=element.getBoundingClientRect(), list=element.parentElement.getBoundingClientRect();
          return row.top >= list.top - 1 && row.bottom <= list.bottom + 1;
        }"""))
        self.press("ArrowDown")
        self.assertEqual(self.state()["selectedId"], first_id)
        self.assertLessEqual(self.page.locator("#lcd .recipe-list").evaluate("element => element.scrollTop"), 1)
        self.press("ArrowUp", 2)
        self.assertEqual(self.state()["selectedId"], last_id)
        self.assertEqual(self.state()["active"], active)

    def test_11_focus_settings_are_bilingual_simulation_and_preserve_recipes(self):
        for lang in ("en", "zh"):
            with self.subTest(language=lang):
                self.page.locator(".language-switch button").nth(0 if lang == "en" else 1).click()
                self.open_settings()
                before = self.invariant()
                self.open_row("camera")
                self.assertIn("Camera settings" if lang == "en" else "相机设置",
                              self.page.locator("#lcd .head-title").inner_text())
                for page, field, label, first, chosen in (
                    ("focus-mode", "mode", "Focus mode" if lang == "en" else "对焦模式", "af-s", "mf"),
                    ("focus-area", "area", "Focus area" if lang == "en" else "对焦区域", "wide", "flexible"),
                ):
                    self.open_row(page)
                    self.assertIn(label, self.page.locator("#lcd .head-title").inner_text())
                    self.assertRegex(self.page.locator("#lcd").inner_text(), r"(?i)simulat|illustrat|示意|模拟")
                    self.focus_setting(first)
                    self.press("ArrowUp")
                    self.assertEqual(self.focused_setting(), chosen)
                    self.press("ArrowDown")
                    self.assertEqual(self.focused_setting(), first)
                    focus_before = self.support("getFocus")
                    self.focus_setting(chosen)
                    self.assertEqual(self.support("getFocus"), focus_before, "Navigation must not commit a focus choice")
                    self.press("Enter")
                    focus = self.support("getFocus")
                    self.assertEqual(focus["source"], "browser-simulation")
                    self.assertEqual(focus[field], chosen)
                    self.assertEqual(self.invariant(), before)
                    self.expect_page("camera")
                    self.assertEqual(self.focused_setting(), page)
                self.press("Escape")
                self.expect_page("index")
                self.assertEqual(self.focused_setting(), "camera")
                self.assertEqual(self.invariant(), before)

    def test_12_menu_rows_wrap_while_numeric_editor_values_remain_bounded(self):
        self.open_settings()
        roots = self.page.locator("#lcd [data-setting]").evaluate_all("rows => rows.map(row => row.dataset.setting)")
        self.focus_setting(roots[0])
        self.press("ArrowUp")
        self.assertEqual(self.focused_setting(), roots[-1])
        self.press("ArrowDown")
        self.assertEqual(self.focused_setting(), roots[0])
        self.open_row("camera")
        self.focus_setting("focus-mode")
        self.press("ArrowUp")
        self.assertEqual(self.focused_setting(), "focus-area")
        self.press("ArrowDown")
        self.assertEqual(self.focused_setting(), "focus-mode")
        self.to_shoot()
        self.press("f")
        self.press("Enter")
        self.assertEqual(self.state()["screen"], "detail")
        actions = self.page.locator("#lcd .detail-actions button").evaluate_all("rows => rows.map(row => row.dataset.action)")
        self.press("ArrowUp")
        self.assertEqual(self.page.evaluate("document.activeElement.dataset.action"), actions[-1])
        self.press("ArrowDown")
        self.assertEqual(self.page.evaluate("document.activeElement.dataset.action"), actions[0])
        self.page.evaluate("filmLabPreview.dispatch('duplicate', {id:'classic-chrome'})")
        self.press("ArrowUp")
        self.assertEqual(self.page.evaluate("document.activeElement.closest('[data-control]').dataset.control"), "save-actions")
        self.press("ArrowDown")
        self.assertEqual(self.page.evaluate("document.activeElement.closest('[data-control]').dataset.control"), "name")
        self.page.evaluate("filmLabPreview.dispatch('field', {key:'color',value:100})")
        for _ in range(20):
            if self.page.evaluate("document.activeElement.closest('[data-control]')?.dataset.control") == "color":
                break
            self.press("ArrowDown")
        self.assertEqual(self.page.evaluate("document.activeElement.closest('[data-control]').dataset.control"), "color")
        self.press("ArrowRight", 3)
        self.assertEqual(self.state()["draft"]["settings"]["color"], 100)
        self.page.evaluate("filmLabPreview.dispatch('field', {key:'color',value:0})")
        self.press("ArrowLeft", 3)
        self.assertEqual(self.state()["draft"]["settings"]["color"], 0)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=BASE_URL)
    parser.add_argument("--chromium", default=CHROMIUM)
    arguments = parser.parse_args()
    BASE_URL = arguments.url
    CHROMIUM = arguments.chromium
    print("Scope: browser prototype only; no camera, physical flash, capture or SD-card validation.")
    unittest.main(argv=[__file__, "-v"])

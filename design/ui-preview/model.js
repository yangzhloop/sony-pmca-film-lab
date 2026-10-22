(function (global) {
  "use strict";

  // UI-review state only. No action in this file controls a camera.
  var STORAGE_KEY = "sony-pmca-film-lab:ui-preview:v1";
  var nextId = 0;
  var defaults = {
    color: 70, tone: 100, shadows: 0, highlights: 0,
    wb: "auto", kelvin: 5500, ab: 0, gm: 0
  };
  var ranges = {
    color: [0, 100], tone: [0, 100], shadows: [-3, 3],
    highlights: [-3, 3], kelvin: [2500, 10000], ab: [-7, 7], gm: [-7, 7]
  };

  function clone(value) { return JSON.parse(JSON.stringify(value)); }
  function localized(value, lang) {
    return typeof value === "string" ? value : value[lang] || value.en;
  }
  function currentLook(lang) {
    return lang === "zh" ? "当前效果（未保存）" : "Current look (unsaved)";
  }
  function settings(values) {
    var result = clone(defaults);
    values = values || {};
    Object.keys(ranges).forEach(function (key) {
      var number = Number(values[key]);
      if (values[key] !== undefined && Number.isFinite(number)) {
        result[key] = Math.round(Math.max(ranges[key][0], Math.min(ranges[key][1], number)));
      }
    });
    if (values.wb === "auto" || values.wb === "kelvin") result.wb = values.wb;
    return result;
  }
  function builtin(id, en, zh, code, accent, mono, description, overrides) {
    return {
      id: id, origin: "builtin", revision: 1, name: { en: en, zh: zh },
      code: code, accent: accent, baseId: id, mono: mono,
      description: { en: description[0], zh: description[1] },
      settings: settings(overrides)
    };
  }
  function catalog() {
    return [
      builtin("neutral", "Neutral", "中性", "NT", "#b4b8bb", false,
        ["A clean starting point for your own recipe.", "自然中性的起点，适合创建自己的配方。"], { color: 0, tone: 0 }),
      builtin("classic-chrome", "Classic Chrome", "经典正片", "CC", "#9aa994", false,
        ["Muted color with a documentary character.", "低饱和色彩，带有纪实影像的质感。"]),
      builtin("portrait", "Soft Portrait", "柔和人像", "PT", "#cda5a0", false,
        ["Gentle color and softer highlights.", "柔和色彩与高光，适合人物。"], { color: 50, tone: 70, highlights: -1 }),
      builtin("negative", "Warm Negative", "暖调负片", "NG", "#c8ab74", false,
        ["Warm color with an easygoing negative-film feel.", "温暖松弛的负片风格。"], { shadows: 1, wb: "kelvin", kelvin: 5900, ab: 1 }),
      builtin("slide", "Rich Slide", "浓郁反转", "SL", "#8ba8bb", false,
        ["Rich color and confident contrast.", "浓郁色彩与鲜明反差。"], { color: 100, shadows: -1 }),
      builtin("cinema", "Soft Cinema", "柔和电影", "CN", "#a5afa4", false,
        ["Restrained color and a gentle tonal roll-off.", "克制的色彩与平缓的明暗过渡。"], { color: 50, tone: 70, highlights: -1 }),
      builtin("acros", "Soft Monochrome", "柔和黑白", "AC", "#bfc1bc", true,
        ["Quiet monochrome with gentle tonality.", "安静柔和的黑白层次。"], { tone: 70 }),
      builtin("deep-mono", "Deep Monochrome", "深调黑白", "DM", "#a7aaad", true,
        ["Deep shadows and strong monochrome structure.", "深邃阴影与鲜明的黑白结构。"], { shadows: -2, highlights: 1 })
    ];
  }
  function userFrom(base, id, name, overrides) {
    return {
      id: id, origin: "user", revision: 1, name: name, code: base.code,
      accent: base.accent, baseId: base.baseId, mono: base.mono,
      description: clone(base.description), settings: settings(overrides || base.settings)
    };
  }
  function nameError(value) {
    var name = typeof value === "string" ? value.trim() : "";
    if (!name) return "nameRequired";
    if (Array.from(name).length > 32) return "nameLong";
    return null;
  }

  function createModel(storage) {
    var storageFailed = false;
    if (arguments.length === 0) {
      try { storage = global.localStorage; } catch (error) { storageFailed = true; }
    }
    var bases = catalog();
    var users = [
      userFrom(bases[3], "user-weekend-gold", "Weekend Gold",
        Object.assign({}, bases[3].settings, { color: 85, tone: 80, kelvin: 6200, ab: 2 })),
      userFrom(bases[1], "user-rainy-corner", "雨后街角",
        Object.assign({}, bases[1].settings, { color: 55, shadows: -1, wb: "kelvin", kelvin: 4800, gm: 1 }))
    ];
    var state = {
      lang: "en", screen: "shoot", tab: "favorites", selectedId: "classic-chrome",
      records: bases.concat(users), favorites: ["classic-chrome", "user-weekend-gold"],
      active: null, draft: null, modal: null, notice: null
    };
    var subscribers = [];

    function find(id) { return state.records.find(function (record) { return record.id === id; }); }
    function isUser(record) { return record && record.origin === "user"; }
    function snapshot(record) {
      return {
        id: record.id, revision: record.revision, baseId: record.baseId,
        name: localized(record.name, state.lang), code: record.code,
        accent: record.accent, mono: record.mono, settings: clone(record.settings)
      };
    }
    state.active = snapshot(bases[1]);

    try {
      if (!storage || typeof storage.getItem !== "function") throw new Error("No storage");
      var raw = storage.getItem(STORAGE_KEY);
      if (raw) {
        var saved = JSON.parse(raw);
        if (saved && saved.version === 1) {
          state.lang = saved.lang === "zh" ? "zh" : "en";
          var seen = new Set(bases.map(function (base) { return base.id; }));
          var restored = [];
          if (Array.isArray(saved.users)) saved.users.forEach(function (record) {
            if (!record || record.origin !== "user" || typeof record.id !== "string" ||
                !record.id.startsWith("user-") || seen.has(record.id) || nameError(record.name)) return;
            var base = bases.find(function (item) { return item.id === record.baseId; });
            if (!base) return;
            var restoredRecord = userFrom(base, record.id, record.name.trim(), record.settings);
            restoredRecord.revision = Number.isInteger(record.revision) && record.revision > 0 ? record.revision : 1;
            restored.push(restoredRecord);
            seen.add(record.id);
          });
          state.records = bases.concat(restored);
          state.favorites = Array.isArray(saved.favorites) ? Array.from(new Set(saved.favorites.filter(function (id) {
            return typeof id === "string" && !!find(id);
          }))) : [];
          var active = saved.active;
          if (active && (active.id === null || find(active.id))) {
            var activeRecord = find(active.id);
            var snapshotBase = bases.find(function (item) { return item.id === active.baseId; }) ||
              (activeRecord && bases.find(function (item) { return item.id === activeRecord.baseId; })) || bases[0];
            state.active = {
              id: active.id,
              revision: Number.isInteger(active.revision) && active.revision > 0 ? active.revision : 1,
              baseId: snapshotBase.id,
              name: active.id === null ? currentLook(state.lang) :
                (activeRecord.origin === "builtin" ? localized(activeRecord.name, state.lang) :
                  (typeof active.name === "string" ? active.name : activeRecord.name)),
              code: snapshotBase.code,
              accent: snapshotBase.accent,
              mono: snapshotBase.mono,
              settings: settings(active.settings)
            };
          } else {
            state.active = snapshot(bases[1]);
          }
          state.selectedId = state.active.id || "classic-chrome";
        }
      }
    } catch (error) {
      storageFailed = true;
    }
    if (storageFailed) state.notice = "storageUnavailable";

    function persist() {
      try {
        if (!storage || typeof storage.setItem !== "function") throw new Error("No storage");
        storage.setItem(STORAGE_KEY, JSON.stringify({
          version: 1, lang: state.lang,
          users: state.records.filter(isUser), favorites: state.favorites, active: state.active
        }));
      } catch (error) { state.notice = "storageUnavailable"; }
    }
    function visible() {
      return state.records.filter(function (record) {
        if (state.tab === "favorites") return state.favorites.indexOf(record.id) !== -1;
        return record.origin === state.tab;
      });
    }
    function selectVisible() {
      var list = visible();
      if (!list.some(function (record) { return record.id === state.selectedId; })) {
        state.selectedId = list.length ? list[0].id : null;
      }
    }
    function libraryAfterDraft(id) {
      state.draft = null;
      state.modal = null;
      state.screen = "library";
      state.tab = "user";
      state.selectedId = id && find(id) ? id : null;
      selectVisible();
    }
    function newId() {
      var id;
      do { id = "user-" + Date.now().toString(36) + "-" + (++nextId).toString(36); } while (find(id));
      return id;
    }
    function emit() {
      subscribers.slice().forEach(function (fn) { fn(clone(state)); });
    }

    function dispatch(type, payload) {
      payload = payload || {};
      state.notice = null;
      var record = find(payload.id || state.selectedId);
      switch (type) {
        case "openLibrary":
          state.screen = "library";
          selectVisible();
          break;
        case "tab":
          if (["builtin", "favorites", "user"].indexOf(payload.tab) !== -1) {
            state.tab = payload.tab;
            selectVisible();
          }
          break;
        case "select":
          if (record) state.selectedId = record.id;
          break;
        case "detail":
          if (record) { state.selectedId = record.id; state.screen = "detail"; }
          break;
        case "use":
          if (record) {
            state.active = snapshot(record);
            state.selectedId = record.id;
            state.screen = "shoot";
            state.draft = null;
            state.modal = null;
            state.notice = "applied";
            persist();
          }
          break;
        case "favorite":
          if (record) {
            var index = state.favorites.indexOf(record.id);
            if (index === -1) state.favorites.push(record.id);
            else state.favorites.splice(index, 1);
            if (state.screen === "library") selectVisible();
            persist();
          }
          break;
        case "create":
          var base = bases.find(function (item) { return item.id === payload.baseId; }) || bases[0];
          state.draft = userFrom(base, newId(), state.lang === "zh" ? "新配方" : "New Recipe");
          state.screen = "editor";
          state.modal = null;
          break;
        case "saveCurrent":
          var currentBase = bases.find(function (item) { return item.id === state.active.baseId; }) || bases[0];
          state.draft = userFrom(currentBase, newId(), state.lang === "zh" ? "新配方" : "New Recipe", state.active.settings);
          state.draft.code = state.active.code;
          state.draft.accent = state.active.accent;
          state.draft.mono = state.active.mono;
          state.screen = "editor";
          state.modal = null;
          break;
        case "duplicate":
          if (record) {
            var suffix = state.lang === "zh" ? " 副本" : " Copy";
            var copiedName = Array.from(localized(record.name, state.lang)).slice(0, 32 - Array.from(suffix).length).join("") + suffix;
            state.draft = userFrom(record, newId(), copiedName);
            state.screen = "editor";
            state.modal = null;
          }
          break;
        case "edit":
          if (isUser(record)) {
            state.draft = clone(record);
            state.screen = "editor";
            state.modal = null;
          }
          break;
        case "field":
          if (!state.draft) break;
          if (payload.key === "name") {
            state.draft.name = typeof payload.value === "string" ? payload.value : "";
          } else if (payload.key === "baseId") {
            var chosen = bases.find(function (item) { return item.id === payload.value; });
            if (chosen) state.draft = userFrom(chosen, state.draft.id, state.draft.name);
          } else if (Object.prototype.hasOwnProperty.call(defaults, payload.key)) {
            if (payload.key === "color" && state.draft.mono) break;
            var fields = clone(state.draft.settings);
            fields[payload.key] = payload.value;
            state.draft.settings = settings(fields);
          }
          break;
        case "save":
          if (!state.draft) break;
          var error = nameError(state.draft.name);
          if (error) { state.notice = error; break; }
          var committed = clone(state.draft);
          committed.origin = "user";
          committed.name = committed.name.trim();
          var existing = state.records.findIndex(function (item) { return item.id === committed.id; });
          if (existing !== -1 && !isUser(state.records[existing])) break;
          committed.revision = existing === -1 ? 1 : state.records[existing].revision + 1;
          if (existing === -1) state.records.push(committed);
          else state.records[existing] = committed;
          libraryAfterDraft(committed.id);
          if (payload.use === true) {
            state.active = snapshot(committed);
            state.screen = "shoot";
            state.notice = "applied";
          } else state.notice = "saved";
          persist();
          break;
        case "rename":
          if (isUser(record)) state.modal = { type: "rename", id: record.id, value: record.name };
          break;
        case "renameValue":
          if (state.modal && state.modal.type === "rename") {
            state.modal.value = typeof payload.value === "string" ? payload.value : "";
          }
          break;
        case "confirmRename":
          if (!state.modal || state.modal.type !== "rename") break;
          var renamed = find(state.modal.id);
          if (!isUser(renamed)) { state.modal = null; break; }
          var renameError = nameError(state.modal.value);
          if (renameError) { state.notice = renameError; break; }
          renamed.name = state.modal.value.trim();
          if (state.active.id === renamed.id) state.active.name = renamed.name;
          state.modal = null;
          state.notice = "renamed";
          persist();
          break;
        case "delete":
          if (isUser(record)) state.modal = { type: "delete", id: record.id };
          break;
        case "confirmDelete":
          if (!state.modal || state.modal.type !== "delete") break;
          var deleted = find(state.modal.id);
          if (!isUser(deleted)) { state.modal = null; break; }
          state.records = state.records.filter(function (item) { return item.id !== deleted.id; });
          state.favorites = state.favorites.filter(function (id) { return id !== deleted.id; });
          if (state.active.id === deleted.id) {
            state.active.id = null;
            state.active.name = currentLook(state.lang);
          }
          libraryAfterDraft(null);
          state.notice = "deleted";
          persist();
          break;
        case "cancelModal":
          state.modal = null;
          break;
        case "back":
          if (state.modal) state.modal = null;
          else if (state.screen === "editor") state.modal = { type: "discard", id: state.draft ? state.draft.id : null };
          else if (state.screen === "detail") { state.screen = "library"; selectVisible(); }
          else if (state.screen === "library" || state.screen === "settings") state.screen = "shoot";
          break;
        case "halfShutter":
          if (state.screen === "editor") state.modal = { type: "discard", id: state.draft ? state.draft.id : null };
          else { state.screen = "shoot"; state.modal = null; }
          break;
        case "discard":
          libraryAfterDraft(state.draft && state.draft.id);
          break;
        case "settings":
          state.screen = "settings";
          break;
        case "language":
          if (payload.lang === "en" || payload.lang === "zh") {
            state.lang = payload.lang;
            var activeBase = find(state.active.id);
            if (activeBase && activeBase.origin === "builtin") state.active.name = localized(activeBase.name, state.lang);
            else if (state.active.id === null) state.active.name = currentLook(state.lang);
            persist();
          }
          break;
        case "dismissNotice":
          break;
      }
      emit();
      return clone(state);
    }

    return {
      getState: function () { return clone(state); },
      subscribe: function (fn) {
        if (typeof fn !== "function") throw new TypeError("Subscriber must be a function");
        subscribers.push(fn);
        return function () { subscribers = subscribers.filter(function (item) { return item !== fn; }); };
      },
      dispatch: dispatch
    };
  }

  global.FilmLabModel = { createModel: createModel };
})(window);

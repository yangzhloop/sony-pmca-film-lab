(function (global) {
  'use strict';

  // Original browser-prototype diagnostics. This module never reads a camera,
  // downloads a file, or sends a report. User-entered strings are not collected.
  var SCHEMA_VERSION = 1;
  var EVENT_LIMIT = 128;
  var MAX_REPORT_BYTES = 256 * 1024;
  var BASE_IDS = ['neutral', 'classic-chrome', 'portrait', 'negative', 'slide', 'cinema', 'acros', 'deep-mono'];
  var PAGES = ['shoot', 'library', 'detail', 'editor', 'settings', 'diagnostics', 'index', 'flash', 'camera', 'focus-mode', 'focus-area', 'language', 'report', 'about', 'licenses', 'developer', 'device', 'processing', 'keys', 'events'];
  var SCREENS = ['shoot', 'library', 'detail', 'editor', 'settings', 'diagnostics'];
  var DISPLAYS = ['info', 'clean', 'histogram', 'level'];
  var FLASH_MODES = ['front', 'slow', 'rear', 'off', 'on', 'auto'];
  var EVENT_TYPES = ['session.start', 'ui.open', 'ui.action', 'ui.field', 'ui.language', 'ui.display', 'ui.key', 'report.snapshot', 'report.download.requested', 'report.download.failed', 'simulated.capture', 'other'];
  var ACTIONS = ['openLibrary', 'tab', 'select', 'detail', 'use', 'favorite', 'create', 'saveCurrent', 'duplicate', 'edit', 'field', 'save', 'rename', 'renameValue', 'confirmRename', 'delete', 'confirmDelete', 'cancelModal', 'back', 'halfShutter', 'discard', 'settings', 'language', 'dismissNotice', 'open-page', 'open-report', 'report-download', 'flash-mode', 'flash-compensation', 'focus-mode', 'focus-area', 'display', 'menu', 'fn', 'disp', 'capture', 'other'];
  var FIELDS = ['name', 'baseId', 'color', 'tone', 'shadows', 'highlights', 'wb', 'kelvin', 'ab', 'gm', 'save-actions', 'flash-mode', 'flash-compensation', 'focus-mode', 'focus-area', 'mode', 'compensationEv'];
  var CONTROLS = FIELDS.concat(PAGES, ['up', 'down', 'left', 'right', 'center', 'menu', 'fn', 'disp', 'half', 'shutter', 'save', 'save-use', 'cancel', 'confirm', 'download', 'printable', 'tab', 'other']);
  var STATUSES = ['ready', 'created', 'requested', 'failed', 'cancelled', 'success', 'unavailable', 'simulated', 'unsupported', 'not-probed', 'storageUnavailable', 'nameRequired', 'nameLong', 'other'];
  var NUMERIC_SETTINGS = ['color', 'tone', 'shadows', 'highlights', 'kelvin', 'ab', 'gm'];

  function copy(value) { return JSON.parse(JSON.stringify(value)); }
  function member(value, allowed) { return typeof value === 'string' && allowed.indexOf(value) !== -1 ? value : null; }
  function number(value) { return typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= 1000000000 ? value : null; }
  function revision(value) { return Number.isInteger(value) && value > 0 && value <= 1000000000 ? value : null; }
  function bool(value) { return typeof value === 'boolean' ? value : null; }
  function object(value) { return value && typeof value === 'object' && !Array.isArray(value) ? value : {}; }
  function settings(value) {
    value = object(value);
    var result = {};
    NUMERIC_SETTINGS.forEach(function (key) { result[key] = number(value[key]); });
    result.wb = member(value.wb, ['auto', 'kelvin']);
    return result;
  }
  function flash(value) {
    if (typeof value === 'boolean') value = { animationActive: value };
    value = object(value);
    var compensation = number(value.compensationEv);
    return {
      source: 'browser-simulation',
      mode: member(value.mode, FLASH_MODES),
      compensationEv: compensation !== null && compensation >= -3 && compensation <= 3 ? compensation : null,
      animationActive: bool(value.animationActive)
    };
  }
  function byteLength(value) {
    if (typeof global.TextEncoder === 'function') return new global.TextEncoder().encode(value).length;
    return encodeURIComponent(value).replace(/%[A-F\d]{2}/gi, 'x').length;
  }
  function clock() {
    try {
      if (global.performance && typeof global.performance.now === 'function') return global.performance.now();
    } catch (error) {}
    return Date.now();
  }

  function create() {
    var events = [];
    var sequence = 0;
    var dropped = 0;
    var reportSequence = 0;
    var startedAt = new Date().toISOString();
    var startedClock = clock();
    var lastElapsed = 0;
    var aliases = new Map();

    // User IDs can be imported from browser storage, so never echo their raw
    // contents. Session aliases correlate records/events without exposing names.
    function recipeId(value) {
      if (typeof value !== 'string' || !value) return null;
      if (BASE_IDS.indexOf(value) !== -1) return value;
      if (!aliases.has(value)) {
        if (aliases.size >= 256) return 'user-overflow';
        aliases.set(value, 'user-' + String(aliases.size + 1).padStart(4, '0'));
      }
      return aliases.get(value);
    }
    function recipe(value, origin) {
      if (!value || typeof value !== 'object') return null;
      return {
        id: recipeId(value.id),
        revision: revision(value.revision),
        origin: member(value.origin || origin, ['builtin', 'user']),
        baseId: member(value.baseId, BASE_IDS),
        mono: bool(value.mono),
        settings: settings(value.settings)
      };
    }
    function safeDetails(input) {
      input = object(input);
      var result = {};
      var choices = {
        action: ACTIONS, control: CONTROLS, field: FIELDS,
        direction: ['up', 'down', 'left', 'right', 'center'],
        phase: ['down', 'up', 'press', 'repeat'],
        page: PAGES, screen: SCREENS, lang: ['en', 'zh'],
        tab: ['builtin', 'user', 'favorites'], displayMode: DISPLAYS,
        status: STATUSES, baseId: BASE_IDS,
        enumValue: ['auto', 'kelvin'].concat(FLASH_MODES)
      };
      Object.keys(choices).forEach(function (key) {
        var value = member(input[key], choices[key]);
        if (value !== null) result[key] = value;
      });
      if (typeof input.id === 'string') result.id = recipeId(input.id);
      if (revision(input.revision) !== null) result.revision = input.revision;
      if (typeof input.use === 'boolean') result.use = input.use;
      if (typeof input.repeat === 'boolean') result.repeat = input.repeat;
      else if (Number.isInteger(input.repeat) && input.repeat >= 0 && input.repeat <= 65535) result.repeat = input.repeat;
      NUMERIC_SETTINGS.concat(['numericValue']).forEach(function (key) {
        var value = number(input[key]);
        if (value !== null) result[key] = value;
      });
      if (input.settings && typeof input.settings === 'object') result.settings = settings(input.settings);
      if (input.flash !== undefined) result.flash = flash(input.flash);
      return result;
    }
    function record(type, details) {
      var elapsed = clock() - startedClock;
      lastElapsed = Math.max(lastElapsed, Number.isFinite(elapsed) ? Math.max(0, Math.round(elapsed)) : lastElapsed);
      var event = {
        sequence: ++sequence,
        elapsedMs: lastElapsed,
        type: member(type, EVENT_TYPES) || 'other',
        details: safeDetails(details)
      };
      if (events.length === EVENT_LIMIT) { events.shift(); dropped++; }
      events.push(event);
      return copy(event);
    }
    function getEvents() { return copy(events); }
    function snapshot(input, options) {
      var state = object(input);
      options = object(options);
      var records = Array.isArray(state.records) ? state.records : [];
      function find(id) { return id == null ? null : records.find(function (item) { return item && item.id === id; }) || null; }
      var activeRecord = find(state.active && state.active.id);
      var selected = find(state.selectedId);
      var modal = object(state.modal);
      return {
        schemaVersion: SCHEMA_VERSION,
        scope: 'browser-prototype',
        app: { version: '0.4', build: 'working-tree', sourceHash: null },
        device: {
          targetModel: 'Sony A7R II / ILCE-7RM2', actualModel: null,
          firmware: null, platformApi: null, androidApi: null,
          source: 'unavailable-in-browser'
        },
        ui: {
          page: member(options.page, PAGES), screen: member(state.screen, SCREENS),
          language: member(state.lang, ['en', 'zh']), tab: member(state.tab, ['builtin', 'user', 'favorites']),
          modal: member(modal.type, ['rename', 'delete', 'discard']),
          displayMode: member(options.displayMode, DISPLAYS)
        },
        recipes: {
          active: recipe(state.active, activeRecord && activeRecord.origin),
          savedActive: recipe(activeRecord), highlighted: recipe(selected), draft: recipe(state.draft)
        },
        flash: flash(options.flash),
        diagnostics: { eventLimit: EVENT_LIMIT, eventCount: events.length, droppedEventCount: dropped, lastSequence: sequence },
        storage: {
          recipePersistence: 'browser-localStorage-attempted',
          currentStorageError: state.notice === 'storageUnavailable',
          persistenceVerification: 'not-performed-by-diagnostics',
          eventPersistence: 'memory-only-current-session', cameraCard: 'unavailable'
        },
        camera: {
          source: 'browser-simulation', readback: 'unavailable', nativeCapabilities: 'unavailable',
          matrix: 'unavailable', curve: 'unavailable', capture: 'unavailable'
        },
        redaction: {
          recipeIds: 'built-in-ids-or-session-user-aliases',
          excluded: ['recipe-names', 'notes', 'text-input', 'printable-key-characters', 'messages', 'library-dump', 'browser-url', 'serial-number']
        }
      };
    }
    function createReport(state, options) {
      if (reportSequence >= 99999) throw new Error('Report sequence exhausted for this session');
      var id = 'FLR' + String(++reportSequence).padStart(5, '0');
      var filename = id + '.TXT';
      var retained = getEvents();
      var report = {
        schemaVersion: SCHEMA_VERSION,
        report: {
          id: id, filename: filename, createdAt: new Date().toISOString(),
          sessionStartedAt: startedAt, timeSource: 'browser-clock', encoding: 'UTF-8',
          maxBytes: MAX_REPORT_BYTES, phase: 'created-in-memory', downloadCompletion: 'not-observed'
        },
        snapshot: snapshot(state, options),
        reproduction: { steps: '', expected: '', actual: '' },
        eventAccounting: { retained: retained.length, droppedFromBuffer: dropped, omittedForReportCap: 0 },
        events: retained
      };
      function render() {
        return 'BROWSER PROTOTYPE — NOT A CAMERA REPORT\n' +
          'Film Lab UI diagnostics. Values describe browser simulation only.\n' +
          'No camera readback, native capability check, camera capture, or SD-card export was performed.\n\n' +
          JSON.stringify(report, null, 2) + '\n';
      }
      var text = render();
      while (byteLength(text) > MAX_REPORT_BYTES && report.events.length) {
        report.events.shift();
        report.eventAccounting.omittedForReportCap++;
        report.eventAccounting.retained = report.events.length;
        text = render();
      }
      if (byteLength(text) > MAX_REPORT_BYTES) throw new Error('Report exceeded its byte limit');
      return { filename: filename, text: text };
    }
    return Object.freeze({ record: record, getEvents: getEvents, snapshot: snapshot, createReport: createReport });
  }

  global.FilmLabDiagnostics = Object.freeze({ create: create });
})(typeof globalThis !== 'undefined' ? globalThis : this);

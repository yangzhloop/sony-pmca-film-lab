'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
function fixture() {
  const time = { value: 0 };
  const context = vm.createContext({ window: {}, TextEncoder, performance: { now: () => time.value } });
  vm.runInContext(fs.readFileSync(path.join(root, 'model.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(root, 'diagnostics.js'), 'utf8'), context);
  const memory = new Map();
  const model = context.window.FilmLabModel.createModel({
    getItem: key => memory.get(key) || null,
    setItem: (key, value) => memory.set(key, value)
  });
  return { model, diagnostics: context.FilmLabDiagnostics.create(), time };
}
function plain(value) { return JSON.parse(JSON.stringify(value)); }
function contents(report) { return JSON.parse(report.text.slice(report.text.indexOf('{'))); }
const options = { page: 'processing', displayMode: 'clean', flash: { mode: 'rear', compensationEv: 2 / 3, animationActive: false } };

test('redacts entered names, messages, key characters and injected IDs/enum fields', () => {
  const { model, diagnostics } = fixture();
  const secret = 'PRIVATE_秘密_abcdefghijklmnopqrstuvwxyz';
  const state = model.getState();
  const selected = state.records.find(item => item.id === state.selectedId);
  selected.name = secret;
  selected.description = { en: secret, zh: secret };
  selected.id = secret;
  selected.baseId = secret;
  selected.settings.color = secret;
  selected.settings.wb = secret;
  state.selectedId = secret;
  state.active.name = secret;
  state.active.notes = secret;
  state.draft = { ...selected, name: secret };
  state.modal = { type: 'rename', value: secret };
  diagnostics.record(secret, { action: secret, control: secret, id: secret, name: secret, message: secret, notes: secret, text: secret, key: secret, value: secret, settings: selected.settings });
  diagnostics.record('ui.key', { control: 'up', phase: 'down', repeat: true, key: secret, text: secret });
  for (const control of ['printable', 'tab', 'other']) {
    diagnostics.record('ui.key', { control, phase: 'press', key: secret, text: secret, character: secret });
  }
  const report = diagnostics.createReport(state, { page: secret, displayMode: secret, flash: { mode: secret, compensationEv: secret } });
  assert.ok(!report.text.includes(secret));
  const body = contents(report);
  assert.equal(body.events[0].type, 'other');
  assert.equal(body.events[0].details.id, body.snapshot.recipes.highlighted.id);
  assert.match(body.snapshot.recipes.highlighted.id, /^user-\d{4}$/);
  assert.equal(body.snapshot.recipes.highlighted.settings.color, null);
  assert.equal(body.snapshot.recipes.highlighted.settings.wb, null);
  assert.equal(body.snapshot.ui.page, null);
  assert.equal(body.snapshot.flash.mode, null);
  assert.deepEqual(body.events[1].details, { control: 'up', phase: 'down', repeat: true });
  assert.deepEqual(body.events.slice(2).map(event => event.details), [
    { control: 'printable', phase: 'press' },
    { control: 'tab', phase: 'press' },
    { control: 'other', phase: 'press' }
  ]);
});

test('retains 128 structured events and reports exact dropped/sequence counts', () => {
  const { model, diagnostics, time } = fixture();
  for (let index = 1; index <= 500; index++) {
    time.value = index * 3;
    diagnostics.record('ui.field', { field: 'tone', numericValue: index, message: 'discard this' });
  }
  const events = diagnostics.getEvents();
  assert.equal(events.length, 128);
  assert.equal(events[0].sequence, 373);
  assert.equal(events[127].sequence, 500);
  assert.equal(events[127].elapsedMs, 1500);
  const summary = diagnostics.snapshot(model.getState(), options).diagnostics;
  assert.deepEqual(plain(summary), { eventLimit: 128, eventCount: 128, droppedEventCount: 372, lastSequence: 500 });
  const report = contents(diagnostics.createReport(model.getState(), options));
  assert.deepEqual(report.eventAccounting, { retained: 128, droppedFromBuffer: 372, omittedForReportCap: 0 });
  time.value = 1;
  assert.equal(diagnostics.record('ui.open', { page: 'report' }).elapsedMs, 1500);
});

test('reports the same English schema for English and Chinese UI states', () => {
  const { model, diagnostics } = fixture();
  const english = plain(diagnostics.snapshot(model.getState(), options));
  model.dispatch('language', { lang: 'zh' });
  const chinese = plain(diagnostics.snapshot(model.getState(), options));
  assert.equal(english.ui.language, 'en');
  assert.equal(chinese.ui.language, 'zh');
  chinese.ui.language = 'en';
  assert.deepEqual(chinese, english);
  const report = diagnostics.createReport(model.getState(), options);
  assert.ok(report.text.startsWith('BROWSER PROTOTYPE — NOT A CAMERA REPORT\n'));
  const body = contents(report);
  assert.equal(body.snapshot.device.targetModel, 'Sony A7R II / ILCE-7RM2');
  assert.equal(body.snapshot.device.actualModel, null);
  assert.equal(body.snapshot.device.firmware, null);
  assert.equal(body.snapshot.device.platformApi, null);
  assert.equal(body.snapshot.app.version, '0.4');
  assert.equal(body.snapshot.app.build, 'working-tree');
  for (const key of ['readback', 'nativeCapabilities', 'matrix', 'curve', 'capture']) assert.equal(body.snapshot.camera[key], 'unavailable');
  assert.equal(body.snapshot.storage.cameraCard, 'unavailable');
  assert.equal(body.report.downloadCompletion, 'not-observed');
});

test('snapshots and event results are independent from inputs and internal data', () => {
  const { model, diagnostics } = fixture();
  const state = model.getState();
  const details = { settings: { color: 75, wb: 'auto' } };
  const emitted = diagnostics.record('ui.field', details);
  details.settings.color = 10;
  emitted.details.settings.color = 15;
  const firstEvents = diagnostics.getEvents();
  firstEvents[0].details.settings.color = 20;
  assert.equal(diagnostics.getEvents()[0].details.settings.color, 75);
  const snapshot = diagnostics.snapshot(state, options);
  const original = state.active.settings.color;
  state.active.settings.color = 999;
  assert.equal(snapshot.recipes.active.settings.color, original);
  snapshot.recipes.active.settings.color = -999;
  assert.notEqual(diagnostics.snapshot(model.getState(), options).recipes.active.settings.color, -999);
});

test('keeps active, saved, highlighted and draft revisions distinct', () => {
  const { model, diagnostics } = fixture();
  model.dispatch('use', { id: 'user-weekend-gold' });
  const original = model.getState().active.settings.color;
  model.dispatch('edit', { id: 'user-weekend-gold' });
  model.dispatch('field', { key: 'color', value: 35 });
  model.dispatch('save', { use: false });
  let snapshot = diagnostics.snapshot(model.getState(), options);
  assert.equal(snapshot.recipes.active.revision, 1);
  assert.equal(snapshot.recipes.active.settings.color, original);
  assert.equal(snapshot.recipes.savedActive.revision, 2);
  assert.equal(snapshot.recipes.savedActive.settings.color, 35);
  assert.equal(snapshot.recipes.highlighted.revision, 2);
  assert.equal(snapshot.recipes.active.id, snapshot.recipes.savedActive.id);
  model.dispatch('edit', { id: 'user-weekend-gold' });
  model.dispatch('field', { key: 'tone', value: 20 });
  snapshot = diagnostics.snapshot(model.getState(), options);
  assert.equal(snapshot.recipes.draft.revision, 2);
  assert.equal(snapshot.recipes.draft.settings.tone, 20);
  assert.notEqual(snapshot.recipes.draft.settings.tone, snapshot.recipes.savedActive.settings.tone);
  model.dispatch('discard');
  model.dispatch('delete', { id: 'user-weekend-gold' });
  model.dispatch('confirmDelete');
  snapshot = diagnostics.snapshot(model.getState(), options);
  assert.equal(snapshot.recipes.active.id, null);
  assert.equal(snapshot.recipes.active.settings.color, original);
  assert.equal(snapshot.recipes.savedActive, null);
});

test('report IDs, browser timestamps and UTF-8 byte bounds are explicit', () => {
  const { model, diagnostics } = fixture();
  for (let index = 0; index < 150; index++) {
    diagnostics.record('ui.action', {
      action: 'field', page: 'processing', screen: 'editor', control: 'shadows', direction: 'right',
      field: 'shadows', revision: 999999999, id: 'user-injected-private-' + index,
      settings: { color: 100, tone: 100, shadows: -3, highlights: 3, kelvin: 10000, ab: -7, gm: 7, wb: 'kelvin' },
      flash: options.flash
    });
  }
  const first = diagnostics.createReport(model.getState(), options);
  const second = diagnostics.createReport(model.getState(), options);
  assert.equal(first.filename, 'FLR00001.TXT');
  assert.equal(second.filename, 'FLR00002.TXT');
  assert.ok(Buffer.byteLength(first.text, 'utf8') <= 256 * 1024);
  assert.match(first.filename, /^[A-Z0-9]{8}\.TXT$/);
  const body = contents(first);
  assert.equal(body.report.id, 'FLR00001');
  assert.ok(Number.isFinite(Date.parse(body.report.createdAt)));
  assert.ok(Number.isFinite(Date.parse(body.report.sessionStartedAt)));
  assert.equal(body.report.encoding, 'UTF-8');
  assert.equal(body.report.maxBytes, 256 * 1024);
  assert.deepEqual(body.reproduction, { steps: '', expected: '', actual: '' });
  assert.equal(body.snapshot.flash.mode, 'rear');
  assert.equal(body.snapshot.flash.compensationEv, 2 / 3);
  assert.equal(body.snapshot.flash.source, 'browser-simulation');
  assert.equal(body.eventAccounting.droppedFromBuffer, 22);
  assert.equal(body.snapshot.redaction.recipeIds, 'built-in-ids-or-session-user-aliases');
});

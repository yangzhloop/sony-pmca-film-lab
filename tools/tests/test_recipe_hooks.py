"""Exercise integration anchors against the real pinned still APK's Smali.

These checks protect native dispatch and failure paths; camera behavior still
requires the hardware checklist. Cache preparation is documented in camera-build.
"""
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import diagnostic_hooks
import recipe_hooks

APP = diagnostic_hooks.APP
SOURCE = Path(os.environ.get('FILMLAB_REFERENCE_DECODED',
    '/workspace/.cache/film-lab/upstream/film-studio-photo-verified'))
PATCHED = [APP + 'PictureEffectPlus.smali', APP + 'shooting/camera/RicohHook.smali',
    'com/sony/imaging/app/base/shooting/camera/CameraSetting.smali',
    'com/sony/imaging/app/base/shooting/camera/executor/ShootingExecutor.smali',
    'com/sony/imaging/app/base/shooting/CaptureStateBase.smali',
    'com/sony/imaging/app/base/shooting/S1OffEEState.smali',
    APP + 'shooting/trigger/PictureEffectPlusS1OffEEStateKeyHandler.smali',
    'com/sony/imaging/app/fw/AppRoot.smali',
    'com/sony/imaging/app/base/shooting/widget/AppNameConstantView.smali']
PROTECTED = [
    'com/sony/imaging/app/base/shooting/trigger/S1OnEEStateKeyHandler.smali',
    'com/sony/imaging/app/base/shooting/camera/FlashController.smali',
    'com/sony/imaging/app/base/common/DisplayModeObserver.smali',
    'com/sony/imaging/app/base/shooting/camera/WhiteBalanceController.smali',
    'com/sony/imaging/app/base/shooting/camera/FocusModeController.smali',
    'com/sony/imaging/app/base/shooting/camera/FocusAreaController.smali',
    APP + 'shooting/EffectProcess.smali',
    APP + 'shooting/SingleProcess.smali',
    APP + 'shooting/PictureEffectEEState.smali',
    'com/sony/imaging/app/base/shooting/EEState.smali',
    APP + 'shooting/camera/RicohHook$Profile0_100.smali']
MENU_LAYOUT = 'com/sony/imaging/app/base/menu/layout/'
PROTECTED += [MENU_LAYOUT + name + '.smali' for name in (
    'SpecialScreenMenuLayout', 'SpecialScreenView', 'FocusModeSpecialScreenMenuLayout',
    'SubMenuLayout', 'SubMenuView', 'VerticalScrollMenuLayout', 'VerticalScrollView',
    'PageMenuLayout', 'ItemAlignedListView', 'AFFlexiblePositionMenuLayout', 'AFLocalPositionMenuLayout')]


def instructions(method):
    """Strip source/debug directives when checking the retained native branches."""
    return '\n'.join(line.strip() for line in method.splitlines()
                     if line.strip() and not line.strip().startswith(('.', '#')))


def walk_boot(method, factor, key=None, housing=False):
    """Follow real generated boot branches with inert native service stand-ins.

    This deliberately executes the Smali's switch/goto/if instructions rather
    than recreating the intended routing in Python. Unknown opcodes fail closed.
    Camera initialization is recorded, never performed by the host test.
    """
    lines = [line.strip().split(' #', 1)[0] for line in method.splitlines()]
    labels = {}
    for i, line in enumerate(lines):
        if line.startswith(':'):
            labels.setdefault(line, i)  # Later switch-table entries reference these labels.
    registers = {'p0': {'TAG': 'test'}, 'p1': {'bootFactor': factor, 'bootKey': key}}
    state = {'bootApp': 'APP_SHOOTING', 'menu': True, 'native': [], 'callbacks': [],
             'native_at_callback': []}
    result = None
    pc = 0
    for _ in range(1000):
        line = lines[pc]
        pc += 1
        if not line or line.startswith(('.', ':', '#')):
            continue
        op, _, args = line.partition(' ')
        if op == 'return-void':
            return state
        if op == 'const-string':
            register, value = args.split(', ', 1)
            registers[register] = value[1:-1]
        elif op.startswith('const'):
            register, value = args.split(', ')
            registers[register] = int(value, 0)
        elif op.startswith('move-result'):
            registers[args] = result
        elif op in ('iget', 'iget-object'):
            register, owner, field = args.split(', ')
            registers[register] = registers[owner][field.split('->')[1].split(':')[0]]
        elif op == 'sget-object':
            register, field = args.split(', ')
            registers[register] = state[field.split('->')[1].split(':')[0]]
        elif op == 'sput-object':
            register, field = args.split(', ')
            state[field.split('->')[1].split(':')[0]] = registers[register]
        elif op == 'sput-boolean':
            pass  # isIn_onShutdown does not affect routing.
        elif op == 'new-instance':
            register, kind = args.split(', ')
            registers[register] = kind
        elif op == 'packed-switch':
            register, label = args.split(', ')
            table = labels[label] + 1
            start = int(lines[table].split()[1], 0)
            targets = []
            while lines[table + 1 + len(targets)].startswith(':'):
                targets.append(lines[table + 1 + len(targets)])
            index = registers[register] - start
            if 0 <= index < len(targets):
                pc = labels[targets[index]]
        elif op == 'goto':
            pc = labels[args]
        elif op in ('if-eqz', 'if-nez'):
            register, label = args.split(', ')
            zero = not registers[register]
            if zero == (op == 'if-eqz'):
                pc = labels[label]
        elif op == 'if-ne':
            left, right, label = args.split(', ')
            if registers[left] != registers[right]:
                pc = labels[label]
        elif op.startswith('invoke-'):
            match = re.fullmatch(r'\{([^}]*)\}, (.*)', args)
            if not match:
                raise AssertionError('Unsupported invocation: ' + line)
            values = [registers[value] for value in match[1].split(', ')] if match[1] else []
            target = match[2]
            if 'Ljava/lang/String;->equals(' in target:
                result = values[0] == values[1]
            elif '->isHousingSupported()Z' in target:
                result = False
            elif '->getKeyStatus(' in target:
                result = {'valid': 1, 'status': 1} if housing else None
            elif '->setIsMenuStateAdd(' in target:
                state['menu'] = bool(values[0])
            elif '->startupRoute(' in target:
                state['callbacks'].append(tuple(values))
                state['native_at_callback'].append(list(state['native']))
            elif target.endswith('BaseApp;->onBoot(Lcom/sony/imaging/app/fw/BootFactor;)V'):
                state['native'].append('super.onBoot')
            elif target == 'Ljava/lang/Thread;->start()V':
                state['native'].append('executor.start')
            elif '->setPreference(' in target:
                state['native'].append(tuple(values[1:]))
            else:
                result = 'inert-service'
        else:
            raise AssertionError('Unhandled boot instruction: ' + line)
    raise AssertionError('Boot control flow did not return')


@unittest.skipUnless((SOURCE / 'smali').is_dir(), 'prepare the pinned still-photo reference first')
class RecipeHooksTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='film-lab-hook-test-')
        self.path = Path(self.tmp.name)
        for relative in PATCHED + PROTECTED:
            target = self.path / 'smali' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(SOURCE / 'smali' / relative, target)
        diagnostic_hooks.patch(self.path)

    def tearDown(self):
        self.tmp.cleanup()

    def read(self, relative):
        return (self.path / 'smali' / relative).read_text()

    def test_real_coefficients_are_transformed_before_observation_and_commit(self):
        receipt = recipe_hooks.patch(self.path)
        self.assertEqual(len(receipt['classes']), 6)
        hook = self.read(APP + 'shooting/camera/RicohHook.smali')
        apply = diagnostic_hooks._method(hook, 'applyHook(L' + APP +
            'shooting/camera/PictureEffectPlusController;Landroid/util/Pair;Ljava/lang/String;)Z')
        self.assertLess(apply.index('->matrix(Ljava/lang/String;[I)[I'), apply.index('->matrixRequested('))
        self.assertLess(apply.index('->matrixRequested('), apply.index('->setRGBMatrix([I)V'))
        self.assertLess(apply.index('->gamma(Ljava/lang/String;[B)[B'), apply.index('->gammaRequested('))
        self.assertLess(apply.index('->gammaRequested('), apply.index('->setExtendedGammaTable('))
        self.assertEqual(apply.count(recipe_hooks.BRIDGE + '->applyResult('), 6)
        strength = diagnostic_hooks._method(hook, 'getStrength()I')
        self.assertEqual(strength.count('->strength(I)I'), 2)
        self.assertLess(strength.index('->strength(I)I'), strength.index('->strengthObserved(I)V'))

    def test_rejected_gamma_releases_table_and_returns_failure(self):
        recipe_hooks.patch(self.path)
        hook = self.read(APP + 'shooting/camera/RicohHook.smali')
        guard = ('    if-nez v4, :filmlab_gamma_valid\n'
            '    invoke-virtual {v3}, Lcom/sony/scalar/hardware/CameraEx$GammaTable;->release()V\n'
            '    goto :cond_5\n    :filmlab_gamma_valid')
        self.assertIn(guard, hook)
        self.assertIn(':cond_5\n    const/4 v0, 0x0', hook)
        self.assertNotIn('    if-eqz v4, :cond_4', hook)

    def test_idle_entry_and_native_dispatch_are_preserved(self):
        before_main = self.read(APP + 'PictureEffectPlus.smali')
        before_root = self.read('com/sony/imaging/app/fw/AppRoot.smali')
        recipe_hooks.patch(self.path)
        main = self.read(APP + 'PictureEffectPlus.smali')
        create = diagnostic_hooks._method(main, 'onCreate(Landroid/os/Bundle;)V')
        self.assertLess(create.index('"native_on_create"'), create.index('invoke-super'))
        boot = diagnostic_hooks._method(main, 'onBoot(Lcom/sony/imaging/app/fw/BootFactor;)V')
        self.assertLess(boot.index('invoke-super'), boot.index('"native_on_boot"'))
        self.assertEqual(diagnostic_hooks._method(before_main, 'onResume()V'),
            diagnostic_hooks._method(main, 'onResume()V'))
        self.assertEqual(diagnostic_hooks._method(before_main, 'onShutdown()V'),
            diagnostic_hooks._method(main, 'onShutdown()V'))
        self.assertIn('invoke-super {p0, p1}, Lcom/sony/imaging/app/base/BaseApp;->dispatchKeyEvent', main)
        root = self.read('com/sony/imaging/app/fw/AppRoot.smali')
        self.assertEqual(diagnostic_hooks._method(before_root, 'dispatchKeyEvent(Landroid/view/KeyEvent;)Z'),
            diagnostic_hooks._method(root, 'dispatchKeyEvent(Landroid/view/KeyEvent;)Z'))
        destroy = diagnostic_hooks._method(root, 'onDestroy()V')
        self.assertIn('.method protected final onDestroy()V', destroy)
        self.assertIn('Landroid/os/Process;->killProcess(I)V', destroy)
        self.assertLess(destroy.index('->detach('), destroy.index('invoke-super'))
        idle = self.read('com/sony/imaging/app/base/shooting/S1OffEEState.smali')
        self.assertIn('->idleReady(Ljava/lang/Object;)V\n\n    return-void', idle)
        self.assertIn('->idleLeft(Ljava/lang/Object;)V', idle)

    def test_launcher_reaches_idle_without_changing_native_boot_routes(self):
        signature = 'onBoot(Lcom/sony/imaging/app/fw/BootFactor;)V'
        before = diagnostic_hooks._method(self.read(APP + 'PictureEffectPlus.smali'), signature)
        recipe_hooks.patch(self.path)
        after = diagnostic_hooks._method(self.read(APP + 'PictureEffectPlus.smali'), signature)
        for factor in (0, 1, 2, 99):
            for key in (None, 'KEY_PLAY_APO', 'KEY_PLAY_PON'):
                for housing in (False, True):
                    with self.subTest(factor=factor, key=key, housing=housing):
                        old = walk_boot(before, factor, key, housing)
                        new = walk_boot(after, factor, key, housing)
                        self.assertEqual(new['bootApp'], old['bootApp'])
                        self.assertEqual(new['native'], old['native'])
                        self.assertEqual(new['callbacks'], [(factor, old['bootApp'])])
                        self.assertEqual(new['native_at_callback'], [new['native']])
                        self.assertEqual(new['menu'], False if factor == 0 else old['menu'])
                        self.assertEqual(new['native'][0], 'super.onBoot')
                        playing = key in ('KEY_PLAY_APO', 'KEY_PLAY_PON')
                        self.assertEqual(new['bootApp'], 'APP_PLAY' if playing else 'APP_SHOOTING')
                        self.assertEqual('executor.start' in new['native'], not playing and not housing)
        # A normal launcher formerly entered ApplicationTop; it now delegates
        # to the untouched native EE state after performing the same setup.
        self.assertTrue(walk_boot(before, 0)['menu'])
        self.assertFalse(walk_boot(after, 0)['menu'])

    def test_launcher_patch_rejects_changed_boot_switch_anchor(self):
        path = self.path / 'smali' / (APP + 'PictureEffectPlus.smali')
        source = path.read_text()
        source = source.replace(':pswitch_0\n    invoke-static {v5}',
                                ':pswitch_0\n    invoke-static {v6}', 1)
        path.write_text(source)
        with self.assertRaises(ValueError):
            recipe_hooks.patch(self.path)

    def test_capture_flash_disp_and_source_arrays_are_unchanged(self):
        before = {name: self.read(name) for name in PROTECTED}
        recipe_hooks.patch(self.path)
        self.assertEqual(before, {name: self.read(name) for name in PROTECTED})
        name = self.read('com/sony/imaging/app/base/shooting/widget/AppNameConstantView.smali')
        self.assertIn('->styleAppName(Ljava/lang/Object;)V', name)
        self.assertNotIn('applyTree', name)

    def test_native_menu_wrap_paths_are_retained(self):
        recipe_hooks.patch(self.path)
        # Sony's special-screen controls (focus, flash and ISO) already wrap.
        # Check the actual edge branches, then protect their complete classes
        # above so the Film Lab patch cannot accidentally replace native logic.
        view = self.read(MENU_LAYOUT + 'SpecialScreenView.smali')
        down = instructions(diagnostic_hooks._method(view, 'moveNext()Z'))
        up = instructions(diagnostic_hooks._method(view, 'movePrevious()Z'))
        owner = 'L' + MENU_LAYOUT + 'SpecialScreenView;'
        self.assertIn('if-ne v0, v1, :cond_3\nconst/4 v0, 0x0\niput v0, p0, '
                      + owner + '->mSelectedPositionAD:I', down)
        self.assertIn('if-nez v0, :cond_3\niget v0, p0, ' + owner + '->mItemCount:I\n'
                      'add-int/lit8 v0, v0, -0x1\niput v0, p0, '
                      + owner + '->mSelectedPositionAD:I', up)
        # Longer menus use the same wrap via rebuilding the visible window.
        self.assertIn('if-ne v0, v1, :cond_0\ninvoke-direct {p0, v2}, '
                      + owner + '->detachAllAndSetupViews(Z)V', down)
        self.assertIn('if-nez v0, :cond_0\ninvoke-direct {p0, v2}, '
                      + owner + '->detachAllAndSetupViews(Z)V', up)
        layout = self.read(MENU_LAYOUT + 'SpecialScreenMenuLayout.smali')
        for key, move in [('Up', 'Previous'), ('Down', 'Next')]:
            self.assertIn('->move' + move + '()Z',
                          diagnostic_hooks._method(layout, 'pushed' + key + 'Key()I'))

        # Sony's native Main1 page list handles physical Up/Down itself and
        # jumps to the opposite edge with setSelectionFromTop (keeps it visible).
        page = instructions(diagnostic_hooks._method(
            self.read(MENU_LAYOUT + 'ItemAlignedListView.smali'),
            'onKeyDown(ILandroid/view/KeyEvent;)Z'))
        self.assertIn(':cond_5\nadd-int/lit8 v10, v7, -0x1', page)
        self.assertIn(':cond_9\nconst/4 v3, 0x0\ninvoke-virtual {p0, v3, v1}, '
                      'L' + MENU_LAYOUT + 'ItemAlignedListView;->setSelectionFromTop(II)V', page)
        self.assertIn('if-lez v3, :cond_5', page)
        self.assertIn('if-ge v3, v2, :cond_9', page)

        # Exposure-style submenu wheels build circular neighbor links instead
        # of clamping at the first/last adapter position.
        for name in ('SubMenuView', 'VerticalScrollView'):
            ring = instructions(diagnostic_hooks._method(
                self.read(MENU_LAYOUT + name + '.smali'), 'initMenuItemViewList()V'))
            self.assertIn('if-nez v0, :cond_3\niget v5, p0, L' + MENU_LAYOUT + name
                          + ';->mItemCount:I\nadd-int/lit8 v3, v5, -0x1', ring)
            self.assertIn('if-ne v0, v5, :cond_4\nmove v2, v4', ring)
            self.assertIn('invoke-direct {v1, p0, v0, v3, v2}', ring)

    def test_native_focus_routes_keep_sony_options_and_position_editors(self):
        recipe_hooks.patch(self.path)
        menu = ET.parse(SOURCE / 'assets/MenuData.xml').getroot()
        nodes = {node.get('ItemId'): node for node in menu.iter() if node.get('ItemId')}
        expected = {
            'FocusSelection': ('ID_FOCUSMODESPECIALSCREENMENULAYOUT', 'FocusModeController'),
            'FocusArea': ('ID_SPECIALSCREENMENULAYOUT', 'FocusAreaController'),
            'FlashMode': ('ID_SPECIALSCREENMENULAYOUT', 'FlashController'),
        }
        for item_id, (layout, controller) in expected.items():
            node = nodes[item_id]
            self.assertEqual(node.get('NextMenuID'), layout)
            self.assertEqual(node.get('ConfigClass'),
                             'com.sony.imaging.app.base.shooting.camera.' + controller)
            self.assertEqual(node.get('ExecType'), 'NEXT_LAYOUT_WITHOUT_SET')
        # Focus-mode choices inherit the same wrapping SpecialScreen handlers.
        focus = self.read(MENU_LAYOUT + 'FocusModeSpecialScreenMenuLayout.smali')
        self.assertIn('.super L' + MENU_LAYOUT + 'SpecialScreenMenuLayout;', focus)
        self.assertNotRegex(focus, r'(?m)^\.method .* pushed(?:Up|Down)Key\(')
        self.assertEqual(nodes['FocusArea_flex-spot'].get('NextMenuID'), 'ID_AFFLEXIBLEPOSITIONMENULAYOUT')
        self.assertEqual(nodes['FocusArea_local'].get('NextMenuID'), 'ID_AFLOCALPOSITIONMENULAYOUT')
        # No Film Lab substitution of supported option lists or focus-position
        # spatial controls: these remain the original Sony implementations.
        for relative in ('com/sony/imaging/app/base/shooting/camera/FocusModeController.smali',
                         'com/sony/imaging/app/base/shooting/camera/FocusAreaController.smali',
                         MENU_LAYOUT + 'AFFlexiblePositionMenuLayout.smali',
                         MENU_LAYOUT + 'AFLocalPositionMenuLayout.smali'):
            self.assertEqual(self.read(relative), (SOURCE / 'smali' / relative).read_text())

    def test_duplicate_or_changed_upstream_is_rejected(self):
        recipe_hooks.patch(self.path)
        with self.assertRaisesRegex(ValueError, 'already present'):
            recipe_hooks.patch(self.path)
        # The parser itself rejects drift rather than applying at an approximate anchor.
        with self.assertRaisesRegex(ValueError, 'exactly one method'):
            diagnostic_hooks._method(self.read(APP + 'PictureEffectPlus.smali'), 'onCreate()V')


if __name__ == '__main__':
    unittest.main()

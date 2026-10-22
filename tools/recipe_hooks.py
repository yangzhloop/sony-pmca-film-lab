"""Checked Film Lab UI/recipe integration for the pinned still-photo APK.

Run after diagnostic_hooks.patch. Sony retains camera ownership, native menu
transitions, capture, playback and DISP. Hooks are deliberately limited to the
app Activity, its idle key handler, S1OffEE lifecycle, and Ricoh coefficients.
"""
from pathlib import Path
import hashlib
import re

from diagnostic_hooks import APP, _method, _once

BRIDGE = 'Lcom/filmlab/app/ShootingBridge;'
DIAGNOSTICS = 'Lcom/filmlab/diagnostics/DiagnosticsBridge;'


def patch(decoded: Path):
    decoded = Path(decoded)
    changes = []

    def read(relative):
        return (decoded / 'smali' / relative).read_text(encoding='utf-8')

    def write(relative, before, after, methods):
        if before == after:
            raise ValueError('Recipe patch made no change: ' + relative)
        (decoded / 'smali' / relative).write_text(after, encoding='utf-8')
        changes.append({'class': relative, 'methods': methods,
                        'before_sha256': hashlib.sha256(before.encode()).hexdigest(),
                        'after_sha256': hashlib.sha256(after.encode()).hexdigest()})

    main_path = APP + 'PictureEffectPlus.smali'
    text = read(main_path)
    if BRIDGE in text:
        raise ValueError('Recipe hooks already present')
    method = _method(text, 'onCreate(Landroid/os/Bundle;)V')
    # This records monotonic time only, so it can precede Context/Sony setup.
    new = _once(method, '    .prologue', '    .prologue\n\n    const-string v0, "native_on_create"\n'
                '    invoke-static {v0}, ' + DIAGNOSTICS + '->milestone(Ljava/lang/String;)V')
    new = _once(new, '    return-void',
                '    invoke-static {p0}, ' + BRIDGE + '->attach(Landroid/app/Activity;)V\n\n    return-void')
    changed = _once(text, method, new)
    # SystemReady may synchronously boot from super.onCreate, before the outer
    # onCreate returns. Attach once at boot entry as well; Java attach is idempotent.
    method = _method(changed, 'onBoot(Lcom/sony/imaging/app/fw/BootFactor;)V')
    new = _once(method, '    .prologue', '    .prologue\n\n    invoke-static {p0}, ' + BRIDGE +
                '->attach(Landroid/app/Activity;)V')
    new = _once(new, '    const-string v3, "\\u80f6\\u7247\\u5de5\\u574a"',
                '    const-string v3, "Film Lab"')
    # Launcher startup otherwise enters the inherited ApplicationTop picker
    # before S1OffEE ever resumes. Keep the native boot branches and initialize
    # the same camera/executor, but let Sony enter its normal idle state first.
    launcher_menu = ('    :pswitch_0\n    invoke-static {v5}, L' + APP +
                     'shooting/PictureEffectEEState;->setIsMenuStateAdd(Z)V')
    new = _once(new, launcher_menu, launcher_menu.replace('{v5}', '{v6}'))
    # bootApp has now been resolved by Sony's playback/housing checks. Java
    # arms its first-idle library only for an ordinary launcher shooting boot.
    new = _once(new, '    return-void',
                '    iget v0, p1, Lcom/sony/imaging/app/fw/BootFactor;->bootFactor:I\n'
                '    sget-object v1, L' + APP + 'PictureEffectPlus;->bootApp:Ljava/lang/String;\n'
                '    invoke-static {v0, v1}, ' + BRIDGE + '->startupRoute(ILjava/lang/String;)V\n\n'
                '    const-string v0, "native_on_boot"\n'
                '    invoke-static {v0}, ' + DIAGNOSTICS + '->milestone(Ljava/lang/String;)V\n\n    return-void')
    changed = _once(changed, method, new)
    if re.search(r'^\.method .* dispatchKeyEvent\(', changed, re.M):
        raise ValueError('Unexpected existing Activity dispatch override')
    changed += '''
.method public dispatchKeyEvent(Landroid/view/KeyEvent;)Z
    .locals 1

    invoke-static {p1}, ''' + BRIDGE + '''->dispatch(Landroid/view/KeyEvent;)Z
    move-result v0
    if-eqz v0, :filmlab_native_dispatch
    return v0

    :filmlab_native_dispatch
    invoke-super {p0, p1}, Lcom/sony/imaging/app/base/BaseApp;->dispatchKeyEvent(Landroid/view/KeyEvent;)Z
    move-result v0
    return v0
.end method
'''
    write(main_path, text, changed, ['onCreate', 'onBoot', 'dispatchKeyEvent'])

    # Sony's final onDestroy kills this process, so it cannot be overridden in
    # the app Activity. Release overlay callbacks before the inherited cleanup.
    root_path = 'com/sony/imaging/app/fw/AppRoot.smali'
    text = read(root_path)
    method = _method(text, 'onDestroy()V')
    if '.method protected final onDestroy()V' not in method:
        raise ValueError('Unexpected AppRoot destruction contract')
    new = _once(method, '    .prologue', '    .prologue\n\n    invoke-static {p0}, ' + BRIDGE +
                '->detach(Landroid/app/Activity;)V')
    write(root_path, text, _once(text, method, new), ['onDestroy'])

    name_path = 'com/sony/imaging/app/base/shooting/widget/AppNameConstantView.smali'
    text = read(name_path)
    method = _method(text, 'onAttachedToWindow()V')
    new = _once(method, '    return-void',
                '    invoke-static {p0}, ' + BRIDGE + '->styleAppName(Ljava/lang/Object;)V\n\n    return-void')
    write(name_path, text, _once(text, method, new), ['onAttachedToWindow'])

    # The overlay is available only while the native camera state is idle EE.
    state_path = 'com/sony/imaging/app/base/shooting/S1OffEEState.smali'
    text = read(state_path)
    method = _method(text, 'onResume()V')
    new = _once(method, '    return-void',
                '    invoke-static/range {p0 .. p0}, ' + BRIDGE + '->idleReady(Ljava/lang/Object;)V\n\n    return-void')
    changed = _once(text, method, new)
    method = _method(changed, 'onPause()V')
    new = _once(method, '    .prologue',
                '    .prologue\n\n    invoke-static/range {p0 .. p0}, ' + BRIDGE + '->idleLeft(Ljava/lang/Object;)V')
    changed = _once(changed, method, new)
    write(state_path, text, changed, ['onResume', 'onPause'])

    # Center is the inherited app's recipe shortcut. Fn opens the same library;
    # MENU opens Film Lab Settings. All other native key methods are inherited.
    key_path = APP + 'shooting/trigger/PictureEffectPlusS1OffEEStateKeyHandler.smali'
    text = read(key_path)
    method = _method(text, 'pushedCenterKey()I')
    new = _once(method, '    .prologue', '''    .prologue
    const-string v0, "library"
    invoke-static {v0}, ''' + BRIDGE + '''->open(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :filmlab_center_fallback
    const/4 v0, 0x1
    return v0

    :filmlab_center_fallback''')
    changed = _once(text, method, new)
    for name, page in [('pushedFnKey', 'library'), ('pushedMenuKey', 'settings')]:
        if re.search(r'^\.method .* ' + name + r'\(', changed, re.M):
            raise ValueError('Unexpected idle key override: ' + name)
        changed += '''
.method public ''' + name + '''()I
    .locals 1

    const-string v0, "''' + page + '''"
    invoke-static {v0}, ''' + BRIDGE + '''->open(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :filmlab_key_fallback
    const/4 v0, 0x1
    return v0

    :filmlab_key_fallback
    invoke-super {p0}, Lcom/sony/imaging/app/base/shooting/trigger/S1OffEEStateKeyHandler;->''' + name + '''()I
    move-result v0
    return v0
.end method
'''
    write(key_path, text, changed, ['pushedCenterKey', 'pushedFnKey', 'pushedMenuKey'])

    hook_path = APP + 'shooting/camera/RicohHook.smali'
    text = read(hook_path)
    hook = 'L' + APP + 'shooting/camera/RicohHook;'
    signature = 'applyHook(L' + APP + 'shooting/camera/PictureEffectPlusController;Landroid/util/Pair;Ljava/lang/String;)Z'
    method = _method(text, signature)
    matrix = ('    invoke-static {p2}, ' + hook + '->getRGBMatrix(Ljava/lang/String;)[I\n\n'
              '    move-result-object v3')
    new = _once(method, matrix, matrix + '\n\n    invoke-static {p2, v3}, ' + BRIDGE +
                '->matrix(Ljava/lang/String;[I)[I\n    move-result-object v3')
    gamma = ('    invoke-static {p2}, ' + hook + '->getGammaBytes(Ljava/lang/String;)[B\n\n'
             '    move-result-object v4')
    new = _once(new, gamma, gamma + '\n\n    invoke-static {p2, v4}, ' + BRIDGE +
                '->gamma(Ljava/lang/String;[B)[B\n    move-result-object v4')
    # A rejected transformed curve cannot silently leave the previous curve active
    # while reporting success. Release the native GammaTable on this failure path.
    guard = '    if-eqz v4, :cond_4'
    new = _once(new, guard, '''    if-nez v4, :filmlab_gamma_valid
    invoke-virtual {v3}, Lcom/sony/scalar/hardware/CameraEx$GammaTable;->release()V
    goto :cond_5
    :filmlab_gamma_valid''')
    observation = '    invoke-static {p2, v0}, ' + DIAGNOSTICS + '->applyResult(Ljava/lang/String;Z)V'
    if new.count(observation) != 6:
        raise ValueError('Diagnostic apply result paths changed')
    new = new.replace(observation, '    invoke-static {p2, v0}, ' + BRIDGE +
                      '->applyResult(Ljava/lang/String;Z)V\n\n' + observation)
    changed = _once(text, method, new)
    method = _method(changed, 'getStrength()I')
    observation = '    invoke-static {v0}, ' + DIAGNOSTICS + '->strengthObserved(I)V'
    if method.count(observation) != 2:
        raise ValueError('Diagnostic strength paths changed')
    new = method.replace(observation, '    invoke-static {v0}, ' + BRIDGE +
                         '->strength(I)I\n    move-result v0\n\n' + observation)
    changed = _once(changed, method, new)
    method = _method(changed, 'getFilterName(Ljava/lang/String;)Ljava/lang/String;')
    if method.count('    return-object v0') != 22:
        raise ValueError('Unexpected inherited filter-name return paths')
    new = method.replace('    return-object v0', '    invoke-static {p0, v0}, ' + BRIDGE +
                         '->displayName(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;\n'
                         '    move-result-object v0\n    return-object v0')
    changed = _once(changed, method, new)
    write(hook_path, text, changed, ['applyHook', 'getStrength', 'getFilterName'])

    return {
        'recipe_hooks_version': 3,
        'classes': changes,
        'ui_entry_state': 'S1OffEE',
        'launcher_entry': 'native idle then Film Lab library; inherited picker suppressed',
        'idle_shortcuts': {'Fn': 'library', 'Center': 'library', 'MENU': 'settings'},
        'native_dispatch_fallback': True,
        'camera_ownership': 'inherited Sony CameraSetting and state machine',
        'profile_source_strength': 100,
        'processing': 'independent 1/1024 RGB matrix and 1024-point tone curve',
        'white_balance': 'native controller support validation and option setters',
        'flash_controls_changed': False,
        'disp_controller_changed': False,
        'capture_algorithm_changed': False,
        'saved_image_verified': False,
    }

"""Narrow, checked observer hooks for the pinned Film Studio still-photo build.

No shooting key handler, DISP controller, flash setter, camera opener, or capture
algorithm is replaced. The helpers themselves catch failures before returning.
"""
from pathlib import Path
import hashlib
import re

BRIDGE = 'Lcom/filmlab/diagnostics/DiagnosticsBridge;'
APP = 'com/yuki/imaging/app/pictureeffectplus/'


def _method(text, signature):
    matches = list(re.finditer(r'^\.method [^\n]* ' + re.escape(signature) + r'\n[\s\S]*?^\.end method', text, re.M))
    if len(matches) != 1:
        raise ValueError('Expected exactly one method: ' + signature)
    return matches[0].group()


def _once(text, old, new):
    if text.count(old) != 1:
        raise ValueError('Pinned hook anchor changed: ' + old[:120])
    return text.replace(old, new, 1)


def patch(decoded: Path):
    decoded = Path(decoded)
    changes = []

    def write(relative, before, after, methods):
        if before == after:
            raise ValueError('Observer patch made no change: ' + relative)
        path = decoded / 'smali' / relative
        path.write_text(after, encoding='utf-8')
        changes.append({'class': relative, 'methods': methods,
                        'before_sha256': hashlib.sha256(before.encode()).hexdigest(),
                        'after_sha256': hashlib.sha256(after.encode()).hexdigest()})

    main_path = APP + 'PictureEffectPlus.smali'
    text = (decoded / 'smali' / main_path).read_text(encoding='utf-8')
    method = _method(text, 'onCreate(Landroid/os/Bundle;)V')
    anchor = '    invoke-super {p0, p1}, Lcom/sony/imaging/app/base/BaseApp;->onCreate(Landroid/os/Bundle;)V'
    new = _once(method, anchor, anchor + '\n\n    invoke-static {p0}, ' + BRIDGE + '->init(Landroid/content/Context;)V')
    write(main_path, text, _once(text, method, new), ['onCreate'])

    hook_path = APP + 'shooting/camera/RicohHook.smali'
    hook = 'L' + APP + 'shooting/camera/RicohHook;'
    text = (decoded / 'smali' / hook_path).read_text(encoding='utf-8')
    signature = 'applyHook(L' + APP + 'shooting/camera/PictureEffectPlusController;Landroid/util/Pair;Ljava/lang/String;)Z'
    method = _method(text, signature)
    matrix = ('    invoke-static {p2}, ' + hook + '->getRGBMatrix(Ljava/lang/String;)[I\n\n'
              '    move-result-object v3')
    new = _once(method, matrix, matrix + '\n\n    invoke-static {p2, v3}, ' + BRIDGE + '->matrixRequested(Ljava/lang/String;[I)V')
    gamma = ('    invoke-static {p2}, ' + hook + '->getGammaBytes(Ljava/lang/String;)[B\n\n'
             '    move-result-object v4')
    new = _once(new, gamma, gamma + '\n\n    invoke-static {p2, v4}, ' + BRIDGE + '->gammaRequested(Ljava/lang/String;[B)V')
    returns = list(re.finditer(r'^    return v0$', new, re.M))
    if len(returns) != 6:
        raise ValueError('Unexpected applyHook return paths: ' + str(len(returns)))
    new = re.sub(r'^    return v0$', '    invoke-static {p2, v0}, ' + BRIDGE + '->applyResult(Ljava/lang/String;Z)V\n\n    return v0', new, flags=re.M)
    changed = _once(text, method, new)
    strength = _method(changed, 'getStrength()I')
    # Every return observes the exact selected integer; do not recalculate it.
    if not re.search(r'^    return v\d+$', strength, re.M):
        raise ValueError('No strength return found')
    new_strength = re.sub(r'^    return (v\d+)$', lambda match: '    invoke-static {' + match.group(1) + '}, ' + BRIDGE + '->strengthObserved(I)V\n\n    return ' + match.group(1), strength, flags=re.M)
    changed = _once(changed, strength, new_strength)
    write(hook_path, text, changed, ['applyHook', 'getStrength'])

    camera_path = 'com/sony/imaging/app/base/shooting/camera/CameraSetting.smali'
    text = (decoded / 'smali' / camera_path).read_text(encoding='utf-8')
    method = _method(text, 'getCameraParameterPair()Landroid/util/Pair;')
    if 'Landroid/hardware/Camera;->getParameters()' not in method:
        raise ValueError('Native parameter method no longer proves a camera readback')
    anchor = '    monitor-exit p0\n\n    return-object v2'
    new = _once(method, anchor, '    monitor-exit p0\n\n    invoke-static {v2}, ' + BRIDGE + '->observeParameters(Ljava/lang/Object;)V\n\n    return-object v2')
    changed = _once(text, method, new)
    method = _method(changed, 'onError(ILcom/sony/scalar/hardware/CameraEx;)V')
    locals_line = re.search(r'^    \.locals \d+$', method, re.M)
    if locals_line is None:
        raise ValueError('Unexpected camera-error register declaration')
    new = _once(method, locals_line.group(), locals_line.group() + '\n\n    invoke-static {p1}, ' + BRIDGE + '->cameraError(I)V')
    changed = _once(changed, method, new)
    write(camera_path, text, changed, ['getCameraParameterPair', 'onError'])

    # Capture request and shutter callback are observations, not saved-file proof.
    for relative, signature, bridge_method in [
        ('com/sony/imaging/app/base/shooting/camera/executor/ShootingExecutor.smali',
         'takePicture(I)V', 'captureRequested'),
        ('com/sony/imaging/app/base/shooting/CaptureStateBase.smali',
         'onShuttered(ILcom/sony/scalar/hardware/CameraEx;)V', 'shutterCallback'),
    ]:
        text = (decoded / 'smali' / relative).read_text(encoding='utf-8')
        method = _method(text, signature)
        prologue = '    .prologue'
        new = _once(method, prologue, prologue + '\n\n    invoke-static/range {p1 .. p1}, ' + BRIDGE + '->' + bridge_method + '(I)V')
        write(relative, text, _once(text, method, new), [signature])

    return {'observer_version': 1, 'classes': changes,
            'readback_source': 'existing native Camera.getParameters result',
            'additional_camera_requests': 0,
            'key_handlers_changed': False, 'disp_controller_changed': False,
            'flash_controls_changed': False, 'saved_image_verified': False}

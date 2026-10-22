"""Build an offline, single-file preview using only Python's standard library."""
from pathlib import Path
import base64

root = Path(__file__).resolve().parent
css = (root / 'styles.css').read_text(encoding='utf-8')
scene = base64.b64encode((root / 'scene.png').read_bytes()).decode('ascii')
css = css.replace("url('scene.png')", "url('data:image/png;base64," + scene + "')")
script_names = ['i18n.js', 'model.js', 'diagnostics.js', 'ui.js']
scripts = '\n'.join((root / name).read_text(encoding='utf-8') for name in script_names)
html = (root / 'index.html').read_text(encoding='utf-8')
html = html.replace('<link rel="stylesheet" href="styles.css">', '<style>' + css + '</style>')
script_tags = ''.join('<script src="' + name + '"></script>' for name in script_names)
if script_tags not in html:
    raise ValueError('Entry-point scripts do not match the portable bundle manifest')
html = html.replace(script_tags, '<script>' + scripts.replace('</script', '<\\/script') + '</script>')
(root / 'preview.html').write_text(html, encoding='utf-8')
print('Portable preview written:', root / 'preview.html')

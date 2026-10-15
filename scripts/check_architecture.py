from pathlib import Path
import re
root = Path('app/src/main/java/br/com/lotobot/tunnel')
failures = []
for layer in ('domain', 'application'):
    for path in (root / layer).rglob('*.kt'):
        for imp in re.findall(r'^import (.+)', path.read_text(), re.M):
            if imp.startswith(('android.', 'androidx.', 'com.jcraft.', 'org.bouncycastle.')) or any(f'.{x}.' in imp for x in ('infrastructure', 'presentation', 'service', 'di')):
                failures.append(f'{path}: {imp}')
            if layer == 'domain' and '.application.' in imp:
                failures.append(f'{path}: {imp}')
if failures:
    raise SystemExit('\n'.join(failures))
print('Arquitetura: domain e application sem dependências externas proibidas.')

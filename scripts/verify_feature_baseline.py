"""Fail CI when a consolidated feature group disappears from the source baseline."""
import json
import pathlib

root = pathlib.Path(__file__).resolve().parents[1]
baseline = json.loads((root / 'D2_FEATURE_BASELINE.json').read_text())
for name in baseline['required_source_files']:
    assert (root / name).is_file(), 'Missing consolidated feature source: ' + name
for name, tokens in baseline['required_wiring'].items():
    text = (root / name).read_text()
    for token in tokens:
        assert token in text, 'Missing feature wiring: ' + name + ' → ' + token
print('D2 Guardian independent feature baseline verified')

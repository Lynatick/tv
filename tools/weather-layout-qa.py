#!/usr/bin/env python3
"""Check bounded forecast layout in the dedicated emulator, never on a network TV."""
import json
from pathlib import Path
import re
import runpy
import sys
import time

requested_sizes = sys.argv[1:]
sys.argv = ['tv-check.py', 'module']
driver = runpy.run_path(str(Path(__file__).with_name('tv-check.py')))
run, ui, click = (driver[name] for name in ('run', 'ui', 'click'))
records = Path(__file__).resolve().parent.parent / 'signing' / 'meteoblue-1.0.5-qa'
records.mkdir(exist_ok=True)

def snapshot():
    tree = ui()
    tiles = [n for n in tree.iter('node') if n.get('content-desc') in {'YouTube', 'Play Store', 'Settings', 'Cast moderator'} and n.get('clickable') == 'true']
    assert tiles, 'No application cards visible'
    bounds = [list(map(int, re.findall(r'\d+', n.get('bounds')))) for n in tiles]
    height = int(run('shell', 'wm', 'size').decode().strip().splitlines()[-1].split('x')[-1])
    assert all(b[1] >= 0 and b[3] <= height and b[3] > b[1] for b in bounds), bounds
    return tree, {n.get('content-desc'): b for n, b in zip(tiles, bounds)}

def activate(text):
    nodes = [n for n in ui().iter('node') if n.get('text') == text]
    assert nodes, text
    if nodes[0].get('focused') != 'true':
        click(text)
    assert any(n.get('text') == text and n.get('focused') == 'true' for n in ui().iter('node')), text
    run('shell', 'input', 'keyevent', '23')


results = []
try:
    run('shell', 'input', 'keyevent', '19')
    configurations = [(1920, 1080, 320), (1280, 720, 213)] if not requested_sizes else [(3840, 2160, 320)]
    for width, height, density in configurations:
        run('shell', 'am', 'force-stop', 'ru.horizon.tv')
        run('shell', 'wm', 'size', f'{width}x{height}')
        run('shell', 'wm', 'density', str(density))
        run('shell', 'am', 'start', '-n', 'ru.horizon.tv/.MainActivity')
        time.sleep(4)
        tree, initial = snapshot()
        focused = [n.get('content-desc') for n in tree.iter('node') if n.get('focused') == 'true']
        assert any(x in initial for x in focused), focused
        periods = {}
        for period in ['День', 'Неделя']:
            activate(period)
            time.sleep(2)
            tree, current = snapshot()
            assert current == initial, (period, initial, current)
            periods[period] = current
            deadline = time.time() + 40
            while not any(n.get('text', '').startswith('Обновлено ') and 'Стрелки' in n.get('text', '') for n in tree.iter('node')):
                assert time.time() < deadline, 'Forecast presentation did not load'
                time.sleep(1)
                tree, current = snapshot()
            name = 'day' if period == 'День' else 'week'
            (records / f'home-{width}-{name}.png').write_bytes(run('exec-out', 'screencap', '-p'))
        activate('Развернуть')
        time.sleep(1)
        assert any(n.get('text') == 'Свернуть' for n in ui().iter('node'))
        activate('Свернуть')
        time.sleep(1)
        tree, current = snapshot()
        assert current == initial, ('after collapse', initial, current)
        focused = [n.get('content-desc') for n in tree.iter('node') if n.get('focused') == 'true']
        assert any(x in initial for x in focused), focused
        text = [n.get('text') for n in tree.iter('node') if n.get('text')]
        assert '↻ Обновить' in text
        (records / f'home-{width}.png').write_bytes(run('exec-out', 'screencap', '-p'))
        results.append({'size': [width, height], 'density': density, 'application_bounds': initial, 'day_week_bounds_unchanged': True, 'collapse_restores_app_focus': True, 'refresh_button_visible': True, 'status': [s for s in text if 'Обновлено' in s or 'обновлялось' in s]})
finally:
    run('shell', 'wm', 'size', 'reset')
    run('shell', 'wm', 'density', 'reset')
    run('shell', 'am', 'force-stop', 'ru.horizon.tv')
    run('shell', 'am', 'start', '-n', 'ru.horizon.tv/.MainActivity')

(records / ('layout-4k.json' if requested_sizes else 'layout.json')).write_text(json.dumps(results, ensure_ascii=False, indent=2))
print(json.dumps(results, ensure_ascii=False))

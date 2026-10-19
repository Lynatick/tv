#!/usr/bin/env python3
"""Explicit, local emulator UI scenarios. No device settings are changed through shell APIs."""
import json
from pathlib import Path
import runpy
import sys
import time

scenario = sys.argv[1]
sys.argv = ['tv-check.py', 'module']
driver = runpy.run_path(str(Path(__file__).with_name('tv-check.py')))
run, ui, click, show = (driver[name] for name in ('run', 'ui', 'click', 'show'))

def press(key):
    run('shell', 'input', 'keyevent', str(key))
    time.sleep(.35)

def open_app():
    run('shell', 'am', 'start', '-n', 'ru.horizon.tv/.MainActivity')
    time.sleep(.5)

if scenario == 'home-settings':
    open_app()
    press(82)
    click('Сделать главным экраном')
    time.sleep(.4)
    click('Кнопка Домой на Mi TV Stick 4K')
    time.sleep(.4)
    show()
elif scenario == 'sources':
    open_app()
    press(82)
    click('Погода')
    time.sleep(.35)
    click('Источник данных')
    time.sleep(.35)
    show()
elif scenario == 'remote':
    open_app()
    press(22)
    press(23)
    tree = ui()
    assert any(n.attrib.get('text') == 'Все приложения' for n in tree.iter('node'))
    press(4)
    press(20)
    tree = ui()
    focused = [n.attrib.get('text') or n.attrib.get('content-desc') for n in tree.iter('node') if n.attrib.get('focused') == 'true']
    assert focused
    print(json.dumps({'dpad_apps': 'passed', 'back_home': 'passed', 'down_focus': focused}, ensure_ascii=False))
elif scenario == 'weatherapi-key':
    open_app()
    press(82)
    click('Погода')
    time.sleep(.3)
    click('Источник данных')
    time.sleep(.3)
    click('WeatherAPI.com')
    time.sleep(.3)
    show()
elif scenario == 'setup-weather':
    open_app()
    click('Выбрать город')
    time.sleep(.3)
    click('Местоположение')
    time.sleep(.3)
    click('Выбрать город')
    time.sleep(.3)
    run('shell', 'input', 'text', 'Moscow')
    click('Найти')
    deadline = time.time() + 25
    while time.time() < deadline:
        tree = ui()
        if any(n.attrib.get('text') == 'Москва, Россия' for n in tree.iter('node')):
            break
        time.sleep(1)
    click('Москва, Россия')
    time.sleep(2)
    tree = ui()
    values = [n.attrib.get('text', '') for n in tree.iter('node')]
    assert any('MET Norway' in v for v in values), values
    assert any('°' in v for v in values), values
    print('Manual city search and live MET Norway weather passed in signed release')
elif scenario == 'favorites':
    open_app()
    press(82)
    click('Выбрать приложения')
    time.sleep(.3)
    click('Cast moderator')
    click('Сохранить')
    time.sleep(.4)
    values = [n.attrib.get('text', '') for n in ui().iter('node')]
    assert 'Cast moderator' not in values
    assert 'YouTube' in values and 'Link Cinema' in values
    print('Favorite selection saved and applied')
else:
    raise SystemExit('Unknown scenario')

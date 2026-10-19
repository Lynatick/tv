#!/usr/bin/env python3
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parent.parent
adb='/Users/dmitry_baranov/Library/Android/sdk/platform-tools/adb'
def run(*args):
    return subprocess.check_output([adb,'-s','emulator-5554',*args])
def ui():
    for attempt in range(3):
        target='/sdcard/horizon-ui-'+str(time.time_ns())+'.xml'
        output=run('shell','uiautomator','dump','--compressed',target).decode(errors='replace')
        if 'dumped' not in output:
            time.sleep(.7)
            continue
        tree=run('exec-out','cat',target)
        run('shell','rm',target)
        try:
            result=ET.fromstring(tree)
            (root/'ui-latest.xml').write_bytes(tree)
            return result
        except ET.ParseError:
            time.sleep(.5)
    raise RuntimeError('UI hierarchy unavailable; no stale snapshot used')
def show():
    tree=ui()
    print('\n'.join(n.attrib.get('text','')+(' [FOCUS]' if n.attrib.get('focused')=='true' else '') for n in tree.iter('node') if n.attrib.get('text') or n.attrib.get('focused')=='true'))
def click(text):
    nodes=list(ui().iter('node'))
    matches=[n for n in nodes if n.attrib.get('text','').casefold()==text.casefold()]
    if not matches: matches=[n for n in nodes if text.casefold() in n.attrib.get('text','').casefold()]
    for n in matches:
        if True:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']))
            run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));return
    raise RuntimeError('Not found: '+text)
command=sys.argv[1] if len(sys.argv)>1 else 'show'
if command=='show': show()
elif command=='launch': run('shell','am','start','-n','ru.horizon.tv/.MainActivity');time.sleep(1);show()
elif command=='input': run('shell','input','text',sys.argv[2]);time.sleep(.3);show()
elif command=='click': click(sys.argv[2]); time.sleep(.5); show()
elif command=='tap':
    run('shell','input','tap',sys.argv[2],sys.argv[3]);time.sleep(.5);show()
elif command=='key': run('shell','input','keyevent',sys.argv[2]); time.sleep(.5); show()
elif command=='screenshot':
    output=root/(sys.argv[2] if len(sys.argv)>2 else 'screenshot.png')
    output.write_bytes(run('exec-out','screencap','-p')); print(output)
elif command=='open':
    run('shell','am','start','-a','android.intent.action.VIEW','-d',sys.argv[2],'-n','ru.horizon.tv/.MainActivity');time.sleep(1);show()
elif command=='prefs': print(run('shell','run-as','ru.horizon.tv','cat','shared_prefs/horizon.xml').decode())
elif command=='errors':
    print(run('logcat','-d','-s','AndroidRuntime:E').decode(errors='replace'))

elif command=='homecheck':
    run('shell','am','start','-a','android.settings.SETTINGS');time.sleep(.4)
    run('shell','input','keyevent','3');time.sleep(1.2)
    state=run('shell','dumpsys','activity','activities').decode(errors='replace')
    top=[line.strip() for line in state.splitlines() if 'topResumedActivity' in line or 'mResumedActivity' in line]
    assert any('ru.horizon.tv/' in line for line in top),top
    print(json.dumps({'home_button_with_accessibility':'passed','top':top},ensure_ascii=False))

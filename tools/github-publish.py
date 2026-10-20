#!/usr/bin/env python3
"""Publish Horizon TV with the existing Git credential helper. Never print credentials."""
from pathlib import Path
import hashlib
import http.client
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

workspace = Path(__file__).resolve().parents[3]
records = workspace/'work/HorizonTV'
checkout = records
outputs = workspace/'outputs/HorizonTV'
repo_name = 'tv'
base = '/repos/Lynatick/'+repo_name
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))
p = subprocess.run(['git','credential','fill'],input='protocol=https\nhost=github.com\n\n',capture_output=True,text=True,env=dict(os.environ,GIT_TERMINAL_PROMPT='0'),check=True)
credential = dict(line.split('=',1) for line in p.stdout.splitlines() if '=' in line)
headers={'Authorization':'Bearer '+credential['password'],'Accept':'application/vnd.github+json','User-Agent':'horizon-tv-publish','X-GitHub-Api-Version':'2022-11-28'}
def api(method,path,body=None):
    req=urllib.request.Request('https://api.github.com'+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=60) as r:
            data=r.read();return json.loads(data) if data else None
    except urllib.error.HTTPError as e:
        data=json.loads(e.read() or b'{}');raise RuntimeError(f'{method} {path}: HTTP {e.code}: {data.get("message","")}') from None

def optional(path):
    try: return api('GET',path)
    except RuntimeError as e:
        if 'HTTP 404:' in str(e):return None
        raise

operation=sys.argv[1]
user=api('GET','/user');assert user['login'].lower()=='lynatick'
if operation=='inspect':
    repo=optional(base)
    print(json.dumps({'account':user['login'],'repository_exists':bool(repo),'repository':None if repo is None else {k:repo.get(k) for k in ['html_url','private','default_branch','homepage']}},ensure_ascii=False))
elif operation=='create':
    repo=optional(base)
    if repo is None:
        repo=api('POST','/user/repos',{'name':repo_name,'description':'A TV-only Android launcher with favorite apps, clock, configurable weather and quick TV settings.','private':False,'auto_init':False,'has_issues':True,'has_wiki':False,'has_projects':False})
    assert repo['owner']['login'].lower()=='lynatick'
    (records/'github-repository.json').write_text(json.dumps({k:repo.get(k) for k in ['id','html_url','clone_url','private','default_branch']},indent=2))
    print(repo['html_url'])
elif operation=='release':
    sha=subprocess.check_output(['git','-C',str(checkout),'rev-parse','HEAD'],text=True).strip()
    release=optional(base+'/releases/tags/v1.0.4')
    if release is None:
        release=api('POST',base+'/releases',{'tag_name':'v1.0.4','target_commitish':sha,'name':'horizon tv 1.0.4','draft':True,'prerelease':False,'body':(checkout/'RELEASE-NOTES.md').read_text()})
    files=['horizon-tv-1.0.4.apk','horizon-tv-1.0.4-source.zip','SHA256SUMS.txt']
    for name in files:
        path=outputs/name;digest='sha256:'+hashlib.sha256(path.read_bytes()).hexdigest()
        old=next((x for x in release['assets'] if x['name']==name),None)
        if old:
            assert old['size']==path.stat().st_size and old.get('digest')==digest,(name,'existing asset differs')
            continue
        url=urllib.parse.urlsplit(release['upload_url'].split('{')[0]);assert url.hostname=='uploads.github.com'
        connection=http.client.HTTPSConnection(url.hostname,timeout=120)
        connection.putrequest('POST',url.path+'?'+urllib.parse.urlencode({'name':name}))
        for k,v in headers.items():connection.putheader(k,v)
        connection.putheader('Content-Type','application/octet-stream');connection.putheader('Content-Length',str(path.stat().st_size));connection.endheaders()
        with path.open('rb') as stream:
            for block in iter(lambda:stream.read(1024*1024),b''):connection.send(block)
        response=connection.getresponse();asset=json.loads(response.read());assert response.status==201,(name,response.status,asset.get('message'));connection.close()
        assert asset['size']==path.stat().st_size and asset['digest']==digest
        print('Uploaded and SHA-256 verified:',name,flush=True)
    release=api('GET',base+'/releases/'+str(release['id']))
    assert set(files)<={x['name'] for x in release['assets']}
    if release['draft']:release=api('PATCH',base+'/releases/'+str(release['id']),{'draft':False,'make_latest':'true'})
    (records/'github-release.json').write_text(json.dumps(release,indent=2))
    print(release['html_url'])
elif operation=='pages':
    pages=optional(base+'/pages')
    body={'build_type':'legacy','source':{'branch':'main','path':'/docs'}}
    if pages is None:pages=api('POST',base+'/pages',body)
    else:pages=api('PUT',base+'/pages',body) or api('GET',base+'/pages')
    api('PATCH',base,{'homepage':'https://lynatick.github.io/tv/'})
    (records/'github-pages.json').write_text(json.dumps(pages,indent=2))
    print(json.dumps({k:pages.get(k) for k in ['html_url','status','build_type','source']},ensure_ascii=False))
elif operation=='status':
    pages=api('GET',base+'/pages')
    builds=optional(base+'/pages/builds/latest')
    runs=api('GET',base+'/actions/runs?per_page=3')['workflow_runs']
    print(json.dumps({'pages':{k:pages.get(k) for k in ['html_url','status','source']},'build':None if builds is None else {k:builds.get(k) for k in ['status','error','commit']},'actions':[{k:r.get(k) for k in ['id','name','status','conclusion','html_url']} for r in runs]},ensure_ascii=False))
elif operation=='verify':
    release=api('GET',base+'/releases/tags/v1.0.4');assert not release['draft']
    for asset in release['assets']:
        path=outputs/asset['name'];assert asset['digest']=='sha256:'+hashlib.sha256(path.read_bytes()).hexdigest()
    url='https://lynatick.github.io/tv/'
    with urllib.request.urlopen(url,timeout=30) as response:
        assert response.status==200;html=response.read().decode()
    assert '<title>horizon tv' in html and 'releases/download/v1.0.4/horizon-tv-1.0.4.apk' in html
    assert 'Meteoblue' in html and 'Обновить' in html and 'Обновлено' in html
    apk_asset=next(a for a in release['assets'] if a['name']=='horizon-tv-1.0.4.apk')
    with urllib.request.urlopen(url+'release.json',timeout=30) as response:
        metadata=json.load(response)
    assert metadata['version']=='1.0.4' and metadata['size_bytes']==(outputs/'horizon-tv-1.0.4.apk').stat().st_size
    assert metadata['sha256']==hashlib.sha256((outputs/'horizon-tv-1.0.4.apk').read_bytes()).hexdigest()
    assert metadata['download']==apk_asset['browser_download_url']
    anonymous=urllib.request.Request(apk_asset['browser_download_url'],headers={'User-Agent':'horizon-tv-download-check'})
    with urllib.request.urlopen(anonymous,timeout=60) as response:
        assert response.status==200
        download_hash=hashlib.sha256()
        count=0
        while True:
            block=response.read(1024*1024)
            if not block:break
            count+=len(block);download_hash.update(block)
    assert download_hash.hexdigest()==hashlib.sha256((outputs/'horizon-tv-1.0.4.apk').read_bytes()).hexdigest()
    assert count==(outputs/'horizon-tv-1.0.4.apk').stat().st_size
    (records/'github-public-verification.json').write_text(json.dumps({'repository':'https://github.com/Lynatick/tv','release':release['html_url'],'site':url,'sha256':hashlib.sha256((outputs/'horizon-tv-1.0.4.apk').read_bytes()).hexdigest(),'verified':True},indent=2))
    print('Public site, release and asset hashes verified:',url)
else:raise SystemExit('Unknown operation')

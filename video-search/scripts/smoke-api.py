"""Real local API / MySQL / OSS upload smoke. Explicit --upload is required.

Credentials are read silently from video-search/.env; no secrets are printed.
"""
import argparse
import hashlib
import http.cookiejar
import json
import urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def run():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upload',action='store_true')
    parser.add_argument('--base',default='http://127.0.0.1:8090')
    args=parser.parse_args()
    if not args.upload: raise SystemExit('Use --upload to create test data and upload the 6-second fixture to OSS.')
    settings={}
    for line in (ROOT/'.env').read_text(encoding='utf-8').splitlines():
        if '=' in line and not line.startswith('#'):
            k,v=line.split('=',1);settings[k]=v
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf={}
    def request(method,path,body=None,binary=False,headers=None):
        data=body if binary else json.dumps(body,ensure_ascii=False).encode() if body is not None else None
        head={'Content-Type':'application/octet-stream' if binary else 'application/json',**(csrf if method!='GET' else {}),**(headers or {})}
        with opener.open(urllib.request.Request(args.base+path,data=data,method=method,headers=head),timeout=90) as response:
            raw=response.read();return response.status,json.loads(raw) if raw else {}
    _,token=request('GET','/api/v1/auth/csrf');csrf[token['headerName']]=token['token']
    request('POST','/api/v1/auth/login',{'username':settings['VS_ADMIN_USER'],'password':settings['VS_ADMIN_PASSWORD']})
    _,token=request('GET','/api/v1/auth/csrf');csrf={token['headerName']:token['token']}
    _,library=request('POST','/api/v1/libraries',{'name':'M1 真实上传验收'})
    source=ROOT/'samples/beverage/fixture-v1/clips/bottle-sip.mp4'
    content=source.read_bytes();digest=hashlib.sha256(content).hexdigest()
    _,upload=request('POST','/api/v1/uploads',{'libraryId':library['id'],'name':'sample.mp4','sizeBytes':len(content),'sha256':digest})
    part_size=upload['partSizeBytes']
    for number,start in enumerate(range(0,len(content),part_size)):
        part=content[start:start+part_size];part_headers={'X-Part-SHA256':hashlib.sha256(part).hexdigest()}
        request('PUT',f"/api/v1/uploads/{upload['id']}/parts/{number}",part,True,part_headers)
        request('PUT',f"/api/v1/uploads/{upload['id']}/parts/{number}",part,True,part_headers)
    status,completed=request('POST',f"/api/v1/uploads/{upload['id']}/complete",{})
    assert status==202
    _,repeated=request('POST',f"/api/v1/uploads/{upload['id']}/complete",{})
    assert completed['videoId']==repeated['videoId'] and completed['taskId']==repeated['taskId']
    video=completed['videoId']
    with opener.open(urllib.request.Request(args.base+f'/api/v1/videos/{video}/content',headers={'Range':'bytes=0-31'}),timeout=30) as response:
        assert response.status==206 and response.read()==content[:32]
    _,task=request('GET',f"/api/v1/tasks/{completed['taskId']}")
    report={'libraryId':library['id'],'videoId':video,'taskId':completed['taskId'],'bytes':len(content),'durationExpectedSeconds':6,
            'duplicatePartPassed':True,'duplicateCompletionPassed':True,'rangePassed':True,'taskStatus':task['status'],
            'note':'Real API/MySQL/OSS upload; task processing is verified separately.'}
    path=ROOT/'output/m1-api-smoke.json';path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__':run()

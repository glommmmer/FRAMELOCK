"""Explicitly run a small paid fixture through the real local application."""
import argparse,hashlib,http.cookiejar,json,time,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--file',type=Path);parser.add_argument('--query');parser.add_argument('--parent-search-id');parser.add_argument('--library',default='饮料素材演示');parser.add_argument('--mode',choices=['NORMAL','AGENT'],default='NORMAL');parser.add_argument('--cloud',action='store_true');parser.add_argument('--record-created',type=Path);args=parser.parse_args()
    if not args.cloud or not (args.file or args.query):raise SystemExit('Use --cloud and --file or --query; processing may incur model charges.')
    settings=dict(line.split('=',1) for line in (ROOT/'.env').read_text(encoding='utf8').splitlines() if '=' in line and not line.startswith('#'))
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));token={};base='http://127.0.0.1:8090/api/v1'
    def call(method,path,body=None,binary=False):
        headers={'Content-Type':'application/octet-stream' if binary else 'application/json'}
        if method!='GET' and token:headers[token['headerName']]=token['token']
        data=body if binary else json.dumps(body,ensure_ascii=False).encode() if body is not None else None
        with opener.open(urllib.request.Request(base+path,method=method,data=data,headers=headers),timeout=90) as response:
            raw=response.read();return json.loads(raw) if raw else None
    token=call('GET','/auth/csrf');call('POST','/auth/login',{'username':settings['VS_ADMIN_USER'],'password':settings['VS_ADMIN_PASSWORD']});token=call('GET','/auth/csrf')
    libraries=call('GET','/libraries');library=next((l for l in libraries if l['name']==args.library),None) or call('POST','/libraries',{'name':args.library})
    if args.file:
        if args.file.stat().st_size>10*1024*1024:raise SystemExit('Fixture helper is limited to 10 MiB; use browser resumable upload for larger files.')
        content=args.file.read_bytes();upload=call('POST','/uploads',{'libraryId':library['id'],'name':args.file.name,'sizeBytes':len(content),'sha256':hashlib.sha256(content).hexdigest()})
        for part,start in enumerate(range(0,len(content),upload['partSizeBytes'])):call('PUT',f"/uploads/{upload['id']}/parts/{part}",content[start:start+upload['partSizeBytes']],True)
        created=call('POST',f"/uploads/{upload['id']}/complete",{})
    else:created=call('POST','/searches',{'libraryId':library['id'],'query':args.query,'mode':args.mode,**({'parentSearchId':args.parent_search_id} if args.parent_search_id else {})})
    id=created['taskId'];print('TASK',id,flush=True);began=time.monotonic();previous=None
    if args.record_created:args.record_created.write_text(json.dumps(created),encoding='utf8')
    while time.monotonic()-began<240:
        try:task=call('GET','/tasks/'+id)
        except urllib.error.HTTPError as error:
            if error.code==401:
                token=call('GET','/auth/csrf');call('POST','/auth/login',{'username':settings['VS_ADMIN_USER'],'password':settings['VS_ADMIN_PASSWORD']});token=call('GET','/auth/csrf');continue
            raise
        except (OSError,urllib.error.URLError):
            print('APP_UNAVAILABLE; task remains persisted',flush=True);time.sleep(2);continue
        state=(task['stage'],task['status'],tuple((c['stage'],c['status']) for c in task.get('children',[])))
        if state!=previous:print('STATE',state,flush=True);previous=state
        if task['status'] in ('SUCCEEDED','FAILED','CANCELLED'):
            result=call('GET','/searches/'+id) if args.query and not args.file else task
            target=ROOT/'output'/('exercise-'+id+'.json');target.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
            print('SAVED',target,'elapsedSeconds',round(time.monotonic()-began,2),flush=True)
            if task['status']!='SUCCEEDED':raise SystemExit('Task failed: '+str(task.get('error_message')))
            if 'result' in result:print('MATCHES',[(m['name'],m['startMs'],m['endMs'],m.get('boundaryConfirmed')) for m in result['result'].get('matches',[])],flush=True)
            return
        time.sleep(2)
    raise SystemExit('Task remains in database; inspect task details, do not silently resubmit.')
if __name__=='__main__':main()

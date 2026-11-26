"""Real MySQL concurrent slot reservation check; no media bytes or model calls."""
import concurrent.futures,json,re,subprocess,urllib.request,urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
state=json.loads((ROOT/'.data/browser-state.json').read_text(encoding='utf8'));cookie='; '.join(c['name']+'='+c['value'] for c in state['cookies']);base='http://127.0.0.1:8090/api/v1'
def call(method,path,body=None):
    headers={'Cookie':cookie,'Content-Type':'application/json'}
    if method!='GET':headers[token['headerName']]=token['token']
    try:
        with urllib.request.urlopen(urllib.request.Request(base+path,method=method,data=json.dumps(body).encode() if body is not None else None,headers=headers),timeout=20) as response:return response.status,json.load(response)
    except urllib.error.HTTPError as error:return error.code,json.load(error)
_,token=call('GET','/auth/csrf');status,library=call('POST','/libraries',{'name':'并发名额验收'});assert status==200
def attempt(i):return call('POST','/uploads',{'libraryId':library['id'],'name':f'reserved-{i}.mp4','sizeBytes':2*1024**3,'sha256':'a'*64})
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:results=list(pool.map(attempt,range(25)))
accepted=[body for status,body in results if status==200];rejected=[status for status,body in results if status!=200]
assert len(accepted)==10 and rejected==[409]*15
assert re.fullmatch('[a-f0-9-]{36}',library['id'])
sql=f"UPDATE vs_upload SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE library_id='{library['id']}' AND status='OPEN';"
script=ROOT/'.data/expire-capacity.sh';script.write_text("#!/bin/sh\ndocker exec -i video-search-mysql-1 sh -c 'MYSQL_PWD=\"$MYSQL_PASSWORD\" exec mysql -uvideo_search video_search' <<'SQL'\n"+sql+"\nSQL\n",encoding='utf8',newline='\n')
linux='/mnt/'+ROOT.drive[0].lower()+script.as_posix()[2:];subprocess.run(['wsl','-d','Ubuntu-22.04','--','sh',linux],check=True)
report={'mode':'Real API/MySQL, 8 concurrent clients / 25 initialization requests','accepted':10,'rejected409':15,'declaredFileSizeGiB':2,'actualUploadedBytes':0,'modelCalls':0,'libraryId':library['id'],'acceptedIds':[x['id'] for x in accepted],'note':'Proves concurrent reservation limit only; does not prove 2 GiB transfer, 5h processing or sustained throughput. Test reservations expired for cleanup.'}
(ROOT/'output/capacity-check.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8');print(json.dumps({k:v for k,v in report.items() if k!='acceptedIds'},ensure_ascii=False))

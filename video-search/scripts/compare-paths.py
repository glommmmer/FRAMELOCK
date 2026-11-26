"""Same seven development clips: OCR-only vs live service NORMAL vs AGENT.

The fixture is muted: A has OCR, no ASR. Never passes annotations to models.
Completed query files are reused. Requires --cloud and stays under the shared ledger.
"""
import argparse,http.cookiejar,json,re,subprocess,sys,time,urllib.request,urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];FIXTURE=ROOT/'samples/beverage/fixture-v1'
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--cloud',action='store_true');args=parser.parse_args()
    if not args.cloud:raise SystemExit('Explicit --cloud required.')
    settings=dict(line.split('=',1) for line in (ROOT/'.env').read_text(encoding='utf8').splitlines() if '=' in line and not line.startswith('#'))
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));token={};base='http://127.0.0.1:8090/api/v1'
    def call(method,path,body=None):
        headers={'Content-Type':'application/json'}
        if method!='GET' and token:headers[token['headerName']]=token['token']
        while True:
            try:
                with opener.open(urllib.request.Request(base+path,method=method,data=json.dumps(body,ensure_ascii=False).encode() if body is not None else None,headers=headers),timeout=30) as response:
                    raw=response.read();return json.loads(raw) if raw else None
            except urllib.error.HTTPError as error:
                if error.code!=429:raise
                print('RATE_LIMIT_WAIT',flush=True);time.sleep(10)
    token=call('GET','/auth/csrf');call('POST','/auth/login',{'username':settings['VS_ADMIN_USER'],'password':settings['VS_ADMIN_PASSWORD']});token=call('GET','/auth/csrf')
    library=next(l for l in call('GET','/libraries') if l['name']=='饮料素材演示')
    clips=json.loads((FIXTURE/'annotations.json').read_text(encoding='utf8'))['clips']
    for clip in clips:
        name=Path(clip['clipPath']).name;videos=call('GET',f"/libraries/{library['id']}/videos")
        if not any(v['name']==name for v in videos):
            subprocess.run([sys.executable,str(ROOT/'scripts/exercise.py'),'--file',str(FIXTURE/clip['clipPath']),'--library','饮料素材演示','--cloud'],check=True)
    videos=call('GET',f"/libraries/{library['id']}/videos");mapping={v['id']:Path(v['name']).stem for v in videos};queries=json.loads((FIXTURE/'queries.json').read_text(encoding='utf8'))['queries']
    # Export only OCR/transcript/time facts from this owned fixture library. No credentials in arguments.
    assert re.fullmatch(r'[a-f0-9-]{36}',library['id'])
    sql=f"SELECT JSON_OBJECT('videoId',s.video_id,'startMs',s.start_ms,'endMs',s.end_ms,'ocr',s.ocr_text) FROM vs_segment s JOIN vs_video v ON v.id=s.video_id AND v.active_version=s.version_id WHERE v.library_id='{library['id']}' AND v.deleted=FALSE;"
    export_script=ROOT/'.data/export-ocr.sh'
    export_script.write_text("#!/bin/sh\ndocker exec -i video-search-mysql-1 sh -c 'MYSQL_PWD=\"$MYSQL_PASSWORD\" exec mysql -uvideo_search -N --raw --default-character-set=utf8mb4 video_search' <<'SQL'\n"+sql+"\nSQL\n",encoding='utf8',newline='\n')
    linux_script='/mnt/'+ROOT.drive[0].lower()+export_script.as_posix()[2:]
    exported=subprocess.run(['wsl','-d','Ubuntu-22.04','--','sh',linux_script],stdout=subprocess.PIPE,check=True);rows=[json.loads(line) for line in exported.stdout.decode('utf8').splitlines() if line.strip()]
    a=ROOT/'output/compare-ocr';a.mkdir(parents=True,exist_ok=True);(a/'mode.json').write_text(json.dumps({'mode':'A-OCR-only; muted fixtures have no ASR; lexical bigrams, no image verification'}),encoding='utf8')
    predictions=[]
    for q in queries:
        term=q['query'];grams={term[i:i+2] for i in range(len(term)-1)}
        ranked=sorted([(sum(g in (row['ocr'] or '') for g in grams),row) for row in rows],key=lambda x:x[0],reverse=True);hits=[];seen=set()
        for score,row in ranked:
            clip=mapping[row['videoId']]
            if score<=0 or clip in seen:continue
            seen.add(clip);hits.append({'videoId':clip,'startMs':row['startMs'],'endMs':row['endMs'],'evidence':'OCR lexical candidate; not visually verified'})
        predictions.append({'queryId':q['queryId'],'query':term,'hits':hits[:5],'elapsedMs':0,'trace':[]})
    (a/'predictions.json').write_text(json.dumps(predictions,ensure_ascii=False,indent=2),encoding='utf8')
    for mode in ['NORMAL','AGENT']:
        output=ROOT/('output/compare-'+mode.lower());output.mkdir(parents=True,exist_ok=True);(output/'mode.json').write_text(json.dumps({'mode':mode+' live service; visual/OCR + fixed verification + 100ms boundary refinement'+('; Planner supplements' if mode=='AGENT' else '')}),encoding='utf8');predictions=[]
        for q in queries:
            saved=output/(q['queryId']+'.json')
            if saved.exists():result=json.loads(saved.read_text(encoding='utf8'))
            else:
                began=time.monotonic();created=call('POST','/searches',{'libraryId':library['id'],'videoIds':list(mapping),'query':q['query'],'mode':mode});id=created['id'];print('SEARCH',mode,q['queryId'],id,flush=True)
                until=time.monotonic()+180
                while True:
                    state=call('GET','/searches/'+id)
                    if state['status'] in ['SUCCEEDED','FAILED','CANCELLED']:break
                    if time.monotonic()>until:raise SystemExit('Timed out waiting for '+id)
                    time.sleep(2)
                if state['status']!='SUCCEEDED':saved.with_suffix('.failure.json').write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf8');raise SystemExit('Search failed; preserved task '+id)
                hits=[{'videoId':mapping[m['videoId']],'startMs':m['startMs'],'endMs':m['endMs'],'evidence':m['evidence']} for m in state['result']['matches']]
                result={'queryId':q['queryId'],'query':q['query'],'hits':hits,'elapsedMs':round((time.monotonic()-began)*1000),'trace':state['result']['trace']};saved.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
            predictions.append(result);(output/'predictions.json').write_text(json.dumps(predictions,ensure_ascii=False,indent=2),encoding='utf8');print('DONE',mode,q['queryId'],'matches',len(result['hits']),flush=True)
    print('All three prediction sets saved. Evaluate using the Java evaluate command with each output directory.',flush=True)
if __name__=='__main__':main()

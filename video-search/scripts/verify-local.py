"""Read-only report/controlled read load; optional deletion only targets our M1 smoke fixture."""
import argparse,json,time,urllib.request,urllib.error,concurrent.futures,statistics
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    args=argparse.ArgumentParser();args.add_argument('--delete-smoke-fixture',action='store_true');args=args.parse_args()
    state=json.loads((ROOT/'.data/browser-state.json').read_text(encoding='utf-8'));cookie='; '.join(c['name']+'='+c['value'] for c in state['cookies']);base='http://127.0.0.1:8090'
    def call(method,path,body=None):
        headers={'Cookie':cookie,'Content-Type':'application/json'}
        if method!='GET':headers[token['headerName']]=token['token']
        data=json.dumps(body).encode() if body is not None else None
        with urllib.request.urlopen(urllib.request.Request(base+'/api/v1'+path,method=method,data=data,headers=headers),timeout=20) as response:
            raw=response.read();return json.loads(raw) if raw else None
    token=call('GET','/auth/csrf');tasks=call('GET','/tasks');searches=[]
    for task in tasks:
        if task['kind']=='SEARCH':searches.append(call('GET','/searches/'+task['id']))
    def read(_):
        began=time.perf_counter();call('GET','/libraries');return (time.perf_counter()-began)*1000
    began=time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:latencies=list(pool.map(read,range(40)))
    elapsed=time.perf_counter()-began
    report={'mode':'MySQL + OSS + local dispatcher/vector index; NOT full middleware integration','tasks':tasks,'searches':searches,
            'readLoad':{'requests':40,'concurrency':4,'elapsedSeconds':elapsed,'requestsPerSecond':40/elapsed,'medianMs':statistics.median(latencies),'p95Ms':sorted(latencies)[37],'scope':'GET libraries only; not model throughput or capacity proof'}}
    if tasks:
        id=tasks[0]['id'];observations=[]
        for _ in range(2):
            with urllib.request.urlopen(urllib.request.Request(base+'/api/v1/tasks/'+id+'/events',headers={'Cookie':cookie}),timeout=10) as response:
                for line in response:
                    if line.startswith(b'data:'):observations.append(json.loads(line[5:]));break
        report['sseReconnect']={'observed':observations,'passed':len(observations)==2 and observations[0]['status']==observations[1]['status']}
    if args.delete_smoke_fixture:
        smoke=json.loads((ROOT/'output/m1-api-smoke.json').read_text());video=smoke['videoId'];call('DELETE','/videos/'+video)
        denied=[]
        for path in ['/videos/'+video+'/content','/videos/'+video+'/thumbnail?timeMs=0']:
            try:call('GET',path);denied.append(False)
            except urllib.error.HTTPError as error:denied.append(error.code==404)
        favorites=call('GET','/favorites');report['deleteRevocation']={'videoId':video,'contentAndThumbnailDenied':all(denied),'favoriteRemoved':all(f['video_id']!=video for f in favorites)}
        old=call('GET','/searches/580e906d-e86d-48a5-aafe-ec1cee919197');report['deleteRevocation']['savedSearchFiltered']=not old.get('result',{}).get('matches')
    output=ROOT/'output/local-acceptance.json';output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'report':str(output),'searches':len(searches),'readLoad':report['readLoad'],'sseReconnectPassed':report.get('sseReconnect',{}).get('passed'),'deleteRevocation':report.get('deleteRevocation')},ensure_ascii=False))
if __name__=='__main__':main()

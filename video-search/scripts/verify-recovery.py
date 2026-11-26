"""Observe a previously recorded forced-crash task; never creates or rewrites tasks."""
import json,time,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
report_path=ROOT/'output/crash-recovery.json';report=json.loads(report_path.read_text(encoding='utf-8-sig'))
state=json.loads((ROOT/'.data/browser-state.json').read_text(encoding='utf8'));cookie='; '.join(c['name']+'='+c['value'] for c in state['cookies'])
def read():
    with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8090/api/v1/tasks/'+report['taskId'],headers={'Cookie':cookie}),timeout=15) as response:return json.load(response)
began=time.monotonic();previous=None
while time.monotonic()-began<180:
    task=read();summary=(task['status'],tuple((c['id'],c['stage'],c['status'],c['attempts']) for c in task.get('children',[])))
    if summary!=previous:print(summary,flush=True);previous=summary
    if task['status'] in ('SUCCEEDED','FAILED','CANCELLED'):
        report['after']=task;report['observedSeconds']=round(time.monotonic()-began,2)
        old={c['id']:c for c in report['before']['children']};report['recovered']=task['status']=='SUCCEEDED' and any(c['attempts']>old.get(c['id'],{}).get('attempts',0) for c in task.get('children',[]))
        report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8');print('RECOVERED',report['recovered'],flush=True);break
    time.sleep(2)
else:raise SystemExit('Still waiting; database task was left intact.')

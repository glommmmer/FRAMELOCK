"""Create ignored browser session state without printing credentials or cookies."""
import http.cookiejar,json,urllib.request
from pathlib import Path
root=Path(__file__).resolve().parents[1]
settings=dict(line.split('=',1) for line in (root/'.env').read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#'))
jar=http.cookiejar.CookieJar();opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar));base='http://127.0.0.1:8090'
with opener.open(base+'/api/v1/auth/csrf') as response:csrf=json.load(response)
data=json.dumps({'username':settings['VS_ADMIN_USER'],'password':settings['VS_ADMIN_PASSWORD']}).encode()
with opener.open(urllib.request.Request(base+'/api/v1/auth/login',data=data,headers={'Content-Type':'application/json',csrf['headerName']:csrf['token']})) as response:assert response.status==200
cookies=[{'name':c.name,'value':c.value,'domain':'127.0.0.1','path':c.path,'expires':-1,'httpOnly':True,'secure':False,'sameSite':'Lax'} for c in jar]
target=root/'.data/browser-state.json';target.write_text(json.dumps({'cookies':cookies,'origins':[]}),encoding='utf-8');print('Browser session state created in ignored .data directory.')

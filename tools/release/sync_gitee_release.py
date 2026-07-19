#!/usr/bin/env python3
import argparse, os, requests
from pathlib import Path

API='https://gitee.com/api/v5'
def main():
 p=argparse.ArgumentParser(); p.add_argument('--owner',required=True); p.add_argument('--repo',required=True); p.add_argument('--tag',required=True); p.add_argument('--name',required=True); p.add_argument('--body-file',required=True); p.add_argument('--assets',nargs='*',default=[]); p.add_argument('--prerelease',action='store_true'); a=p.parse_args()
 token=os.environ['GITEE_TOKEN']; base=f'{API}/repos/{a.owner}/{a.repo}'; auth={'access_token':token}
 releases=requests.get(base+'/releases',params={**auth,'per_page':100},timeout=30).json()
 release=next((r for r in releases if r.get('tag_name')==a.tag),None)
 data={**auth,'tag_name':a.tag,'name':a.name,'body':Path(a.body_file).read_text(encoding='utf-8'),'prerelease':str(a.prerelease).lower(),'target_commitish':'main'}
 if release:
  release=requests.patch(base+f"/releases/{release['id']}",data=data,timeout=30).json()
 else:
  release=requests.post(base+'/releases',data=data,timeout=30).json()
 if 'id' not in release: raise SystemExit(f'Gitee release error: {release}')
 existing={x.get('name'):x for x in release.get('assets',[])}
 for path in map(Path,a.assets):
  if path.name in existing:
   requests.delete(base+f"/releases/{release['id']}/attach_files/{existing[path.name]['id']}",params=auth,timeout=30)
  with path.open('rb') as f:
   response=requests.post(base+f"/releases/{release['id']}/attach_files",data=auth,files={'file':(path.name,f)},timeout=300)
  if response.status_code not in range(200,300): raise SystemExit(f'Upload failed {path}: {response.status_code} {response.text}')
 print(release.get('html_url',''))
if __name__=='__main__': main()

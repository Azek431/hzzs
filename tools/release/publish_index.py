#!/usr/bin/env python3
"""Publish the signed channel index to a dedicated release-index branch on both hosts."""
import argparse, base64, os, requests
from pathlib import Path

def github(owner,repo,path,branch,content,token):
 api=f'https://api.github.com/repos/{owner}/{repo}'; h={'Authorization':f'Bearer {token}','Accept':'application/vnd.github+json'}
 ref=requests.get(api+f'/git/ref/heads/{branch}',headers=h,timeout=30)
 if ref.status_code==404:
  main=requests.get(api+'/git/ref/heads/main',headers=h,timeout=30).json(); requests.post(api+'/git/refs',headers=h,json={'ref':f'refs/heads/{branch}','sha':main['object']['sha']},timeout=30).raise_for_status()
 current=requests.get(api+f'/contents/{path}',headers=h,params={'ref':branch},timeout=30)
 body={'message':f'更新 {path}','content':base64.b64encode(content).decode(),'branch':branch}
 if current.status_code==200: body['sha']=current.json()['sha']
 r=requests.put(api+f'/contents/{path}',headers=h,json=body,timeout=30); r.raise_for_status()

def gitee(owner,repo,path,branch,content,token):
 api=f'https://gitee.com/api/v5/repos/{owner}/{repo}'; auth={'access_token':token}
 # branch is expected to have been mirrored from GitHub first.
 current=requests.get(api+f'/contents/{path}',params={**auth,'ref':branch},timeout=30)
 data={**auth,'message':f'更新 {path}','content':base64.b64encode(content).decode(),'branch':branch}
 if current.status_code==200: data['sha']=current.json()['sha']
 r=requests.put(api+f'/contents/{path}',data=data,timeout=30)
 if r.status_code not in range(200,300): raise SystemExit(f'Gitee index update failed: {r.status_code} {r.text}')

def main():
 p=argparse.ArgumentParser(); p.add_argument('--owner',required=True); p.add_argument('--repo',required=True); p.add_argument('--channel',required=True); p.add_argument('--file',required=True); a=p.parse_args()
 content=Path(a.file).read_bytes(); path=f'updates/{a.channel}.json'; branch='release-index'
 github(a.owner,a.repo,path,branch,content,os.environ['GH_TOKEN'])
 gitee(a.owner,a.repo,path,branch,content,os.environ['GITEE_TOKEN'])
if __name__=='__main__': main()

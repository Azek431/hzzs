#!/usr/bin/env python3
import argparse, hashlib, json
from pathlib import Path

def sha(p):
 h=hashlib.sha256()
 with open(p,'rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''): h.update(b)
 return h.hexdigest()

def artifact(path):
 p=Path(path); return {'name':p.name,'sha256':sha(p),'size':p.stat().st_size}

def main():
 p=argparse.ArgumentParser()
 p.add_argument('--tag',required=True); p.add_argument('--version-name',required=True); p.add_argument('--version-code',type=int,required=True)
 p.add_argument('--channel',choices=['stable','beta'],required=True); p.add_argument('--package-name',required=True); p.add_argument('--certificate-sha256',required=True)
 p.add_argument('--apk',required=True); p.add_argument('--notes',default=''); p.add_argument('--patch-list'); p.add_argument('--output',required=True)
 a=p.parse_args(); patches=[]
 if a.patch_list and Path(a.patch_list).exists():
  for row in Path(a.patch_list).read_text().splitlines():
   if not row.strip(): continue
   version,old_sha,path=row.split('\t',2); patches.append({'fromVersionCode':int(version),'fromApkSha256':old_sha,'patch':artifact(path)})
 payload={'schemaVersion':1,'tag':a.tag,'versionName':a.version_name,'versionCode':a.version_code,'channel':a.channel,'packageName':a.package_name,'certificateSha256':a.certificate_sha256.replace(':','').lower(),'fullApk':artifact(a.apk),'patches':patches,'releaseNotes':a.notes}
 Path(a.output).write_text(json.dumps(payload,ensure_ascii=False,indent=2,sort_keys=True),encoding='utf-8')
if __name__=='__main__': main()

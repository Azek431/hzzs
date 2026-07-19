#!/usr/bin/env python3
import argparse, hashlib, json, zipfile

def sha(path):
 h=hashlib.sha256()
 with open(path,'rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''): h.update(b)
 return h.hexdigest()

def apply(old_path, patch_path, output):
 with zipfile.ZipFile(patch_path) as z:
  m=json.loads(z.read('patch.json')); data=z.read('data.bin')
  assert sha(old_path)==m['oldSha256']
  with open(old_path,'rb') as old, open(output,'wb') as out:
   for op in m['operations']:
    if op['type']=='copy': old.seek(op['offset']); out.write(old.read(op['length']))
    else: out.write(data[op['offset']:op['offset']+op['length']])
 assert sha(output)==m['newSha256']
if __name__=='__main__':
 p=argparse.ArgumentParser(); p.add_argument('old'); p.add_argument('patch'); p.add_argument('output'); a=p.parse_args(); apply(a.old,a.patch,a.output)

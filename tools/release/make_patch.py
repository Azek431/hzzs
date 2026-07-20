#!/usr/bin/env python3
"""Create a deterministic, streaming-friendly HZZS block patch."""
import argparse, hashlib, json, zipfile
from pathlib import Path

BLOCK = 64 * 1024

def sha(path):
    h=hashlib.sha256()
    with open(path,'rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''): h.update(chunk)
    return h.hexdigest()

def build(old_path, new_path, out_path):
    old=Path(old_path).read_bytes(); new=Path(new_path).read_bytes()
    lookup={}
    for off in range(0,len(old),BLOCK):
        chunk=old[off:off+BLOCK]; lookup.setdefault(hashlib.sha256(chunk).digest(),[]).append(off)
    operations=[]; data=bytearray(); pos=0
    while pos < len(new):
        chunk=new[pos:pos+BLOCK]; found=None
        for off in lookup.get(hashlib.sha256(chunk).digest(),[]):
            if old[off:off+len(chunk)]==chunk: found=off; break
        if found is not None:
            if operations and operations[-1]['type']=='copy' and operations[-1]['offset']+operations[-1]['length']==found:
                operations[-1]['length'] += len(chunk)
            else: operations.append({'type':'copy','offset':found,'length':len(chunk)})
        else:
            data_off=len(data); data.extend(chunk)
            if operations and operations[-1]['type']=='data' and operations[-1]['offset']+operations[-1]['length']==data_off:
                operations[-1]['length'] += len(chunk)
            else: operations.append({'type':'data','offset':data_off,'length':len(chunk)})
        pos += len(chunk)
    manifest={'formatVersion':1,'blockSize':BLOCK,'oldSha256':sha(old_path),'newSha256':sha(new_path),'newSize':len(new),'operations':operations}
    with zipfile.ZipFile(out_path,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        z.writestr('patch.json',json.dumps(manifest,separators=(',',':'),sort_keys=True))
        z.writestr('data.bin',bytes(data))

if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('old'); p.add_argument('new'); p.add_argument('output'); a=p.parse_args(); build(a.old,a.new,a.output)

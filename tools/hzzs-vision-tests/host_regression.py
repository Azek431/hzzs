#!/usr/bin/env python3
from __future__ import annotations
import argparse, ctypes, json, statistics, subprocess, tempfile, time
from pathlib import Path
from PIL import Image
import numpy as np

class FrameView(ctypes.Structure):
    _fields_=[('pixels',ctypes.POINTER(ctypes.c_uint32)),('width',ctypes.c_int),('height',ctypes.c_int),('stride',ctypes.c_int)]
class Detection(ctypes.Structure):
    _fields_=[(name,ctypes.c_int) for name in ('found','kind','left','top','right','bottom','centerX','centerY','edgeGapPx','widthPx','heightPx','widthMilliP','heightMilliP','sizeClass','scorePermille','samples')]
class Analysis(ctypes.Structure):
    _fields_=[('width',ctypes.c_int),('height',ctypes.c_int),('playerLeft',ctypes.c_int),('playerRight',ctypes.c_int),('playerCenterX',ctypes.c_int),('playerCenterY',ctypes.c_int),('playerWidth',ctypes.c_int),('primaryKind',ctypes.c_int),('totalSamples',ctypes.c_int),('bottle',Detection),('cake',Detection),('spike',Detection)]

def expected(name:str)->list[str]:
    n=int(name.split('_')[1].split('.')[0]); out=[]
    if 18<=n<=21 or 155<=n<=160 or 3903<=n<=3905: out.append('BOTTLE')
    if 60<=n<=75 or 110<=n<=115 or 119<=n<=122 or 168<=n<=175 or 4141<=n<=4157: out.append('CAKE')
    if 181<=n<=188 or 194<=n<=195 or 219<=n<=226 or 3935<=n<=3942: out.append('SPIKE')
    return out

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--source-root',type=Path,required=True)
    ap.add_argument('--images',type=Path,required=True)
    ap.add_argument('--output',type=Path,required=True)
    ap.add_argument('--work-width',type=int,default=480)
    args=ap.parse_args()
    with tempfile.TemporaryDirectory(prefix='hzzs-vision-') as td:
        lib=Path(td)/'libhzzsvision_host.so'
        subprocess.run(['g++','-std=c++17','-O3','-fPIC','-shared',str(args.source_root/'HzzsVisionCore.cpp'),'-o',str(lib)],check=True)
        dll=ctypes.CDLL(str(lib))
        dll.hzzs_vision_analyze_packed.argtypes=[ctypes.POINTER(ctypes.c_uint32),ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.POINTER(ctypes.c_int32),ctypes.c_int]
        dll.hzzs_vision_analyze_packed.restype=ctypes.c_int
        rows=[]; costs=[]
        files=sorted([p for p in args.images.rglob('*') if p.suffix.lower() in {'.jpg','.jpeg','.png','.webp'}])
        for p in files:
            im=Image.open(p).convert('RGB')
            if im.width>args.work_width:
                im=im.resize((args.work_width,round(im.height*args.work_width/im.width)),Image.Resampling.BILINEAR)
            rgb=np.asarray(im,dtype=np.uint8)
            argb=np.ascontiguousarray(np.uint32(0xff000000)|(rgb[:,:,0].astype(np.uint32)<<16)|(rgb[:,:,1].astype(np.uint32)<<8)|rgb[:,:,2].astype(np.uint32))
            packed=(ctypes.c_int32*64)()
            t=time.perf_counter_ns(); count=dll.hzzs_vision_analyze_packed(argb.ctypes.data_as(ctypes.POINTER(ctypes.c_uint32)),im.width,im.height,im.width,3,2,packed,64); ms=(time.perf_counter_ns()-t)/1e6
            if count < 60: raise RuntimeError('native result too short')
            actual=[]
            if packed[12]: actual.append('BOTTLE')
            if packed[28]: actual.append('CAKE')
            if packed[44]: actual.append('SPIKE')
            want=expected(p.name); ok=set(actual)==set(want)
            rows.append({'filename':p.name,'expected':want,'actual':actual,'passed':ok,'cost_ms':ms,'samples':packed[9]})
            costs.append(ms)
        report={'total':len(rows),'passed':sum(r['passed'] for r in rows),'failed':sum(not r['passed'] for r in rows),'mean_ms':statistics.mean(costs) if costs else 0,'p95_ms':sorted(costs)[max(0,int(len(costs)*.95)-1)] if costs else 0,'rows':rows}
        args.output.parent.mkdir(parents=True,exist_ok=True); args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({k:v for k,v in report.items() if k!='rows'},ensure_ascii=False,indent=2))
        if report['failed']:
            for r in rows:
                if not r['passed']: print(r)
            raise SystemExit(1)
if __name__=='__main__': main()

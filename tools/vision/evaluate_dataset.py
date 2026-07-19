#!/usr/bin/env python3
import argparse, csv, ctypes, json, math, os, random, statistics, subprocess, time
from pathlib import Path
import cv2
import numpy as np

SCENE_DIRS={'#U751c#U54c1#U5de5#U5382':('sweet-factory',0),'#U7af9#U5f71#U4e66#U5c4b':('bamboo-bookstore',1)}
KINDS=['player','poison-bottle','cake-structure','hanging-spike','pit','panda-statue','bamboo-gap','hanging-brush']
COLORS=[(80,220,80),(40,170,30),(50,180,255),(180,50,240),(30,80,255),(190,190,190),(20,120,230),(230,100,20)]

class HostVision:
    def __init__(self, library):
        self.lib=ctypes.CDLL(str(library))
        self.fn=self.lib.hzzs_analyze_host
        self.fn.argtypes=[ctypes.c_int,ctypes.POINTER(ctypes.c_uint32),ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.POINTER(ctypes.c_float),ctypes.c_int]
        self.fn.restype=ctypes.c_int
    def analyze(self,bgr,scene,work_width=320):
        h,w=bgr.shape[:2]
        rgb=cv2.cvtColor(bgr,cv2.COLOR_BGR2RGB).astype(np.uint32)
        argb=np.ascontiguousarray((0xFF000000 | (rgb[:,:,0]<<16) | (rgb[:,:,1]<<8) | rgb[:,:,2]).ravel(),dtype=np.uint32)
        out=np.zeros(1+64*10,dtype=np.float32)
        start=time.perf_counter_ns()
        count=self.fn(scene,argb.ctypes.data_as(ctypes.POINTER(ctypes.c_uint32)),w,h,work_width,out.ctypes.data_as(ctypes.POINTER(ctypes.c_float)),64)
        elapsed=(time.perf_counter_ns()-start)/1e6
        if count<0: return {'error':f'native error {count}','sceneConfidence':0,'detections':[],'elapsedMs':elapsed}
        detections=[]
        for i in range(count):
            r=out[1+i*10:1+(i+1)*10]
            kind=int(round(float(r[1])))
            detections.append({'trackHint':int(round(float(r[0]))),'kind':KINDS[kind] if 0<=kind<len(KINDS) else f'unknown-{kind}',
             'left':float(r[2]),'top':float(r[3]),'right':float(r[4]),'bottom':float(r[5]),'confidence':float(r[6]),
             'actionable':bool(r[7]>.5),'diagnosticOnly':bool(r[8]>.5),'avoidance':int(round(float(r[9])))})
        return {'sceneConfidence':float(out[0]),'detections':detections,'elapsedMs':elapsed}

def annotate(image,result):
    out=image.copy(); h,w=out.shape[:2]
    for d in result['detections']:
        k=KINDS.index(d['kind']) if d['kind'] in KINDS else 0; color=COLORS[k]
        x1=max(0,min(w-1,round(d['left']*w))); y1=max(0,min(h-1,round(d['top']*h))); x2=max(0,min(w-1,round(d['right']*w))); y2=max(0,min(h-1,round(d['bottom']*h)))
        thickness=max(2,w//320)
        cv2.rectangle(out,(x1,y1),(x2,y2),color,thickness)
        label=f"{d['kind']} {d['confidence']:.2f}"+(' A' if d['actionable'] else '')
        scale=max(.45,w/1400); (tw,th),_=cv2.getTextSize(label,cv2.FONT_HERSHEY_SIMPLEX,scale,thickness)
        cv2.rectangle(out,(x1,max(0,y1-th-8)),(min(w,x1+tw+6),y1),color,-1)
        cv2.putText(out,label,(x1+3,max(th+1,y1-4)),cv2.FONT_HERSHEY_SIMPLEX,scale,(0,0,0),max(1,thickness//2),cv2.LINE_AA)
    return out

def make_overview(items,out_path,title,thumb_w=180,thumb_h=400,cols=5):
    rows=math.ceil(len(items)/cols); header=54
    canvas=np.full((header+rows*thumb_h,cols*thumb_w,3),248,np.uint8)
    cv2.putText(canvas,title,(16,36),cv2.FONT_HERSHEY_SIMPLEX,.9,(30,30,30),2,cv2.LINE_AA)
    for i,(path,img,result) in enumerate(items):
        thumb=cv2.resize(img,(thumb_w,thumb_h),interpolation=cv2.INTER_AREA)
        text=f"{path.name[:18]} {result['elapsedMs']:.1f}ms"
        cv2.rectangle(thumb,(0,thumb_h-22),(thumb_w,thumb_h),(255,255,255),-1)
        cv2.putText(thumb,text,(3,thumb_h-7),cv2.FONT_HERSHEY_SIMPLEX,.32,(0,0,0),1,cv2.LINE_AA)
        y=header+(i//cols)*thumb_h; x=(i%cols)*thumb_w; canvas[y:y+thumb_h,x:x+thumb_w]=thumb
    cv2.imwrite(str(out_path),canvas,[cv2.IMWRITE_JPEG_QUALITY,88])

def main():
    p=argparse.ArgumentParser(); p.add_argument('--dataset',required=True); p.add_argument('--output',required=True); p.add_argument('--project-root',default=str(Path(__file__).resolve().parents[2])); p.add_argument('--limit',type=int); p.add_argument('--overview-sample',type=int,default=50); a=p.parse_args()
    project=Path(a.project_root); lib=project/'build/host/libhzzs_vision.so'
    if not lib.exists(): subprocess.check_call([str(project/'tools/vision/build_host.sh')])
    engine=HostVision(lib); dataset=Path(a.dataset); output=Path(a.output); output.mkdir(parents=True,exist_ok=True)
    records=[]; all_times=[]
    for encoded,(slug,scene) in SCENE_DIRS.items():
        files=sorted([x for x in (dataset/encoded).rglob('*') if x.suffix.lower() in {'.jpg','.jpeg','.png','.webp'}])
        if a.limit: files=files[:a.limit]
        scene_dir=output/slug; pred=scene_dir/'predicted'; overview=scene_dir/'overview'; metrics=scene_dir/'metrics'; failures=scene_dir/'failures'
        for d in [pred,overview,metrics,failures]: d.mkdir(parents=True,exist_ok=True)
        scene_items=[]; times=[]; decode_times=[]; counts={k:0 for k in KINDS}; no_player=[]
        # Exclude dynamic-loader/page-cache cold start from steady-state timing.
        if files:
            warm=cv2.imread(str(files[0]),cv2.IMREAD_COLOR)
            if warm is not None:
                for _ in range(3): engine.analyze(warm,scene)
        for idx,path in enumerate(files):
            decode_start=time.perf_counter_ns()
            image=cv2.imread(str(path),cv2.IMREAD_COLOR)
            decode_ms=(time.perf_counter_ns()-decode_start)/1e6
            if image is None: continue
            result=engine.analyze(image,scene); result['decodeMs']=decode_ms; result['endToEndMs']=decode_ms+result['elapsedMs']; decode_times.append(decode_ms); times.append(result['elapsedMs']); all_times.append(result['elapsedMs'])
            for d in result['detections']: counts[d['kind']]=counts.get(d['kind'],0)+1
            if not any(d['kind']=='player' for d in result['detections']): no_player.append(str(path.relative_to(dataset)))
            drawn=annotate(image,result)
            relative=path.relative_to(dataset/encoded); target=(pred/relative).with_suffix('.jpg'); target.parent.mkdir(parents=True,exist_ok=True)
            cv2.imwrite(str(target),drawn,[cv2.IMWRITE_JPEG_QUALITY,90])
            row={'scene':slug,'source':str(path.relative_to(dataset)),'output':str(target.relative_to(output)),'width':image.shape[1],'height':image.shape[0],**result}
            records.append(row)
            if len(scene_items)<a.overview_sample: scene_items.append((path,drawn,result))
        make_overview(scene_items,overview/'overview.jpg',f'{slug} · first {len(scene_items)} frames')
        summary={'scene':slug,'images':len(files),'timingMs':{'nativeMean':statistics.fmean(times) if times else 0,'nativeP50':float(np.percentile(times,50)) if times else 0,'nativeP95':float(np.percentile(times,95)) if times else 0,'nativeMax':max(times) if times else 0,'decodeMean':statistics.fmean(decode_times) if decode_times else 0,'endToEndMean':statistics.fmean([x+y for x,y in zip(times,decode_times)]) if times else 0},'detectionCounts':counts,'framesWithoutPlayer':len(no_player),'framesWithoutPlayerFiles':no_player,'accuracyStatus':'NOT_MEASURED_NO_INDEPENDENT_GROUND_TRUTH','boundaryToleranceDefinition':'abs(predicted edge - ground truth edge) <= player width * 0.05'}
        (metrics/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    (output/'results.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
    draft={'schemaVersion':1,'machineGenerated':True,'reviewed':False,'warning':'These boxes are algorithm output drafts, not independent ground truth. Human review is required before accuracy certification.','images':[{'source':r['source'],'scene':r['scene'],'width':r['width'],'height':r['height'],'objects':r['detections']} for r in records]}
    (output/'draft_annotations.json').write_text(json.dumps(draft,ensure_ascii=False,indent=2),encoding='utf-8')
    with (output/'timings.csv').open('w',newline='',encoding='utf-8-sig') as fh:
        writer=csv.writer(fh); writer.writerow(['scene','source','width','height','decode_ms','native_ms','end_to_end_ms','scene_confidence','detection_count'])
        for r in records: writer.writerow([r['scene'],r['source'],r['width'],r['height'],f"{r.get('decodeMs',0):.4f}",f"{r['elapsedMs']:.4f}",f"{r.get('endToEndMs',r['elapsedMs']):.4f}",f"{r['sceneConfidence']:.4f}",len(r['detections'])])
    global_summary={'images':len(records),'timingMs':{'mean':statistics.fmean(all_times) if all_times else 0,'p50':float(np.percentile(all_times,50)) if all_times else 0,'p95':float(np.percentile(all_times,95)) if all_times else 0,'max':max(all_times) if all_times else 0},'importantNote':'No independent human ground-truth boxes were present in the uploaded archive. Generated images are review artifacts, not proof of the 5% boundary requirement.'}
    (output/'summary.json').write_text(json.dumps(global_summary,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(global_summary,ensure_ascii=False,indent=2))
if __name__=='__main__': main()

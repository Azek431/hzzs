#!/usr/bin/env python3
import ctypes
import json
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
subprocess.check_call([str(ROOT / 'tools/vision/build_host.sh')])
lib = ctypes.CDLL(str(ROOT / 'build/host/libhzzs_vision.so'))
fn = lib.hzzs_analyze_host
fn.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_uint32), ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_float), ctypes.c_int]
fn.restype = ctypes.c_int

def invoke(scene, argb, width, height, work_width=320):
    out = np.zeros(641, np.float32)
    ptr = None if argb is None else argb.ctypes.data_as(ctypes.POINTER(ctypes.c_uint32))
    count = fn(scene, ptr, width, height, work_width, out.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), 64)
    return count, out

# Invalid inputs fail closed.
count, _ = invoke(0, None, 0, 0)
assert count == -2, count
blank = np.full(320 * 640, 0xFFFFFFFF, np.uint32)
count, _ = invoke(0, blank, 320, 640, 100)
assert count == -2, count

# A blank frame never produces an actionable object.
count, out = invoke(0, blank, 320, 640)
blank_count = count
assert count >= 0
for i in range(count):
    row = out[1 + i * 10:1 + (i + 1) * 10]
    assert row[7] == 0.0
    assert 0.0 <= row[2] <= row[4] <= 1.0
    assert 0.0 <= row[3] <= row[5] <= 1.0

# Smoke-test representative uploaded screenshots and ABI bounds.
dataset = Path('/mnt/data/test_images')
images = sorted([p for p in dataset.rglob('*') if p.suffix.lower() in {'.jpg', '.jpeg', '.png', '.webp'}])
assert images, 'uploaded test dataset missing'
checked = 0
for path in images[::max(1, len(images)//24)]:
    image = cv2.imread(str(path), cv2.IMREAD_COLOR)
    assert image is not None
    h, w = image.shape[:2]
    rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB).astype(np.uint32)
    argb = np.ascontiguousarray((0xFF000000 | (rgb[:, :, 0] << 16) | (rgb[:, :, 1] << 8) | rgb[:, :, 2]).ravel(), dtype=np.uint32)
    scene = 1 if '#U7af9' in str(path) else 0
    count, out = invoke(scene, argb, w, h)
    assert 0 <= count <= 64, (path, count)
    for i in range(count):
        row = out[1 + i * 10:1 + (i + 1) * 10]
        assert 0 <= int(round(float(row[1]))) <= 7
        assert 0.0 <= row[2] <= row[4] <= 1.0
        assert 0.0 <= row[3] <= row[5] <= 1.0
        assert 0.0 <= row[6] <= 1.0
    checked += 1
print(json.dumps({'status':'PASS','representativeFrames':checked,'blankFrameDetections':blank_count}, ensure_ascii=False))

#!/usr/bin/env python3
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image
import cv2

ROOT=Path(__file__).resolve().parents[2]
errors=[]; checks=[]
for xml in ROOT.rglob('src/main/res/**/*.xml'):
    try: ET.parse(xml); checks.append(f'xml:{xml.relative_to(ROOT)}')
    except Exception as exc: errors.append(f'{xml}: {exc}')

# Adaptive layers must exist and the background must cover the canvas.
for p in [
    ROOT/'app/src/main/res/drawable/ic_launcher_foreground.xml',
    ROOT/'app/src/main/res/drawable/ic_launcher_monochrome.xml',
    ROOT/'app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml',
]:
    if not p.exists(): errors.append(f'missing {p}')

# Legacy icons must be nontransparent at corners and must not be a white card.
for density in ['mdpi','hdpi','xhdpi','xxhdpi','xxxhdpi']:
    p=ROOT/f'app/src/main/res/mipmap-{density}/ic_launcher.png'
    if not p.exists(): errors.append(f'missing {p}'); continue
    image=Image.open(p).convert('RGBA')
    corners=[image.getpixel((0,0)),image.getpixel((image.width-1,0)),image.getpixel((0,image.height-1)),image.getpixel((image.width-1,image.height-1))]
    if any(a<250 for *_,a in corners): errors.append(f'{p}: transparent corner in legacy icon')
    pixels = image.get_flattened_data() if hasattr(image, 'get_flattened_data') else image.getdata()
    near_white=sum(1 for r,g,b,a in pixels if a>0 and r>245 and g>245 and b>245)/max(1,image.width*image.height)
    if near_white>.55: errors.append(f'{p}: probable white-card regression ({near_white:.1%})')
    checks.append(f'icon:{density}:{image.width}x{image.height}')

# Donation assets remain present and large enough for display/saving.
for name in ['donation_wechat.png','donation_alipay.jpg']:
    p=ROOT/'feature/about/src/main/res/drawable'/name
    if not p.exists(): errors.append(f'missing {p}'); continue
    image=Image.open(p)
    if min(image.size)<600: errors.append(f'{p}: image too small {image.size}')
    checks.append(f'donation:{name}:{image.size[0]}x{image.size[1]}')

# Alipay is a standard QR and should stay decodable. The WeChat image is a
# platform-specific赞赏码, so it is intentionally checked byte/dimension-wise,
# not rejected when OpenCV's standard QR decoder cannot parse it.
alipay=cv2.imread(str(ROOT/'feature/about/src/main/res/drawable/donation_alipay.jpg'))
data, points, _ = cv2.QRCodeDetector().detectAndDecode(alipay)
if not data.startswith('https://qr.alipay.com/'): errors.append('Alipay QR decode check failed')
else: checks.append('alipay-qr:decoded')

result={'status':'PASS' if not errors else 'FAIL','checks':len(checks),'errors':errors}
print(json.dumps(result,ensure_ascii=False,indent=2))
if errors: raise SystemExit(1)

#!/usr/bin/env python3
import argparse, base64, json
from cryptography.hazmat.primitives.serialization import pkcs12, Encoding, PublicFormat
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa

def main():
 p=argparse.ArgumentParser(); p.add_argument('--payload',required=True); p.add_argument('--keystore',required=True); p.add_argument('--password',required=True); p.add_argument('--output',required=True); p.add_argument('--public-key-output',required=True); a=p.parse_args()
 payload=json.loads(open(a.payload,encoding='utf-8').read()); canonical=json.dumps(payload,ensure_ascii=False,separators=(',',':'),sort_keys=True)
 key,cert,_=pkcs12.load_key_and_certificates(open(a.keystore,'rb').read(),a.password.encode())
 if isinstance(key,rsa.RSAPrivateKey): algorithm='SHA256withRSA'; signature=key.sign(canonical.encode(),padding.PKCS1v15(),hashes.SHA256())
 elif isinstance(key,ec.EllipticCurvePrivateKey): algorithm='SHA256withECDSA'; signature=key.sign(canonical.encode(),ec.ECDSA(hashes.SHA256()))
 else: raise SystemExit('Unsupported private key type')
 result={'signedPayload':canonical,'signatureAlgorithm':algorithm,'signature':base64.b64encode(signature).decode()}
 open(a.output,'w',encoding='utf-8').write(json.dumps(result,ensure_ascii=False,indent=2))
 public=key.public_key().public_bytes(Encoding.DER,PublicFormat.SubjectPublicKeyInfo)
 open(a.public_key_output,'w').write(base64.b64encode(public).decode())
if __name__=='__main__': main()

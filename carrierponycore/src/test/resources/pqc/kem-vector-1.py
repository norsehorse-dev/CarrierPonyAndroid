import hashlib, json
from kyber_py.ml_kem import ML_KEM_768
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.keywrap import aes_key_wrap
from cryptography.hazmat.primitives import serialization as s
def combine(mk, ek, eph, rpub):
    return hashlib.sha3_256(mk+ek+eph+rpub+bytes([35])+b"OpenPGPCompositeKDFv1"+bytes([21])).digest()
kat = combine(bytes(range(0,32)),bytes(range(32,64)),bytes(range(64,96)),bytes(range(96,128))).hex()
assert kat=="8b341efe0f87f2e97e1ab8772b8e7cebc6b52240a631ad4d5e220acd0f419b75", kat
def h(label,n): return hashlib.shake_256(b"carrierpony-kem-vector-1/"+label).digest(n)
seed=h(b"mlkem-seed",64); xsec=h(b"x25519-secret",32); esec=h(b"ephemeral-secret",32); sk=h(b"session-key",32); m=h(b"encaps-m",32)
ek,dk=ML_KEM_768.key_derive(seed)
raw=lambda k:k.public_key().public_bytes(s.Encoding.Raw,s.PublicFormat.Raw)
xp=X25519PrivateKey.from_private_bytes(xsec); xpub=raw(xp)
ep=X25519PrivateKey.from_private_bytes(esec); epub=raw(ep)
share,ct=ML_KEM_768._encaps_internal(ek,m)
assert ML_KEM_768.decaps(dk,ct)==share
ecdh=ep.exchange(X25519PublicKey.from_public_bytes(xpub))
kek=combine(share,ecdh,epub,xpub)
wrapped=aes_key_wrap(kek,sk)
v=dict(compositeSecret=(xsec+seed).hex(),compositePublic=(xpub+ek).hex(),ephemeralPublic=epub.hex(),mlkemCiphertext=ct.hex(),kek=kek.hex(),sessionKey=sk.hex(),wrappedSessionKey=wrapped.hex())
json.dump(v,open("kem-vector.json","w"),indent=1)
print({k:len(x)//2 for k,x in v.items()}); print(kek.hex(), wrapped.hex())

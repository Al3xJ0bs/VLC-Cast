#!/usr/bin/env python3
"""Independent M3 oracle for AirCast v0.1.3 (pair-verify).

Computes the expected Pair-Verify M3 controller signature for FIXED inputs,
using pyatv's own hkdf_expand and the cryptography library — following
pyatv's verify1 formula exactly (auth/hap_srp.py):

    device_info = self._public_bytes + credentials.client_id + session_pub_key
                  = client X25519 pub + client_id + server X25519 pub

The Kotlin test (tests/TestMain.kt, "m3-pyatv-crosscheck") recomputes the
same values with AirCast's own crypto code and must match these hex strings.
If the app signed serverPub + clientId + ourPublic (the v0.1.2 bug), the
signature would equal WRONG_ORDER_SIG instead — and a real Apple TV
answers M4 error 2.

Run: tests/venv/bin/python tests/m3_oracle.py  (venv: cryptography + srptools + pyatv)
"""
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey, X25519PublicKey)
from pyatv.auth.hap_srp import hkdf_expand

# Fixed inputs (arbitrary but deterministic; NOT real credentials)
CLIENT_X_PRIV = bytes(range(1, 33))          # 01..20
SERVER_X_PRIV = bytes(range(33, 65))         # 21..40
CLIENT_ED_SEED = bytes.fromhex(
    "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")  # RFC 8032 test 1
CLIENT_ID = b"TestClient01"

client_x_priv = X25519PrivateKey.from_private_bytes(CLIENT_X_PRIV)
server_x_priv = X25519PrivateKey.from_private_bytes(SERVER_X_PRIV)
client_pub = client_x_priv.public_key().public_bytes_raw()
server_pub = server_x_priv.public_key().public_bytes_raw()
shared = client_x_priv.exchange(
    X25519PublicKey.from_public_bytes(server_pub))
session_key = hkdf_expand(
    "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)

client_ed_priv = Ed25519PrivateKey.from_private_bytes(CLIENT_ED_SEED)
client_ltpk = client_ed_priv.public_key().public_bytes_raw()

# pyatv order (correct): clientPub + clientId + serverPub
device_info = client_pub + CLIENT_ID + server_pub
sig_correct = client_ed_priv.sign(device_info)
# v0.1.2 order (wrong): serverPub + clientId + clientPub
wrong_info = server_pub + CLIENT_ID + client_pub
sig_wrong = client_ed_priv.sign(wrong_info)

print("CLIENT_PUB   =", client_pub.hex())
print("SERVER_PUB   =", server_pub.hex())
print("SHARED       =", shared.hex())
print("SESSION_KEY  =", session_key.hex())
print("CLIENT_LTPK  =", client_ltpk.hex())
print("CLIENT_ID    =", CLIENT_ID.hex())
print("DEVICE_INFO  =", device_info.hex())
print("SIG_CORRECT  =", sig_correct.hex())
print("SIG_WRONG    =", sig_wrong.hex())
print("SIGS_DIFFER  =", sig_correct != sig_wrong)

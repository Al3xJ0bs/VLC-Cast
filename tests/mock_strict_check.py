#!/usr/bin/env python3
"""Negative control for the strict mock (tests/mock_airplay.py).

Proves the mock's Pair-Verify M3 check is a REAL oracle, not a rubber
stamp: it does a full Pair-Setup (SRP, PIN 1234) against the running mock,
then Pair-Verify twice — once with the M3 signature over the WRONG byte
order (serverPub + clientId + clientPub, the AirCast v0.1.2 bug) and once
with the CORRECT order (clientPub + clientId + serverPub, pyatv verify1).

Expected: wrong order -> M4 error 2; correct order -> M4 seq 4, no error.
If the wrong order were accepted, this script exits 1 — the mock would
be mirroring the client bug again.

Usage: start the mock first (tests/run_mock_test.sh does this), then:
    tests/venv/bin/python tests/mock_strict_check.py [--port 7000]
"""
import argparse
import hashlib
import hmac as hmac_mod
import os
import socket
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey)
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey, X25519PublicKey)
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from srptools import SRPClientSession, SRPContext, constants

PIN = "1234"


def read_tlv(data):
    out = {}
    i = 0
    while i + 1 < len(data):
        t, ln = data[i], data[i + 1]
        out[t] = out.get(t, b"") + data[i + 2:i + 2 + ln]
        i += 2 + ln
    return out


def write_tlv(d):
    out = bytearray()
    for t, v in d.items():
        off = 0
        while off < len(v) or (off == 0 and len(v) == 0):
            chunk = v[off:off + 255]
            out.append(t)
            out.append(len(chunk))
            out.extend(chunk)
            off += len(chunk)
            if len(v) == 0:
                break
    return bytes(out)


def hkdf(salt, info, ikm, length=32):
    return HKDF(algorithm=hashes.SHA512(), length=length,
                salt=salt.encode(), info=info.encode()).derive(ikm)


class Conn:
    def __init__(self, port):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=10)

    def post(self, uri, body=b""):
        req = (f"POST {uri} HTTP/1.1\r\nContent-Length: {len(body)}\r\n"
               f"Content-Type: application/octet-stream\r\n\r\n").encode() + body
        self.s.sendall(req)
        raw = b""
        while b"\r\n\r\n" not in raw:
            raw += self.s.recv(4096)
        head, rest = raw.split(b"\r\n\r\n", 1)
        clen = 0
        for line in head.decode().split("\r\n")[1:]:
            if line.lower().startswith("content-length:"):
                clen = int(line.split(":", 1)[1])
        while len(rest) < clen:
            rest += self.s.recv(4096)
        return rest[:clen]

    def close(self):
        self.s.close()


def pair_setup(port):
    """Full Pair-Setup, returns (client_id, client_ed_seed)."""
    c = Conn(port)
    c.post("/pair-pin-start")
    m2 = read_tlv(c.post("/pair-setup", write_tlv({6: b"\x01", 0: b"\x00"})))
    ctx = SRPContext("Pair-Setup", PIN, prime=constants.PRIME_3072,
                     generator=constants.PRIME_3072_GEN, hash_func=hashlib.sha512)
    sess = SRPClientSession(ctx)
    sess.process(m2[3].hex(), m2[2].hex())
    a_pub, proof = sess.public_bin, sess.key_proof_bin
    m4 = read_tlv(c.post("/pair-setup", write_tlv(
        {6: b"\x03", 3: a_pub, 4: proof})))
    assert 7 not in m4, f"M4 error: {m4}"
    key = sess.key_bin
    skey = hkdf("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", key)
    ed = Ed25519PrivateKey.generate()
    client_id = b"StrictCheck01"
    ltpk = ed.public_key().public_bytes_raw()
    ctrl_x = hkdf("Pair-Setup-Controller-Sign-Salt",
                  "Pair-Setup-Controller-Sign-Info", key)
    sig = ed.sign(ctrl_x + client_id + ltpk)
    inner = write_tlv({1: client_id, 3: ltpk, 10: sig})
    enc = ChaCha20Poly1305(skey).encrypt(b"\x00" * 4 + b"PS-Msg05", inner, None)
    m6 = read_tlv(c.post("/pair-setup", write_tlv({6: b"\x05", 5: enc})))
    assert 7 not in m6, f"M6 error: {m6}"
    c.close()
    return client_id, ed


def pair_verify(port, client_id, ed, wrong_order):
    """One Pair-Verify. Returns the M4 error byte, or None on success."""
    c = Conn(port)
    x_priv = X25519PrivateKey.generate()
    our_pub = x_priv.public_key().public_bytes_raw()
    m2 = read_tlv(c.post("/pair-verify", write_tlv({6: b"\x01", 3: our_pub})))
    server_pub = m2[3]
    shared = x_priv.exchange(X25519PublicKey.from_public_bytes(server_pub))
    skey = hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
    inner2 = read_tlv(ChaCha20Poly1305(skey).decrypt(
        b"\x00" * 4 + b"PV-Msg02", m2[5], None))
    assert inner2[1] == b"MockAppleTV", f"unexpected server id: {inner2[1]}"
    signed = (server_pub + client_id + our_pub) if wrong_order \
        else (our_pub + client_id + server_pub)
    sig = ed.sign(signed)
    inner3 = write_tlv({1: client_id, 10: sig})
    enc3 = ChaCha20Poly1305(skey).encrypt(b"\x00" * 4 + b"PV-Msg03", inner3, None)
    m4 = read_tlv(c.post("/pair-verify", write_tlv({6: b"\x03", 5: enc3})))
    c.close()
    return m4.get(7, b"")[0] if 7 in m4 else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=7000)
    args = ap.parse_args()
    client_id, ed = pair_setup(args.port)
    print("pair-setup OK")
    err_wrong = pair_verify(args.port, client_id, ed, wrong_order=True)
    print(f"wrong-order M3 -> M4 error: {err_wrong}")
    err_right = pair_verify(args.port, client_id, ed, wrong_order=False)
    print(f"correct-order M3 -> M4 error: {err_right}")
    ok = err_wrong == 2 and err_right is None
    print("STRICT CHECK PASSED" if ok else "STRICT CHECK FAILED")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Self-test for tests/mock_fairplay.py — golden vectors from
tests/FairPlayTest.kt (doubletake vectors, LGPL-3.0).

Runs every golden vector, prints PASS/FAIL per vector, exits 0 only
if ALL pass. Run with: tests/venv/bin/python tests/mock_fairplay_selftest.py

Notes on the two unwrap vectors:
- The synthetic ekey (ekey[i] = i*7+3) has no valid FPLY header and no
  HMAC, by construction (the Kotlin/Go tests use an unwrap helper that
  validates nothing but the size). It is checked here through the raw
  unwrap path (mock_fairplay._unwrap_ekey_raw), which is exactly the
  ECB-decrypt + mask-xor step.
- The captured ekey/m3 vector likewise only pins the raw-key decryption
  in the Go/Kotlin tests: its HMAC-SHA1 field does NOT verify under
  descriptor(senderSAP, receiverSAP) (checked against every plausible
  descriptor ordering / HMAC input; the Go comment says the vector is
  from the independent playfair_decrypt reference, whose MAC was made
  under a different session). It is therefore checked through the raw
  unwrap path too, and a separate check asserts that the validating
  unwrap_ekey() DOES reject it for the bad MAC — the validation itself
  is proven end-to-end by the self-consistent altmac ekey below, which
  unwrap_ekey() accepts and whose tampered copies it rejects.
"""

import hashlib
import hmac
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mock_fairplay as mf
from fairplay_tables import M1_PAYLOAD

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

FAILURES = []


def check(name, cond, extra=""):
    if cond:
        print("PASS %s" % name)
    else:
        print("FAIL %s %s" % (name, extra))
        FAILURES.append(name)


def hx(b):
    return bytes(b).hex()


LOCAL_SAP = bytes.fromhex('0001e4e3dd688293e6fa66b95ba41768e587c65f750218ff1be21543d573cefb087bd36e0c6363c3c8242f4abcfa6d660b801032015405eb4ab04dda7aeff38ffb36f4cfa48f0b5d92ae363f68b45925bbe6413ab6bdc4968f548d21e67d20f1912b6820e53f1013cde29df7350a9b9fa7c51320aea62d2949786c87642e34ba')
RECEIVER_SAP = bytes.fromhex('0001cc342a5e5b1a6773c20e21b8224df862481864ef810aae2e3703c8819c23539de5f5d749bc5b7a266c496283ce7f03937ae1f616de0c15ff338ccaffb09eaabbe40f5d5f558fb97f1731f8f7da60a0ec6579c33ea98312c3b67135a6694ff82305d9ba5c615fa254d2b1834583cee42d4426c835a7a5f6c8421c0da3f1c7')
CAPTURED_M2 = bytes.fromhex('46504c59030102000000008202034a114c26b77d4e2eec2c8f89fdb653b5b32d3576bc176816d110a14c3f53c08dbb936183bfdfe0a4f3c12e85216003b46f738c40c54da6c436d29d1b342d63c7b314309ae79a33bb1787709ef077cbfe4190117a3423e270fd1a2eac44da1a7934f59dc681d1b70783f228c4d077c2d495f5285c3bf8df586fc2ebfe17fb5b65')
CAPTURED_M3 = bytes.fromhex('46504c590301030000000098018f1a9c7d0af257b31f21f5c2d2bc814c032d457835ad0b06250574bbc7ab4a58cca6eead2c911d7f3e1e7ed4c058955dff3d5ceef014387a985bdb34995015e3dfbdacc56047cb926e093b13e9fdb5e1eee317c018bbc87fc5453c7671647da686da3d564875d03f8aea9d60092de06110bc7be0c16f391c369c75344ae47f33acfcf10e63a9b58bfce215e96001c49e4be967c5067f2a')
CAPTURED_EKEY = bytes.fromhex('46504c59010201000000003c0000000088e4f82c8178c18b4751ac24b27c0c2a00000010c899dc6965c1081de6a9d966e2ba3e34548cdbc651c322db18dc22f58fe154a60aecee18')
ALT_M3 = bytes.fromhex('46504c590301030000000098018f1a9c5b9228300aafe0b41f28b66a62a6cd62bf84eb623273dead10b1f034a8d568126faa133f6ad5ab91acda3839817b4d9530b679fee43ac9e950f6e7aaf1381bd2d3d5198a03bf5648890d19234270a3583e4651893be09c6c75463c42e544fec9abc9f7722a2cc254364365ef91ded76b8c00f9674b08920fb9401e4be6d52a33f2f9ed6fadb672be45c3cde5ad94f3fea5b32ee4')
ALT_RAW = bytes.fromhex('000102030405060708090a0b0c0d0e0f')
ALT_MASK = bytes.fromhex('c853e777b9b65e7652d768d97c974f15')

MSG_VECTORS = [
        (0, 'b66a3295ffa6b56e02ed1b3d67fef74b90fe148570de65e6773669126a4905d8405644cae0b2f5ed6109c099c7aea7398dac8d623fbd69b87242b374d98f89502bb5a63e29c46a8ed0e98466966191ec1e6c8675087fde21337db1c8fab4c21db824026335f6fc37e2e5b6f53357d06994bd383d6029a0aff654fb1521bcdde4', 'f7dd1ccb9e745f7951a6e325d73a1f5f'),
        (1, '0f95c6ddc8987eda18577da2db074e7c04715af8b3914a73be1b3d6c111953017ee0a39dfcab3e0d57f2f9fbd59c5e18101788c2ab8e3cbb403bcb48b53f3e5bf74f949e79fa5ca679df4bfcb33a69b1442675d03f948fe5bd0c5ffb64b73a5ab58f46d6baae097b599624147c2487991163ecffc4d966240f9526346a10fdb0', 'b44ad891396f097aa309bc132f5b8889'),
        (2, '40f18751b44d733e0aa0416401a7d3f40375fad3ce56900602578bca14660909820e6ef3a5e943cafef5370f72c52177d9b82278b414811201a3d99202bedcca26a4d1ad08bc2669f4bae6ca54b8a120d0425edb6082f51f5aecdb547bfdb319099c9ea2729ae6a1c4480827ce9991e273843cf1c7d74ebbebc2657659bcea9f', 'd38cd8efecdb20f333273c4312d9b236'),
        (3, '70a3c30edf0e1dfa1785ce4336ed547062672a47f714a0c1f89a83d95691103dfe5cf653d4cb8299793faf33fd0d4482ef5333b41ab094a90e1baf996bcf4989783f6918397fbacddaf00a2b97556dd8099841578bc5eb1444912b47298eaf356fdd6701bb3f64e725a80eb4c6f3556195de35c93e7cc703bdd24351468e9847', '769e2fe4c5ad7fbe6fd6772d00f529f4')
]


def main():
    # --- m1 record ---
    rec = mf.new_fpsap_record(1, len(M1_PAYLOAD))
    rec[12:] = bytes(M1_PAYLOAD)
    check("fp m1", hx(rec) == "46504c590301010000000004020003bb")
    try:
        mf.validate_fpsap_record(bytes(rec), 1, 4)
        check("fp record-valid", True)
    except ValueError:
        check("fp record-valid", False)

    # --- Modified MD5 + SAP hash ---
    block = bytes((i * 3 + 1) & 0xFF for i in range(64))
    key = bytes(range(16))
    words = mf.fairplay_md5_compress(mf.fairplay_words_from_le(key), block, 2)
    md5le = b"".join(w.to_bytes(4, "little") for w in words)
    check("fp md5-kdf", hx(md5le) == "f6f728cb5a4397b675664f9291b859aa")
    check("fp sap-hash", hx(mf.fairplay_sap_hash(block)) == "75498a4e218773030e9cdf04f0c49367")

    # SAP hash corpus: 64 rounds of xorshift, SHA-256 of the digests.
    corpus = hashlib.sha256()
    state = 0x6A09E667F3BCC909
    mask64 = (1 << 64) - 1
    for _ in range(64):
        b = bytearray(64)
        for i in range(64):
            state = (state ^ ((state << 13) & mask64)) & mask64
            state = state ^ (state >> 7)
            state = (state ^ ((state << 17) & mask64)) & mask64
            b[i] = state & 0xFF
        corpus.update(mf.fairplay_sap_hash(bytes(b)))
    check(
        "fp sap-hash-corpus",
        corpus.hexdigest() == "36ad2a7920076af59452d9f0c91e3b7d1aebc53f9143bd6819e39119d4535c92",
    )

    # --- Message vectors modes 0..3 ---
    for mode, decrypted, aes_key in MSG_VECTORS:
        message = bytearray(164)
        message[12] = mode
        for i in range(16, 144):
            message[i] = (i * 5 + 7) & 0xFF
        dec = mf.decrypt_fairplay_message(bytes(message))
        check("fp msg-decrypt-mode%d" % mode, hx(dec) == decrypted)
        enc = mf.encrypt_fairplay_message(mode, dec)
        check("fp msg-encrypt-mode%d" % mode, hx(enc) == hx(message[16:144]))
        check(
            "fp msg-decrypt-repeat-mode%d" % mode,
            mf.decrypt_fairplay_message(bytes(message)) == dec,
        )
        wrapping = mf.derive_wrapping_key(RECEIVER_SAP, bytes(message))
        check("fp wrapping-key-mode%d" % mode, hx(wrapping) == aes_key)

    # --- Key unwrap vector, synthetic ekey (mode 3) ---
    # Raw path only: this ekey has no valid header/HMAC by construction.
    m3 = bytearray(164)
    m3[12] = 3
    for i in range(16, 144):
        m3[i] = (i * 5 + 7) & 0xFF
    ekey = bytes((i * 7 + 3) & 0xFF for i in range(72))
    check(
        "fp key-unwrap-synthetic",
        hx(mf._unwrap_ekey_raw(RECEIVER_SAP, bytes(m3), ekey)) == "903e5be94732428e9965afb262b193a4",
    )
    try:
        mf.unwrap_ekey(RECEIVER_SAP, bytes(m3), ekey)
        check("fp key-unwrap-synthetic-rejected-by-validator", False)
    except ValueError:
        check("fp key-unwrap-synthetic-rejected-by-validator", True)

    # --- Captured ekey/m3 decrypt vector ---
    # Raw path only for the raw key (see module docstring: its MAC does
    # not verify under this session's descriptor).
    check(
        "fp captured-raw-key",
        hx(mf._unwrap_ekey_raw(RECEIVER_SAP, CAPTURED_M3, CAPTURED_EKEY))
        == "8e1214398d46d72e7b1b8e32f80c8bf0",
    )
    check(
        "fp captured-ekey-len",
        CAPTURED_EKEY[32] == 0 and CAPTURED_EKEY[33] == 0
        and CAPTURED_EKEY[34] == 0 and CAPTURED_EKEY[35] == 16,
    )
    try:
        mf.unwrap_ekey(RECEIVER_SAP, CAPTURED_M3, CAPTURED_EKEY)
        check("fp captured-mac-rejected-by-validator", False)
    except ValueError:
        check("fp captured-mac-rejected-by-validator", True)

    # --- Self-consistent ekey: proves the validating unwrap path ---
    # Built with the wrap construction (Go fairplay_crypto.go) from the
    # altmac captured m3; the Kotlin test pins ekey[:56] for this input.
    sender_sap = mf.decrypt_fairplay_message(ALT_M3)
    wrapping_key = mf.derive_wrapping_key(RECEIVER_SAP, ALT_M3)
    masked = bytes(ALT_RAW[i] ^ ALT_MASK[i] for i in range(16))
    enc = Cipher(algorithms.AES(wrapping_key), modes.ECB()).encryptor()
    chunk2 = enc.update(masked) + enc.finalize()
    good = bytearray(72)
    good[0:16] = bytes.fromhex("46504c59010201000000003c00000000")
    good[16:32] = ALT_MASK
    good[32:36] = (16).to_bytes(4, "big")
    good[56:72] = chunk2
    mac_key = mf.fpsap_descriptor_for_sap(sender_sap, RECEIVER_SAP)
    good[36:56] = hmac.new(mac_key, bytes(good[0:36]) + ALT_RAW, hashlib.sha1).digest()
    check(
        "fp altmac-ekey-prefix",
        hx(good[0:56])
        == "46504c59010201000000003c00000000c853e777b9b65e7652d768d97c974f15000000102a53a0008888fe26bfb1e1f825f38f50d6730059",
    )
    check(
        "fp altmac-mac-key",
        hx(mac_key) == "2fd95dc2c23122bc77c57b983a9188c4760db322",
    )
    check(
        "fp unwrap-validated",
        mf.unwrap_ekey(RECEIVER_SAP, ALT_M3, bytes(good)) == ALT_RAW,
    )
    for label, tamper in (
        ("mac", 40),
        ("header", 5),
        ("size", 35),
    ):
        bad = bytearray(good)
        bad[tamper] ^= 1
        try:
            mf.unwrap_ekey(RECEIVER_SAP, ALT_M3, bytes(bad))
            check("fp unwrap-tampered-%s-rejected" % label, False)
        except ValueError:
            check("fp unwrap-tampered-%s-rejected" % label, True)

    # --- Exchange golden vectors ---
    exchange_cases = [
        ("all-zeros", bytes(128), "6f627565f3e77f5b5ede91beee7baf92e4241e0b"),
        ("all-ff", bytes([0xFF] * 128), "dc2cc74f2ed55484f59f95b96082f0f5c017dd17"),
        ("captured-m2", CAPTURED_M2[14:142], "4b911e48af23d8406368aeafbb61bfcd569e3e55"),
        ("42-at-0", bytes(bytearray([0x42] + [0] * 127)), "9bfb9556b8659c2ac94b7ef9e587d71e159ea624"),
        ("42-at-63", bytes(bytearray([0] * 63 + [0x42] + [0] * 64)), "150d9fa4eb456e73ba48de5779c5c996b16b3b23"),
        ("42-at-64", bytes(bytearray([0] * 64 + [0x42] + [0] * 63)), "a167db30424ff8890d085c0f1c92b2c5cc06fc45"),
        ("42-at-127", bytes(bytearray([0] * 127 + [0x42])), "d246ec5e7adc8118994b8df77146529486ac7caf"),
    ]
    for name, payload, want in exchange_cases:
        m2sap = mf.decrypt_fpsap_body(3, payload)
        check(
            "fp exchange-%s" % name,
            hx(mf.fpsap_exchange_for_sap(LOCAL_SAP, m2sap)) == want,
        )

    # --- Descriptor vectors ---
    check(
        "fp descriptor-zero",
        hx(mf.fpsap_descriptor_for_sap(LOCAL_SAP, mf.decrypt_fpsap_body(3, bytes(128))))
        == "7e38958ffe4ed433743919fe7eb16376afa4eb9e",
    )
    check(
        "fp descriptor-one-at-zero",
        hx(mf.fpsap_descriptor_for_sap(
            LOCAL_SAP, mf.decrypt_fpsap_body(3, bytes(bytearray([1] + [0] * 127)))
        )) == "ea46797d726c6a9be43ffa72385ff97ce1c54f1b",
    )

    # --- Random round-trip, all modes ---
    rng = random.Random(20261110)
    for mode in range(4):
        plain = rng.randbytes(128)
        check(
            "fp random-roundtrip-mode%d" % mode,
            mf.decrypt_fairplay_message(
                _wrap_message(mode, mf.encrypt_fairplay_message(mode, plain))
            ) == plain,
        )

    # --- Exchange determinism ---
    m2sap = mf.decrypt_fpsap_body(3, bytes(128))
    check(
        "fp exchange-deterministic",
        mf.fpsap_exchange_for_sap(LOCAL_SAP, m2sap)
        == mf.fpsap_exchange_for_sap(LOCAL_SAP, m2sap),
    )

    # --- Record validation rejects ---
    for label, record, rtype, plen in (
        ("short", bytes(15), 1, 4),
        ("bad-magic", bytes(bytearray([0x58, 0x50, 0x4C, 0x59, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 3, 187])), 1, 4),
        ("bad-type", bytes(rec[:6]) + bytes([2]) + bytes(rec[7:]), 1, 4),
        ("bad-len", bytes(rec[:11]) + bytes([5]) + bytes(rec[12:]), 1, 4),
    ):
        try:
            mf.validate_fpsap_record(record, rtype, plen)
            check("fp record-%s-rejected" % label, False)
        except ValueError:
            check("fp record-%s-rejected" % label, True)

    total = len(FAILURES)
    if total == 0:
        print("MOCK_FAIRPLAY ALL PASS")
        return 0
    print("MOCK_FAIRPLAY FAILURES: %d (%s)" % (total, ", ".join(FAILURES)))
    return 1


def _wrap_message(mode, body):
    msg = bytearray(164)
    msg[12] = mode
    msg[16:144] = body
    return bytes(msg)


if __name__ == "__main__":
    sys.exit(main())

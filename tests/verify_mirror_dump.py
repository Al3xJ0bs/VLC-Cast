#!/usr/bin/env python3
"""Independent verifier for AirCast mirror stream dumps (v0.4.7).

Built ONLY from doubletake's protocol code
(research/doubletake/internal/airplay/mirror.go, audio.go, fairplay.go)
— NOT from MirrorClient.kt. It parses an ACD1 dump file (the exact bytes
the sender put on the data channel + the SETUP bodies), re-derives the
video ChaCha key the doubletake way, decrypts the video frames and
validates the H.264 stream:

  * ChaCha20-Poly1305 decrypt of every 'V' record (nonce = 4 zero bytes
    + LE64 counter from 0, AAD = the full 128-byte header — doubletake
    mirror.go:2099-2109), tag must verify;
  * AVCC structure of every decrypted payload (4-byte BE lengths, first
    NAL type must match the IDR flag — doubletake _validate_avcc);
  * frame timestamps monotonic (codec frame and its video frame may share
    one ts — doubletake mirror.go: the codec frame and its video frame
    share one packetTimestamp), timeline ID constant;
  * avcC of every 'C' record (profile/level/SPS/PPS) + SPS decode
    (Exp-Golomb, H.264 spec — profile/level/width/height/VUI);
  * heartbeat shape ([4]=0x02, [6]=0x1e, no payload — mirror.go:2218);
  * SETUP bodies ('Q'/'S') listed with their keys (video SETUP request
    streamConnectionID vs response streamConnectionID/dataPort).

Key: --key <hex> (32-byte ChaCha key, e.g. from the dump's .txt summary)
or --derive <shared-secret-hex> <streamConnectionID> (HKDF-SHA512,
salt "DataStream-Salt<id>", info "DataStream-Output-Encryption-Key" —
doubletake mirror.go:2203-2213, IKM = pair-verify X25519 shared secret).

Usage:
  python3 tests/verify_mirror_dump.py dump.bin --key <hex>
  python3 tests/verify_mirror_dump.py dump.bin --derive <shared-hex> <id> [--clock-id 0x...]
Exit 0 = stream is doubletake-correct, 1 = problems found.
"""

import argparse
import hashlib
import hmac
import plistlib
import struct
import sys


def hkdf_sha512(salt, info, ikm, length=32):
    """HKDF-SHA512 (RFC 5869), doubletake mirror.go deriveChaChaKey."""
    prk = hmac.new(salt, ikm, hashlib.sha512).digest()
    out = b""
    prev = b""
    counter = 1
    while len(out) < length:
        prev = hmac.new(prk, prev + info + bytes([counter]), hashlib.sha512).digest()
        out += prev
        counter += 1
    return out[:length]


def derive_chacha_key(shared_secret, stream_connection_id):
    salt = ("DataStream-Salt%d" % stream_connection_id).encode()
    return hkdf_sha512(salt, b"DataStream-Output-Encryption-Key", shared_secret)


class BitReader:
    """H.264 Exp-Golomb reader (spec, not our decodeSps)."""

    def __init__(self, data):
        self.data = data
        self.pos = 0

    def _bit(self):
        byte = self.data[self.pos // 8]
        bit = (byte >> (7 - (self.pos % 8))) & 1
        self.pos += 1
        return bit

    def bits(self, n):
        v = 0
        for _ in range(n):
            v = (v << 1) | self._bit()
        return v

    def ue(self):
        zeros = 0
        while self._bit() == 0:
            zeros += 1
            if zeros > 31:
                raise ValueError("ue(v) overflow")
        return (1 << zeros) - 1 + self.bits(zeros)

    def se(self):
        v = self.ue()
        return (v + 1) // 2 if v % 2 else -(v // 2)


def decode_sps(sps):
    """Decode an SPS RBSP (Annex-B start code / emulation prevention
    already stripped by the caller). Returns dict or raises ValueError."""
    if len(sps) < 4 or (sps[0] & 0x1F) != 7:
        raise ValueError("not an SPS NAL (type %d)" % (sps[0] & 0x1F))
    # Strip emulation prevention bytes (00 00 03 -> 00 00).
    rbsp = bytearray()
    i = 1
    while i < len(sps):
        if i + 2 < len(sps) and sps[i] == 0 and sps[i + 1] == 0 and sps[i + 2] == 3:
            rbsp += b"\x00\x00"
            i += 3
        else:
            rbsp.append(sps[i])
            i += 1
    r = BitReader(bytes(rbsp))
    profile = r.bits(8)
    r.bits(8)  # constraint flags + reserved
    level = r.bits(8)
    r.ue()  # seq_parameter_set_id
    if profile in (100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135):
        chroma = r.ue()
        if chroma == 3:
            r.bits(1)
        r.ue()  # bit_depth_luma_minus8
        r.ue()  # bit_depth_chroma_minus8
        r.bits(1)  # qpprime_y_zero_transform_bypass_flag
        if r.bits(1):  # seq_scaling_matrix_present_flag
            for k in range(12 if chroma != 3 else 8):
                if r.bits(1):
                    n = 16 if k < 6 else 64
                    last = next_ = 8
                    for _ in range(n):
                        delta = r.se()
                        next_ = (last + delta + 256) % 256
                        if next_:
                            last = next_
    r.ue()  # log2_max_frame_num_minus4
    poc_type = r.ue()
    if poc_type == 0:
        r.ue()
    elif poc_type == 1:
        r.bits(1)
        r.se()
        r.se()
        n = r.ue()
        for _ in range(n):
            r.se()
    r.ue()  # max_num_ref_frames
    r.bits(1)  # gaps_in_frame_num_value_allowed_flag
    w_mbs = r.ue() + 1
    h_map = r.ue() + 1
    frame_mbs_only = r.bits(1)
    if not frame_mbs_only:
        r.bits(1)
    r.bits(1)  # direct_8x8_inference_flag
    crop_left = crop_right = crop_top = crop_bottom = 0
    if r.bits(1):  # frame_cropping_flag
        crop_left = r.ue()
        crop_right = r.ue()
        crop_top = r.ue()
        crop_bottom = r.ue()
    vui = bool(r.bits(1))
    unit_x, unit_y = 2, 2 * (2 - frame_mbs_only)
    width = w_mbs * 16 - (crop_left + crop_right) * unit_x
    height = (2 - frame_mbs_only) * h_map * 16 - (crop_top + crop_bottom) * unit_y
    return {"profile": profile, "level": level, "width": width,
            "height": height, "vui": vui}


def parse_avcc(payload):
    """Walk 4-byte BE lengths; return list of (nal_type, nal_bytes)."""
    nals = []
    off = 0
    while off + 4 <= len(payload):
        ln = struct.unpack(">I", payload[off:off + 4])[0]
        if ln == 0 or off + 4 + ln > len(payload):
            raise ValueError("bad AVCC length %d at %d (payload %d B)" % (ln, off, len(payload)))
        nal = payload[off + 4:off + 4 + ln]
        nals.append((nal[0] & 0x1F, nal))
        off += 4 + ln
    if off != len(payload) or not nals:
        raise ValueError("AVCC walk ended at %d/%d, %d NALs" % (off, len(payload), len(nals)))
    return nals


def parse_avcc_config(payload):
    """avcC payload (doubletake buildAVCCConfig): 01, profile, 0xff,
    0xe1, BE16 SPS len, SPS, 0x01, BE16 PPS len, PPS, 4-byte trailer."""
    if len(payload) < 11 or payload[0] != 0x01 or payload[4] != 0xFF or payload[5] != 0xE1:
        raise ValueError("bad avcC header %s" % payload[:8].hex())
    sps_len = struct.unpack(">H", payload[6:8])[0]
    sps = payload[8:8 + sps_len]
    if len(sps) != sps_len or (sps[0] & 0x1F) != 7:
        raise ValueError("bad SPS in avcC")
    off = 8 + sps_len
    if payload[off] != 0x01:
        raise ValueError("bad PPS count in avcC")
    pps_len = struct.unpack(">H", payload[off + 1:off + 3])[0]
    pps = payload[off + 3:off + 3 + pps_len]
    if len(pps) != pps_len or (pps[0] & 0x1F) != 8:
        raise ValueError("bad PPS in avcC")
    return {"profile": payload[1], "level": payload[3], "sps": sps, "pps": pps,
            "sps_info": decode_sps(sps)}


def read_dump(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"ACD1":
        raise ValueError("not an ACD1 dump (magic %r)" % data[:4])
    records = []
    off = 4
    while off < len(data):
        if off + 5 > len(data):
            raise ValueError("truncated record header at %d" % off)
        kind = chr(data[off])
        ln = struct.unpack(">I", data[off + 1:off + 5])[0]
        if off + 5 + ln > len(data):
            raise ValueError("truncated record %r at %d" % (kind, off))
        records.append((kind, data[off + 5:off + 5 + ln]))
        off += 5 + ln
    return records


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("dump", help="ACD1 dump file (.bin)")
    ap.add_argument("--key", default=None, help="32-byte ChaCha key, hex")
    ap.add_argument("--derive", nargs=2, metavar=("SHARED_HEX", "STREAM_ID"),
                    help="derive the key doubletake-style from the HAP shared secret")
    ap.add_argument("--clock-id", default=None, help="expected timeline ID, hex (e.g. 0x1122334455667788)")
    args = ap.parse_args()

    if args.key and args.derive:
        ap.error("use --key or --derive, not both")
    if args.derive:
        shared = bytes.fromhex(args.derive[0])
        key = derive_chacha_key(shared, int(args.derive[1]))
        print("key derived (doubletake HKDF): %s" % key.hex())
    elif args.key:
        key = bytes.fromhex(args.key)
    else:
        key = None
    if key is not None and len(key) != 32:
        ap.error("ChaCha key must be 32 bytes")
    clock_id = int(args.clock_id, 0) if args.clock_id else None

    try:
        from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
    except ImportError:
        print("ERROR: cryptography package not available")
        return 1

    try:
        records = read_dump(args.dump)
    except Exception as e:
        print("ERROR: cannot parse dump: %s" % e)
        return 1

    problems = []
    counts = {"C": 0, "V": 0, "H": 0, "Q": 0, "S": 0}
    decrypt_ok = decrypt_fail = avcc_ok = avcc_fail = 0
    idr_frames = 0
    nonce_counter = 0
    last_ts = None
    last_was_codec = False
    tids = set()
    sps_infos = []
    first_video_ts = None

    for kind, payload in records:
        counts[kind] = counts.get(kind, 0) + 1
        if kind == "Q" or kind == "S":
            try:
                body = plistlib.loads(payload)
                keys = sorted(body.keys()) if isinstance(body, dict) else type(body).__name__
                streams = body.get("streams") if isinstance(body, dict) else None
                extra = ""
                if isinstance(streams, list) and streams:
                    s0 = streams[0]
                    extra = " streams[0]: type=%s streamConnectionID=%s dataPort=%s" % (
                        s0.get("type"), s0.get("streamConnectionID"), s0.get("dataPort"))
                print("SETUP %s: %d B, keys=%s%s" % ("request" if kind == "Q" else "response",
                                                     len(payload), keys, extra))
            except Exception as e:
                print("SETUP %s: %d B, not a bplist (%s)" % ("request" if kind == "Q" else "response",
                                                            len(payload), e))
            continue
        if len(payload) < 128:
            problems.append("%s record: only %d B (header is 128)" % (kind, len(payload)))
            continue
        header, body = payload[:128], payload[128:]
        ptype = header[4]
        if kind == "H":
            if ptype != 0x02 or header[6] != 0x1E or len(body) != 0:
                problems.append("heartbeat: bad shape [4]=0x%02x [6]=0x%02x len=%d" % (ptype, header[6], len(body)))
            continue
        if kind == "C":
            if ptype != 0x01:
                problems.append("codec frame: [4]=0x%02x, expected 0x01" % ptype)
                continue
            try:
                cfg = parse_avcc_config(body)
                sps_infos.append(cfg["sps_info"])
                print("codec frame: avcC profile=%d level=%d SPS %d B %dx%d VUI=%s PPS %d B" % (
                    cfg["profile"], cfg["level"], len(cfg["sps"]),
                    cfg["sps_info"]["width"], cfg["sps_info"]["height"],
                    cfg["sps_info"]["vui"], len(cfg["pps"])))
            except Exception as e:
                problems.append("codec frame: avcC/SPS parse failed: %s" % e)
            ts = struct.unpack("<Q", header[8:16])[0]
            last_ts = ts
            last_was_codec = True
            continue
        if kind == "V":
            if ptype != 0x00:
                problems.append("video frame: [4]=0x%02x, expected 0x00" % ptype)
                continue
            is_idr = header[5] == 0x10
            ts = struct.unpack("<Q", header[8:16])[0]
            tid = struct.unpack("<Q", header[40:48])[0]
            tids.add(tid)
            if first_video_ts is None:
                first_video_ts = ts
            if last_ts is not None:
                if ts < last_ts or (ts == last_ts and not last_was_codec):
                    problems.append("video frame #%d: ts not increasing (%d <= %d)" % (
                        counts["V"], ts, last_ts))
            last_ts = ts
            last_was_codec = False
            if is_idr:
                idr_frames += 1
            if key is None:
                problems.append("video frame #%d: no key given (--key/--derive), cannot decrypt" % counts["V"])
                continue
            nonce = b"\x00" * 4 + nonce_counter.to_bytes(8, "little")
            nonce_counter += 1
            try:
                plain = ChaCha20Poly1305(key).decrypt(nonce, body, header)
                decrypt_ok += 1
            except Exception as e:
                decrypt_fail += 1
                problems.append("video frame #%d: ChaCha decrypt failed (nonce %d): %s" % (
                    counts["V"], nonce_counter - 1, e))
                continue
            try:
                nals = parse_avcc(plain)
                first_type = nals[0][0]
                if is_idr and first_type != 5:
                    raise ValueError("IDR flag set but first NAL type is %d" % first_type)
                if not is_idr and first_type == 5:
                    raise ValueError("IDR NAL (type 5) without the IDR flag")
                avcc_ok += 1
            except Exception as e:
                avcc_fail += 1
                problems.append("video frame #%d: AVCC invalid: %s" % (counts["V"], e))
            continue
        problems.append("unknown record kind %r" % kind)

    print("records: codec=%d video=%d heartbeat=%d setupReq=%d setupResp=%d" % (
        counts["C"], counts["V"], counts["H"], counts["Q"], counts["S"]))
    print("video: decrypt_ok=%d decrypt_fail=%d avcc_ok=%d avcc_fail=%d idr=%d" % (
        decrypt_ok, decrypt_fail, avcc_ok, avcc_fail, idr_frames))
    if tids:
        print("timeline IDs seen: %s" % ", ".join("0x%016x" % t for t in sorted(tids)))
        if len(tids) > 1:
            problems.append("timeline ID changed mid-stream: %s" % tids)
        if clock_id is not None and tids != {clock_id}:
            problems.append("timeline ID %s != expected clock-id 0x%016x" % (tids, clock_id))
    if sps_infos:
        s0 = sps_infos[0]
        print("SPS: profile=%d level=%d %dx%d VUI=%s" % (
            s0["profile"], s0["level"], s0["width"], s0["height"], s0["vui"]))
        if any(s != s0 for s in sps_infos):
            problems.append("SPS changed between codec frames")
    if counts["V"] == 0:
        problems.append("no video frames in dump")
    if counts["C"] == 0 and counts["V"] > 0:
        problems.append("video frames without a codec frame")

    if problems:
        print("VERDICT: FAIL (%d problem%s)" % (len(problems), "s" if len(problems) != 1 else ""))
        for p in problems:
            print("  - %s" % p)
        return 1
    print("VERDICT: PASS — stream is doubletake-correct "
          "(%d video frames decrypted, %d codec frames, %d heartbeats)" % (
              counts["V"], counts["C"], counts["H"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())

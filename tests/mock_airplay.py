#!/usr/bin/env python3
"""Mock Apple TV (AirPlay 2) for AirCast JVM tests.

Implements server-side HAP Pair-Setup (SRP-6a via srptools, PIN 1234) and
Pair-Verify (X25519 + Ed25519 via cryptography), HAP ChaCha20-Poly1305
framing, RTSP SETUP/RECORD/FEEDBACK, POST /play, GET /playback-info and
GET /server-info — mirroring pyatv's client expectations.

v0.5.2: the direct-cast flags below (--playback-info-500, --send-event,
--refuse-rcs, --ptp, --no-command-event, --reject-*, --clockports-port)
are UNUSED now — run_mock_test.sh starts only the mirror mocks
(7010-7012, --mirror), the Companion mock, and a plain 7000 used solely
as the HAP oracle for mock_strict_check.py. The flags and the /play,
/command and PTP code paths are kept, not stripped, so the mock still
documents what the dead direct cast expected; nothing starts them.

NOT a real Apple TV: it proves the Kotlin client speaks the protocol
(request framing, crypto, sequencing), not that tvOS 26/27 accepts it.

Since v0.1.3 the mock verifies Pair-Verify M3 STRICTLY per the HAP spec
(pyatv verify1: controller signature over clientPub + clientId +
serverPub) and answers M4 error 2 on a bad signature, like a real TV.
The v0.1.2 mock mirrored the app's own wrong byte order, so its pass
meant nothing — that is fixed here.

Usage: mock_airplay.py [--port 7000] [--pin 1234] [--skip-record]
                       [--playback-info-500] [--send-event]
                       [--refuse-rcs] [--ptp] [--no-command-event]
Prints "MOCK READY <port>" on stdout, writes tests/mock_summary.json on exit.

v0.2.1 flags (for session-guard / black-screen tests):
  --skip-record: SETUP отвечает {"eventPort": N, "skipRecord": true} —
    ТВ так велит на tvOS 27; v0.3.1: клиент в /command-пути RECORD
    всё равно шлёт (оба PR #2899/#2846 шлют RECORD до RCS безусловно),
    а флаг остался для проверки legacy-пути. v0.4.6: в зеркальном
    режиме (--mirror) флаг добавляет skipRecord=true и в ответ
    control SETUP (doubletake читает его оттуда) — для теста 14(d).
  --playback-info-500: GET /playback-info всегда 500 — как живой ТВ
    на tvOS 26/27; клиент должен дать V1-fallback и вердикт
    «ТВ не начал воспроизведение», а не «может играть».
  --send-event: после подключения event channel послать ОДНО
    синтетическое событие POST /event с bplist {"state": "playing"}.
    Плоская форма — legacy, для совместимости парсера; с v0.3.0
    /command-путь шлёт ВЛОЖЕННОЕ событие по #2846/#2899 (params.data).

v0.3.0 flags (для /command-пути, ожидания — по диффам pyatv #2846/#2899,
а не по коду приложения):
  --refuse-rcs: RCS SETUP отвечает 500 — клиент обязан откатиться на
    legacy POST /play + /playback-info и записать это в лог.
  --ptp: слушать UDP 319/320 как PTP-ведомый: считать Sync/Announce/
    Follow_Up, послать Delay_Req на исходный порт приложения и
    проверить Delay_Resp (sequenceId, requestingPortIdentity,
    receiveTimestamp ≈ время отправки Delay_Req). PTP-пакеты парсятся
    по IEEE 1588-2008, а не по кодировке приложения.
  --no-command-event: после insertPlayQueueItem НЕ слать вложенное
    событие playing — /command-путь без единого события: клиент обязан
    дать вердикт «ТВ не начал воспроизведение» через 15 с (watcher),
    без V1-fallback и без опроса /playback-info.
  GET /info отвечает {"psi": PSI}; RCS SETUP (streams type 130)
  отвечает {"streams": [{"streamID": 1}]}; POST /command до RCS — 500,
  после — 200; после insertPlayQueueItem на event channel уходит
  вложенное событие playbackState playing (position 1.0, duration
  60.0, rate 1.0); на playbackInfo-команду — событие kind=response
  с тем же messageID.
  Summary пишется НА ХОДУ (write-through в note()/flush_summary),
  чтобы JVM-тест мог читать его во время прогона, а не после kill.

v0.3.1 — СТРОГИЙ /command: живой ТВ ответил 400 на insertPlayQueueItem
  v0.3.0, а mock его принимал (писался с того же понимания, что и
  приложение). Теперь validate_command() проверяет тело по диффам
  pyatv #2846/#2899 НЕЗАВИСИМО от кода приложения и отвечает 400 на:
  неверную вложенность params.data (не bplist-байты), отсутствие
  обязательных ключей, mediaType != "file", Start-Position с
  timescale != 1 (v0.3.0 слал мс/1000), playerLoggingID длиннее
  6 символов, uuid не в верхнем регистре, IsTLSEnabled не по схеме
  URL. v0.5.2: негативный тест /command (mock_command_strict_check.py)
  удалён вместе с прямым кастом; validate_command() оставлен как
  документация мёртвого пути.
"""
import argparse
import hashlib
import hmac
import json
import os
import plistlib
import queue
import signal
import socket
import struct
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey, Ed25519PublicKey)
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey, X25519PublicKey)
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from srptools import SRPContext, SRPServerSession, constants

PIN = "1234"
SKIP_RECORD = False
PLAYBACK_INFO_500 = False
SEND_EVENT = False
REFUSE_RCS = False
PTP = False
# v0.3.3: рекламированный PTP-порт ТВ в timingPeerInfo ClockPorts
# ответа SETUP (0 — не рекламировать, фолбэк 319).
CLOCKPORTS_PORT = 0
NO_COMMAND_EVENT = False
# v0.3.2: симуляция ТВ, которому нужен ПОЛНЫЙ item #2899, а минимальный
# #2846 он отклоняет 400, — для проверки второй попытки приложения
# (mock 7005 в run_mock_test.sh). По умолчанию — выключено: обычный
# mock принимает обе живые формы.
REJECT_MINIMAL = False
# v0.3.5: симуляция ТВ, который отклоняет insert БЕЗ messageID
# (все формы), а insert с kind="request"+messageID — принимает.
# Модель tvOS 27.2, если она требует messageID на insert (probe
# playbackInfo с kind+messageID живой ТВ принял 200, а insert без
# них — 400, в одной сессии). Для проверки третьей попытки
# приложения (mock 7006 в run_mock_test.sh). По умолчанию выключено.
REJECT_ALL_INSERTS = False
# v0.3.5: симуляция ТВ, который отклоняет ЛЮБОЙ insert 400
# (все три попытки приложения) — для проверки вердикта «Вставка в
# очередь отклонена, воспроизведение невозможно» (mock 7007).
# Без этого флага mock принимает insert, как обычный ТВ.
REJECT_INSERTS_ALWAYS = False
# v0.3.2: допустимые поля item — объединение живых форм: минимальная
# #2846 {uuid, mediaType, Content-Location} и полная #2899 (+остальные).
# Поле вне этого множества — 400 (как живой ТВ на тело v0.3.1 с
# лишними полями, если это и есть причина 400).
ITEM_ALLOWED_KEYS = {
    "uuid", "mediaType", "Content-Location",
    "IsTLSEnabled", "playbackRestrictions", "referenceRestrictions",
    "supportsIntegratedTimeline", "snapTimeToPausePlayback",
    "clientBundleID", "clientProcName", "playerLoggingID",
    "playerItemLoggingID", "Start-Position",
}
MINIMAL_ITEM_KEYS = {"uuid", "mediaType", "Content-Location"}
# v0.3.0: psi для GET /info — фиксированный, чтобы тест мог проверить,
# что клиент взял channelID именно из /info, а не выдумал.
PSI = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
SUMMARY_PATH = "tests/mock_summary.json"
SUMMARY = {"pair_pin_start": 0, "pair_setup": [], "pair_verify": [],
           "server_info": 0, "setup": 0, "record": 0, "feedback": 0,
           "play": [], "rate": [], "playback_info": 0, "stop": 0,
           "set_property": 0, "scrub": 0, "volume": 0, "event_channel": 0,
           "info": 0, "rcs_setup": [], "command": [], "video_setup_timing": [],
           "command_playing_event": 0, "playback_info_response_event": 0,
           "ptp": {"sync": 0, "followup": 0, "announce": 0,
                   "delay_req": 0, "delay_resp": 0, "delay_resp_ok": False,
                   "followup_ok": False, "announce_ok": False,
                   # v0.3.3: Sync на рекламированном ClockPorts-порту;
                   # gPTP-форма Sync (majorSdoId 1 + UNICAST|TIMESCALE|
                   # TWO_STEP); TLV в Follow_Up.
                   "sync_adv": 0, "sync_gptp_ok": False,
                   "followup_tlv_ok": False,
                   # v0.3.5: clockIdentity первого Sync (первые 8 Б
                   # sourcePortIdentity) — должен равняться первым 8 Б
                   # sessionCorrelationUUID из видео-SETUP: PTP
                   # clockIdentity теперь берётся оттуда, а не случайно.
                   "sync_clock_id": None},
           "errors": []}
SUMMARY_LOCK = threading.Lock()

# ---------- Mirror mode (v0.4.0) ----------
# Mock TV that speaks the AirPlay mirroring protocol (doubletake
# setupMirrorSession): FairPlay SAP (/fp-setup), control/audio/video
# SETUP, RECORD, /feedback, event channel, TCP data channel with
# ChaCha20-Poly1305-encrypted H.264 frames. The FairPlay receiver side
# lives in tests/mock_fairplay.py (independent Python port of the
# doubletake spec, verified against the same golden vectors as the
# app's Kotlin port) — imported lazily so the non-mirror mocks keep
# working even if that module is absent.
MIRROR = False
# Mirror-mode features: the bits the mirroring client actually checks
# (7 canvas 1280x720 fallback, 14 FairPlay SAP, 18-21 audio/screen,
# 27 legacy pairing, 41 PTP, 42 HEVC-capable, 59 streamConnections,
# 61 RFC2198). This is a SUBSET of the real TV's 0x3c177fde4a7fdfd5
# (the TV sets many more bits); the mirror client only reads these.
MIRROR_FEATURES = (1 << 7) | (1 << 14) | (1 << 18) | (1 << 19) | (1 << 20) | (1 << 21) | (1 << 27) | (1 << 41) | (1 << 42) | (1 << 59) | (1 << 61)
MIRROR_SOURCE_VERSION = "980.71.1"
MIRROR_CLOCK_ID = 0x1122334455667788
MIRROR_SHARED = [None]        # pair-verify X25519 secret (data-channel keys)
MIRROR_VIDEO_STREAM_ID = [None]  # video streamConnectionID (ChaCha HKDF salt)
MIRROR_ANCHOR_COMPACT = [None]   # compact ts of the control-SETUP anchor
MIRROR_ANCHOR_WALL = [None]      # monotonic ms when the anchor was issued
MIRROR_DATA_LISTENER = [None]
MIRROR_EVENT_SERVER = [None]
# v0.4.7: аудио-UDP (реальные порты, как у живого ТВ — v0.4.6 ТВ дал
# 54495/55821; раньше mock рекламировал dataPort=0/controlPort=50010,
# ни на чём не слушая) + слушатель TimeAnnounce + дамп data-канала.
MIRROR_VIDEO_ECHO_ID = False  # --mirror-video-echo-id: другой streamConnectionID в video-ответе
MIRROR_DUMP_PATH = None        # --mirror-dump: куда писать ACD1-дамп принятых кадров
MIRROR_AUDIO_DATA_PORT = [0]
MIRROR_AUDIO_CTRL_PORT = [0]
MIRROR_AUDIO_CTRL_SOCK = [None]
MIRROR_ANNOUNCE = {"count": 0, "ok": True, "first_hex": None, "errors": []}
MIRROR_DUMP = {"fh": None, "lock": threading.Lock()}
SUMMARY["mirror"] = {
    "fp_setup": [], "control_setup": [], "audio_setup": [], "video_setup": [],
    "record": 0, "teardown": 0, "feedback": 0, "event_channel": 0,
    "frames": 0, "codec_frames": 0, "idr_frames": 0, "heartbeats": 0,
    "first_ts": None, "last_ts": None, "ts_monotonic": True,
    "timeline_ok": True, "decrypt_ok": 0, "decrypt_fail": 0,
    "avcc_ok": 0, "avcc_fail": 0, "sps_len": None, "pps_len": None,
    "errors": [],
    # v0.4.7: TimeAnnounce + параметры для независимого верификатора.
    "announce_count": 0, "announce_ok": True, "announce_first_hex": None,
    "shared_secret_hex": None, "video_stream_id": None,
    # v0.4.7-fix: секреты по сессиям (порядок = порядок pair-verify);
    # shared_secret_hex — последний, а дамп клиента — из 11-й секции
    # (первая mirror-сессия), поэтому верификатору нужен именно [0].
    "shared_secrets": [],
}

def mirror_note(key, value=True):
    """Note into SUMMARY['mirror'] (same semantics as note())."""
    with SUMMARY_LOCK:
        m = SUMMARY["mirror"]
        if isinstance(m.get(key), list):
            m[key].append(value)
        elif isinstance(m.get(key), int):
            m[key] += 1
        else:
            m[key] = value
    flush_summary()

def mirror_error(msg):
    with SUMMARY_LOCK:
        SUMMARY["mirror"]["errors"].append(msg)
    log(f"MIRROR ERROR: {msg}")
    flush_summary()

def mirror_info_dict():
    """GET /info body in mirror mode: the real TV's features/version."""
    return {
        "deviceID": "AA:BB:CC:DD:EE:FF",
        "features": MIRROR_FEATURES,
        "model": "Apple TV 4K",
        "name": "MockAppleTV",
        "sourceVersion": MIRROR_SOURCE_VERSION,
        "psi": PSI,
        "displays": [{"widthPixels": 1920, "heightPixels": 1080,
                      "widthPixelsMax": 1920, "heightPixelsMax": 1080}],
        "supportedFormats": {"screenStream": 0x01040000},  # ALAC + AAC-ELD
    }

def mirror_ptp_headers():
    """PTP anchor headers (mock boot-relative ms, like a real TV)."""
    now_ms = int(time.monotonic() * 1000)
    return ({"X-Apple-RequestReceivedTimestamp": str(now_ms),
             "X-Apple-ProcessingTime": "5"}, now_ms + 5)


def _validate_announce(pkt):
    """TimeAnnounce (doubletake audio.go sendSyncPacketAt, PTP): 28 B,
    [0]=0x90/0x80, [1]=0xd7, seq 4, playheadRTP дважды, networkTime —
    PTP-наносекунды, timelineID == ClockID."""
    if len(pkt) != 28:
        return False, f"len {len(pkt)} != 28"
    if pkt[0] not in (0x90, 0x80):
        return False, f"[0]=0x{pkt[0]:02x}"
    if pkt[1] != 0xD7:
        return False, f"[1]=0x{pkt[1]:02x} != 0xd7"
    if struct.unpack(">H", pkt[2:4])[0] != 4:
        return False, "seq != 4"
    if pkt[4:8] != pkt[16:20]:
        return False, "playheadRTP mismatch"
    ns = struct.unpack(">Q", pkt[8:16])[0]
    if ns == 0 or ns > 10 ** 16:
        return False, f"networkTime ns={ns}"
    tid = struct.unpack(">Q", pkt[20:28])[0]
    if tid != MIRROR_CLOCK_ID:
        return False, f"timelineID 0x{tid:016x} != ClockID"
    return True, ""


def _announce_listener():
    """Слушает аудио-control UDP-порт mock-ТВ и проверяет TimeAnnounce
    (v0.4.7: клиент обязан слать его раз в секунду после первого кадра)."""
    sock = MIRROR_AUDIO_CTRL_SOCK[0]
    if sock is None:
        return
    sock.settimeout(1.0)
    while True:
        try:
            pkt, _ = sock.recvfrom(1024)
        except socket.timeout:
            continue
        except Exception:
            return
        ok, err = _validate_announce(pkt)
        with SUMMARY_LOCK:
            a = MIRROR_ANNOUNCE
            a["count"] += 1
            if a["first_hex"] is None:
                a["first_hex"] = pkt.hex()
            if not ok:
                a["ok"] = False
                a["errors"].append(err)
            SUMMARY["mirror"]["announce_count"] = a["count"]
            SUMMARY["mirror"]["announce_ok"] = a["ok"]
            SUMMARY["mirror"]["announce_first_hex"] = a["first_hex"]
        if not ok:
            mirror_error(f"TimeAnnounce invalid: {err}")
        flush_summary()


def _dump_record(kind, payload):
    """ACD1-запись в mock-дамп (тот же формат, что у приложения):
    [kind 1 B][len 4 B BE][payload]. Открывается лениво при первой
    записи; закрывается при выходе mock'а."""
    if MIRROR_DUMP_PATH is None:
        return
    with MIRROR_DUMP["lock"]:
        try:
            fh = MIRROR_DUMP["fh"]
            if fh is None:
                fh = open(MIRROR_DUMP_PATH, "wb")
                fh.write(b"ACD1")
                MIRROR_DUMP["fh"] = fh
            fh.write(kind)
            fh.write(struct.pack(">I", len(payload)))
            fh.write(payload)
            fh.flush()
        except Exception as e:
            log(f"mirror dump write failed: {e}")


def _dump_reset():
    """Новая сессия (новый data-канал) — новый дамп: старые кадры
    другой сессии (другой ключ!) не смешиваются с новыми."""
    if MIRROR_DUMP_PATH is None:
        return
    with MIRROR_DUMP["lock"]:
        try:
            if MIRROR_DUMP["fh"] is not None:
                MIRROR_DUMP["fh"].close()
            MIRROR_DUMP["fh"] = None
        except Exception:
            pass

# Long-term server identity (persists across connections, like a real TV)
SERVER_ED = Ed25519PrivateKey.generate()
SERVER_ED_PUB = SERVER_ED.public_key().public_bytes_raw()
SERVER_ID = b"MockAppleTV"
# Paired controller (filled by pair-setup M5, used by pair-verify)
PAIRED = {}  # client_id -> ltpk bytes


def log(msg):
    print(f"[mock] {msg}", flush=True)


def bplist_dumps_signed(obj):
    """Minimal binary-plist writer with APPLE-style signed-minimal ints.

    v0.4.6: plistlib writes ints unsigned-minimal (44100 as 2-byte
    0xAC44), while Apple's writer uses signed-minimal (44100 as
    4-byte 0x0000AC44). The app's Bplist decoder reads per spec, so a
    plistlib body decodes sr=44100 as -21436. Used for the /feedback
    body, where the sr value is asserted in the client log. Supports
    dict / list / str (ASCII) / int / bool / None / bytes.
    """
    items = []

    def flat(o):
        i = len(items)
        items.append(None)
        if isinstance(o, dict):
            ks = [flat(k) for k in o]
            vs = [flat(v) for v in o.values()]
            items[i] = ("dict", ks, vs)
        elif isinstance(o, (list, tuple)):
            items[i] = ("array", [flat(v) for v in o])
        elif isinstance(o, bool):
            items[i] = ("bool", o)
        elif o is None:
            items[i] = ("null",)
        elif isinstance(o, int):
            items[i] = ("int", o)
        elif isinstance(o, (bytes, bytearray)):
            items[i] = ("data", bytes(o))
        elif isinstance(o, str):
            items[i] = ("ascii", o.encode("ascii"))
        else:
            raise TypeError(f"bplist_dumps_signed: unsupported {type(o)}")
        return i

    top = flat(obj)
    n = len(items)
    ref_size = 1 if n < 256 else 2

    def enc_int_body(v):
        if -128 <= v <= 127:
            return b"\x10" + v.to_bytes(1, "big", signed=True)
        if -32768 <= v <= 32767:
            return b"\x11" + v.to_bytes(2, "big", signed=True)
        if -2147483648 <= v <= 2147483647:
            return b"\x12" + v.to_bytes(4, "big", signed=True)
        return b"\x13" + v.to_bytes(8, "big", signed=True)

    def enc_ref(r):
        return r.to_bytes(ref_size, "big")

    def enc(o):
        kind = o[0]
        if kind == "dict":
            ks, vs = o[1], o[2]
            head = bytes([0xD0 | len(ks)]) if len(ks) < 15 else b"\xdf" + enc_int_body(len(ks))
            return head + b"".join(enc_ref(r) for r in ks + vs)
        if kind == "array":
            refs = o[1]
            head = bytes([0xA0 | len(refs)]) if len(refs) < 15 else b"\xaf" + enc_int_body(len(refs))
            return head + b"".join(enc_ref(r) for r in refs)
        if kind == "bool":
            return b"\x09" if o[1] else b"\x08"
        if kind == "null":
            return b"\x00"
        if kind == "int":
            return enc_int_body(o[1])
        if kind == "data":
            b = o[1]
            head = bytes([0x40 | len(b)]) if len(b) < 15 else b"\x4f" + enc_int_body(len(b))
            return head + b
        if kind == "ascii":
            b = o[1]
            head = bytes([0x50 | len(b)]) if len(b) < 15 else b"\x5f" + enc_int_body(len(b))
            return head + b
        raise AssertionError(kind)

    out = bytearray(b"bplist00")
    offsets = []
    for it in items:
        offsets.append(len(out))
        out += enc(it)
    offset_table = len(out)
    max_off = max(offsets) if offsets else 0
    off_size = 1 if max_off < 256 else 2
    for off in offsets:
        out += off.to_bytes(off_size, "big")
    trailer = (b"\x00" * 6 + bytes([off_size, ref_size]) +
               n.to_bytes(8, "big") + top.to_bytes(8, "big") +
               offset_table.to_bytes(8, "big"))
    return bytes(out) + trailer


def flush_summary():
    # v0.3.0: summary пишется на ходу, чтобы JVM-тест читал его во
    # время прогона (раньше — только при выходе, т.е. после kill).
    # Атомарно (tmp + os.replace), чтобы читатель не ловил рваный JSON.
    try:
        with SUMMARY_LOCK:
            data = json.dumps(SUMMARY, indent=2, default=str)
        tmp = SUMMARY_PATH + ".tmp"
        with open(tmp, "w") as f:
            f.write(data)
        os.replace(tmp, SUMMARY_PATH)
    except Exception:
        pass


def note(key, value=True):
    with SUMMARY_LOCK:
        if isinstance(SUMMARY.get(key), list):
            SUMMARY[key].append(value)
        elif isinstance(SUMMARY.get(key), int):
            SUMMARY[key] += 1
        else:
            SUMMARY[key] = value
    flush_summary()


# ---------- TLV8 ----------
def read_tlv(data):
    out = {}
    i = 0
    while i + 1 < len(data):
        t, ln = data[i], data[i + 1]
        chunk = data[i + 2:i + 2 + ln]
        out[t] = out.get(t, b"") + chunk
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


# ---------- HKDF / ChaCha ----------
def hkdf(salt, info, ikm, length=32):
    return HKDF(algorithm=hashes.SHA512(), length=length,
                salt=salt.encode(), info=info.encode()).derive(ikm)


class ChaCha:
    """Counter-nonce ChaCha20-Poly1305 (pyatv Chacha20Cipher8byteNonce)."""

    def __init__(self, key):
        self.key = key
        self.out_ctr = 0
        self.in_ctr = 0

    def _nonce(self, ctr):
        return b"\x00" * 4 + ctr.to_bytes(8, "little")

    def encrypt(self, data, nonce=None, aad=None):
        if nonce is None:
            n = self._nonce(self.out_ctr)
            self.out_ctr += 1
        else:
            n = b"\x00" * (12 - len(nonce)) + nonce
        return ChaCha20Poly1305(self.key).encrypt(n, data, aad)

    def decrypt(self, data, nonce=None, aad=None):
        if nonce is None:
            n = self._nonce(self.in_ctr)
            self.in_ctr += 1
        else:
            n = b"\x00" * (12 - len(nonce)) + nonce
        return ChaCha20Poly1305(self.key).decrypt(n, data, aad)


class HapFraming:
    """HAP session framing (1024-byte frames, 2-byte LE length as AAD)."""

    def __init__(self, out_key, in_key):
        self.out = ChaCha(out_key)
        self.in_ = ChaCha(in_key)
        self.buf = bytearray()

    def encrypt(self, data):
        out = bytearray()
        off = 0
        while off < len(data):
            chunk = data[off:off + 1024]
            ln = len(chunk).to_bytes(2, "little")
            out += ln + self.out.encrypt(chunk, aad=ln)
            off += len(chunk)
        return bytes(out)

    def decrypt(self, data):
        self.buf += data
        out = bytearray()
        while len(self.buf) >= 2:
            ln = int.from_bytes(self.buf[:2], "little")
            if len(self.buf) < 2 + ln + 16:
                break
            block = bytes(self.buf[2:2 + ln + 16])
            out += self.in_.decrypt(block, aad=bytes(self.buf[:2]))
            del self.buf[:2 + ln + 16]
        return bytes(out)


# ---------- HTTP helpers ----------
def read_http_message(conn, framed=None):
    """Read one HTTP/RTSP request. Returns (method, uri, proto, headers, body)."""
    raw = bytearray()
    if framed is not None:
        # framed mode: read until we can parse a full request from plaintext
        while True:
            chunk = conn.recv(8192)
            if not chunk:
                raise EOFError("closed")
            raw += framed.decrypt(chunk)
            try:
                return parse_http(bytes(raw), framed)
            except _NeedMore:
                continue
    else:
        while b"\r\n\r\n" not in raw:
            chunk = conn.recv(8192)
            if not chunk:
                raise EOFError("closed")
            raw += chunk
        head, rest = bytes(raw).split(b"\r\n\r\n", 1)
        return parse_http(bytes(raw), None)


class _NeedMore(Exception):
    pass


def parse_http(data, framed):
    idx = data.find(b"\r\n\r\n")
    if idx < 0:
        raise _NeedMore()
    head = data[:idx].decode("iso-8859-1")
    lines = head.split("\r\n")
    method, uri, proto = lines[0].split(" ", 2)
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    clen = int(headers.get("content-length", "0"))
    total = idx + 4 + clen
    if len(data) < total:
        raise _NeedMore()
    body = data[idx + 4:total]
    return method, uri, proto, headers, body


def send_response(conn, framed, status_line, headers, body=b""):
    head = status_line + "\r\n"
    for k, v in headers.items():
        head += f"{k}: {v}\r\n"
    head += f"Content-Length: {len(body)}\r\n\r\n"
    raw = head.encode("iso-8859-1") + body
    conn.sendall(framed.encrypt(raw) if framed else raw)


# ---------- Event channel ----------
def event_request_bytes(payload, nested=False):
    """POST /event с bplist-телом. nested=True: вложенная форма
    /command-пути (#2846/#2899): {"params": {"data": <bplist>}}."""
    if nested:
        inner = plistlib.dumps(payload, fmt=plistlib.FMT_BINARY)
        body = plistlib.dumps({"params": {"data": inner}}, fmt=plistlib.FMT_BINARY)
    else:
        body = plistlib.dumps(payload, fmt=plistlib.FMT_BINARY)
    return (f"POST /event RTSP/1.0\r\nContent-Length: {len(body)}\r\n\r\n").encode("iso-8859-1") + body


class EventChannelServer(threading.Thread):
    def __init__(self, shared_secret):
        super().__init__(daemon=True)
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(5)
        self.port = self.sock.getsockname()[1]
        self.shared = shared_secret
        # v0.3.0: очередь исходящих событий (insert/playbackInfo из
        # handle_client кладут сюда; поток канала шифрует и шлёт).
        self.send_q = queue.Queue()

    def send_event(self, payload, nested=False):
        """Поставить событие в очередь (потокобезопасно)."""
        self.send_q.put(event_request_bytes(payload, nested))

    def run(self):
        # v0.4.6: цикл accept — по одному event-каналу на сессию,
        # как у живого ТВ. Раньше был ОДИН accept на весь процесс:
        # второе и третье подключения «успевали» через backlog
        # listen(1), а четвёртое (закрытые, но не принятые соединения
        # слот не освобождают) висело до таймаута — 4-я зеркальная
        # сессия в одном прогоне mock-теста тратила 30 с на 5 ретраев
        # подключения event-канала (воспроизведено /tmp/ev_repro.py:
        # #1 accepted, #2/#3 через backlog, #4 — connect timed out).
        while True:
            try:
                conn, _ = self.sock.accept()
            except Exception as e:
                log(f"event channel accept ended: {e}")
                return
            note("event_channel", 1)
            try:
                # Client encrypts with Events-Read, decrypts with Events-Write,
                # so the mock does the opposite.
                framing = HapFraming(
                    hkdf("Events-Salt", "Events-Write-Encryption-Key", self.shared),
                    hkdf("Events-Salt", "Events-Read-Encryption-Key", self.shared))
                conn.settimeout(0.5)
                if SEND_EVENT:
                    # v0.2.1: одно СИНТЕТИЧЕСКОЕ событие через 1 с после
                    # подключения (плоская форма {"state": "playing"} —
                    # legacy, проверяет совместимость парсера).
                    time.sleep(1.0)
                    try:
                        conn.sendall(framing.encrypt(
                            event_request_bytes({"state": "playing", "category": 1})))
                        log("sent synthetic event POST /event {state: playing}")
                    except Exception as e:
                        log(f"synthetic event failed: {e}")
                while True:
                    try:
                        chunk = conn.recv(8192)
                    except socket.timeout:
                        chunk = None  # таймаут — не конец: слить очередь
                    except Exception:
                        break
                    if chunk == b"":
                        break  # клиент закрыл канал
                    if chunk:
                        try:
                            plain = framing.decrypt(chunk)
                        except Exception:
                            plain = b""
                        if plain:
                            # Parse request, answer 200 echoing CSeq — ТОЛЬКО
                            # настоящим запросам. Ответ клиента ("RTSP/1.0
                            # 200 OK") запросом не является: отвечать на
                            # него — слать мусор обратно в канал.
                            try:
                                method, uri, proto, headers, _ = parse_http(plain, None)
                            except _NeedMore:
                                method = ""
                            if method in ("POST", "GET", "PUT", "SETUP", "RECORD"):
                                resp = f"{proto} 200 OK\r\nContent-Length: 0\r\n"
                                if "cseq" in headers:
                                    resp += f"CSeq: {headers['cseq']}\r\n"
                                resp += "\r\n"
                                conn.sendall(framing.encrypt(resp.encode("iso-8859-1")))
                    # Слить очередь исходящих событий.
                    while True:
                        try:
                            data = self.send_q.get_nowait()
                        except queue.Empty:
                            break
                        try:
                            conn.sendall(framing.encrypt(data))
                        except Exception as e:
                            log(f"event send failed: {e}")
                            break
            except Exception as e:
                log(f"event channel ended: {e}")
            finally:
                try:
                    conn.close()
                except Exception:
                    pass


# ---------- PTP listener (v0.3.3) ----------
# PTPv2 по IEEE 1588-2008 + gPTP/802.1AS, форма iOS (airplay-cli).
# v0.3.0–v0.3.2: mock повторял СДВИГ заголовка приложения
# (clockIdentity в 16 вместо 20, sequenceId в 26 вместо 30) —
# тесты были зелёными при битых пакетах. Теперь раскладка
# СТАНДАРТНАЯ (clockIdentity 20..27, portNumber 28..29, seq 30..31,
# control 32, logInterval 33), первый байт 0x10|type (majorSdoId 1),
# twoStepFlag — 0x0200 (не 0x0080), Announce — форма iOS
# (utcOffset 0, clockClass 6, accuracy 0x21, variance 0x436A,
# timeSource GPS 0x20, PATH_TRACE TLV, 76 Б), Follow_Up — 96 Б
# с двумя TLV. Delay_Req — тоже в форме gPTP.
class PtpListener(threading.Thread):
    def __init__(self, adv_port=0):
        super().__init__(daemon=True)
        self.clock_identity = b"MOCKPTP1"  # 8 байт
        self.port_identity = self.clock_identity + struct.pack(">H", 1)
        self.sync_src = None  # (addr, port) приложения — куда слать Delay_Req
        self.delay_req_seq = 1
        self.delay_req_sent_at = None
        # v0.3.3: рекламированный ClockPorts-порт ТВ (не 319) — mock
        # слушает и его тоже: Sync туда = приложение распарсило
        # ClockPorts из ответа SETUP и перенацелило PTP.
        # v0.3.4: ClockPorts — НЕ UDP-порт (живой факт: ICMP
        # unreachable), поэтому Sync туда теперь = РЕГРЕССИЯ
        # (sync_adv в summary должен быть 0); mock слушает порт
        # именно чтобы это поймать.
        self.adv_port = adv_port

    @staticmethod
    def parse(data):
        if len(data) < 34 or data[1] != 0x02:
            return None
        return {
            "type": data[0] & 0x0F,
            "sdo": (data[0] >> 4) & 0x0F,
            "flags": struct.unpack(">H", data[6:8])[0],
            "seq": struct.unpack(">H", data[30:32])[0],
            "control": data[32],
            "src": data[20:30],
            "length": struct.unpack(">H", data[2:4])[0],
        }

    @staticmethod
    def timestamp(data, off):
        sec = int.from_bytes(data[off:off + 6], "big")
        nanos = struct.unpack(">I", data[off + 6:off + 10])[0]
        return sec, nanos

    def header(self, mtype, length, flags, seq, control, log_interval):
        out = bytearray(34)
        out[0] = 0x10 | (mtype & 0x0F)  # majorSdoId=1 (gPTP)
        out[1] = 0x02
        out[2:4] = struct.pack(">H", length)
        out[6:8] = struct.pack(">H", flags)
        out[16:20] = b"\x00" * 4  # messageTypeSpecific
        out[20:28] = self.clock_identity
        out[28:30] = struct.pack(">H", 1)
        out[30:32] = struct.pack(">H", seq)
        out[32] = control
        out[33] = log_interval & 0xFF
        return bytes(out)

    def build_delay_req(self):
        # Delay_Req: event-сообщение, length 44, controlField 1,
        # флаги UNICAST|TIMESCALE (форма iOS).
        return self.header(0x1, 44, 0x0408, self.delay_req_seq, 1, 0x7F) + b"\x00" * 10

    def run(self):
        s319 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s320 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s_adv = None
        s_adv_gen = None
        if self.adv_port:
            s_adv = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            # v0.3.3: general-порт ТВ (ClockPorts+1) — приложение шлёт
            # туда Announce/Follow_Up/Delay_Resp; без слушателя эти
            # пакеты уходили в ICMP Port Unreachable, а их копии на
            # :320 травились той же ошибкой на общем сокете (см.
            # sendGeneral в приложении). Слушаем и его тоже.
            s_adv_gen = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s319.bind(("127.0.0.1", 319))
            s320.bind(("127.0.0.1", 320))
            if s_adv is not None:
                s_adv.bind(("127.0.0.1", self.adv_port))
            if s_adv_gen is not None:
                s_adv_gen.bind(("127.0.0.1", self.adv_port + 1))
        except Exception as e:
            log(f"PTP listener: bind 319/320 failed: {e}")
            return
        s319.settimeout(0.5)
        s320.settimeout(0.5)
        if s_adv is not None:
            s_adv.settimeout(0.5)
        if s_adv_gen is not None:
            s_adv_gen.settimeout(0.5)
        log(f"PTP listener on UDP 319/320" + (f" + adv {self.adv_port}/{self.adv_port + 1}" if s_adv is not None else ""))
        delay_req_attempts = 0
        adv_delay_req_attempts = 0
        s319_connected = False
        s_adv_connected = False
        socks = [(s319, 319), (s320, 320)]
        if s_adv is not None:
            socks.append((s_adv, self.adv_port))
        if s_adv_gen is not None:
            socks.append((s_adv_gen, self.adv_port + 1))
        while True:
            for s, port in socks:
                try:
                    data, addr = s.recvfrom(256)
                except socket.timeout:
                    continue
                except Exception:
                    continue
                msg = self.parse(data)
                if msg is None:
                    log(f"PTP: мусорный пакет {len(data)} Б на :{port}")
                    continue
                t = msg["type"]
                if t == 0x0:  # Sync
                    with SUMMARY_LOCK:
                        SUMMARY["ptp"]["sync"] += 1
                        # v0.3.5: clockIdentity первого Sync — для
                        # проверки, что клиент шлёт PTP именно с
                        # sessionCorrelationUUID (первые 8 Б), а не со
                        # случайным clockIdentity.
                        if SUMMARY["ptp"]["sync_clock_id"] is None:
                            SUMMARY["ptp"]["sync_clock_id"] = msg["src"][:8].hex()
                        if port == self.adv_port:
                            SUMMARY["ptp"]["sync_adv"] += 1
                    flush_summary()
                    # v0.3.3: twoStepFlag — 0x0200 (не 0x0080!);
                    # gPTP-форма: majorSdoId 1 + UNICAST|TIMESCALE.
                    two_step = bool(msg["flags"] & 0x0200)
                    gptp = (msg.get("sdo") == 1 and bool(msg["flags"] & 0x0400)
                            and bool(msg["flags"] & 0x0008) and two_step)
                    if gptp:
                        with SUMMARY_LOCK:
                            SUMMARY["ptp"]["sync_gptp_ok"] = True
                        flush_summary()
                    log(f"PTP: Sync #{msg['seq']} от {addr} (twoStep={two_step}, gptp={gptp}, len={msg['length']}) на :{port}")
                    # Delay_Req — С ТОГО ЖЕ сокета, что принял Sync
                    # (connect): Delay_Resp возвращается на исходный
                    # порт Delay_Req, а одноразовый сокет к тому
                    # времени закрыт — ответ терялся (было:
                    # delay_resp=0 при рабочем приёмнике приложения).
                    # ВАЖНО (песочница VM): UDP sendto() — EPERM,
                    # работает только connect() + send().
                    if port == self.adv_port:
                        if not s_adv_connected:
                            try:
                                s.connect(addr)
                                s_adv_connected = True
                            except Exception as e:
                                log(f"PTP: s_adv connect failed: {e}")
                        if (s_adv_connected and not SUMMARY["ptp"]["delay_resp_ok"]
                                and adv_delay_req_attempts < 3):
                            req = self.build_delay_req()
                            try:
                                s.send(req)
                                self.delay_req_sent_at = time.time()
                                adv_delay_req_attempts += 1
                                with SUMMARY_LOCK:
                                    SUMMARY["ptp"]["delay_req"] += 1
                                flush_summary()
                                log(f"PTP: Delay_Req #{self.delay_req_seq} → {addr} (adv :{port}, попытка {adv_delay_req_attempts})")
                            except Exception as e:
                                log(f"PTP: Delay_Req send failed: {e}")
                        continue
                    if self.sync_src is None:
                        self.sync_src = addr
                        # Delay_Req — С ТОГО ЖЕ сокета 319 (connect):
                        # Delay_Resp возвращается на исходный порт
                        # Delay_Req, а одноразовый сокет к тому времени
                        # закрыт — ответ терялся (было: delay_resp=0
                        # при рабочем приёмнике приложения).
                        # ВАЖНО (песочница VM): UDP sendto() — EPERM,
                        # работает только connect() + send().
                        try:
                            s319.connect(addr)
                            s319_connected = True
                        except Exception as e:
                            log(f"PTP: s319 connect failed: {e}")
                    # До 3 попыток Delay_Req (по одной на Sync): ответ
                    # может попасть в микросекундное окно connect()
                    # отправителя приложения — повтор страхует.
                    if (s319_connected and not SUMMARY["ptp"]["delay_resp_ok"]
                            and delay_req_attempts < 3):
                        req = self.build_delay_req()
                        try:
                            s319.send(req)
                            self.delay_req_sent_at = time.time()
                            delay_req_attempts += 1
                            with SUMMARY_LOCK:
                                SUMMARY["ptp"]["delay_req"] += 1
                            flush_summary()
                            log(f"PTP: Delay_Req #{self.delay_req_seq} → {self.sync_src} (попытка {delay_req_attempts})")
                        except Exception as e:
                            log(f"PTP: Delay_Req send failed: {e}")
                elif t == 0x8:  # Follow_Up
                    with SUMMARY_LOCK:
                        SUMMARY["ptp"]["followup"] += 1
                    ok = False
                    sec = nanos = None
                    if len(data) >= 44:
                        sec, nanos = self.timestamp(data, 34)
                        now_ptp = int(time.time()) + 37
                        ok = abs(sec - now_ptp) <= 5 and nanos < 1_000_000_000
                    # v0.3.3: TLV формы iOS (96 Б): 802.1AS info @44
                    # (type 0x0003, len 0x001C, OUI 00:80:C2, subtype 1)
                    # + Apple clock-id @76 (type 0x0003, len 0x0010,
                    # OUI 00:0D:93, subtype 4).
                    tlv_ok = False
                    if len(data) >= 96:
                        tlv_ok = (struct.unpack(">H", data[44:46])[0] == 0x0003
                                  and struct.unpack(">H", data[46:48])[0] == 0x001C
                                  and data[48:51] == b"\x00\x80\xc2" and data[53] == 0x01
                                  and struct.unpack(">H", data[76:78])[0] == 0x0003
                                  and struct.unpack(">H", data[78:80])[0] == 0x0010
                                  and data[80:83] == b"\x00\x0d\x93" and data[85] == 0x04)
                    with SUMMARY_LOCK:
                        if ok:
                            SUMMARY["ptp"]["followup_ok"] = True
                        if tlv_ok:
                            SUMMARY["ptp"]["followup_tlv_ok"] = True
                    flush_summary()
                    log(f"PTP: Follow_Up #{msg['seq']} preciseOrigin ok={ok} tlv ok={tlv_ok}" + ("" if ok else f" (sec={sec} nanos={nanos} now={int(time.time()) + 37})"))
                elif t == 0xB:  # Announce
                    with SUMMARY_LOCK:
                        SUMMARY["ptp"]["announce"] += 1
                    # v0.3.3: форма iOS (76 Б): utcOffset 0,
                    # clockClass 6, accuracy 0x21, variance 0x436A,
                    # timeSource GPS 0x20, PATH_TRACE TLV @64
                    # (type 0x0008, len 8). Было: utc 37, класс 248,
                    # accuracy 0xFE, источник 0xA0, 64 Б.
                    ok = False
                    if len(data) >= 76:
                        utc = struct.unpack(">H", data[44:46])[0]
                        pri1, cc, acc = data[47], data[48], data[49]
                        var = struct.unpack(">H", data[50:52])[0]
                        pri2, tsrc = data[52], data[63]
                        pathtrace = (struct.unpack(">H", data[64:66])[0] == 0x0008
                                     and struct.unpack(">H", data[66:68])[0] == 0x0008)
                        ok = (utc == 0 and pri1 == 128 and cc == 6 and acc == 0x21
                              and var == 0x436A and pri2 == 128 and tsrc == 0x20
                              and pathtrace)
                    with SUMMARY_LOCK:
                        if ok:
                            SUMMARY["ptp"]["announce_ok"] = True
                    flush_summary()
                    log(f"PTP: Announce #{msg['seq']} fields ok={ok}")
                elif t == 0x9:  # Delay_Resp
                    with SUMMARY_LOCK:
                        SUMMARY["ptp"]["delay_resp"] += 1
                    ok = False
                    if len(data) >= 54:
                        sec, nanos = self.timestamp(data, 34)
                        req_id = data[44:54]
                        now_ptp = int(time.time()) + 37
                        ok = (msg["seq"] == self.delay_req_seq
                              and req_id == self.port_identity
                              and abs(sec - now_ptp) <= 5
                              and nanos < 1_000_000_000)
                    with SUMMARY_LOCK:
                        if ok:
                            SUMMARY["ptp"]["delay_resp_ok"] = True
                    flush_summary()
                    log(f"PTP: Delay_Resp #{msg['seq']} ok={ok} "
                        f"(seq match, requestingPortIdentity match, recv ts sane)")
                # остальные типы — игнорируем


                # остальные типы — игнорируем


# ---------- Mirror data channel (v0.4.0) ----------
class MirrorDataListener(threading.Thread):
    """TCP listener for the mirror video dataPort.

    Accepts one connection, parses the 128-byte mirroring headers
    (doubletake sendFrame/sendCodecFrame/dataHeartbeatLoop) and
    CRYPTOGRAPHICALLY validates the frames: ChaCha20-Poly1305 decrypt
    with the HKDF key from the pair-verify shared secret (same salt/info
    as the app's deriveChaChaKey), AVCC structure of the decrypted
    payload, IDR flag vs first NAL type, monotonic timestamps, timeline
    ID == ClockID, heartbeat shape. A mock that only counted bytes
    would be a rubber stamp — decryption is the proof.
    """
    def __init__(self):
        super().__init__(daemon=True)
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(1)
        self.port = self.sock.getsockname()[1]
        self.chacha_nonce = 0
        self.last_ts = None
        self.last_was_codec = False
        self.chacha_key = None
        # v0.4.7: новый data-канал — новый дамп (см. _dump_reset).
        _dump_reset()

    def _read_exact(self, conn, n):
        buf = bytearray()
        while len(buf) < n:
            chunk = conn.recv(n - len(buf))
            if not chunk:
                raise EOFError(f"data channel closed after {len(buf)}/{n} B")
            buf += chunk
        return bytes(buf)

    def _chacha_key_for(self):
        if self.chacha_key is not None:
            return self.chacha_key
        shared = MIRROR_SHARED[0]
        sid = MIRROR_VIDEO_STREAM_ID[0]
        if shared is None or sid is None:
            return None
        self.chacha_key = hkdf(f"DataStream-Salt{sid}",
                               "DataStream-Output-Encryption-Key", shared)
        return self.chacha_key

    def _validate_avcc(self, payload, is_idr, what):
        """Walk 4-byte BE lengths; first NAL type must match the IDR flag."""
        off = 0
        first_type = None
        nals = 0
        while off + 4 <= len(payload):
            ln = struct.unpack(">I", payload[off:off + 4])[0]
            if ln == 0 or off + 4 + ln > len(payload):
                mirror_error(f"{what}: bad AVCC length {ln} at {off} (payload {len(payload)} B)")
                return False
            ntype = payload[off + 4] & 0x1F
            if first_type is None:
                first_type = ntype
            nals += 1
            off += 4 + ln
        if off != len(payload) or nals == 0:
            mirror_error(f"{what}: AVCC walk ended at {off}/{len(payload)} B, {nals} NALs")
            return False
        if is_idr and first_type != 5:
            mirror_error(f"{what}: IDR flag set but first NAL type is {first_type}, not 5")
            return False
        if not is_idr and first_type == 5:
            mirror_error(f"{what}: IDR NAL (type 5) without the IDR flag")
            return False
        return True

    def _validate_avcc_config(self, payload):
        """avcC payload (doubletake buildAVCCConfig): 01, profile bytes,
        0xff, 0xe1, BE16 SPS len, SPS (0x67), 0x01, BE16 PPS len, PPS
        (0x68), 4-byte trailer with 0x02 at [0]."""
        try:
            if len(payload) < 11 or payload[0] != 0x01 or payload[4] != 0xFF or payload[5] != 0xE1:
                mirror_error(f"codec frame: bad avcC header {payload[:8].hex()}")
                return False
            sps_len = struct.unpack(">H", payload[6:8])[0]
            sps = payload[8:8 + sps_len]
            if len(sps) != sps_len or not sps or (sps[0] & 0x1F) != 7:
                mirror_error("codec frame: bad SPS")
                return False
            off = 8 + sps_len
            if payload[off] != 0x01:
                mirror_error("codec frame: bad PPS count")
                return False
            pps_len = struct.unpack(">H", payload[off + 1:off + 3])[0]
            pps = payload[off + 3:off + 3 + pps_len]
            if len(pps) != pps_len or not pps or (pps[0] & 0x1F) != 8:
                mirror_error("codec frame: bad PPS")
                return False
            if payload[off + 3 + pps_len] != 0x02:
                mirror_error("codec frame: bad avcC trailer")
                return False
            with SUMMARY_LOCK:
                SUMMARY["mirror"]["sps_len"] = len(sps)
                SUMMARY["mirror"]["pps_len"] = len(pps)
            return True
        except Exception as e:
            mirror_error(f"codec frame: avcC parse failed: {e}")
            return False

    def run(self):
        try:
            conn, _ = self.sock.accept()
            log(f"mirror data channel connected (port {self.port})")
            conn.settimeout(10.0)
            # v0.4.6: тестовая проба (ТОЛЬКО mock — живой ТВ на
            # data-канал ничего не шлёт): один 4-байтовый чанк
            # deadbeef, чтобы проверить, что клиент логирует входящие
            # данные data-канала (hex + длина), а не молчит.
            try:
                conn.sendall(bytes.fromhex("deadbeef"))
                log("mirror data channel: probe deadbeef sent")
            except Exception as e:
                log(f"mirror data channel: probe failed: {e}")
            while True:
                header = self._read_exact(conn, 128)
                payload_size = struct.unpack("<I", header[0:4])[0]
                ptype = header[4]
                payload = self._read_exact(conn, payload_size) if payload_size else b""
                # v0.4.7: сырые байты — в ACD1-дамп для независимого
                # верификатора (тот же формат, что пишет приложение).
                _dump_record({0x01: b"C", 0x00: b"V", 0x02: b"H"}.get(ptype, b"?"), header + payload)
                if ptype == 0x01:  # codec frame (unencrypted)
                    ts = struct.unpack("<Q", header[8:16])[0]
                    # v0.4.5: размеры растра в codec-кадре — doubletake
                    # берёт их из SPS, а не из холста. Пишем в summary,
                    # чтобы тест проверил, что клиент шлёт размеры SPS.
                    w = struct.unpack("<f", header[16:20])[0]
                    h = struct.unpack("<f", header[20:24])[0]
                    with SUMMARY_LOCK:
                        SUMMARY["mirror"]["codec_w"] = int(w)
                        SUMMARY["mirror"]["codec_h"] = int(h)
                    ok = self._validate_avcc_config(payload)
                    mirror_note("codec_frames")
                    if ok:
                        mirror_note("avcc_ok")
                    else:
                        mirror_note("avcc_fail")
                    self._check_ts(ts, "codec")
                elif ptype == 0x00:  # encrypted video frame
                    is_idr = header[5] == 0x10
                    ts = struct.unpack("<Q", header[8:16])[0]
                    tid = struct.unpack("<Q", header[40:48])[0]
                    self._check_ts(ts, "video")
                    if tid != MIRROR_CLOCK_ID:
                        mirror_error(f"video frame: timelineID 0x{tid:016x} != ClockID 0x{MIRROR_CLOCK_ID:016x}")
                        with SUMMARY_LOCK:
                            SUMMARY["mirror"]["timeline_ok"] = False
                    key = self._chacha_key_for()
                    if key is None:
                        mirror_error("video frame: no ChaCha key (pair-verify secret or stream id missing)")
                        continue
                    nonce = b"\x00" * 4 + self.chacha_nonce.to_bytes(8, "little")
                    self.chacha_nonce += 1
                    try:
                        plain = ChaCha20Poly1305(key).decrypt(nonce, payload, header)
                        mirror_note("decrypt_ok")
                    except Exception as e:
                        mirror_note("decrypt_fail")
                        mirror_error(f"video frame: ChaCha decrypt failed (nonce {self.chacha_nonce - 1}): {e}")
                        continue
                    mirror_note("frames")
                    if is_idr:
                        mirror_note("idr_frames")
                    if self._validate_avcc(plain, is_idr, "video frame"):
                        mirror_note("avcc_ok")
                    else:
                        mirror_note("avcc_fail")
                elif ptype == 0x02:  # heartbeat
                    if header[6] != 0x1E or payload_size != 0:
                        mirror_error(f"heartbeat: bad shape [6]=0x{header[6]:02x} size={payload_size}")
                    else:
                        mirror_note("heartbeats")
                else:
                    mirror_error(f"data channel: unknown payload type 0x{ptype:02x}")
        except EOFError as e:
            log(f"mirror data channel closed: {e}")
        except socket.timeout:
            log("mirror data channel: 10 s without frames, closing")
        except Exception as e:
            mirror_error(f"data channel: {type(e).__name__}: {e}")
        finally:
            try:
                conn.close()
            except Exception:
                pass
            flush_summary()

    def _check_ts(self, ts, what):
        # NOTE: mirror_error() takes SUMMARY_LOCK itself — collect the
        # message here and report it AFTER releasing the lock.
        # Monotonicity rule (from doubletake mirror.go): frameTimeAt
        # applies the monotonic guard ONCE per frame, and the resulting
        # packetTimestamp is SHARED by the codec frame and its video
        # frame (sendCodecFrame(avcC, packetTimestamp) then
        # sendFrame(..., packetTimestamp, ...)). So a video frame may
        # carry the SAME timestamp as the codec frame that precedes it;
        # anything else must be strictly increasing.
        err = None
        with SUMMARY_LOCK:
            m = SUMMARY["mirror"]
            if m["first_ts"] is None:
                m["first_ts"] = ts
            m["last_ts"] = ts
            if self.last_ts is not None:
                equal_ok = (what == "video" and self.last_was_codec)
                if ts < self.last_ts or (ts == self.last_ts and not equal_ok):
                    m["ts_monotonic"] = False
                    err = f"{what} frame: timestamp not increasing ({ts} <= {self.last_ts})"
            self.last_ts = ts
            self.last_was_codec = (what == "codec")
            anchor = MIRROR_ANCHOR_COMPACT[0]
            if anchor is not None and ts < anchor:
                err = f"{what} frame: timestamp {ts} before SETUP anchor {anchor}"
        if err:
            mirror_error(err)


def validate_command(inner):
    """v0.3.2: СТРОГАЯ проверка тела /command по диффам pyatv #2846 /
    #2899 — НЕЗАВИСИМО от кода приложения. Возвращает None, если тело
    соответствует живой форме, иначе строку-причину отклонения (mock
    отвечает 400, как живой ТВ на тело v0.3.0).

    Правила (источник — диффы, а не приложение):
    - type — обязательная строка (#2899 send_command, #2846 send_command).
    - insertPlayQueueItem (#2899 _queue_item; #2846 — минимальный item):
      item — словарь; ключи — ТОЛЬКО из объединения живых форм
      (ITEM_ALLOWED_KEYS): неизвестное поле — 400; uuid — непустая
      строка В ВЕРХНЕМ регистре (#2899: str(uuid4()).upper(); #2846:
      литерал в верхнем регистре); mediaType == "file";
      Content-Location — http(s) URL; IsTLSEnabled (если есть) — по
      схеме URL (#2899: startswith https); Start-Position (если есть)
      — CMTime #2899: {flags: 1, value: int (СЕКУНДЫ), epoch: 0,
      timescale: 1} — мс/1000 (v0.3.0) отклоняется; playerLoggingID
      (если есть) — не длиннее 6 символов (#2899: длиннее рвёт
      соединение без ошибки). Режим --reject-minimal-item: item ровно
      из 3 полей #2846 — 400 (ТВ хочет полную форму #2899).
    - setProperty: обязательны property (строка); item.uuid (если item
      есть) — строка в верхнем регистре.
    - setRate: обязателен rate (число).
    - playbackInfo: обязательны kind == "request" и messageID (число).
    """
    if not isinstance(inner, dict):
        return "command is not a dict"
    ctype = inner.get("type")
    if not isinstance(ctype, str) or not ctype:
        return "missing/invalid 'type'"
    if ctype == "insertPlayQueueItem":
        item = inner.get("item")
        if not isinstance(item, dict):
            return "insertPlayQueueItem: missing 'item' dict"
        # v0.3.5: режим --reject-inserts-always — ЛЮБОЙ insert 400
        # (все три попытки приложения отклонены — для проверки
        # вердикта «вставка отклонена, воспроизведение невозможно»).
        if REJECT_INSERTS_ALWAYS:
            return "insert rejected by this TV (all inserts)"
        # v0.3.5: режим --reject-all-inserts — insert БЕЗ messageID
        # отклоняется 400 (модель tvOS 27.2, если она требует
        # messageID на insert); с kind="request"+messageID —
        # проходит обычную проверку дальше. Так проверяется третья
        # попытка приложения (minimal + messageID).
        if REJECT_ALL_INSERTS and not isinstance(inner.get("messageID"), int):
            return ("insert without messageID rejected by this TV "
                    "(wants kind/messageID on insert)")
        # v0.3.2: поле вне живых форм #2846/#2899 — 400. Именно так
        # живой ТВ мог отклонить тело v0.3.1 (полная форма #2899 +
        # ничего лишнего в ней нет — но любое НЕИЗВЕСТНОЕ поле здесь
        # ловится, а не принимается молча).
        unknown = set(item) - ITEM_ALLOWED_KEYS
        if unknown:
            return (f"item has unknown fields (not in #2846/#2899): "
                    f"{sorted(unknown)!r}")
        # v0.3.2: режим --reject-minimal-item — минимальный item
        # (ровно 3 поля #2846) отклоняется 400, полная форма #2899 —
        # принимается. Так проверяется вторая попытка приложения.
        if REJECT_MINIMAL and set(item) == MINIMAL_ITEM_KEYS:
            return ("minimal #2846 item rejected by this TV "
                    "(wants the full #2899 item)")
        uuid = item.get("uuid")
        if not isinstance(uuid, str) or not uuid:
            return "item: missing 'uuid'"
        if uuid != uuid.upper():
            return f"item uuid not uppercase: {uuid}"
        if item.get("mediaType") != "file":
            return f"item mediaType != 'file': {item.get('mediaType')!r}"
        url = item.get("Content-Location")
        if not isinstance(url, str) or not (url.startswith("http://")
                                             or url.startswith("https://")):
            return f"item Content-Location not http(s): {url!r}"
        if "IsTLSEnabled" in item:
            want_tls = url.startswith("https://")
            if item["IsTLSEnabled"] is not want_tls:
                return ("item IsTLSEnabled mismatch with URL scheme: "
                        f"{item['IsTLSEnabled']!r} for {url!r}")
        if "Start-Position" in item:
            sp = item["Start-Position"]
            if not isinstance(sp, dict):
                return "Start-Position is not a dict"
            if sp.get("timescale") != 1:
                return (f"Start-Position timescale != 1 (#2899: seconds/1): "
                        f"{sp.get('timescale')!r}")
            if sp.get("flags") != 1 or sp.get("epoch") != 0:
                return f"Start-Position flags/epoch wrong: {sp!r}"
            if not isinstance(sp.get("value"), int):
                return f"Start-Position value not int seconds: {sp.get('value')!r}"
        pid = item.get("playerLoggingID")
        if pid is not None and (not isinstance(pid, str) or len(pid) > 6):
            return f"playerLoggingID > 6 chars: {pid!r}"
        return None
    if ctype == "setProperty":
        if not isinstance(inner.get("property"), str):
            return "setProperty: missing 'property'"
        it = inner.get("item")
        if it is not None:
            if not isinstance(it, dict) or not isinstance(it.get("uuid"), str):
                return "setProperty: item.uuid missing/not a string"
            if it["uuid"] != it["uuid"].upper():
                return "setProperty: item uuid not uppercase"
        return None
    if ctype == "setRate":
        if not isinstance(inner.get("rate"), (int, float)):
            return "setRate: missing 'rate'"
        return None
    if ctype == "playbackInfo":
        if inner.get("kind") != "request":
            return "playbackInfo: kind != 'request'"
        if not isinstance(inner.get("messageID"), int):
            return "playbackInfo: missing messageID"
        return None
    return f"unknown command type: {ctype!r}"


# ---------- Main connection handler ----------
def handle_client(conn, addr):
    framed = None
    srp_server = None
    srp_ctx = None
    srp_salt = None
    srp_key = None
    verify_shared = None
    played = {"done": False}
    rcs = {"registered": False, "stream_id": 1}
    # v0.4.0-mirror: FairPlay receiver state for this connection.
    fp_receiver_sap = None
    fp_mode = 3
    try:
        while True:
            method, uri, proto, headers, body = read_http_message(conn, framed)
            log(f"{method} {uri} ({proto}, {len(body)} B, framed={framed is not None})")
            cseq = headers.get("cseq")

            def reply(status, extra_headers=None, resp_body=b""):
                h = {}
                if cseq:
                    h["CSeq"] = cseq
                if extra_headers:
                    h.update(extra_headers)
                send_response(conn, framed, status, h, resp_body)

            if uri == "/server-info" and method == "GET":
                note("server_info")
                info = {
                    "deviceid": "AA:BB:CC:DD:EE:FF",
                    "features": (1 << 0) | (1 << 9) | (1 << 38) | (1 << 43) | (1 << 48) | (1 << 49),
                    "model": "AppleTV5,3",
                    "srcvers": "550.10",
                    "vv": 2,
                    "pi": "12345678-1234-1234-1234-123456789012",
                }
                reply("HTTP/1.1 200 OK", {"Content-Type": "application/x-apple-binary-plist"},
                      plistlib.dumps(info, fmt=plistlib.FMT_BINARY))
            elif uri == "/pair-pin-start":
                note("pair_pin_start")
                reply("HTTP/1.1 200 OK")
            elif uri == "/pair-setup":
                tlv = read_tlv(body)
                seq = tlv.get(6, b"\x00")[0]
                note("pair_setup", f"seq={seq}")
                if seq == 1:
                    # M1/M2: start SRP
                    salt = os.urandom(16)
                    srp_salt = salt
                    x = int.from_bytes(hashlib.sha512(
                        salt + hashlib.sha512(b"Pair-Setup:" + PIN.encode()).digest()
                    ).digest(), "big")
                    N = int(constants.PRIME_3072, 16)
                    verifier = pow(5, x, N)
                    srp_ctx = SRPContext("Pair-Setup", PIN, prime=constants.PRIME_3072,
                                         generator=constants.PRIME_3072_GEN,
                                         hash_func=hashlib.sha512)
                    srp_server = SRPServerSession(srp_ctx, password_verifier=verifier)
                    b_pub = srp_server.public_bin
                    reply("HTTP/1.1 200 OK", {"Content-Type": "application/octet-stream"},
                          write_tlv({6: b"\x02", 2: salt, 3: b_pub}))
                elif seq == 3:
                    # M3/M4: verify client proof
                    a_pub = tlv[3]
                    proof = tlv[4]
                    try:
                        srp_server.process(a_pub.hex(), srp_salt.hex())
                        ok = srp_server.verify_proof(proof)
                    except Exception as e:
                        log(f"SRP process failed: {e}")
                        ok = False
                    log(f"SRP client proof valid: {ok}")
                    if not ok:
                        reply("HTTP/1.1 200 OK", {"Content-Type": "application/octet-stream"},
                              write_tlv({6: b"\x04", 7: b"\x02"}))
                        continue
                    srp_key = srp_server.key_bin
                    reply("HTTP/1.1 200 OK", {"Content-Type": "application/octet-stream"},
                          write_tlv({6: b"\x04"}))
                elif seq == 5:
                    # M5/M6: encrypted long-term identity
                    session_key = hkdf("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", srp_key)
                    ch = ChaCha(session_key)
                    inner = read_tlv(ch.decrypt(tlv[5], nonce=b"PS-Msg05"))
                    client_id = inner[1]
                    client_ltpk = inner[3]
                    client_sig = inner[10]
                    controller_x = hkdf("Pair-Setup-Controller-Sign-Salt",
                                        "Pair-Setup-Controller-Sign-Info", srp_key)
                    Ed25519PublicKey.from_public_bytes(client_ltpk).verify(
                        client_sig, controller_x + client_id + client_ltpk)
                    log("M5 client signature OK")
                    PAIRED[client_id] = client_ltpk
                    server_x = controller_x
                    server_sig = SERVER_ED.sign(server_x + SERVER_ID + client_ltpk)
                    m6_inner = write_tlv({1: SERVER_ID, 3: SERVER_ED_PUB, 10: server_sig})
                    enc = ch.encrypt(m6_inner, nonce=b"PS-Msg06")
                    reply("HTTP/1.1 200 OK", {"Content-Type": "application/octet-stream"},
                          write_tlv({6: b"\x06", 5: enc}))
                else:
                    reply("HTTP/1.1 400 Bad Request")
            elif uri == "/pair-verify":
                tlv = read_tlv(body)
                seq = tlv.get(6, b"\x00")[0]
                note("pair_verify", f"seq={seq}")
                if seq == 1:
                    client_pub = tlv[3]
                    client_pub_holder[0] = client_pub
                    verify_priv = X25519PrivateKey.generate()
                    verify_pub = verify_priv.public_key().public_bytes_raw()
                    verify_shared = verify_priv.exchange(
                        X25519PublicKey.from_public_bytes(client_pub))
                    session_key = hkdf("Pair-Verify-Encrypt-Salt",
                                       "Pair-Verify-Encrypt-Info", verify_shared)
                    ch = ChaCha(session_key)
                    # sign serverPub + serverId + clientPub with server Ed key
                    sig = SERVER_ED.sign(verify_pub + SERVER_ID + client_pub)
                    inner = write_tlv({1: SERVER_ID, 10: sig})
                    enc = ch.encrypt(inner, nonce=b"PV-Msg02")
                    reply("HTTP/1.1 200 OK", {"Content-Type": "application/octet-stream"},
                          write_tlv({6: b"\x02", 3: verify_pub, 5: enc}))
                    # stash for M3
                    handle_client.verify_session_key = session_key
                    handle_client.verify_pub = verify_pub
                elif seq == 3:
                    session_key = handle_client.verify_session_key
                    ch = ChaCha(session_key)
                    try:
                        inner = read_tlv(ch.decrypt(tlv[5], nonce=b"PV-Msg03"))
                        client_id = inner[1]
                        client_sig = inner[10]
                    except Exception as e:
                        log(f"pair-verify M3: decrypt failed: {e}")
                        reply("HTTP/1.1 200 OK", {"Content-Type": "application/octet-stream"},
                              write_tlv({6: b"\x04", 7: b"\x02"}))
                        continue
                    client_ltpk = PAIRED.get(client_id)
                    if client_ltpk is None:
                        log("pair-verify M3: unknown client id")
                        reply("HTTP/1.1 200 OK", {"Content-Type": "application/octet-stream"},
                              write_tlv({6: b"\x04", 7: b"\x02"}))
                        continue
                    # STRICT (v0.1.3): verify the controller signature per
                    # HAP spec / pyatv verify1: clientPub + clientId +
                    # serverPub. The v0.1.2 mock verified the same WRONG
                    # order as the app (serverPub + clientId + clientPub),
                    # so a mock-pass proved nothing about the real TV.
                    # A bad signature now gets a proper M4 error 2 reply
                    # (like a real TV) instead of a dropped connection.
                    try:
                        Ed25519PublicKey.from_public_bytes(client_ltpk).verify(
                            client_sig,
                            client_pub_holder[0] + client_id + handle_client.verify_pub)
                    except InvalidSignature:
                        log("pair-verify M3: BAD controller signature (wrong signed data?) -> M4 error 2")
                        reply("HTTP/1.1 200 OK", {"Content-Type": "application/octet-stream"},
                              write_tlv({6: b"\x04", 7: b"\x02"}))
                        continue
                    log("pair-verify M3 client signature OK")
                    reply("HTTP/1.1 200 OK", {"Content-Type": "application/octet-stream"},
                          write_tlv({6: b"\x04"}))
                    # Enable HAP framing from now on
                    framed = HapFraming(
                        hkdf("Control-Salt", "Control-Read-Encryption-Key", verify_shared),
                        hkdf("Control-Salt", "Control-Write-Encryption-Key", verify_shared))
                    log("control channel encryption enabled")
                    # v0.4.0-mirror: the data channel derives its ChaCha key
                    # from this secret.
                    MIRROR_SHARED[0] = verify_shared
                    # v0.4.7: секрет — в summary для независимого
                    # верификатора (тестовый mock-секрет, не пользовательский).
                    with SUMMARY_LOCK:
                        SUMMARY["mirror"]["shared_secret_hex"] = verify_shared.hex()
                        SUMMARY["mirror"]["shared_secrets"].append(verify_shared.hex())
                    flush_summary()
                else:
                    reply("HTTP/1.1 400 Bad Request")
            elif uri == "/fp-setup" and method == "POST":
                # v0.4.0-mirror: FairPlay SAP receiver side. m1 (16 B) ->
                # m2 (142 B, mode 3, encrypted random receiver SAP); m3
                # (164 B) -> m4 (32 B, echo of the m3 exchange tail).
                # A bad m3 tail is REJECTED with 400 — the mock is the
                # oracle for the client's SAP implementation, not a rubber
                # stamp (same discipline as the pair-verify M4 strict check).
                if not MIRROR:
                    reply("HTTP/1.1 404 Not Found")
                    continue
                try:
                    import mock_fairplay as fp
                except Exception as e:
                    log(f"/fp-setup: mock_fairplay недоступен: {e}")
                    reply("HTTP/1.1 500 Internal Server Error")
                    continue
                if len(body) == 16:  # m1
                    try:
                        fp.validate_fpsap_record(body, 1, 4)
                        if bytes(body[12:16]) != bytes([0x02, 0x00, 0x03, 0xBB]):
                            raise ValueError(f"m1 payload {bytes(body[12:16]).hex()} != 020003bb")
                    except Exception as e:
                        mirror_error(f"fp-setup m1: {e}")
                        reply("RTSP/1.0 400 Bad Request")
                        continue
                    fp_receiver_sap = bytes([0x00, 0x01]) + os.urandom(126)
                    fp_mode = 3  # hardest path: reverse CBC traversal
                    enc = fp.encrypt_fairplay_message(fp_mode, fp_receiver_sap)
                    m2 = bytearray(fp.new_fpsap_record(2, 130))
                    m2[12] = 0x02
                    m2[13] = fp_mode
                    m2[14:142] = enc
                    mirror_note("fp_setup", {"stage": "m1->m2", "mode": fp_mode})
                    log("FairPlay SAP: m1 принят, m2 отправлен (mode 3)")
                    reply("RTSP/1.0 200 OK",
                          {"Content-Type": "application/octet-stream", "X-Apple-ET": "32"},
                          bytes(m2))
                elif len(body) == 164:  # m3
                    try:
                        fp.validate_fpsap_record(body, 3, 152)
                        mode = body[12]
                        if fp_receiver_sap is None:
                            raise ValueError("m3 без предшествующего m1/m2")
                        if mode != fp_mode:
                            raise ValueError(f"m3 mode {mode} != выбранный {fp_mode}")
                        sender_sap = fp.decrypt_fairplay_message(bytes(body))
                        tail = fp.fpsap_exchange_for_sap(sender_sap, fp_receiver_sap)
                        if tail != bytes(body[144:164]):
                            raise ValueError(
                                f"m3 tail mismatch: got {bytes(body[144:164]).hex()}, want {tail.hex()}")
                    except Exception as e:
                        mirror_error(f"fp-setup m3: {e}")
                        reply("RTSP/1.0 400 Bad Request")
                        continue
                    m4 = bytearray(fp.new_fpsap_record(4, 20))
                    m4[12:32] = body[144:164]
                    mirror_note("fp_setup", {"stage": "m3->m4", "mode": mode, "tail_ok": True})
                    log("FairPlay SAP: m3 tail OK, m4 отправлен")
                    reply("RTSP/1.0 200 OK",
                          {"Content-Type": "application/octet-stream", "X-Apple-ET": "32"},
                          bytes(m4))
                else:
                    mirror_error(f"fp-setup: неожиданная длина {len(body)} B")
                    reply("RTSP/1.0 400 Bad Request")
            elif uri == "/info" and method == "GET":
                # v0.3.0: #2846 rtsp.info() — psi для channelID RCS-сессии.
                note("info")
                if MIRROR:
                    # v0.4.0-mirror: полный info — features, sourceVersion,
                    # displays, supportedFormats.screenStream.
                    reply("RTSP/1.0 200 OK", {"Content-Type": "application/x-apple-binary-plist"},
                          plistlib.dumps(mirror_info_dict(), fmt=plistlib.FMT_BINARY))
                    continue
                reply("RTSP/1.0 200 OK", {"Content-Type": "application/x-apple-binary-plist"},
                      plistlib.dumps({"psi": PSI}, fmt=plistlib.FMT_BINARY))
            elif uri == "/command" and method == "POST":
                # v0.3.0: POST /command — 500 до регистрации RCS (#2899:
                # "returns 500 until this is done"), 200 после.
                try:
                    outer = plistlib.loads(body) if body else {}
                    inner = plistlib.loads(outer["params"]["data"])
                except Exception as e:
                    log(f"/command: не bplist: {e}")
                    reply("HTTP/1.1 400 Bad Request")
                    continue
                ctype = inner.get("type") if isinstance(inner, dict) else None
                if not rcs["registered"]:
                    log(f"/command {ctype}: 500 (RCS не зарегистрирован)")
                    note("command", {"type": ctype, "code": 500})
                    reply("HTTP/1.1 500 Internal Server Error")
                    continue
                # v0.3.1: строгая проверка тела по #2846/#2899 — 400 на
                # отклонение от живой формы (как живой ТВ на тело v0.3.0).
                violation = validate_command(inner)
                if violation is not None:
                    log(f"/command {ctype}: 400 STRICT: {violation}")
                    note("command", {"type": ctype, "code": 400,
                                     "reason": violation,
                                     "item": inner.get("item") if isinstance(inner, dict) else None})
                    reply("HTTP/1.1 400 Bad Request")
                    continue
                log(f"/command {ctype}: {inner}")
                # v0.3.5: messageID — в summary, чтобы тест мог проверить
                # третью попытку (insert с kind/messageID) отдельно от
                # первых двух (без messageID).
                note("command", {"type": ctype, "code": 200,
                                 "item": inner.get("item"),
                                 "messageID": inner.get("messageID")})
                if ctype == "insertPlayQueueItem" and not NO_COMMAND_EVENT:
                    # Вложенное событие playbackState playing — через ~1 с,
                    # как живой ТВ (#2846: playing несёт position/duration/rate).
                    ev = getattr(handle_client, "event_server", None)
                    if ev is not None:
                        def _send_playing(ev=ev):
                            time.sleep(1.0)
                            ev.send_event(
                                {"type": "playbackState",
                                 "params": {"playbackState": {
                                     "name": "playing", "position": 1.0,
                                     "duration": 60.0, "rate": 1.0}}},
                                nested=True)
                            note("command_playing_event")
                            log("sent nested event playbackState playing "
                                "(position 1.0, duration 60.0, rate 1.0)")
                        threading.Thread(target=_send_playing, daemon=True).start()
                elif ctype == "playbackInfo":
                    # Ответ — событием kind=response с тем же messageID
                    # (#2846 responseFor). v0.3.4: под --no-command-event
                    # событие НЕ шлём вообще (флаг значит «ТВ не шлёт
                    # никаких событий от команд» — иначе probe v0.3.4
                    # ломал бы секцию 6 mock-теста, где событий ноль).
                    mid = inner.get("messageID")
                    ev = getattr(handle_client, "event_server", None)
                    if ev is not None and not NO_COMMAND_EVENT:
                        ev.send_event(
                            {"kind": "response", "messageID": mid,
                             "duration": 60.0, "position": 1.0,
                             "rate": 1.0, "readyToPlay": True},
                            nested=True)
                        note("playback_info_response_event")
                        log(f"sent nested event kind=response messageID={mid}")
                reply("HTTP/1.1 200 OK")
            elif method == "SETUP":
                note("setup")
                try:
                    setup_body = plistlib.loads(body) if body else {}
                    log(f"SETUP body: {setup_body}")
                except Exception as e:
                    setup_body = {}
                    log(f"SETUP body not bplist: {e}")
                # v0.4.0-mirror: mirroring SETUPs (control / audio 96 /
                # video 110). Проверяются ДО RCS-ветки: у зеркала тоже
                # есть "streams", но тип 96/110 и isScreenMirroringSession
                # отличают его от RCS (type 130).
                if MIRROR and isinstance(setup_body, dict) and (
                        setup_body.get("isScreenMirroringSession") is True or
                        isinstance(setup_body.get("streams"), list)):
                    streams = setup_body.get("streams") or []
                    stype = streams[0].get("type") if streams else None
                    if setup_body.get("isScreenMirroringSession") is True:
                        # Control SETUP: session plist, без "streams".
                        mirror_note("control_setup", {
                            "sessionUUID": setup_body.get("sessionUUID"),
                            "deviceID": setup_body.get("deviceID"),
                            "timingProtocol": setup_body.get("timingProtocol"),
                            "sourceVersion": setup_body.get("sourceVersion"),
                            "hasEkey": "ekey" in setup_body,
                            "hasEiv": "eiv" in setup_body,
                        })
                        if setup_body.get("timingProtocol") not in ("PTP", "NTP"):
                            mirror_error(f"control SETUP: неизвестный timingProtocol {setup_body.get('timingProtocol')}")
                            reply("RTSP/1.0 400 Bad Request")
                            continue
                        if "ekey" in setup_body or "eiv" in setup_body:
                            mirror_error("control SETUP: ekey/eiv при HAP-шифровании (должен быть descriptor-only)")
                        ev = MIRROR_EVENT_SERVER[0]
                        if ev is None:
                            if verify_shared is None:
                                mirror_error("control SETUP: нет pair-verify секрета для event-канала")
                                reply("RTSP/1.0 500 Internal Server Error")
                                continue
                            ev = EventChannelServer(verify_shared)
                            ev.start()
                            MIRROR_EVENT_SERVER[0] = ev
                        headers, anchor_ms = mirror_ptp_headers()
                        MIRROR_ANCHOR_COMPACT[0] = ((anchor_ms // 1000) << 32) | (((anchor_ms % 1000) << 32) // 1000)
                        MIRROR_ANCHOR_WALL[0] = int(time.monotonic() * 1000)
                        resp_body = {
                            "eventPort": ev.port,
                            "timingPeerInfo": {
                                "ID": "MOCKPEER-0000-0000-0000-000000000000",
                                "Addresses": ["127.0.0.1"],
                                "ClockID": MIRROR_CLOCK_ID,
                                "DeviceType": 1,
                                "SupportsClockPortMatchingOverride": True,
                            },
                            "info": mirror_info_dict(),
                        }
                        if SKIP_RECORD:
                            # v0.4.6: --skip-record в зеркальном режиме:
                            # ТВ велит пропустить RECORD (doubletake
                            # читает skipRecord из control SETUP).
                            # Раньше флаг добавлял skipRecord только в
                            # legacy video/base SETUP (/command-путь),
                            # а зеркало его никогда не видело — тест
                            # 14(d) получал skipRecord=false и RECORD
                            # уходил вопреки флагу.
                            resp_body["skipRecord"] = True
                        if setup_body.get("timingProtocol") == "NTP":
                            resp_body["timingPort"] = 32000
                        reply("RTSP/1.0 200 OK",
                              dict({"Content-Type": "application/x-apple-binary-plist"}, **headers),
                              plistlib.dumps(resp_body, fmt=plistlib.FMT_BINARY))
                        log(f"mirror control SETUP ok: timing={setup_body.get('timingProtocol')}, eventPort={ev.port}, ClockID=0x{MIRROR_CLOCK_ID:016x}")
                        # updateTimingPeerInfo через ~2 с — проверка
                        # обработчика клиента (clock.updateTimingPeerInfo).
                        def _send_tpi(ev=ev):
                            time.sleep(2.0)
                            try:
                                ev.send_event({"type": "updateTimingPeerInfo",
                                               "value": {"ClockID": MIRROR_CLOCK_ID,
                                                         "ID": "MOCKPEER-0000-0000-0000-000000000000",
                                                         "Addresses": ["127.0.0.1"]}})
                                log("mirror: отправлен updateTimingPeerInfo")
                            except Exception as e:
                                log(f"mirror: updateTimingPeerInfo failed: {e}")
                        threading.Thread(target=_send_tpi, daemon=True).start()
                        continue
                    if stype == 96:
                        desc = streams[0]
                        sc = desc.get("streamConnections")
                        # v0.4.3: форма streamConnections — СЛОВАРЬ
                        # (doubletake addModernScreenAudioStreamFields:
                        # ключи streamConnectionTypeRTP/RTCP). Список
                        # словарей реальный ТВ отклоняет 400 (живой факт
                        # v0.4.2) — mock повторяет это строго.
                        sc_shape = "absent" if sc is None else ("map" if isinstance(sc, dict) else "list" if isinstance(sc, list) else "other")
                        mirror_note("audio_setup", {
                            "ct": desc.get("ct"), "spf": desc.get("spf"),
                            "sr": desc.get("sr"), "audioFormat": desc.get("audioFormat"),
                            "audioMode": desc.get("audioMode"),
                            "usingScreen": desc.get("usingScreen"),
                            "latencyMin": desc.get("latencyMin"),
                            "latencyMax": desc.get("latencyMax"),
                            "redundantAudio": desc.get("redundantAudio"),
                            "hasShk": "shk" in desc,
                            "shkLen": len(desc["shk"]) if isinstance(desc.get("shk"), bytes) else None,
                            "streamConnections": sc is not None,
                            "streamConnectionsShape": sc_shape,
                            "hasControlPort": "controlPort" in desc,
                        })
                        if isinstance(sc, list):
                            mirror_error("audio SETUP: streamConnections списком отклонён (нужен словарь)")
                            reply("RTSP/1.0 400 Bad Request")
                            log("mirror audio SETUP rejected: streamConnections is a list")
                            continue
                        ev = MIRROR_EVENT_SERVER[0]
                        # v0.4.7: реальные аудио-порты (сокеты связаны при
                        # старте mock'а в mirror-режиме) — как живой ТВ
                        # (v0.4.6: 54495/55821). Старые dataPort=0 /
                        # controlPort=50010 ни на чём не слушали.
                        resp = {"streams": [{"type": 96,
                                             "dataPort": MIRROR_AUDIO_DATA_PORT[0],
                                             "controlPort": MIRROR_AUDIO_CTRL_PORT[0]}],
                                "eventPort": ev.port if ev else 0,
                                "skipRecord": False}
                        reply("RTSP/1.0 200 OK", {"Content-Type": "application/x-apple-binary-plist"},
                              plistlib.dumps(resp, fmt=plistlib.FMT_BINARY))
                        log("mirror audio SETUP ok (type 96 placeholder)")
                        continue
                    if stype == 110:
                        desc = streams[0]
                        MIRROR_VIDEO_STREAM_ID[0] = desc.get("streamConnectionID")
                        mirror_note("video_setup", {
                            "streamConnectionID": desc.get("streamConnectionID"),
                            "latencyMs": desc.get("latencyMs"),
                            "timestampInfo": [t.get("name") for t in desc.get("timestampInfo", [])],
                            "shkLen": len(desc["shk"]) if isinstance(desc.get("shk"), bytes) else None,
                            "shivLen": len(desc.get("shiv")) if isinstance(desc.get("shiv"), bytes) else None,
                        })
                        if not isinstance(desc.get("shk"), bytes) or len(desc["shk"]) != 16:
                            mirror_error("video SETUP: shk не 16 B")
                        if not isinstance(desc.get("shiv"), bytes) or len(desc["shiv"]) != 16:
                            mirror_error("video SETUP: shiv не 16 B")
                        listener = MirrorDataListener()
                        listener.start()
                        MIRROR_DATA_LISTENER[0] = listener
                        ev = MIRROR_EVENT_SERVER[0]
                        # v0.4.7: --mirror-video-echo-id — ТВ возвращает в
                        # ответе streamConnectionID, ОТЛИЧНЫЙ от запрошенного
                        # (живой вопрос: присваивает ли ТВ свой id).
                        # Клиент обязан залогировать вердикт и НЕ менять
                        # свой id/ключ — mock расшифровывает запрошенным id.
                        video_stream = {"type": 110, "dataPort": listener.port}
                        if MIRROR_VIDEO_ECHO_ID:
                            video_stream["streamConnectionID"] = desc.get("streamConnectionID", 0) + 1000000
                        resp = {"streams": [video_stream],
                                "eventPort": ev.port if ev else 0}
                        with SUMMARY_LOCK:
                            SUMMARY["mirror"]["video_stream_id"] = desc.get("streamConnectionID")
                        reply("RTSP/1.0 200 OK", {"Content-Type": "application/x-apple-binary-plist"},
                              plistlib.dumps(resp, fmt=plistlib.FMT_BINARY))
                        log(f"mirror video SETUP ok (type 110, dataPort={listener.port})")
                        continue
                    # streams есть, но тип не 96/110 — не зеркало, пусть
                    # разбирается RCS-ветка ниже.
                # v0.3.0: RCS SETUP отличается от видео/base SETUP по
                # ключу "streams" (type 130, controlType 1).
                if isinstance(setup_body.get("streams"), list):
                    streams = setup_body["streams"]
                    if REFUSE_RCS:
                        log("RCS SETUP: --refuse-rcs → 500")
                        note("rcs_setup", {"code": 500, "streams": streams})
                        reply("RTSP/1.0 500 Internal Server Error")
                        continue
                    rcs["registered"] = True
                    rcs["stream_id"] = 1
                    note("rcs_setup", {"code": 200, "streams": streams})
                    log(f"RCS SETUP ok: channelID={streams[0].get('channelID')}, "
                        f"clientTypeUUID={streams[0].get('clientTypeUUID')}")
                    reply("RTSP/1.0 200 OK", {"Content-Type": "application/x-apple-binary-plist"},
                          plistlib.dumps({"streams": [{"streamID": 1}]},
                                         fmt=plistlib.FMT_BINARY))
                    continue
                # Видео/base SETUP: timingProtocol — PTP (v0.3.0) или NTP.
                note("video_setup_timing", setup_body.get("timingProtocol"))
                ev = EventChannelServer(verify_shared)
                ev.start()
                handle_client.event_server = ev
                # v0.2.1 --skip-record: ТВ велит пропустить RECORD.
                setup_resp_body = {"eventPort": ev.port}
                if SKIP_RECORD:
                    setup_resp_body["skipRecord"] = True
                # v0.3.3 --clockports-port: ТВ сообщает свой PTP-порт
                # в timingPeerInfo ClockPorts (знаковый int16 в bplist,
                # как eventPort: 32770 → -32766). Mock слушает и этот
                # порт (см. PtpListener adv).
                if CLOCKPORTS_PORT:
                    signed = CLOCKPORTS_PORT - 65536 if CLOCKPORTS_PORT > 32767 else CLOCKPORTS_PORT
                    setup_resp_body["timingPeerInfo"] = {
                        "ID": "AFEBA180-9AAD-465A-85EB-6A37D79F8D13",
                        "Addresses": ["127.0.0.1"],
                        "ClockID": 5251580086010183688,
                        "DeviceType": 1,
                        "ClockPorts": {"A6F277E0-F2A7-498C-A4A1-00C2D7BD84A7": signed},
                        "SupportsClockPortMatchingOverride": True,
                    }
                reply("RTSP/1.0 200 OK", {"Content-Type": "application/x-apple-binary-plist"},
                      plistlib.dumps(setup_resp_body, fmt=plistlib.FMT_BINARY))
            elif method == "RECORD":
                note("record")
                reply("RTSP/1.0 200 OK")
            elif uri == "/feedback" or uri.startswith("/feedback"):
                note("feedback")
                if MIRROR:
                    mirror_note("feedback")
                    headers, _ = mirror_ptp_headers()
                    # v0.4.6: тело ответа — как у doubletake receiver:
                    # {"streams": [{"type": 96, "sr": 44100}]}. Клиент
                    # v0.4.6 обязан логировать тело целиком — раньше
                    # пустой ответ не давал это проверить. Тело
                    # кодируется bplist_dumps_signed (Apple-style
                    # signed-minimal ints): plistlib записал бы 44100
                    # как 2-байтовый 0xAC44, а декодер клиента по
                    # спеке читает его как -21436.
                    headers["Content-Type"] = "application/x-apple-binary-plist"
                    reply("RTSP/1.0 200 OK", headers,
                          bplist_dumps_signed({"streams": [{"type": 96, "sr": 44100}]}))
                else:
                    reply("RTSP/1.0 200 OK")
            elif uri == "/play":
                try:
                    play_body = plistlib.loads(body) if body else {}
                except Exception:
                    play_body = {"raw": body[:200]}
                # v0.2.1: ключи тела в лог — так тест отличает V2 /play
                # (Start-Position-Seconds) от V1 /play (Start-Position).
                log(f"play body keys: {sorted(str(k) for k in play_body.keys())}")
                note("play", str(play_body.get("Content-Location", "?")))
                played["done"] = True
                handle_client.played_url = play_body.get("Content-Location")
                reply("HTTP/1.1 200 OK")
            elif uri.startswith("/setProperty"):
                note("set_property")
                reply("RTSP/1.0 200 OK")
            elif uri.startswith("/rate"):
                note("rate", uri)
                reply("RTSP/1.0 200 OK")
            elif uri == "/playback-info":
                note("playback_info")
                # v0.2.1 --playback-info-500: живой ТВ на tvOS 26/27
                # отвечает 500 на КАЖДЫЙ опрос.
                if PLAYBACK_INFO_500:
                    reply("HTTP/1.1 500 Internal Server Error")
                else:
                    if played["done"]:
                        info = {"duration": 60.0, "position": 1.0, "rate": 1.0,
                                "readyToPlay": True}
                    else:
                        info = {}
                    reply("HTTP/1.1 200 OK", {"Content-Type": "application/x-apple-binary-plist"},
                          plistlib.dumps(info, fmt=plistlib.FMT_BINARY))
            elif uri == "/stop":
                note("stop")
                reply("HTTP/1.1 200 OK")
            elif uri.startswith("/scrub"):
                note("scrub", uri)
                reply("HTTP/1.1 200 OK")
            elif uri.startswith("/volume"):
                note("volume", uri)
                reply("HTTP/1.1 200 OK")
            elif method == "TEARDOWN":
                if MIRROR:
                    mirror_note("teardown")
                reply("RTSP/1.0 200 OK")
            else:
                log(f"UNHANDLED {method} {uri}")
                with SUMMARY_LOCK:
                    SUMMARY["errors"].append(f"{method} {uri}")
                reply("HTTP/1.1 404 Not Found")
    except (EOFError, ConnectionResetError, BrokenPipeError) as e:
        log(f"connection closed: {e}")
    except Exception as e:
        import traceback
        traceback.print_exc()
        with SUMMARY_LOCK:
            SUMMARY["errors"].append(f"{type(e).__name__}: {e}")
    finally:
        try:
            conn.close()
        except Exception:
            pass


# M3 verify needs the client X25519 public key from M1; keep it in a holder.
client_pub_holder = [b""]


def tlv_client_pub(_):
    return client_pub_holder[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=7000)
    ap.add_argument("--pin", default="1234")
    ap.add_argument("--summary", default="tests/mock_summary.json")
    ap.add_argument("--skip-record", action="store_true")
    ap.add_argument("--playback-info-500", action="store_true")
    ap.add_argument("--send-event", action="store_true")
    ap.add_argument("--refuse-rcs", action="store_true")
    ap.add_argument("--ptp", action="store_true")
    ap.add_argument("--clockports-port", type=int, default=0,
                    help="v0.3.3: рекламировать этот PTP-порт ТВ в "
                    "timingPeerInfo ClockPorts ответа SETUP и слушать "
                    "его; v0.3.4: приложение обязано его НЕ использовать "
                    "как UDP-порт (sync_adv в summary должен быть 0), "
                    "а только записать как номер PTP-порта "
                    "(0 — не рекламировать)")
    ap.add_argument("--no-command-event", action="store_true")
    ap.add_argument("--reject-minimal-item", action="store_true",
                    help="v0.3.2: минимальный item #2846 -> 400, полный #2899 -> 200")
    ap.add_argument("--reject-all-inserts", action="store_true",
                    help="v0.3.5: insert без messageID -> 400, с kind/messageID -> 200 (модель tvOS 27.2)")
    ap.add_argument("--reject-inserts-always", action="store_true",
                    help="v0.3.5: любой insert -> 400 (проверка вердикта 'вставка отклонена')")
    ap.add_argument("--mirror", action="store_true",
                    help="v0.4.0: режим зеркала — FairPlay SAP (/fp-setup), "
                    "control/audio/video SETUP, RECORD, /feedback, event "
                    "channel, TCP data channel с проверкой кадров")
    ap.add_argument("--mirror-video-echo-id", action="store_true",
                    help="v0.4.7: в ответе video SETUP вернуть streamConnectionID, "
                    "отличный от запрошенного (проверка вердикта клиента)")
    ap.add_argument("--mirror-dump", default=None,
                    help="v0.4.7: путь к ACD1-дампу принятых кадров data-канала "
                    "(для независимого верификатора tests/verify_mirror_dump.py)")
    args = ap.parse_args()
    global PIN, SKIP_RECORD, PLAYBACK_INFO_500, SEND_EVENT, REFUSE_RCS, PTP, CLOCKPORTS_PORT, SUMMARY_PATH, NO_COMMAND_EVENT, REJECT_MINIMAL, REJECT_ALL_INSERTS, REJECT_INSERTS_ALWAYS, MIRROR
    global MIRROR_VIDEO_ECHO_ID, MIRROR_DUMP_PATH
    PIN = args.pin
    SKIP_RECORD = args.skip_record
    PLAYBACK_INFO_500 = args.playback_info_500
    SEND_EVENT = args.send_event
    REFUSE_RCS = args.refuse_rcs
    PTP = args.ptp
    CLOCKPORTS_PORT = args.clockports_port
    NO_COMMAND_EVENT = args.no_command_event
    REJECT_MINIMAL = args.reject_minimal_item
    REJECT_ALL_INSERTS = args.reject_all_inserts
    REJECT_INSERTS_ALWAYS = args.reject_inserts_always
    MIRROR = args.mirror
    MIRROR_VIDEO_ECHO_ID = args.mirror_video_echo_id
    MIRROR_DUMP_PATH = args.mirror_dump
    SUMMARY_PATH = args.summary
    if MIRROR:
        # v0.4.7: аудио-UDP сокеты — как живой ТВ (реальные порты).
        # data — для RTP (не шлём, но порт рекламируем), control — для
        # TimeAnnounce/RTCP (слушаем и проверяем).
        _data_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        _data_sock.bind(("127.0.0.1", 0))
        _ctrl_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        _ctrl_sock.bind(("127.0.0.1", 0))
        MIRROR_AUDIO_DATA_PORT[0] = _data_sock.getsockname()[1]
        MIRROR_AUDIO_CTRL_PORT[0] = _ctrl_sock.getsockname()[1]
        MIRROR_AUDIO_CTRL_SOCK[0] = _ctrl_sock
        threading.Thread(target=_announce_listener, daemon=True).start()
        log(f"mirror audio UDP: data={MIRROR_AUDIO_DATA_PORT[0]} control={MIRROR_AUDIO_CTRL_PORT[0]}")
    # v0.3.0: при SIGTERM (как убивает run_mock_test.sh) — тоже
    # записать summary, а не терять его.
    def _on_term(signum, frame):
        flush_summary()
        raise SystemExit(0)
    signal.signal(signal.SIGTERM, _on_term)

    if PTP:
        if CLOCKPORTS_PORT and not (1 <= CLOCKPORTS_PORT <= 65535):
            ap.error("--clockports-port должен быть 1..65535")
        PtpListener(adv_port=CLOCKPORTS_PORT).start()

    # Patch handle_client to remember the M1 client pub key
    orig = handle_client
    import types

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", args.port))
    srv.listen(5)
    srv.settimeout(1.0)
    print(f"MOCK READY {args.port}", flush=True)
    log(f"mock Apple TV on 127.0.0.1:{args.port}, PIN {PIN}")
    try:
        while True:
            try:
                conn, addr = srv.accept()
            except socket.timeout:
                continue
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt:
        pass
    finally:
        if MIRROR_DUMP["fh"] is not None:
            try:
                MIRROR_DUMP["fh"].close()
            except Exception:
                pass
        with open(args.summary, "w") as f:
            json.dump(SUMMARY, f, indent=2, default=str)
        log(f"summary written to {args.summary}: {json.dumps(SUMMARY, default=str)}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Mock Companion server (TCP 49153) for the AirCast v0.5.0-vlc-test mock test.

Speaks the Companion framing from pyatv/protocols/companion/connection.py
(4-byte header [type:1B][payload length:3B big-endian, tag included]) and
decodes OPACK with pyatv's own implementation (pyatv.support.opack), so a
pass here means the Kotlin bytes match pyatv — not just our own code.
Session encryption is ChaCha20-Poly1305 with the pyatv nonce layout
(counter in the FIRST 8 bytes little-endian, zeros in the last 4;
AAD = frame header), via `cryptography`.

LIMITATION (honest, by design): the mock does NOT run an SRP server, so
Pair-Setup / Pair-Verify are not exercised here. Both sides use PRE-SHARED
test keys (TEST_CLIENT_OUT_KEY / TEST_CLIENT_IN_KEY below, identical
constants in tests/MockTest.kt section 16): the client connects with
CompanionClient.connectTestSession() and sends _launchApp directly. The
SRP handshake against a real TV is only verified live, on a real TV.
The mock answers every OPACK request (_t=2) with
{"_i": <same>, "_x": <same xid>, "_t": 3, "_c": {}} and records the
_launchApp dict + deep link in the summary JSON (flushed immediately).
v0.9.3 (#14): FetchLaunchableApplicationsEvent gets a _c dict with
a VLC entry (bundle id org.videolan.vlc-ios, name "VLC").
"""
import argparse
import json
import socket
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "research", "pyatv"))
from pyatv.support.opack import pack, unpack  # noqa: E402
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305  # noqa: E402

# Pre-shared test keys — MUST match tests/MockTest.kt section 16.
TEST_CLIENT_OUT_KEY = bytes(range(32))       # client encrypts, mock decrypts
TEST_CLIENT_IN_KEY = bytes(range(32, 64))    # mock encrypts, client decrypts

E_OPACK = 8


def nonce(counter):
    return counter.to_bytes(12, "little")


def read_exact(conn, n):
    data = b""
    while len(data) < n:
        chunk = conn.recv(n - len(data))
        if not chunk:
            raise EOFError("connection closed (%d/%d bytes)" % (len(data), n))
        data += chunk
    return data


def jsonable(o):
    if isinstance(o, bytes):
        return {"__bytes_hex__": o.hex()}
    if isinstance(o, dict):
        return {str(k): jsonable(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [jsonable(v) for v in o]
    return o


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=49153)
    ap.add_argument("--summary", default="tests/mock_companion_summary.json")
    args = ap.parse_args()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", args.port))
    srv.listen(5)
    print("MOCK COMPANION READY on %d" % args.port, flush=True)

    summary = {"launch_app": None, "deep_link": None, "xid": None, "requests": [], "frames": 0}

    def write_summary():
        with open(args.summary, "w") as f:
            json.dump(summary, f, indent=2)

    while True:
        conn, _ = srv.accept()
        print("[mock-companion] connection accepted", flush=True)
        in_cipher = ChaCha20Poly1305(TEST_CLIENT_OUT_KEY)
        out_cipher = ChaCha20Poly1305(TEST_CLIENT_IN_KEY)
        in_counter = 0
        out_counter = 0
        try:
            while True:
                header = read_exact(conn, 4)
                ftype = header[0]
                plen = int.from_bytes(header[1:4], "big")
                payload = read_exact(conn, plen) if plen > 0 else b""
                summary["frames"] += 1
                if plen > 0:
                    # Encrypted session frame: decrypt (AAD = header verifies framing).
                    plain = in_cipher.decrypt(nonce(in_counter), payload, header)
                    in_counter += 1
                else:
                    plain = b""
                data, _ = unpack(plain)
                print("[mock-companion] frame type=%d data=%r" % (ftype, data), flush=True)
                if not isinstance(data, dict):
                    continue
                summary["requests"].append(jsonable(data))
                if data.get("_i") == "_launchApp":
                    summary["launch_app"] = jsonable(data)
                    summary["deep_link"] = data.get("_c", {}).get("_urlS")
                    summary["xid"] = data.get("_x")
                    write_summary()
                    print("[mock-companion] _launchApp recorded: %r" % (data,), flush=True)
                if data.get("_t") == 2:  # Request -> Response
                    if data.get("_i") == "FetchLaunchableApplicationsEvent":
                        # v0.9.3 (#14): app_list — _c = {bundle_id: name},
                        # с VLC (имя "VLC" + vlc в bundle id).
                        content = {
                            "com.apple.tv": "TV",
                            "org.videolan.vlc-ios": "VLC",
                            "com.netflix.Netflix": "Netflix",
                        }
                        resp = {"_i": data.get("_i"), "_x": data.get("_x"), "_t": 3, "_c": content}
                    else:
                        resp = {"_i": data.get("_i"), "_x": data.get("_x"), "_t": 3, "_c": {}}
                    resp_bytes = pack(resp)
                    resp_header = bytes([ftype]) + (len(resp_bytes) + 16).to_bytes(3, "big")
                    enc = out_cipher.encrypt(nonce(out_counter), resp_bytes, resp_header)
                    out_counter += 1
                    conn.sendall(resp_header + enc)
        except (EOFError, ConnectionResetError, BrokenPipeError) as e:
            print("[mock-companion] connection closed: %s" % (e,), flush=True)
        except Exception as e:
            print("[mock-companion] ERROR: %r" % (e,), flush=True)
        finally:
            write_summary()
            try:
                conn.close()
            except Exception:
                pass


if __name__ == "__main__":
    main()

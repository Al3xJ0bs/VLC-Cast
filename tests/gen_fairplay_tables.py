#!/usr/bin/env python3
"""Generate FairPlay constant tables for Kotlin and Python from doubletake Go sources.

Source of truth: research/doubletake/internal/airplay/*.go (omarroth/doubletake,
LGPL-3.0, commit 5e2f1cb). The converter parses Go array literals — no numbers
are typed by hand, so a transcription error is impossible. If the Go source
changes, re-run this script and the table-checksum test (TestFPSAPTableData
vector: sha256 28d0986a... over 90128 expanded bytes) will catch any drift.

Outputs:
  app/src/main/java/com/alexey/aircast/fairplay/FairPlayTables.kt
  tests/fairplay_tables.py  (for the mirror mock TV)
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GO = ROOT / "research/doubletake/internal/airplay"


def strip_comments(src: str) -> str:
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return src


def find_var(src: str, name: str) -> str:
    """Return the brace-balanced literal following `var <name> =`."""
    m = re.search(r"var\s+" + re.escape(name) + r"\s*=\s*", src)
    if not m:
        raise ValueError(f"var {name} not found")
    i = src.index("{", m.end())
    depth = 0
    for j in range(i, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[i:j + 1]
    raise ValueError(f"unbalanced braces for {name}")


def parse_tree(text: str):
    """Parse a Go literal into nested lists of ints (hex 0x.. or decimal)."""
    tokens = []
    i = 0
    while i < len(text):
        c = text[i]
        if c in "{},":
            tokens.append(c)
            i += 1
        elif c == "0" and i + 1 < len(text) and text[i + 1] in "xX":
            m = re.match(r"0[xX][0-9a-fA-F]+", text[i:])
            tokens.append(int(m.group(0), 16))
            i += len(m.group(0))
        elif c.isdigit():
            m = re.match(r"[0-9]+", text[i:])
            tokens.append(int(m.group(0)))
            i += len(m.group(0))
        else:
            i += 1
    stack: list = [[]]
    for t in tokens:
        if t == "{":
            stack.append([])
        elif t == "}":
            node = stack.pop()
            stack[-1].append(node)
        elif t == ",":
            pass
        else:
            stack[-1].append(t)
    assert len(stack) == 1 and len(stack[0]) == 1, f"parse failed: {stack}"
    return stack[0][0]


def flat(tree):
    out = []
    for v in tree:
        if isinstance(v, list):
            out.extend(flat(v))
        else:
            out.append(v)
    return out


def get(src: str, name: str):
    lit = find_var(src, name)
    # Type annotations ([9][16]fpsapByteLookup, [6][256]byte, ...) carry
    # dimensions that are not data; Go literals never use square brackets.
    lit = re.sub(r"\[\d+\]", "", lit)
    return parse_tree(lit)


def check_shape(name, tree, shape):
    got = []
    t = tree
    while isinstance(t, list) and t and isinstance(t[0], list):
        got.append(len(t))
        t = t[0]
    if isinstance(t, list):
        got.append(len(t))
    if tuple(got) != tuple(shape):
        raise ValueError(f"{name}: shape {got} != {shape}")
    return tree


def kt_int_array(name: str, values, per_line=16) -> str:
    lines = []
    for i in range(0, len(values), per_line):
        lines.append("        " + ", ".join(str(v) for v in values[i:i + per_line]) + ",")
    return f"    val {name}: IntArray = intArrayOf(\n" + "\n".join(lines) + "\n    )\n"


def main() -> int:
    msg = strip_comments((GO / "fairplay_message.go").read_text())
    tables = strip_comments((GO / "fpsap_tables.go").read_text())
    fpsap = strip_comments((GO / "fpsap.go").read_text())
    # fpsapM1Payload references the const fpsapM1Capabilities = byte(3);
    # substitute the value so the tokenizer never sees the "1" in "M1".
    fpsap = fpsap.replace("fpsapM1Capabilities", "3")
    sap = strip_comments((GO / "fairplay_sap.go").read_text())
    md5 = strip_comments((GO / "fairplay_md5.go").read_text())
    crypto = strip_comments((GO / "fairplay_crypto.go").read_text())

    data = {}
    # fairplay_message.go
    data["MESSAGE_IV"] = check_shape("MESSAGE_IV", get(msg, "fairplayMessageIV"), (4, 16))
    data["MESSAGE_ROUND_KEY_0"] = check_shape("RK0", get(msg, "fairplayMessageRoundKey0"), (16,))
    data["MESSAGE_ROUND_KEY_10"] = check_shape("RK10", get(msg, "fairplayMessageRoundKey10"), (16,))
    data["MESSAGE_MIDDLE_KEYS"] = check_shape("MID", get(msg, "fairplayMessageMiddleKeys"), (4, 9, 16))
    data["INVERSE_AES_SBOX"] = check_shape("ISBOX", get(msg, "inverseAESSBox"), (256,))
    # fpsap_tables.go
    data["SUBSTITUTION_BASES"] = check_shape("SUB", get(tables, "fpsapSubstitutionBases"), (6, 256))
    data["MIX_BASES"] = check_shape("MIX", get(tables, "fpsapMixBases"), (7, 256))
    data["FIRST_INPUT_MASK"] = check_shape("FIM", get(tables, "fpsapFirstInputMask"), (16,))
    data["SECOND_OUTPUT_MASK"] = check_shape("SOM", get(tables, "fpsapSecondOutputMask"), (16,))
    first = get(tables, "fpsapFirstTables")
    second = get(tables, "fpsapSecondTables")
    # fpsapNetworkTables: {roundSubstitution [9][16] triples, mixColumns [4][4] triples,
    # finalSubstitution [16] triples} — the Go literal is a struct with 3 fields.
    for label, tbl in (("FIRST", first), ("SECOND", second)):
        rs, mc, fs = tbl
        data[f"{label}_ROUND_SUB"] = check_shape(label + " RS", rs, (9, 16, 3))
        data[f"{label}_MIX_COLS"] = check_shape(label + " MC", mc, (4, 4, 3))
        data[f"{label}_FINAL_SUB"] = check_shape(label + " FS", fs, (16, 3))
    # fpsap.go
    data["M1_PAYLOAD"] = check_shape("M1", get(fpsap, "fpsapM1Payload"), (4,))
    data["M3_LABEL"] = check_shape("M3L", get(fpsap, "fpsapM3Label"), (3,))
    data["DESCRIPTOR_PREFIX"] = check_shape("DP", get(fpsap, "fpsapDescriptorPrefix"), (17,))
    data["DESCRIPTOR_SUFFIX"] = check_shape("DS", get(fpsap, "fpsapDescriptorSuffix"), (17,))
    data["FIXED_BLOCK"] = check_shape("FB", get(fpsap, "fpsapFixedBlock"), (16,))
    data["FIRST_POSITION_MAP"] = check_shape("FPM", get(fpsap, "fpsapFirstPositionMap"), (16,))
    data["SECOND_POSITION_MAP"] = check_shape("SPM", get(fpsap, "fpsapSecondPositionMap"), (16,))
    # fairplay_sap.go
    data["SAP_SEED"] = check_shape("SEED", get(sap, "sapSeed"), (21,))
    init = get(sap, "sapInitialState")
    data["SAP_INITIAL_HASH"] = check_shape("IH", init[0], (20,))
    data["SAP_INITIAL_MATRIX"] = check_shape("IM", init[1], (35,))
    # fairplay_md5.go
    data["MD5_SHIFT"] = check_shape("SH", get(md5, "fairplayMD5Shift"), (64,))
    data["MD5_CONSTANT"] = check_shape("KC", get(md5, "fairplayMD5Constant"), (64,))
    # fairplay_crypto.go
    data["INITIAL_SESSION_KEY"] = check_shape("ISK", get(crypto, "fairplayInitialSessionKey"), (16,))
    data["KDF_PREFIX"] = check_shape("KP", get(crypto, "fairplayKDFPrefix"), (17,))
    data["KDF_SUFFIX"] = check_shape("KS", get(crypto, "fairplayKDFSuffix"), (17,))

    # ---- Kotlin ----
    kt = []
    kt.append("package com.alexey.aircast.fairplay")
    kt.append("")
    kt.append("/**")
    kt.append(" * FairPlay SAP constant tables — GENERATED, do not edit by hand.")
    kt.append(" * Generated by tests/gen_fairplay_tables.py from doubletake")
    kt.append(" * (omarroth/doubletake, LGPL-3.0, commit 5e2f1cb),")
    kt.append(" * internal/airplay/{fairplay_message,fpsap_tables,fpsap,fairplay_sap,")
    kt.append(" * fairplay_md5,fairplay_crypto}.go. Values are bytes as Ints 0..255;")
    kt.append(" * MD5 constants are unsigned 32-bit values (stored as Int, use as UInt")
    kt.append(" * where the Go code uses uint32). Regenerate on upstream change; the")
    kt.append(" * table-checksum unit test pins the expanded tables.")
    kt.append(" *")
    kt.append(" * SPDX-License-Identifier: LGPL-3.0-or-later")
    kt.append(" */")
    kt.append("internal object FairPlayTables {")
    for name, tree in data.items():
        if name == "MD5_CONSTANT":
            kt.append(kt_int_array(name, [v if v < 2**31 else v - 2**32 for v in flat(tree)]))
        else:
            kt.append(kt_int_array(name, flat(tree)))
    kt.append("}")
    out_kt = ROOT / "app/src/main/java/com/alexey/aircast/fairplay/FairPlayTables.kt"
    out_kt.parent.mkdir(parents=True, exist_ok=True)
    out_kt.write_text("\n".join(kt))
    print(f"wrote {out_kt} ({sum(len(flat(v)) for v in data.values())} values)")

    # ---- Python (mirror mock) ----
    py = []
    py.append('"""FairPlay constant tables — GENERATED, do not edit by hand.')
    py.append("Generated by tests/gen_fairplay_tables.py from doubletake")
    py.append("(omarroth/doubletake, LGPL-3.0, commit 5e2f1cb).")
    py.append('SPDX-License-Identifier: LGPL-3.0-or-later')
    py.append('"""')
    py.append("")
    for name, tree in data.items():
        vals = flat(tree)
        py.append(f"{name} = {vals!r}")
        py.append("")
    out_py = ROOT / "tests/fairplay_tables.py"
    out_py.write_text("\n".join(py))
    print(f"wrote {out_py}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

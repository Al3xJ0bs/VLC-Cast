# SPDX-License-Identifier: LGPL-3.0-or-later
# FairPlay SAP (mirroring authentication) — Python port of doubletake's
# internal/airplay/{fairplay_sap,fairplay_md5,fairplay_message,fpsap,
# fairplay_crypto,fairplay}.go (omarroth/doubletake, LGPL-3.0-or-later,
# commit 5e2f1cb), translated from the verified Kotlin port in
# app/src/main/java/com/alexey/aircast/fairplay/FairPlaySap.kt.
# This file is a translation of that LGPL-3.0 code into Python; the
# translation itself is likewise LGPL-3.0-or-later.
#
# Receiver-side primitives for a mock Apple TV, used to validate the
# app's FairPlay handshake. Pure Python (stdlib + `cryptography`).
#
# Conventions: bytes are ints 0..255; uint32 words are ints masked to
# 0xFFFFFFFF after every add/sub/mul; every byte assignment wraps
# mod 256 via _b(). Kotlin Int is 32-bit two's-complement: (~x) is
# emulated as (~x) & 0xFFFFFFFF for words and (~x) & 0xFF for bytes.

import hashlib
import hmac
import os
import sys

try:
    from fairplay_tables import (
        DESCRIPTOR_PREFIX,
        DESCRIPTOR_SUFFIX,
        FIRST_FINAL_SUB,
        FIRST_INPUT_MASK,
        FIRST_MIX_COLS,
        FIRST_POSITION_MAP,
        FIRST_ROUND_SUB,
        FIXED_BLOCK,
        INITIAL_SESSION_KEY,
        INVERSE_AES_SBOX,
        KDF_PREFIX,
        KDF_SUFFIX,
        MD5_CONSTANT,
        MD5_SHIFT,
        MESSAGE_IV,
        MESSAGE_MIDDLE_KEYS,
        MESSAGE_ROUND_KEY_0,
        MESSAGE_ROUND_KEY_10,
        MIX_BASES,
        SAP_INITIAL_HASH,
        SAP_INITIAL_MATRIX,
        SAP_SEED,
        SECOND_FINAL_SUB,
        SECOND_MIX_COLS,
        SECOND_OUTPUT_MASK,
        SECOND_POSITION_MAP,
        SECOND_ROUND_SUB,
        SUBSTITUTION_BASES,
    )
except ImportError:  # pragma: no cover - import from another cwd
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from fairplay_tables import (
        DESCRIPTOR_PREFIX,
        DESCRIPTOR_SUFFIX,
        FIRST_FINAL_SUB,
        FIRST_INPUT_MASK,
        FIRST_MIX_COLS,
        FIRST_POSITION_MAP,
        FIRST_ROUND_SUB,
        FIXED_BLOCK,
        INITIAL_SESSION_KEY,
        INVERSE_AES_SBOX,
        KDF_PREFIX,
        KDF_SUFFIX,
        MD5_CONSTANT,
        MD5_SHIFT,
        MESSAGE_IV,
        MESSAGE_MIDDLE_KEYS,
        MESSAGE_ROUND_KEY_0,
        MESSAGE_ROUND_KEY_10,
        MIX_BASES,
        SAP_INITIAL_HASH,
        SAP_INITIAL_MATRIX,
        SAP_SEED,
        SECOND_FINAL_SUB,
        SECOND_MIX_COLS,
        SECOND_OUTPUT_MASK,
        SECOND_POSITION_MAP,
        SECOND_ROUND_SUB,
        SUBSTITUTION_BASES,
    )

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

MUTATION_SWAP = 0
MUTATION_CYCLE = 1
MUTATION_KDF = 2

_MASK32 = 0xFFFFFFFF


def _b(x):
    return x & 0xFF


def _u32(x):
    return x & _MASK32


def _rotl8(x, k):
    s = k & 7
    x = x & 0xFF
    if s == 0:
        return x
    return ((x << s) | (x >> (8 - s))) & 0xFF


def _rotl32(x, k):
    x = x & _MASK32
    if k == 0:
        return x
    return ((x << k) | (x >> (32 - k))) & _MASK32


def _rotate_or_zero(inp, count):
    if count == 0:
        return 0
    return _rotl8(inp, count)


def _wide_seed(inp, count):
    seed = SAP_SEED
    if count == 0:
        return seed[0]
    left = 0 if count >= 64 else (inp << count)
    rshift = (8 - count) & 0xFF
    right = 0 if rshift >= 64 else (inp >> rshift)
    idx = (left | right) % 21
    return seed[idx]


def _majority(a, b, c):
    return _b(a ^ ((a ^ b) & (a ^ c)))


def _select_bits(mask, if_set, if_clear):
    return _b(if_clear ^ ((if_set ^ if_clear) & mask))


def _square(v):
    return _b(v * v)


def _cube(v):
    return _b(v * v * v)


def _trunc_div(a, b):
    """Kotlin Int division: truncate toward zero (Python // floors)."""
    q = abs(a) // abs(b)
    return -q if (a < 0) ^ (b < 0) else q


# ---------------------------------------------------------------------------
# Modified MD5 (fairplay_md5.go)
# ---------------------------------------------------------------------------

def fairplay_words_from_le(inp):
    out = []
    for i in range(4):
        out.append(
            (inp[i * 4])
            | (inp[i * 4 + 1] << 8)
            | (inp[i * 4 + 2] << 16)
            | (inp[i * 4 + 3] << 24)
        )
    return out


def fairplay_words_to_be(words):
    out = bytearray(16)
    for i in range(4):
        w = words[i] & _MASK32
        out[i * 4] = (w >> 24) & 0xFF
        out[i * 4 + 1] = (w >> 16) & 0xFF
        out[i * 4 + 2] = (w >> 8) & 0xFF
        out[i * 4 + 3] = w & 0xFF
    return bytes(out)


def _mutate_message(message, a, b, c, d, mutation):
    def swap(i, j):
        t = message[i]
        message[i] = message[j]
        message[j] = t

    if mutation == MUTATION_SWAP:
        idx = [
            a & 15, b & 15, c & 15, d & 15,
            (a >> 4) & 15, (b >> 4) & 15, (c >> 4) & 15, (d >> 4) & 15,
        ]
        for i in range(len(idx)):
            swap(i, idx[i])
    elif mutation == MUTATION_CYCLE:
        idx = [
            a & 15, b & 15, c & 15, d & 15,
            (a >> 4) & 15, (b >> 4) & 15, (c >> 4) & 15, (d >> 4) & 15,
        ]
        first = message[idx[0]]
        for i in range(len(idx) - 1):
            message[idx[i]] = message[idx[i + 1]]
        message[idx[-1]] = first
    elif mutation == MUTATION_KDF:
        swap(a & 15, b & 15)
        swap(c & 15, d & 15)
        shift = 4
        while shift <= 12:
            swap((a >> shift) & 15, (b >> shift) & 15)
            shift += 4


def fairplay_md5_compress(state, block, mutation):
    message = [0] * 16
    for i in range(16):
        message[i] = (
            (block[i * 4] << 24)
            | (block[i * 4 + 1] << 16)
            | (block[i * 4 + 2] << 8)
            | block[i * 4 + 3]
        )
    a = _u32(state[0])
    b = _u32(state[1])
    c = _u32(state[2])
    d = _u32(state[3])
    for rnd in range(64):
        if rnd < 16:
            f = (b & c) | (((~b) & _MASK32) & d)
            word = rnd
        elif rnd < 32:
            f = (d & b) | (((~d) & _MASK32) & c)
            word = (5 * rnd + 1) & 15
        elif rnd < 48:
            f = b ^ c ^ d
            word = (3 * rnd + 5) & 15
        else:
            f = c ^ (b | ((~d) & _MASK32))
            word = (7 * rnd) & 15
        f = _u32(f)
        na = d
        nb = _u32(b + _rotl32(_u32(a + f + MD5_CONSTANT[rnd] + message[word]), MD5_SHIFT[rnd]))
        nc = b
        nd = c
        a, b, c, d = na, nb, nc, nd
        if rnd == 31:
            _mutate_message(message, a, b, c, d, mutation)
    return [
        _u32(state[0] + a),
        _u32(state[1] + b),
        _u32(state[2] + c),
        _u32(state[3] + d),
    ]


# ---------------------------------------------------------------------------
# Proprietary SAP hash (fairplay_sap.go)
# ---------------------------------------------------------------------------

def fairplay_sap_hash(block):
    if len(block) < 64:
        raise ValueError("SAP hash needs a 64-byte block")
    hash_ = list(SAP_INITIAL_HASH)
    matrix = list(SAP_INITIAL_MATRIX)
    aux = [0] * 10
    work = [0] * 210
    for i in range(len(work)):
        work[i] = block[(i & 63) ^ 3] & 0xFF

    for i in range(840):
        def u32mod(k, _i=i):
            return (((_i - k) & _MASK32) % 210)

        x = work[u32mod(155)]
        y = work[u32mod(57)]
        z = work[u32mod(13)]
        w = work[i % 210]
        work[i % 210] = _b(_rotl8(y, 5) + (_rotl8(z, 3) ^ w) - _rotl8(x, 7))

    _nonlinear_circuit(hash_, matrix, aux, work)

    out = [0] * 16
    for i in range(0, 3):
        out[i] = aux[i]
    for i in range(4, 11):
        out[i] = aux[i - 1]
    for i in range(len(out)):
        out[i] = _b(out[i] + 0xE1)
    out[3] = 0x3D
    out[11] = 0x3C
    out[10] = _b(out[10] ^ aux[3] ^ 133)

    for i in range(len(work)):
        value = work[i]
        if i < len(matrix):
            value = value ^ matrix[i]
        if i < len(hash_):
            value = value ^ hash_[i]
        out[i & 15] = _b(out[i & 15] ^ value)

    for i in range(256):
        out[i & 15] = _b(
            out[i & 15]
            ^ _rotl8(out[(i - 7) & 15], 1)
            ^ _rotl8(out[(i - 5) & 15], 6)
            ^ _rotl8(out[(i - 1) & 15], 5)
        )
    return bytes(out)


def _nonlinear_circuit(hash_, matrix, aux, work):
    def hi(i):
        return hash_[i % 20]

    def si(i):
        return SAP_SEED[i % 21]

    def h(i):
        return hi(work[i])

    def m(i):
        return matrix[work[i] % 35]

    def s(i):
        return si(work[i])

    def ma(i):
        return matrix[aux[i] % 35]

    matrix[12] = _b(0x14 + (_select_bits(92, work[64], work[99] // 3) & _wide_seed(s(206), 4)))
    work[4] = _b(2 * _square(work[99] // 5))
    work[153] = _b(work[153] ^ (_square(m(203)) * work[190]))
    hash_[3] = _b(0x13 ^ ((s(205) >> 1) & 0x10))
    work[33] = _b(work[33] - (s(36) & ((~9) & 0xFF)))
    aux[5] = _b(((m(67) & ((~2) & 0xFF)) | 1 | ((h(181) >> 6) & 2) | (hash_[3] & 0x10)) - 15)
    matrix[12] = 0x07
    work[2] = _b(work[2] - 64)
    hash_[19] = s(58)
    aux[4] = _b(92 - m(32))
    aux[9] = _b(m(15) + 0x9E)
    work[34] = _b(work[34] + si(aux[9]) // 5)
    hash_[19] = _b(hash_[19] + (0xE6 ^ ((hi(aux[9]) >> 1) & 0x66)))
    work[15] = _b(work[15] ^ (3 * _rotate_or_zero(work[72], (((-s(190)) & 0xFF) & 7)) - 9 * s(126)))
    hash_[15] = _b(hash_[15] ^ _cube(m(181)))
    matrix[4] = _b(matrix[4] ^ (work[202] // 3))
    matrix[1] = _b(matrix[1] + _cube(_majority(_b(92 - hi(aux[4])), ((~work[105]) & 0xFF), 0xC6)))
    hash_[19] = _b(hash_[19] ^ (((224 | (s(92) & 27)) * m(41)) // 3))
    work[140] = _b(work[140] + _rotate_or_zero(92, (((-work[5]) & 0xFF) & 7)))
    matrix[12] = _b(matrix[12] + _majority(_b(((~work[4]) ^ m(12))), work[182], 192))
    work[36] = _b(work[36] + 125)
    work[124] = _rotl8(_majority(_majority(work[138], hash_[15], 74), h(43), 95), 4)
    aux_hash = hi(aux[9])
    aux[1] = _b(0x4C & (~(aux_hash & (s(68) << 1))))
    aux[2] = _b(222 - _majority((work[177] + s(79)) >> 1, (3 * work[148]) // 5, matrix[1]))
    matrix[16] = _b(matrix[16] + (((ma(4) & ((~0x60) & 0xFF)) | aux_hash | 8) - (_rotl8(work[33], 2) | 128)))
    hash_[14] = _b(hash_[14] ^ ma(2))
    work[19] = _b(work[19] + _majority(
        _rotate_or_zero(si(h(201)), ((m(112) << 1) & 6)),
        (((h(208) & ((~0x7C) & 0xFF)) | (h(164) & 0x7C)) // 5),
        37,
    ))
    matrix[8] = _b(_rotate_or_zero(140, (((-_square(s(45))) & 0xFF) & 7)) ^ aux[4])
    work[190] = 56
    work[53] = _b(~(((h(83) | 204) // 5)))
    hash_[13] = _b(hash_[13] + h(41))
    hash_[10] = _majority(ma(4), work[2], aux[2]) // 15
    aux[3] = _b(92 - _square(0x28 | (ma(1) & (0x12 | (s(2) & 4)))))
    seed_bits = si(aux[4])
    matrix[13] = _b(matrix[13] ^ seed_bits)
    aux[6] = _b(92 + _square(_majority(_b(m(179) - 38), aux[2], 177)))
    expansion_bits = _majority(_b(aux[3] + (aux[4] & 74)), ((~seed_bits) & 0xFF), 121)
    work[47] = _b(work[47] ^ (m(89) + _majority(_b(expansion_bits ^ 0xA6), aux[4], 4)))
    aux[7] = _b(seed_bits // 3 - ma(9) - (0x14 | (work[151] & ((aux[4] & 0x88) | 0x62)) | (aux[4] & 0x22)))
    expanded_selector = _b(expansion_bits ^ ((aux[4] & 0xCA) >> 1) ^ 75)
    aux[9] = _b(aux[9] + (0x80 | (_majority(aux[7], work[151], 0x20) & 0x64) | (seed_bits & 0x44) | (ma(9) & 0x1B)))
    matrix[33] = _b(matrix[33] ^ work[26])
    matrix[30] = _b((aux[9] // 3 - ((aux[4] & ((~8) & 0xFF)) | 0x13)) ^ h(122))
    work[22] = _b((m(90) & 0x1B) | 0x44)
    wide = _select_bits(71, matrix[expanded_selector % 35], si(aux[5]))
    matrix[18] = _b(matrix[18] + ((wide * wide * wide) >> 1))
    matrix[5] = _b(matrix[5] - s(92))
    matrix[18] = _b(matrix[18] ^ (
        _select_bits(aux[3], ma(3), _select_bits(16, m(183), work[41]))
        * _select_bits(expanded_selector, h(59), work[17])
    ))
    matrix[22] = _b(_majority(
        _select_bits((hash_[14] | 28), ((work[7] & 28) | 0x82), h(93)),
        _rotate_or_zero(ma(4), _rotate_or_zero(work[11], (((-m(28)) & 0xFF) & 7)) & 7),
        matrix[33],
    ) + 74)
    hash_[15] = _b(hash_[15] - _majority(
        _majority(aux[3], aux[4], 214),
        si(_b(h(39) ^ 217)),
        aux[6],
    ))

    hash9 = hi(aux[9])
    _a = (aux[4] // 3) - (aux[9] | work[22])
    _b1 = (m(57) | hash9) & (0x52 | (aux[9] & 0x0D))
    _b2 = (((m(57) & hash9) | aux[9]) & 0x20)
    indexed_hash = hi(_b(_a ^ aux[6] ^ (_b1 | _b2)))
    aux[6] = _b(_square(_square(h(99))) | ma(9))
    aux[1] = _b(aux[1] + _rotate_or_zero(_b(h(151) | s(202)), h(50) & 7) + _majority(
        h(4),
        ((_select_bits(matrix[16], indexed_hash, m(138)) + _select_bits(17, work[33], s(39))) // 5),
        147,
    ))
    aux[0] = _select_bits(
        hash_[10] & 7,
        ma(6) & h(209),
        _select_bits(0x47, _rotate_or_zero(s(127), ma(6) & 7), _b(si(ma(5)) << 1)),
    )
    selected_square = _select_bits(198, _square(m(14)), _b(h(145) ^ aux[0]))
    seed9 = si(aux[9])
    hash3 = hi(aux[3])
    matrix[2] = _b(matrix[2] + (((hash3 << 1) & ((work[25] & 0x96) | (seed9 & 8))) | (seed9 & 0x40)))
    matrix[14] = _b(matrix[14] - _select_bits(34, work[97], ma(3) & (aux[0] ^ m(100))))
    work[23] = _b(work[23] ^ (_majority(_majority(s(17), hash3, aux[0]), work[50] // 3, 0x76) << 1))
    hash_[17] = 115
    hash_[13] = _b(((_majority(hi(aux[7]), work[10], 82) >> 1) & 0x68) | (h(39) & 0x17))
    matrix[33] = _b(matrix[33] - (work[113] & 9))
    matrix[28] = _b(matrix[28] - ((aux[3] & ((~0x20) & 0xFF)) | ((work[110] >> 1) & 0x20)))
    work[95] = si(aux[3])
    hash_[15] = _b(_majority(_b(work[95] - 48), ((~work[184]) & 0xFF), 189) & _cube(_majority(aux[7], si(aux[1]), 0xAA)))
    matrix[22] = _b(matrix[22] + work[183])
    aux[4] = _b(aux[4] ^ (3 * s(1)))
    aux[5] = _b(aux[5] + (198 * _majority(s(178), ma(1), 209) * h(13) * (s(26) >> 1)))
    aux[8] = _select_bits(10, ma(3), ma(9))
    matrix[18] = _b(matrix[18] - _select_bits(hash_[15], aux[5] // 15, _cube(_b(hi(aux[6]) | 81))))
    aux[1] = _b(aux[1] + (si(hi(aux[1])) // 3 - h(160)))
    hash_[16] = _b(147 - _majority(
        aux[0],
        _majority(s(69), work[172], _b(aux[2] - selected_square + 77)),
        _b(0xC2 | (aux[0] & 5)),
    ))
    hash_[3] = _b(hash_[3] - _wide_seed(
        _majority(s(155), work[105], 141),
        _majority(s(168), h(29), 6) & 7,
    ))
    work[5] = _b(_rotate_or_zero(0x38, (((-(h(61) // 5)) & 0xFF) & 7)) ^ (((~ma(8)) & 0xFF) // 5))
    work[198] = _b(work[198] + work[3])
    wide = 162 | ma(9)
    work[164] = _b(work[164] + (wide * wide // 5))
    aux[2] = _b(_majority(
        _rotate_or_zero(139, (((-aux[5]) & 0xFF) & 6)),
        hi(aux[3]),
        12,
    ) | _select_bits(95, _cube(seed9), hi(aux[7])))
    matrix[12] = _b(matrix[12] + ((16 | ((work[103] | 60) & (aux[2] | (work[103] & 32)))) // 3))
    work[143] = _b(work[143] - (0x12 | (
        _select_bits(aux[9], _select_bits(matrix[8], work[35], aux[7]), aux[8] // 3)
        & (0x4D | ((work[172] >> 1) & 0x20))
    )))
    matrix[29] = 162
    hash_[15] = _b(hash_[15] + _majority(
        _b(m(149) ^ _square(work[43])),
        _select_bits(95, h(125), si(aux[1])) >> 1,
        115,
    ))
    aux[9] = _b(aux[9] - hi(aux[7]))
    hash_[7] = _b(hash_[7] - _square(_rotate_or_zero(ma(5), _b((-m(17)) * (m(17) & 1)))))
    matrix[8] = _b(matrix[8] + (_cube(s(202)) - work[184]))
    hash_[16] = _b((m(102) << 1) & 0x84)
    aux[6] = _b(aux[6] ^ (si(aux[7]) >> 1))
    hash_[7] = _b(hash_[7] - (h(191) - _select_bits(177, si(si(aux[1])), _b(s(80) << 1))))
    hash_[6] = h(119)
    hash_[12] = _b((hi(aux[8]) ^ (m(71) + m(15))) & _majority(
        _b((work[118] & ((~0x2C) & 0xFF)) | 2),
        _square(hi(aux[9])),
        27,
    ))
    digest_index = _select_bits(0xA9, _b(s(57) * 231), _majority(work[32], ma(1), 23)) // 5
    seed_sample = si(aux[6])
    aux[5] = _b(_majority(
        _b((seed_sample & 0x1C) | (h(82) & 0xA2) | (si(digest_index) & 0x41)),
        _majority(_cube(hi(aux[7])), work[82], 92),
        192,
    ) ^ digest_index)
    matrix[25] = _b(matrix[25] ^ (2 * hi(aux[9]) * work[5] - (_rotate_or_zero(aux[4], seed_sample & 7) & (aux[3] + 110))))


# ---------------------------------------------------------------------------
# FairPlay message cipher (fairplay_message.go)
# ---------------------------------------------------------------------------

_FORWARD_AES_SBOX = [0] * 256
for _i in range(256):
    _FORWARD_AES_SBOX[INVERSE_AES_SBOX[_i]] = _i


def _aes_gf_multiply(a0, b0):
    a = _b(a0)
    b = _b(b0)
    product = 0
    while b != 0:
        if b & 1 != 0:
            product = product ^ a
        high = a & 0x80
        a = _b(a << 1)
        if high != 0:
            a = a ^ 0x1B
        b = b >> 1
    return product


def _xor_round_key(state, key):
    for i in range(16):
        state[i] = state[i] ^ key[i]


def _inv_shift_rows(state):
    prev = list(state)
    for row in range(4):
        for col in range(4):
            state[4 * col + row] = prev[4 * ((col - row + 4) & 3) + row]


def _shift_rows(state):
    prev = list(state)
    for row in range(4):
        for col in range(4):
            state[4 * col + row] = prev[4 * ((col + row) & 3) + row]


def _inv_mix_columns(state):
    for col in range(4):
        off = col * 4
        a = state[off]
        bb = state[off + 1]
        c = state[off + 2]
        d = state[off + 3]
        state[off] = _aes_gf_multiply(a, 14) ^ _aes_gf_multiply(bb, 11) ^ _aes_gf_multiply(c, 13) ^ _aes_gf_multiply(d, 9)
        state[off + 1] = _aes_gf_multiply(a, 9) ^ _aes_gf_multiply(bb, 14) ^ _aes_gf_multiply(c, 11) ^ _aes_gf_multiply(d, 13)
        state[off + 2] = _aes_gf_multiply(a, 13) ^ _aes_gf_multiply(bb, 9) ^ _aes_gf_multiply(c, 14) ^ _aes_gf_multiply(d, 11)
        state[off + 3] = _aes_gf_multiply(a, 11) ^ _aes_gf_multiply(bb, 13) ^ _aes_gf_multiply(c, 9) ^ _aes_gf_multiply(d, 14)


def _mix_columns(state):
    for col in range(4):
        off = col * 4
        a = state[off]
        bb = state[off + 1]
        c = state[off + 2]
        d = state[off + 3]
        state[off] = _aes_gf_multiply(a, 2) ^ _aes_gf_multiply(bb, 3) ^ c ^ d
        state[off + 1] = a ^ _aes_gf_multiply(bb, 2) ^ _aes_gf_multiply(c, 3) ^ d
        state[off + 2] = a ^ bb ^ _aes_gf_multiply(c, 2) ^ _aes_gf_multiply(d, 3)
        state[off + 3] = _aes_gf_multiply(a, 3) ^ bb ^ c ^ _aes_gf_multiply(d, 2)


def _middle_key(mode, rnd):
    base = (mode * 9 + rnd) * 16
    return MESSAGE_MIDDLE_KEYS[base:base + 16]


def _decrypt_message_block(state, mode):
    _xor_round_key(state, MESSAGE_ROUND_KEY_10)
    for rnd in range(9, 0, -1):
        _inv_shift_rows(state)
        for i in range(16):
            state[i] = INVERSE_AES_SBOX[state[i]]
        _xor_round_key(state, _middle_key(mode, rnd - 1))
        _inv_mix_columns(state)
    _inv_shift_rows(state)
    for i in range(16):
        state[i] = INVERSE_AES_SBOX[state[i]]
    _xor_round_key(state, MESSAGE_ROUND_KEY_0)


def _encrypt_message_block(state, mode):
    _xor_round_key(state, MESSAGE_ROUND_KEY_0)
    for i in range(16):
        state[i] = _FORWARD_AES_SBOX[state[i]]
    _shift_rows(state)
    for rnd in range(9):
        _mix_columns(state)
        _xor_round_key(state, _middle_key(mode, rnd))
        for i in range(16):
            state[i] = _FORWARD_AES_SBOX[state[i]]
        _shift_rows(state)
    _xor_round_key(state, MESSAGE_ROUND_KEY_10)


def decrypt_fairplay_message(message):
    if len(message) < 144:
        raise ValueError("FairPlay message must be at least 144 bytes")
    mode = message[12]
    if mode not in (0, 1, 2, 3):
        raise ValueError("unsupported FairPlay mode %d" % mode)
    iv = MESSAGE_IV[mode * 16:(mode + 1) * 16]
    plaintext = bytearray(128)
    for step in range(8):
        block = step
        if mode == 3:
            block = 7 - step
        start = 16 + block * 16
        state = list(message[start:start + 16])
        _decrypt_message_block(state, mode)
        if block > 0:
            chain = list(message[start - 16:start])
        else:
            chain = list(iv)
        for i in range(16):
            plaintext[block * 16 + i] = state[i] ^ chain[i]
    return bytes(plaintext)


def encrypt_fairplay_message(mode, plaintext):
    if mode not in (0, 1, 2, 3):
        raise ValueError("unsupported FairPlay mode %d" % mode)
    if len(plaintext) != 128:
        raise ValueError("FairPlay plaintext must be 128 bytes")
    iv = MESSAGE_IV[mode * 16:(mode + 1) * 16]
    chain = list(iv)
    encrypted = bytearray(128)
    for block in range(8):
        start = block * 16
        state = [_b(plaintext[start + i] ^ chain[i]) for i in range(16)]
        _encrypt_message_block(state, mode)
        for i in range(16):
            encrypted[start + i] = state[i]
        chain = list(encrypted[start:start + 16])
    return bytes(encrypted)


# ---------------------------------------------------------------------------
# FPSAP records, descriptor, white-box exchange (fpsap.go)
# ---------------------------------------------------------------------------

def new_fpsap_record(type_, payload_len):
    rec = bytearray(12 + payload_len)
    rec[0] = 0x46
    rec[1] = 0x50
    rec[2] = 0x4C
    rec[3] = 0x59
    rec[4] = 3
    rec[5] = 1
    rec[6] = type_ & 0xFF
    rec[7] = 0
    rec[8] = (payload_len >> 24) & 0xFF
    rec[9] = (payload_len >> 16) & 0xFF
    rec[10] = (payload_len >> 8) & 0xFF
    rec[11] = payload_len & 0xFF
    return rec


def validate_fpsap_record(record, type_, payload_len):
    if len(record) != 12 + payload_len:
        raise ValueError(
            "FPSAP record length %d, want %d" % (len(record), 12 + payload_len)
        )
    if bytes(record[0:4]) != b"FPLY":
        raise ValueError("bad FPLY magic")
    if not (record[4] == 3 and record[5] == 1 and record[6] == (type_ & 0xFF) and record[7] == 0):
        raise ValueError("bad FPLY version/type")
    declared = (
        (record[8] << 24) | (record[9] << 16) | (record[10] << 8) | record[11]
    )
    if declared != payload_len:
        raise ValueError(
            "FPSAP declared payload %d, want %d" % (declared, payload_len)
        )


def decrypt_fpsap_body(mode, payload):
    if mode not in (0, 1, 2, 3):
        raise ValueError("unsupported FairPlay mode %d" % mode)
    if len(payload) != 128:
        raise ValueError("SAP payload must be 128 bytes")
    message = bytearray(144)
    message[12] = mode
    message[16:144] = payload
    return decrypt_fairplay_message(bytes(message))


def fpsap_descriptor_for_sap(m3sap, m2sap):
    if len(m3sap) != 128 or len(m2sap) != 128:
        raise ValueError("SAPs must be 128 bytes")
    padded = bytearray(320)
    off = 0
    padded[off:off + 17] = bytes(DESCRIPTOR_PREFIX)
    off += 17
    padded[off:off + 128] = m3sap
    off += 128
    padded[off:off + 128] = m2sap
    off += 128
    padded[off:off + 17] = bytes(DESCRIPTOR_SUFFIX)
    off += 17
    padded[off] = 0x80
    bit_len = off * 8
    for i in range(8):
        padded[312 + i] = (bit_len >> (8 * i)) & 0xFF

    state = fairplay_words_from_le(bytes(INITIAL_SESSION_KEY))
    first_final = [0] * 4
    for block_off in range(0, 320, 64):
        block = bytes(padded[block_off:block_off + 64])
        add = fairplay_sap_hash(block)
        for i in range(4):
            w = (
                add[i * 4]
                | (add[i * 4 + 1] << 8)
                | (add[i * 4 + 2] << 16)
                | (add[i * 4 + 3] << 24)
            )
            state[i] = _u32(state[i] + w)
        state = fairplay_md5_compress(state, block, MUTATION_CYCLE)
        if block_off == 320 - 64:
            first_final = list(state)
            state = fairplay_md5_compress(state, block, MUTATION_CYCLE)
    out = bytearray(20)
    out[0] = (first_final[0] >> 24) & 0xFF
    out[1] = (first_final[0] >> 16) & 0xFF
    out[2] = (first_final[0] >> 8) & 0xFF
    out[3] = first_final[0] & 0xFF
    out[4:20] = fairplay_words_to_be(state)
    return bytes(out)


def fpsap_masks(seed):
    if len(seed) != 20:
        raise ValueError("fpsap seed must be 20 bytes")
    state = [0x1D4A4587, 0x92F39FCC, 0x1D87D836, 0xCDC86697]
    suffix = bytes([
        0x57, 0xD8, 0xEE, 0xCB, 0xDE, 0xFB, 0xCF, 0x59, 0x1C, 0x27,
        0xA2, 0xCF, 0xBE, 0xB0, 0x89,
    ])
    masks = []
    for i in range(9):
        block = bytearray(64)
        block[0:20] = seed
        block[20] = i
        block[21:36] = suffix
        block[36] = 0x80
        block[56] = 0x20
        block[57] = 0x03
        block[58] = 0
        block[59] = 0
        masks.append(fairplay_words_to_be(
            fairplay_md5_compress(state, bytes(block), MUTATION_SWAP)
        ))
    return masks


def fpsap_digest32(left, right):
    if len(left) != 16 or len(right) != 16:
        raise ValueError("fpsapDigest32 inputs must be 16 bytes")
    block = bytearray(64)
    block[0:16] = left
    block[16:32] = right
    block[32] = 0x80
    block[56] = 0x00
    block[57] = 0x01
    block[58] = 0
    block[59] = 0
    state = [0xB9F3DCDC, 0xFBDC740B, 0x60F77F86, 0x51907216]
    return fairplay_words_to_be(
        fairplay_md5_compress(state, bytes(block), MUTATION_SWAP)
    )


def _lookup_substitute(table_idx, input_xor, output_xor, value):
    return SUBSTITUTION_BASES[table_idx * 256 + _b(value ^ input_xor)] ^ output_xor


def _lookup_mix(table_idx, input_xor, output_xor, value):
    return MIX_BASES[table_idx * 256 + _b(value ^ input_xor)] ^ output_xor


def _fpsap_mix(mix_cols, state, substituted):
    for word in range(4):
        off = word * 4
        for output_byte in range(4):
            mixed = 0
            for input_byte in range(4):
                idx = (input_byte * 4 + output_byte) * 3
                mixed = mixed ^ _lookup_mix(
                    mix_cols[idx], mix_cols[idx + 1], mix_cols[idx + 2],
                    substituted[off + input_byte],
                )
            state[off + output_byte] = mixed


def _round_substitute(round_sub, bank, index, value):
    idx = (bank * 16 + index) * 3
    return _lookup_substitute(
        round_sub[idx], round_sub[idx + 1], round_sub[idx + 2], value
    )


def _final_substitute(final_sub, index, value):
    idx = index * 3
    return _lookup_substitute(
        final_sub[idx], final_sub[idx + 1], final_sub[idx + 2], value
    )


def fpsap_first_network(masks):
    state = [_b(FIXED_BLOCK[i] ^ FIRST_INPUT_MASK[i]) for i in range(16)]
    for bank in range(9):
        substituted = [0] * 16
        for output in range(16):
            inp = FIRST_POSITION_MAP[output]
            substituted[output] = _round_substitute(
                FIRST_ROUND_SUB, bank, inp, state[inp]
            )
        _fpsap_mix(FIRST_MIX_COLS, state, substituted)
        for i in range(16):
            state[i] = state[i] ^ masks[bank][i]
    out = bytearray(16)
    for output in range(16):
        inp = FIRST_POSITION_MAP[output]
        out[output] = _final_substitute(FIRST_FINAL_SUB, inp, state[inp])
    return bytes(out)


def fpsap_second_network(state_in, masks):
    state = list(state_in)
    for bank in range(8, -1, -1):
        substituted = [0] * 16
        for output in range(16):
            inp = SECOND_POSITION_MAP[output]
            substituted[output] = (
                _round_substitute(SECOND_ROUND_SUB, bank, output, state[inp])
                ^ masks[bank][output]
            )
        _fpsap_mix(SECOND_MIX_COLS, state, substituted)
    out = bytearray(16)
    for output in range(16):
        inp = SECOND_POSITION_MAP[output]
        out[output] = _b(
            _final_substitute(SECOND_FINAL_SUB, output, state[inp])
            ^ SECOND_OUTPUT_MASK[output]
        )
    return bytes(out)


def fpsap_exchange_seed(seed):
    masks = fpsap_masks(seed)
    intermediate = fpsap_first_network(masks)
    left = fpsap_digest32(intermediate, bytes(FIXED_BLOCK))
    whitebox = fpsap_second_network(left, masks)
    digest = fpsap_digest32(left, whitebox)
    return whitebox[0:4] + digest


def fpsap_exchange_for_sap(m3sap, m2sap):
    return fpsap_exchange_seed(fpsap_descriptor_for_sap(m3sap, m2sap))


# ---------------------------------------------------------------------------
# Key wrapping (fairplay_crypto.go, fairplay.go)
# ---------------------------------------------------------------------------

def derive_wrapping_key(receiver_sap, m3):
    if len(receiver_sap) != 128:
        raise ValueError("receiver SAP must be 128 bytes")
    decrypted = decrypt_fairplay_message(m3)
    material = bytearray(320)
    off = 0
    material[off:off + 17] = bytes(KDF_PREFIX)
    off += 17
    material[off:off + 128] = decrypted
    off += 128
    material[off:off + 128] = receiver_sap
    off += 128
    material[off:off + 17] = bytes(KDF_SUFFIX)
    off += 17
    material[off] = 0x80
    bit_len = off * 8
    for i in range(8):
        material[312 + i] = (bit_len >> (8 * i)) & 0xFF

    state = fairplay_words_from_le(bytes(INITIAL_SESSION_KEY))
    for block_off in range(0, 320, 64):
        block = bytes(material[block_off:block_off + 64])
        modified = fairplay_md5_compress(state, block, MUTATION_KDF)
        hashed = fairplay_sap_hash(block)
        for word in range(4):
            w = (
                hashed[word * 4]
                | (hashed[word * 4 + 1] << 8)
                | (hashed[word * 4 + 2] << 16)
                | (hashed[word * 4 + 3] << 24)
            )
            state[word] = _u32(modified[word] + w)
    return fairplay_words_to_be(state)


def _aes_ecb_decrypt_block(key, block):
    cipher = Cipher(algorithms.AES(key), modes.ECB())
    dec = cipher.decryptor()
    return dec.update(block) + dec.finalize()


def _unwrap_ekey_raw(receiver_sap, m3, ekey):
    if len(ekey) != 72:
        raise ValueError("ekey must be 72 bytes")
    wrapping_key = derive_wrapping_key(receiver_sap, m3)
    masked = _aes_ecb_decrypt_block(wrapping_key, ekey[56:72])
    return bytes(_b(masked[i] ^ ekey[16 + i]) for i in range(16))


def unwrap_ekey(receiver_sap, m3, ekey):
    if len(ekey) != 72:
        raise ValueError("ekey must be 72 bytes")
    if bytes(ekey[0:4]) != b"FPLY":
        raise ValueError("bad ekey magic")
    if not (ekey[4] == 0x01 and ekey[5] == 0x02 and ekey[6] == 0x01 and ekey[7] == 0x00):
        raise ValueError("bad ekey header")
    if int.from_bytes(ekey[32:36], "big") != 16:
        raise ValueError("bad ekey key-size field, want BE32(16) at [32:36]")
    raw_key = _unwrap_ekey_raw(receiver_sap, m3, ekey)
    sender_sap = decrypt_fairplay_message(m3)
    descriptor = fpsap_descriptor_for_sap(sender_sap, receiver_sap)
    expected_mac = hmac.new(
        descriptor, bytes(ekey[0:36]) + raw_key, hashlib.sha1
    ).digest()
    if not hmac.compare_digest(expected_mac, bytes(ekey[36:56])):
        raise ValueError("ekey HMAC-SHA1 verification failed")
    return raw_key

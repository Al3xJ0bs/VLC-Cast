package com.alexey.aircast.test

import com.alexey.aircast.Opack
import java.util.UUID

// hex()/hex(b) helpers live in TestMain.kt (same package, same test.jar);
// do NOT redefine them here (overload ambiguity breaks the whole suite).

/**
 * OPACK unit tests — vectors copied from pyatv tests/support/test_opack.py
 * (0.18.0), plus the negative-int rule (pyatv bug: pack(-1) -> 0x07) and
 * the test_golden _systemInfo dictionary. Run: tests/run_tests.sh
 */
var opackFailures = 0
fun ocheck(name: String, cond: Boolean, extra: String = "") {
    if (cond) println("PASS $name") else { println("FAIL $name $extra"); opackFailures++ }
}

/** Deep equality where SizedInt(n, _) == Long n and ByteArray compares by content. */
fun odeepEq(a: Any?, b: Any?): Boolean {
    if (a is Opack.SizedInt && b is Number) return a.value == b.toLong()
    if (b is Opack.SizedInt && a is Number) return b.value == a.toLong()
    if (a is Opack.SizedInt && b is Opack.SizedInt) return a.value == b.value
    if (a is ByteArray && b is ByteArray) return a.contentEquals(b)
    if (a is List<*> && b is List<*>) {
        if (a.size != b.size) return false
        return a.indices.all { odeepEq(a[it], b[it]) }
    }
    if (a is Map<*, *> && b is Map<*, *>) {
        if (a.size != b.size) return false
        return a.entries.all { (k, v) -> b.containsKey(k) && odeepEq(v, b[k]) }
    }
    return a == b
}

private val PYATV_GOLDEN_HEX =
    "e5425f694b5f73797374656d496e666f425f78325164c04a455f6274485002425f63ed465f70756249445141413a42423a43" +
    "433a44443a45453a4646435f7376453233302e31435f626608495f73697269496e666fe358636f6c6c6563746f72456c6563" +
    "74696f6e56657273696f6e36000000000000f03f526465766963654361706162696c6974696573e24e7365796d6f7572456e" +
    "61626c65640953766f69636554726967676572456e61626c65640a527368617265644461746150726f746f42756692000208" +
    "0808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808" +
    "0808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808" +
    "0808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808" +
    "0808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808" +
    "0808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808" +
    "0808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808" +
    "0808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808" +
    "0808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808" +
    "0808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808" +
    "0808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808" +
    "0808080808080808080808445f737441d653636f6d2e6170706c652e4c697665417564696f55636f6d2e6170706c652e7369" +
    "72692e77616b65757051636f6d2e6170706c652e5365796d6f757252636f6d2e6170706c652e616e6e6f756e636557636f6d" +
    "2e6170706c652e636f7265647565742e73796e6358636f6d2e6170706c652e5365796d6f757253657373696f6ea04c366336" +
    "326663613138623131455f636c466c3080465f6964734944612434344531344142432d444444442d343138382d423636312d" +
    "313142414141463645434445465f686b554944d10517ed160a81f84488962c6b1a83eb0081435f64434131435f7366310001" +
    "456d6f64656c4a6950686f6e6531302c36446e616d65466950686f6e65425f740a"

fun main() {
    // ---- pack vectors ----
    ocheck("opack-pack-bool", hex(Opack.pack(true)) == "01" && hex(Opack.pack(false)) == "02")
    ocheck("opack-pack-none", hex(Opack.pack(null)) == "04")
    ocheck("opack-pack-uuid", hex(Opack.pack(UUID.fromString("12345678-1234-5678-1234-567812345678"))) == "0512345678123456781234567812345678")
    ocheck("opack-pack-small-int", hex(Opack.pack(0L)) == "08" && hex(Opack.pack(0xFL)) == "17" && hex(Opack.pack(0x27L)) == "2f")
    ocheck("opack-pack-larger-int", hex(Opack.pack(0x28L)) == "3028" && hex(Opack.pack(0x1FFL)) == "31ff01" &&
        hex(Opack.pack(0x1FFFFFFL)) == "32ffffff01" && hex(Opack.pack(0x1FFFFFFFFFFFFFFL)) == "33ffffffffffffff01")
    ocheck("opack-pack-sized-int", hex(Opack.pack(Opack.SizedInt(0x1, 1))) == "3001" &&
        hex(Opack.pack(Opack.SizedInt(0x1, 2))) == "310100" &&
        hex(Opack.pack(Opack.SizedInt(0x1, 4))) == "3201000000" &&
        hex(Opack.pack(Opack.SizedInt(0x1, 8))) == "330100000000000000")
    ocheck("opack-pack-float64", hex(Opack.pack(1.0)) == "36000000000000f03f")
    ocheck("opack-pack-short-string", hex(Opack.pack("a")) == "4161" && hex(Opack.pack("abc")) == "43616263" &&
        hex(Opack.pack("a".repeat(0x20))) == "60" + "61".repeat(0x20))
    ocheck("opack-pack-longer-string", hex(Opack.pack("a".repeat(33))) == "6121" + "61".repeat(33) &&
        hex(Opack.pack("a".repeat(256))) == "620001" + "61".repeat(256))
    ocheck("opack-pack-short-bytes", hex(Opack.pack(byteArrayOf(0xAC.toByte()))) == "71ac" &&
        hex(Opack.pack(byteArrayOf(0x12, 0x34, 0x56))) == "73123456" &&
        hex(Opack.pack(ByteArray(0x20) { 0xAD.toByte() })) == "90" + "ad".repeat(0x20))
    ocheck("opack-pack-longer-bytes", hex(Opack.pack(ByteArray(33) { 0x61 })) == "9121" + "61".repeat(33) &&
        hex(Opack.pack(ByteArray(256) { 0x61 })) == "920001" + "61".repeat(256) &&
        hex(Opack.pack(ByteArray(65536) { 0x61 })) == "9300000100" + "61".repeat(65536))
    ocheck("opack-pack-array", hex(Opack.pack(emptyList<Any?>())) == "d0" &&
        hex(Opack.pack(listOf<Any?>(1L, "test", false))) == "d309447465737402" &&
        hex(Opack.pack(listOf<Any?>(listOf<Any?>(true)))) == "d1d101")
    ocheck("opack-pack-endless-array", hex(Opack.pack(List(15) { "a" })) == "df4161" + "a0".repeat(14) + "03")
    ocheck("opack-pack-dict", hex(Opack.pack(emptyMap<Any?, Any?>())) == "e0" &&
        hex(Opack.pack(linkedMapOf<Any?, Any?>("a" to 12L, false to null))) == "e24161140204" &&
        hex(Opack.pack(linkedMapOf<Any?, Any?>(true to linkedMapOf<Any?, Any?>("a" to 2L)))) == "e101e141610a")
    ocheck("opack-pack-endless-dict", hex(Opack.pack(
        (97 until 127 step 2).associate { it.toChar().toString() to (it + 1).toChar().toString() }
    )) == "ef416141624163416441654166416741684169416a416b416c416d416e416f4170417141724173417441754176417741784179417a417b417c417d417e03")
    ocheck("opack-pack-ptr", hex(Opack.pack(listOf("a", "a"))) == "d24161a0" &&
        hex(Opack.pack(listOf("foo", "bar", "foo", "bar"))) == "d443666f6f43626172a0a1" &&
        hex(Opack.pack(linkedMapOf<Any?, Any?>("a" to "b", "c" to linkedMapOf<Any?, Any?>("d" to "a"), "d" to true))) == "e3416141624163e14164a0a301")

    // test_pack_more_ptr: 257 single-char byte strings (chr(x).encode(),
    // UTF-8 — codepoints >=128 are 2 bytes) packed twice. Byte-exact
    // literal lives in test_opack.py; here: round-trip + spot checks
    // (endless-list header, first refs, C1/C2 long refs, terminator).
    val moreData = (0 until 257).map { String(intArrayOf(it), 0, 1).toByteArray(Charsets.UTF_8) }
    val morePacked = Opack.pack(moreData + moreData)
    val moreUnpacked = Opack.unpack(morePacked).first
    ocheck("opack-pack-more-ptr-roundtrip", odeepEq(moreUnpacked, moreData + moreData),
        "unpacked size=${(moreUnpacked as? List<*>)?.size}")
    val moreHex = hex(morePacked)
    ocheck("opack-pack-more-ptr-shape", moreHex.startsWith("df71007101") && moreHex.endsWith("03") &&
        moreHex.contains("c121") && moreHex.contains("c20001"),
        "head=${moreHex.take(24)} tail=${moreHex.takeLast(8)} len=${morePacked.size}")

    // Negative ints: pyatv packs -1 as 0x07 (broken tag). Kotlin encodes
    // them as float64 instead — never repeat the pyatv branch.
    ocheck("opack-pack-negative-int-is-float64", hex(Opack.pack(-1L)) == "36" + "000000000000f0bf" &&
        hex(Opack.pack(-42L)) == hex(Opack.pack(-42.0)), "got ${hex(Opack.pack(-1L))}")
    ocheck("opack-pack-negative-roundtrip", odeepEq(Opack.unpack(Opack.pack(-1L)).first, -1.0))

    // ---- unpack vectors ----
    fun unpackEq(name: String, hexBytes: String, expected: Any?) {
        val (v, rest) = Opack.unpack(hex(hexBytes))
        ocheck(name, odeepEq(v, expected) && rest.isEmpty(), "got $v rest=${hex(rest)}")
    }
    unpackEq("opack-unpack-bool", "01", true)
    unpackEq("opack-unpack-bool-false", "02", false)
    unpackEq("opack-unpack-none", "04", null)
    unpackEq("opack-unpack-uuid", "0512345678123456781234567812345678", UUID.fromString("12345678-1234-5678-1234-567812345678"))
    unpackEq("opack-unpack-abs-time", "060100000000000000", 1L)
    unpackEq("opack-unpack-small-int", "08", 0L)
    unpackEq("opack-unpack-small-int-f", "17", 0xFL)
    unpackEq("opack-unpack-small-int-27", "2f", 0x27L)
    unpackEq("opack-unpack-larger-int", "3028", 0x28L)
    unpackEq("opack-unpack-larger-int-1ff", "31ff01", 0x1FFL)
    unpackEq("opack-unpack-larger-int-1ffffff", "32ffffff01", 0x1FFFFFFL)
    unpackEq("opack-unpack-larger-int-big", "33ffffffffffffff01", 0x1FFFFFFFFFFFFFFL)
    unpackEq("opack-unpack-float32", "350000803f", 1.0)
    unpackEq("opack-unpack-float64", "36000000000000f03f", 1.0)
    unpackEq("opack-unpack-short-string", "4161", "a")
    unpackEq("opack-unpack-short-string-abc", "43616263", "abc")
    unpackEq("opack-unpack-longer-string", "6121" + "61".repeat(33), "a".repeat(33))
    unpackEq("opack-unpack-short-bytes", "71ac", byteArrayOf(0xAC.toByte()))
    unpackEq("opack-unpack-short-bytes-3", "73123456", byteArrayOf(0x12, 0x34, 0x56))
    unpackEq("opack-unpack-longer-bytes", "9121" + "61".repeat(33), ByteArray(33) { 0x61 })
    unpackEq("opack-unpack-array", "d0", emptyList<Any?>())
    unpackEq("opack-unpack-array-3", "d309447465737402", listOf<Any?>(1L, "test", false))
    unpackEq("opack-unpack-endless-array", "df4161" + "a0".repeat(15) + "03", List(16) { "a" })
    unpackEq("opack-unpack-dict", "e0", emptyMap<Any?, Any?>())
    unpackEq("opack-unpack-dict-2", "e24161140204", linkedMapOf<Any?, Any?>("a" to 12L, false to null))
    unpackEq("opack-unpack-ptr", "d24161a0", listOf("a", "a"))
    unpackEq("opack-unpack-ptr-foo-bar", "d443666f6f43626172a0a1", listOf("foo", "bar", "foo", "bar"))
    unpackEq("opack-unpack-uid-c1", "df30013002c10103", listOf<Any?>(1L, 2L, 2L))
    unpackEq("opack-unpack-uid-c2", "df30013002c2010003", listOf<Any?>(1L, 2L, 2L))
    unpackEq("opack-unpack-uid-c3", "df30013002c301000003", listOf<Any?>(1L, 2L, 2L))
    unpackEq("opack-unpack-uid-c4", "df30013002c40100000003", listOf<Any?>(1L, 2L, 2L))

    // Sized ints keep their size on unpack (pyatv _sized_int).
    val (s1, _) = Opack.unpack(hex("3001"))
    val (s2, _) = Opack.unpack(hex("310100"))
    val (s4, _) = Opack.unpack(hex("3201000000"))
    val (s8, _) = Opack.unpack(hex("330100000000000000"))
    ocheck("opack-unpack-sized-int", (s1 as? Opack.SizedInt)?.size == 1 && (s2 as? Opack.SizedInt)?.size == 2 &&
        (s4 as? Opack.SizedInt)?.size == 4 && (s8 as? Opack.SizedInt)?.size == 8)

    // Unsupported tag must fail loudly, not silently produce garbage.
    var threw = false
    try { Opack.unpack(byteArrayOf(0x00)) } catch (e: Exception) { threw = true }
    ocheck("opack-unpack-unknown-tag-throws", threw)

    // ---- test_golden (test_opack.py): real _systemInfo dict ----
    val golden = linkedMapOf<Any?, Any?>(
        "_i" to "_systemInfo",
        "_x" to 1254122577L,
        "_btHP" to false,
        "_c" to linkedMapOf<Any?, Any?>(
            "_pubID" to "AA:BB:CC:DD:EE:FF",
            "_sv" to "230.1",
            "_bf" to 0L,
            "_siriInfo" to linkedMapOf<Any?, Any?>(
                "collectorElectionVersion" to 1.0,
                "deviceCapabilities" to linkedMapOf<Any?, Any?>("seymourEnabled" to 1L, "voiceTriggerEnabled" to 2L),
                "sharedDataProtoBuf" to ByteArray(512) { 0x08 },
            ),
            "_stA" to listOf("com.apple.LiveAudio", "com.apple.siri.wakeup", "com.apple.Seymour", "com.apple.announce", "com.apple.coreduet.sync", "com.apple.SeymourSession"),
            "_i" to "6c62fca18b11",
            "_clFl" to 128L,
            "_idsID" to "44E14ABC-DDDD-4188-B661-11BAAAF6ECDE",
            "_hkUID" to listOf(UUID.fromString("17ed160a-81f8-4488-962c-6b1a83eb0081")),
            "_dC" to "1",
            "_sf" to 256L,
            "model" to "iPhone10,6",
            "name" to "iPhone",
        ),
        "_t" to 2L,
    )
    val goldenPacked = Opack.pack(golden)
    val (goldenUnpacked, goldenRest) = Opack.unpack(goldenPacked)
    ocheck("opack-golden-roundtrip", odeepEq(goldenUnpacked, golden) && goldenRest.isEmpty(),
        "rest=${goldenRest.size} unpacked=$goldenUnpacked")
    // Cross-check against pyatv itself: the packed bytes must be
    // byte-identical to pyatv pack() of the same dict (983 bytes,
    // computed from research/pyatv, not hand-written).
    ocheck("opack-golden-bytes-pyatv", hex(goldenPacked) == PYATV_GOLDEN_HEX,
        "len=${goldenPacked.size} head=${hex(goldenPacked).take(48)}")

    // ---- generic round-trips ----
    // NOTE: the repeated values go FIRST, before any container — pyatv's
    // own pack/unpack tables skew after the first container (see the
    // header note in Opack.kt), so a repeat AFTER a container (e.g.
    // {"list": [...], "dup": ["same", "same", "same"]}) crashes pyatv
    // unpack on pyatv's own pack output. Real Companion payloads never
    // hit that pattern; the test pins the working order instead.
    val nested = linkedMapOf<Any?, Any?>(
        "dup" to listOf("same", "same", "same"),
        "list" to listOf<Any?>(1L, 2.5, "x", byteArrayOf(1, 2, 3), null, true),
        "empty" to linkedMapOf<Any?, Any?>(),
    )
    ocheck("opack-roundtrip-nested", odeepEq(Opack.unpack(Opack.pack(nested)).first, nested))
    // pyatv's skew, documented: pack([["a"], "b", "b"]) = d3 d1 41 61 41 62 a2
    // and pyatv unpack() raises IndexError on it. Our unpack mirrors
    // pyatv, so it must fail CLEANLY (OpackException), not crash.
    var skewThrewClean = false
    try { Opack.unpack(hex("d3d141614162a2")) } catch (e: Opack.OpackException) { skewThrewClean = true }
    ocheck("opack-pyatv-skew-clean-failure", skewThrewClean)
    ocheck("opack-roundtrip-launchapp", odeepEq(
        Opack.unpack(Opack.pack(linkedMapOf<Any?, Any?>(
            "_i" to "_launchApp", "_t" to 2L,
            "_c" to linkedMapOf<Any?, Any?>("_urlS" to "vlc-x-callback://x-callback-url/stream?url=https%3A%2F%2Fexample.com%2Fa.mp4"),
            "_x" to 12345L,
        ))).first,
        linkedMapOf<Any?, Any?>(
            "_i" to "_launchApp", "_t" to 2L,
            "_c" to linkedMapOf<Any?, Any?>("_urlS" to "vlc-x-callback://x-callback-url/stream?url=https%3A%2F%2Fexample.com%2Fa.mp4"),
            "_x" to 12345L,
        )))

    println(if (opackFailures == 0) "OPACK TESTS PASSED" else "$opackFailures OPACK TESTS FAILED")
    if (opackFailures > 0) kotlin.system.exitProcess(1)
}

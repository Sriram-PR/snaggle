package io.github.sriram_pr.snaggle.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtDlpSignatureTest {
    private fun res(name: String) = javaClass.getResource("/ytdlp-sig/$name")!!.readBytes()

    @Test fun embeddedKeyIsYtDlpsSigningKey() =
        assertEquals("AC0CBBE6848D6A873464AF4E57CF65933B5A7581", YtDlpSignature.keyFingerprint())

    @Test fun stableRelease() =
        assertTrue(YtDlpSignature.verify(res("stable-2026.08.19.sums"), res("stable-2026.08.19.sig")))

    @Test fun nightlyRelease() =
        assertTrue(YtDlpSignature.verify(res("nightly-2026.09.27.232945.sums"), res("nightly-2026.09.27.232945.sig")))

    @Test fun tamperedChecksums() {
        val sums = res("stable-2026.08.19.sums")
        sums[0] = if (sums[0] == 'a'.code.toByte()) 'b'.code.toByte() else 'a'.code.toByte()
        assertFalse(YtDlpSignature.verify(sums, res("stable-2026.08.19.sig")))
    }

    @Test fun signatureOfAnotherFile() =
        assertFalse(YtDlpSignature.verify(res("stable-2026.08.19.sums"), res("nightly-2026.09.27.232945.sig")))

    @Test fun garbageSignature() {
        assertFalse(YtDlpSignature.verify(res("stable-2026.08.19.sums"), ByteArray(0)))
        assertFalse(YtDlpSignature.verify(res("stable-2026.08.19.sums"), "not a signature".toByteArray()))
        assertFalse(YtDlpSignature.verify(res("stable-2026.08.19.sums"), res("stable-2026.08.19.sig").copyOf(100)))
    }
}

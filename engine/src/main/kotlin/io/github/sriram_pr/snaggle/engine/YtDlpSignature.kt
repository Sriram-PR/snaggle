package io.github.sriram_pr.snaggle.engine

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

/**
 * Verifies the detached OpenPGP signature yt-dlp publishes next to SHA2-256SUMS (stable and
 * nightly), against yt-dlp's release signing key (github.com/yt-dlp/yt-dlp/blob/master/public.key).
 * Only what yt-dlp uses is supported: a v4 RSA signature over a binary document (RFC 9580 §5.2).
 */
object YtDlpSignature {
    // Fingerprint AC0C BBE6 848D 6A87 3464 AF4E 57CF 6593 3B5A 7581 (checked by a unit test).
    private const val KEY_CREATED = 1677455406L
    private val MODULUS = BigInteger(
        "f4ac5f738c63c0b74b6196de42d5e6f371c0155fb35bd6ff9ea908e4f961197531135428375803399e002b947766fdf66acd480efa9018ac1eb418" +
            "a7a0edf7d40dff92d1714a11975e44b61e01aaaa2a14a26d56a50e3bce04ecedbb75bad3b1cb113400a80ef04cfb88b765aa9a92fad0d21ccabdbb" +
            "2f1aa681d37798dbd9da22cef9b3f49ca47f0be3e013d1f8cadcb00dad76cccc7a3e66429d538e3aee97644166754c71750f5f143a9df4b701298d" +
            "8b0ce712628d6c698d185a26561edcd5a19bb590ece39d2e458eaff204724b2260c7ee93711e6eecfcf9dababa66405b4316bba4b7eda58439e929" +
            "4986aff23fdc81ea1ff5fef673e84b8ba2a90e47a5b941f012bee167e96a2120ef92e7bee38f7d920f298c976bfd00ed0a2333080eac3f3ca334fe" +
            "54290a92427e8a35d9ee01cd8e670169474a2bb7c320439823adcbc9ce905a41904e28057df4945d61c308ef5a5cd84fdde8ea15680632d094b2b6" +
            "e4709916b9663bab9011cbfd391db48cdcae0353d7aab67c66895d5643c9d7b88f49809411be1e4f4e49f2e4bb71ab3726aaf58ef538c705baecd7" +
            "804f10ef5f717c4a2ff08505bcd70d2bef02002b76aaa53289dc43fca271395f2faab327c10a162a96d7840c4dd027e92bae653a1d5c24e2227c28" +
            "4fb5a38ee14f28a76f7c7d463f86e7a9dcd39f32f10920c812d7f27cfb085fef4623b26493fcd98d",
        16,
    )
    private val EXPONENT = BigInteger.valueOf(65537)

    private const val TAG_SIGNATURE = 2
    private const val SIG_BINARY = 0x00
    private const val ALGO_RSA = 1
    private val HASHES = mapOf(8 to "SHA256withRSA", 9 to "SHA384withRSA", 10 to "SHA512withRSA")

    /** True only for a valid signature of [data] by yt-dlp's key; malformed input is just false. */
    fun verify(data: ByteArray, signature: ByteArray): Boolean = runCatching {
        val body = signaturePacketBody(signature)
        require(body[0].u() == 4 && body[1].u() == SIG_BINARY && body[2].u() == ALGO_RSA)
        val algorithm = HASHES.getValue(body[3].u())
        val hashedEnd = 6 + body.u16(4)
        val unhashedEnd = hashedEnd + 2 + body.u16(hashedEnd)
        val mpiStart = unhashedEnd + 2 // after the 2-byte hash prefix
        val mpiBytes = (body.u16(mpiStart) + 7) / 8
        val value = body.copyOfRange(mpiStart + 2, mpiStart + 2 + mpiBytes)
        require(value.size == mpiBytes)
        // RSA verifiers want the signature as long as the modulus; the MPI drops leading zeros.
        val modulusBytes = (MODULUS.bitLength() + 7) / 8
        require(value.size <= modulusBytes)
        val padded = ByteArray(modulusBytes - value.size) + value

        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(MODULUS, EXPONENT))
        Signature.getInstance(algorithm).run {
            initVerify(key)
            update(data)
            update(body, 0, hashedEnd)
            update(byteArrayOf(4, 0xFF.toByte()) + hashedEnd.toBigEndian(4))
            verify(padded)
        }
    }.getOrDefault(false)

    /** V4 fingerprint of the embedded key, so a test can tie the constants to the published key. */
    fun keyFingerprint(): String {
        val body = byteArrayOf(4) + KEY_CREATED.toInt().toBigEndian(4) + byteArrayOf(ALGO_RSA.toByte()) + mpi(MODULUS) + mpi(EXPONENT)
        val digest = MessageDigest.getInstance("SHA-1").digest(byteArrayOf(0x99.toByte()) + body.size.toBigEndian(2) + body)
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun signaturePacketBody(packet: ByteArray): ByteArray {
        val ctb = packet[0].u()
        require(ctb and 0x80 != 0)
        val (tag, start, length) = if (ctb and 0x40 == 0) {
            val lengthBytes = when (ctb and 3) { 0 -> 1; 1 -> 2; 2 -> 4; else -> error("indeterminate length") }
            Triple((ctb shr 2) and 0xF, 1 + lengthBytes, packet.uN(1, lengthBytes))
        } else {
            val first = packet[1].u()
            when {
                first < 192 -> Triple(ctb and 0x3F, 2, first)
                first < 224 -> Triple(ctb and 0x3F, 3, ((first - 192) shl 8) + packet[2].u() + 192)
                first == 255 -> Triple(ctb and 0x3F, 6, packet.uN(2, 4))
                else -> error("partial length")
            }
        }
        require(tag == TAG_SIGNATURE && start + length <= packet.size)
        return packet.copyOfRange(start, start + length)
    }

    private fun mpi(n: BigInteger): ByteArray {
        val bytes = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return n.bitLength().toBigEndian(2) + bytes
    }

    private fun Byte.u() = toInt() and 0xFF
    private fun ByteArray.u16(at: Int) = uN(at, 2)
    private fun ByteArray.uN(at: Int, n: Int) = (0 until n).fold(0) { acc, i -> (acc shl 8) or this[at + i].u() }
    private fun Int.toBigEndian(n: Int) = ByteArray(n) { i -> (this shr (8 * (n - 1 - i))).toByte() }
}

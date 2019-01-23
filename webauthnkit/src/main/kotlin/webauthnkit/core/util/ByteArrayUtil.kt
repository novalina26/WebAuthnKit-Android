package webauthnkit.core.util

import android.util.Base64
import kotlinx.serialization.internal.HexConverter
import kotlinx.serialization.toUtf8Bytes
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.*

@ExperimentalUnsignedTypes
object ByteArrayUtil {

    fun sha256(str: String): ByteArray {
        return sha256(str.toUtf8Bytes())
    }

    fun sha256(bytes: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes)
    }

    fun encodeBase64URL(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun encodeBase64URL(bytes: UByteArray): String {
        return encodeBase64URL(bytes.toByteArray())
    }

    fun zeroUUIDBytes(): ByteArray {
        return byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    fun fromUUID(uuid: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        return buf.array()
    }

    fun merge(b1: UByteArray, b2: UByteArray): UByteArray {
        return merge(b1.toByteArray(), b2.toByteArray()).toUByteArray()
    }

    fun merge(b1: ByteArray, b2: ByteArray): ByteArray {
        val b1l = b1.size
        val b2l = b2.size
        val result = ByteArray(b1l + b2l)
        System.arraycopy(b1, 0, result, 0, b1l)
        System.arraycopy(b2, 0, result, b1l, b2l)
        return result
    }

    fun equals(b1: ByteArray, b2: ByteArray): Boolean {
        if (b1.size == b2.size) {
            for (i in 0..(b1.size-1)) {
                if (b1[i] != b2[i]) {
                   return false
                }
            }
            return true
        } else {
            return false
        }
    }

    fun fromHex(str: String): ByteArray {
        //return HexConverter.parseHexBinary(str)
        return ByteArray(str.length / 2) { str.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun toHex(bytes: ByteArray): String {
        //return HexConverter.printHexBinary(bytes, lowerCase = true)
        return buildString {
            for (b in bytes) {
                append(String.format("%02x", b))
            }
        }
    }


}
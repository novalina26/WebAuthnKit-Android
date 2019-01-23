package webauthnkit.core.util

import kotlinx.io.ByteArrayOutputStream
import kotlinx.serialization.toUtf8Bytes
import java.nio.ByteBuffer
import java.util.*
import kotlin.experimental.or

// TODO better performance

@ExperimentalUnsignedTypes
object CBORBits {

    val falseBits: UByte
        get() = 0xf4.toUByte()

    val trueBits: UByte
        get() = 0xf5.toUByte()

    val nullBits: UByte
        get() = 0xf6.toUByte()

    val headerPart: UByte
        get() = 0b11100000.toUByte()

    val valuePart: UByte
        get() = 0b00011111.toUByte()

    val stringHeader: UByte
        get() = 0b01100000.toUByte()

    val bytesHeader: UByte
        get() = 0b01000000.toUByte()

    val negativeHeader: UByte
        get() = 0b00100000.toUByte()

    val floatBits: UByte
        get() = 0xfa.toUByte()

    val doubleBits: UByte
        get() = 0xfb.toUByte()

    val arrayHeader: UByte
        get() = 0x80.toUByte()

    val mapHeader: UByte
        get() = 0xa0.toUByte()

    val indefiniteArrayBits: UByte
        get() = 0x9f.toUByte()

    val indefiniteMapBits: UByte
        get() = 0xbf.toUByte()

    val breakBits: UByte
        get() = 0xff.toUByte()

}


@ExperimentalUnsignedTypes
class CBORReader(private val bytes: UByteArray) {

    private val size = bytes.size
    private var cursor = 0

    companion object {
        private val TAG = this::class.simpleName
    }

    fun getReadSize(): Int {
        return cursor
    }

    fun getRestSize(): Int {
        return (size - cursor)
    }

    private fun nextByte(): UByte? {
        return if (this.cursor < this.size) {
            this.bytes[this.cursor]
        } else {
            WAKLogger.d(TAG, "no enough size")
            null
        }
    }

    private fun replaceNextByte(value: UByte) {
        this.bytes[this.cursor] = value
    }

    private fun readByte(): UByte? {

        return if (this.cursor < this.size) {

            val b = this.bytes[this.cursor]
            this.cursor = this.cursor + 1

            return b

        } else {
            WAKLogger.d(TAG, "no enough size")
            null
        }
    }

    private fun readBytes(size: Int): UByteArray? {

        return if ((this.cursor + size - 1) < this.size) {

            val b = Arrays.copyOfRange(this.bytes.toByteArray(),
                this.cursor, this.cursor + size)
            this.cursor = this.cursor + size

            return b.toUByteArray()

        } else {
            WAKLogger.d(TAG, "no enough size")
            null
        }
    }

    fun readFloat(): Float? {

        val b1 = this.readByte() ?: return null

        if (b1 != CBORBits.floatBits) {
            WAKLogger.d(TAG, "Invalid 'float' format")
            return null
        }

        val b2 = this.readBytes(4) ?: return null
        return ByteBuffer.wrap(b2.toByteArray()).float
    }

    fun readDouble(): Double? {

        val b1 = this.readByte() ?: return null

        if (b1 != CBORBits.doubleBits) {
            WAKLogger.d(TAG, "Invalid 'double' format")
            return null
        }

        val b2 = this.readBytes(8) ?: return null
        return ByteBuffer.wrap(b2.toByteArray()).double
    }

    fun readByteString(): UByteArray? {

        val b1 = this.nextByte() ?: return null

        if ((b1 and CBORBits.headerPart) != CBORBits.bytesHeader) {
            WAKLogger.d(TAG, "Invalid 'bytes' format")
            return null
        }

        this.replaceNextByte(b1 and CBORBits.valuePart)

        val len = this.readNumber()?.toInt() ?: return null
        if (len == 0) {
           return ubyteArrayOf()
        }

        return this.readBytes(len)
    }

    fun readString(): String? {

        val b1 = this.nextByte() ?: return null

        if ((b1 and CBORBits.headerPart) != CBORBits.stringHeader) {
            WAKLogger.d(TAG, "Invalid 'string' format")
            return null
        }

        this.replaceNextByte(b1 and CBORBits.valuePart)

        val len = this.readNumber()?.toInt() ?: return null
        if (len == 0) {
            return ""
        }

        val b2 = this.readBytes(len) ?: return null

        return String(
            bytes   = b2.toByteArray(),
            charset = Charsets.UTF_8
        )
    }

    fun readBool(): Boolean? {

        val b1 = readByte() ?: return false

        return when (b1) {
            CBORBits.falseBits -> false
            CBORBits.trueBits  -> true
            else               -> null
        }
    }

    fun readNull(): Boolean {
        val b1 = readByte() ?: return false
        return (b1 and CBORBits.nullBits) == CBORBits.nullBits
    }

    fun readAny(): Any? {

        val v1 = nextByte() ?: return null
        val v1i = v1.toInt()

        when {

            v1i in 0..27 -> {
                // positive number
                return this.readNumber()
            }

            v1i in 32..59 -> {
                // negative number
                return this.readNumber()
            }

            v1 == CBORBits.trueBits -> {
                return true
            }

            v1 == CBORBits.falseBits -> {
                return false
            }

            v1 == CBORBits.nullBits -> {
                // FIXME
                return true
            }

            v1 == CBORBits.floatBits -> {
                return this.readFloat()
            }

            v1 == CBORBits.doubleBits -> {
                return this.readDouble()
            }

            (v1 and CBORBits.headerPart) == CBORBits.stringHeader -> {
                return this.readString()
            }

            (v1 and CBORBits.headerPart) == CBORBits.bytesHeader -> {
                return this.readByteString()
            }

            (v1 and CBORBits.headerPart) == CBORBits.arrayHeader -> {
                return this.readArray()
            }

            (v1 and CBORBits.headerPart) == CBORBits.mapHeader -> {
                // currently, support nested-map only when its key is string
                return this.readStringKeyMap()
            }

            else -> {
                WAKLogger.d(TAG, "Unsupported value type for 'Any'")
                return null
            }
        }

    }

    fun readIntKeyMap(): Map<Int, Any>? {

        val b1 = nextByte() ?: return null

        if ((b1 and CBORBits.headerPart) != CBORBits.mapHeader) {
            WAKLogger.d(TAG, "Invalid 'map' format")
            return null
        }

        this.replaceNextByte(b1 and CBORBits.valuePart)

        val count = this.readNumber()?.toInt() ?: return null

        var results = mutableMapOf<Int, Any>()

        if (count == 0) {
            return results
        }

        val max = count - 1
        for (i in 0..max) {
            val key = this.readNumber()?.toInt() ?: return null
            val result = this.readAny() ?: return null
            results[key] = result
        }

        return results
    }

    fun readStringKeyMap(): Map<String, Any>? {

        val b1 = nextByte() ?: return null

        if ((b1 and CBORBits.headerPart) != CBORBits.mapHeader) {
            WAKLogger.d(TAG, "Invalid 'map' format")
            return null
        }

        this.replaceNextByte(b1 and CBORBits.valuePart)

        val count = this.readNumber()?.toInt() ?: return null

        var results = mutableMapOf<String, Any>()

        if (count == 0) {
            return results
        }

        val max = count - 1
        for (i in 0..max) {
            val key = this.readString() ?: return null
            val result = this.readAny() ?: return null
            results[key] = result
        }

        return results
    }

    fun readArray(): List<Any>? {

        val b1 = nextByte() ?: return null

        if ((b1 and CBORBits.headerPart) != CBORBits.arrayHeader) {
            WAKLogger.d(TAG, "Invalid 'array' format")
            return null
        }

        this.replaceNextByte(b1 and CBORBits.valuePart)

        val count = this.readNumber() ?: return null

        var results = mutableListOf<Any>()

        if (count == 0L) {
            return results
        }

        val max = count.toInt() - 1
        for (i in 0..max) {
            val result = this.readAny() ?: return null
            results.add(result)
        }

        return results
    }

    fun readNumber(): Long? {

        val b1 = this.readByte() ?: return null

        val value = (b1 and CBORBits.valuePart).toInt()

        val isNegative = (b1 and CBORBits.headerPart) == CBORBits.negativeHeader

        val bytesToRead = when (value) {
            in 0..23 -> 0
            24 -> 1
            25 -> 2
            26 -> 4
            27 -> 8
            else -> {
                WAKLogger.d(TAG, "Invalid 'number' format")
                return null
            }

        }

        if (bytesToRead == 0) {
            return if (isNegative) {
                ((value + 1) * -1).toLong()
            } else {
                value.toLong()
            }
        }

        val b2 = this.readBytes(bytesToRead) ?: return null

        val result = when (bytesToRead) {
            1 -> b2[0].toLong()
            2 -> ByteBuffer.wrap(b2.toByteArray()).short.toLong()
            4 -> ByteBuffer.wrap(b2.toByteArray()).int.toLong()
            8 -> ByteBuffer.wrap(b2.toByteArray()).long
            else -> {
                WAKLogger.d(TAG, "Invalid 'number' format")
                return null
            }
        }

        return if (isNegative) {
            -(result + 1)
        } else {
            result
        }

    }
}

@ExperimentalUnsignedTypes
class CBORWriter() {

    private var result = ByteArrayOutputStream()

    fun putArray(values: List<*>): CBORWriter {
        var bytes = this.composePositive(values.count().toLong())
        bytes[0] = bytes[0] or CBORBits.arrayHeader.toByte()
        this.result.write(bytes)
        values.forEach {
            when (it) {
                is Long -> {
                    this.putNumber(it)
                }
                is String -> {
                    this.putString(it)
                }
                is ByteArray -> {
                    this.putByteString(it)
                }
                is Float -> {
                    this.putFloat(it)
                }
                is Double -> {
                    this.putDouble(it)
                }
                is Boolean -> {
                    this.putBool(it)
                }
                else -> {
                    throw AssertionError("unsupported value type")
                }
            }
        }
        return this
    }

    fun putStringKeyMap(values: Map<String, Any>): CBORWriter {
        var bytes = this.composePositive(values.count().toLong())
        bytes[0] = bytes[0] or CBORBits.mapHeader.toByte()
        this.result.write(bytes)
        values.forEach {
            this.putString(it.key)
            val value = it.value
            when (value) {
                is Long -> {
                    this.putNumber(value)
                }
                is String -> {
                    this.putString(value)
                }
                is ByteArray -> {
                    this.putByteString(value)
                }
                is Float -> {
                    this.putFloat(value)
                }
                is Double -> {
                    this.putDouble(value)
                }
                is Boolean -> {
                    this.putBool(value)
                }
                is Map<*, *> -> {
                    // TODO check type
                    this.putStringKeyMap(value as Map<String, Any>)
                }
                is List<*> -> {
                    this.putArray(value)
                }
                else -> {
                    throw AssertionError("unsupported value type")
                }
            }
        }
        return this
    }

    // for COSE Key
    fun putIntKeyMap(values: Map<Int, Any>): CBORWriter {
        var bytes = this.composePositive(values.count().toLong())
        bytes[0] = bytes[0] or CBORBits.mapHeader.toByte()
        this.result.write(bytes)
        values.forEach {
            this.putNumber(it.key.toLong())
            val value = it.value
            when (value) {
                is Long -> {
                    this.putNumber(value)
                }
                is String -> {
                    this.putString(value)
                }
                is ByteArray -> {
                    this.putByteString(value)
                }
                is Float -> {
                    this.putFloat(value)
                }
                is Double -> {
                    this.putDouble(value)
                }
                is Boolean -> {
                    this.putBool(value)
                }
                else -> {
                    throw AssertionError("unsupported value type")
                }
            }
        }
        return this
    }

    fun startArray(): CBORWriter {
        this.result.write(CBORBits.indefiniteArrayBits.toInt())
        return this
    }

    fun startMap(): CBORWriter {
        this.result.write(CBORBits.indefiniteMapBits.toInt())
        return this
    }

    fun end(): CBORWriter {
        this.result.write(CBORBits.breakBits.toInt())
        return this
    }

    fun putNumber(value: Long): CBORWriter {
        this.result.write(this.composeNumber(value))
        return this
    }

    fun putString(value: String): CBORWriter {
        val data = value.toUtf8Bytes()
        val header = composeNumber(data.size.toLong())
        header[0] = header[0] or CBORBits.stringHeader.toByte()
        this.result.write(header)
        this.result.write(data)
        return this
    }

    fun putByteString(value: ByteArray): CBORWriter {
        val header = composeNumber(value.size.toLong())
        header[0] = header[0] or CBORBits.bytesHeader.toByte()
        this.result.write(header)
        this.result.write(value)
        return this
    }

    fun putFloat(value: Float): CBORWriter {
        val data = ByteBuffer.allocate(5)
            .put(CBORBits.floatBits.toByte())
            .putFloat(value)
            .array()
        this.result.write(data)
        return this
    }

    fun putDouble(value: Double): CBORWriter {
        val data = ByteBuffer.allocate(9)
            .put(CBORBits.doubleBits.toByte())
            .putDouble(value)
            .array()
        this.result.write(data)
        return this
    }

    fun putNull(): CBORWriter {
        this.result.write(CBORBits.nullBits.toInt())
        return this
    }

    fun putBool(value: Boolean): CBORWriter {
        if (value) {
            this.result.write(CBORBits.trueBits.toInt())
        } else {
            this.result.write(CBORBits.falseBits.toInt())
        }
        return this
    }

    private fun composeNumber(value: Long): ByteArray =
        if (value >= 0) composePositive(value) else composeNegative(value)

    private fun composeNegative(value: Long): ByteArray {
        val aVal = if (value == Long.MIN_VALUE) Long.MAX_VALUE else -1 - value
        val data = composePositive(aVal)
        data[0] = data[0] or CBORBits.negativeHeader.toByte()
        return data
    }

    private fun composePositive(value: Long): ByteArray =
        when (value) {
            in 0..23 -> byteArrayOf(value.toByte())
            in 24..Byte.MAX_VALUE -> byteArrayOf(24, value.toByte())
            in Byte.MAX_VALUE + 1..Short.MAX_VALUE -> {
                ByteBuffer.allocate(3).put(25.toByte()).putShort(value.toShort()).array()
            }
            in Short.MAX_VALUE + 1..Int.MAX_VALUE -> {
                ByteBuffer.allocate(5).put(26.toByte()).putInt(value.toInt()).array()
            }
            in (Int.MAX_VALUE.toLong() + 1..Long.MAX_VALUE) -> {
                ByteBuffer.allocate(9).put(27.toByte()).putLong(value).array()
            }
            else -> throw AssertionError("should be positive")
        }

    fun compute(): ByteArray {
        return result.toByteArray()
    }
}


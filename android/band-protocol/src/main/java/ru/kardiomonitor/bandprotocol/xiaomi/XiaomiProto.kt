package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Ограниченный proto2 subset оригинального локального core, без generated-зависимостей. */
internal class XiaomiProto private constructor(private val fields: Map<Int, Any>) : AutoCloseable {
    fun has(field: Int): Boolean = fields.containsKey(field)
    fun number(field: Int): Long = fields[field] as? Long ?: invalid()
    fun bytes(field: Int): ByteArray = fields[field] as? ByteArray ?: invalid()
    fun message(field: Int): XiaomiProto = parse(bytes(field))
    fun success() { if (has(100) && number(100) != 0L) invalid() }
    fun text(field: Int): String {
        val bytes = bytes(field)
        if (bytes.size !in 1..100) invalid()
        val result = bytes.toString(Charsets.UTF_8)
        if (!result.matches(Regex("[A-Za-z0-9 ._()/+-]{1,100}"))) invalid()
        return result
    }
    override fun close() { fields.values.forEach { wipe(it) } }
    override fun toString(): String = "XiaomiProto"
    private class Fixed(val value: ByteArray)

    companion object {
        private fun wipe(value: Any) {
            when (value) {
                is ByteArray -> value.fill(0)
                is Fixed -> value.value.fill(0)
            }
        }
        fun invalid(): Nothing = throw IOException("Некорректный ответ Xiaomi")
        fun parse(input: ByteArray): XiaomiProto {
            if (input.size > 4096) invalid()
            val fields = linkedMapOf<Int, Any>()
            val cursor = Cursor(input)
            try {
                while (cursor.position < input.size) {
                    val tag = cursor.varint()
                    val field = tag ushr 3
                    if (field !in 1..0x1fffffff || fields.size >= 64 || fields.containsKey(field.toInt())) invalid()
                    fields[field.toInt()] = when ((tag and 7).toInt()) {
                        0 -> cursor.varint()
                        2 -> cursor.bytes(cursor.varint())
                        1 -> Fixed(cursor.bytes(8))
                        5 -> Fixed(cursor.bytes(4))
                        else -> invalid()
                    }
                }
                return XiaomiProto(fields)
            } catch (error: IOException) {
                fields.values.forEach { wipe(it) }
                throw error
            }
        }
        fun command(type: Int, subtype: Int): ByteArray = Writer().use {
            it.number(1, type.toLong()).number(2, subtype.toLong()).build()
        }
    }

    private class Cursor(private val input: ByteArray) {
        var position: Int = 0
        fun varint(): Long {
            var value = 0L
            for (index in 0..9) {
                if (position == input.size) invalid()
                val byte = input[position++].toInt() and 255
                if (index == 9 && byte and 254 != 0) invalid()
                value = value or ((byte and 127).toLong() shl (7 * index))
                if (byte and 128 == 0) return value
            }
            invalid()
        }
        fun bytes(size: Long): ByteArray {
            if (size < 0 || size > input.size - position) invalid()
            val result = input.copyOfRange(position, position + size.toInt())
            position += size.toInt()
            return result
        }
    }

    class Writer : AutoCloseable {
        private class Buffer : ByteArrayOutputStream() {
            fun wipe() { buf.fill(0); reset() }
        }
        private val out = Buffer()
        private fun varint(input: Long) {
            var value = input
            do {
                val byte = (value and 127).toInt()
                value = value ushr 7
                out.write(if (value == 0L) byte else byte or 128)
            } while (value != 0L)
        }
        fun number(field: Int, value: Long): Writer {
            varint(field.toLong() shl 3); varint(value); return this
        }
        fun bytes(field: Int, value: ByteArray): Writer {
            varint((field.toLong() shl 3) or 2); varint(value.size.toLong()); out.write(value); return this
        }
        fun text(field: Int, value: String): Writer {
            val encoded = value.toByteArray(Charsets.UTF_8)
            try { return bytes(field, encoded) } finally { encoded.fill(0) }
        }
        fun float32(field: Int, value: Float): Writer {
            varint((field.toLong() shl 3) or 5)
            val bits = value.toRawBits()
            for (index in 0..3) out.write(bits ushr (8 * index))
            return this
        }
        fun build(): ByteArray = out.toByteArray()
        override fun close() { out.wipe() }
        override fun toString(): String = "XiaomiProto.Writer"
    }
}

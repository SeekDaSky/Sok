package Sok.Buffer

import kotlinx.cinterop.*

enum class ByteOrder {
    BIG_ENDIAN, LITTLE_ENDIAN;

    companion object {
        private val native: ByteOrder

        init {
            native = memScoped {
                val i = alloc<IntVar>()
                i.value = 1
                val bytes = i.reinterpret<ByteVar>()
                if (bytes.value == 0.toByte()) BIG_ENDIAN else LITTLE_ENDIAN
            }
        }

        fun nativeOrder(): ByteOrder = native
    }
}
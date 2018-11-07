package Sok.Buffer

import kotlinx.cinterop.*

/**
 * Class used to identify the Endianess and know if we need to change it when getting/setting values in a Buffer
 */
internal enum class ByteOrder {
    BIG_ENDIAN, LITTLE_ENDIAN;

    companion object {
        //Contains the native Endianess of the current platform.
        //Snippet copied froms the kotlinx-io project (https://github.com/Kotlin/kotlinx-io/tree/master/kotlinx-io-native/src/main/kotlin/kotlinx/io/core)
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
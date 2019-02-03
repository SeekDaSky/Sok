package Sok.Buffer

import Sok.Buffer.MultiplatformBuffer
import kotlinx.cinterop.*
import platform.posix.memcpy
import platform.posix.size_t
import kotlin.experimental.and
import Sok.Exceptions.BufferDestroyedException

/**
 * Native implementation of the `MultiplatformBuffer` class
 *
 * @property isBigEndian Endianness of the platform, used to knows if we should swap the endianness of the data or not
 * @property firstPointer Pointer to the first element of the buffer
 * @property pinned if the buffer is created with a ByteArray, we store the pin of this array to unpin it when destroy()
 * is called in order to avoid memory leaks
 */
class NativeMultiplatformBuffer : MultiplatformBuffer{

    private val isBigEndian = ByteOrder.BIG_ENDIAN === ByteOrder.nativeOrder()

    private val firstPointer : CPointer<ByteVar>

    private val pinned : Pinned<ByteArray>?

    /**
     * Allocate a new MultiplatformBuffer
     *
     * @param size size of the buffer
     */
    constructor(size : Int) : super(size){
        this.firstPointer = nativeHeap.allocArray<ByteVar>(size)
        this.pinned = null
    }

    /**
     * Create a new MultiplatformBuffer wrapping a byteArray , thus avoiding copy
     *
     * @param array array wrapped
     */
    constructor(array : ByteArray) : super(array.size){
        //pin the array in memory (to have a stable pointer)
        this.pinned = array.pin()
        this.firstPointer = pinned.addressOf(0)
    }

    /**
     * Get the byte at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the byte, buffer.cursor is used if the index is null
     * @return byte
     */
    override fun getByteImpl(index: Int?): Byte {
        return this.firstPointer[index?: this.cursor]
    }

    /**
     * Get an array of bytes of a given length starting at the current buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param length amount of data to get
     * @param index index of the first byte, buffer.cursor is used if the index is null
     *
     * @return data copied from the buffer
     */
    override fun getBytesImpl(length: Int, index: Int?): ByteArray {
        //when pinned, the address() method throws when size == 0
        if(length == 0) return ByteArray(0)

        val realIndex = index ?: this.cursor
        val arr = ByteArray(length)
        arr.usePinned{
            memcpy(it.addressOf(0),this.firstPointer+realIndex,length.toULong())
        }
        return arr
    }

    /**
     * Copy bytes into the array starting from the current cursor position or given index. You can start the copy with an offset in the
     * destination array and specify the number of byte you want to be copied.
     *
     * @param array destination array
     * @param index index of the first byte, buffer.cursor is used if the index is null
     * @param destinationOffset The offset within the array of the first byte to be written
     * @param length amount of data to copy
     */
    override fun getBytesImpl(array : ByteArray, index: Int?, destinationOffset : Int, length: Int){
        //when pinned, the address() method throws when size == 0
        if(length == 0 || array.size == 0) return

        val realIndex = index ?: this.cursor
        array.usePinned{
            memcpy(it.addressOf(0)+destinationOffset,this.firstPointer+realIndex,length.toULong())
        }
    }

    /**
     * Get the unsigned byte at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the byte, buffer.cursor is used if the index is null
     * @return byte
     */
    override fun getUByteImpl(index: Int?): UByte {
        return this.getByteImpl(index).toUByte()
    }

    /**
     * Get the short at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the short, buffer.cursor is used if the index is null
     * @return short
     */
    override fun getShortImpl(index: Int?): Short {
        var v = (this.firstPointer + (index ?: this.cursor))!!.reinterpret<ShortVar>()[0]
        if(!this.isBigEndian) v = this.swap(v)

        return v
    }

    /**
     * Get the unsigned short at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the short, buffer.cursor is used if the index is null
     * @return short
     */
    override fun getUShortImpl(index: Int?): UShort {
        return this.getShortImpl(index).toUShort()
    }

    /**
     * Get the integer at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the int, buffer.cursor is used if the index is null
     * @return int
     */
    override fun getIntImpl(index: Int?): Int {
        var v = (this.firstPointer + (index ?: this.cursor))!!.reinterpret<IntVar>()[0]
        if(!this.isBigEndian) v = this.swap(v)

        return v
    }

    /**
     * Get the unsigned integer at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the int, buffer.cursor is used if the index is null
     * @return int
     */
    override fun getUIntImpl(index: Int?): UInt {
        return this.getIntImpl(index).toUInt()
    }

    /**
     * Get the long at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the long, buffer.cursor is used if the index is null
     * @return int
     */
    override fun getLongImpl(index: Int?): Long {
        var v = (this.firstPointer + (index ?: this.cursor))!!.reinterpret<LongVar>()[0]
        if(!this.isBigEndian) v = this.swap(v)

        return v
    }

    /**
     * Get the unsigned long at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the long, buffer.cursor is used if the index is null
     * @return long
     */
    override fun getULongImpl(index: Int?): ULong {
        return this.getLongImpl(index).toULong()
    }

    /**
     * Put the given byte array inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param array data to put in the buffer
     * @param index index of the first byte, buffer.cursor is used if the index is null
     */
    override fun putBytesImpl(array: ByteArray, index: Int?) {
        //when pinned, the address() method throws when size == 0
        if(array.size == 0) return

        val realIndex = index ?: this.cursor
        array.usePinned {
            val address = it.addressOf(0)
            memcpy(this.firstPointer + realIndex, address, it.get().size.toULong())
        }
    }

    /**
     * Put the given byte inside the buffer at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value byte to put in the buffer
     * @param index index of the byte, buffer.cursor is used if the index is null
     */
    override fun putByteImpl(value: Byte, index: Int?) {
        this.firstPointer[index ?: this.cursor] = value
    }

    /**
     * Put the given short inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value short to put in the buffer
     * @param index index of the short, buffer.cursor is used if the index is null
     */
    override fun putShortImpl(value: Short, index: Int?) {
        //swap if the endianness is wrong
        var v = value
        if (!isBigEndian) v = swap(value)

        //interpret the index as a short and write
        (this.firstPointer + (index ?: this.cursor))!!.reinterpret<ShortVar>()[0] = v
    }

    /**
     * Put the given integer inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value int to put in the buffer
     * @param index index of the int, buffer.cursor is used if the index is null
     */
    override fun putIntImpl(value: Int, index: Int?) {
        //swap if the endianness is wrong
        var v = value
        if (!isBigEndian) v = swap(value)

        //interpret the index as a short and write
        (this.firstPointer + (index ?: this.cursor))!!.reinterpret<IntVar>()[0] = v
    }

    /**
     * Put the given long inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value long to put in the buffer
     * @param index index of the long, buffer.cursor is used if the index is null
     */
    override fun putLongImpl(value: Long, index: Int?) {
        //swap if the endianness is wrong
        var v = value
        if (!isBigEndian) v = swap(value)

        //interpret the index as a short and write
        (this.firstPointer + (index ?: this.cursor))!!.reinterpret<LongVar>()[0] = v
    }

    /**
     * Get all the data between the start of the buffer and its limit, the data is copied and is not linked to the content
     * of the buffer. WARNING this behaviour is different from the ByteBuffer array() method, please read the documentation
     * carefully
     *
     * @return data copied from the start fo the buffer to the limit
     */
    override fun toArray(): ByteArray {
        if(this.destroyed) throw BufferDestroyedException()
        val tmpArr = ByteArray(this.limit)
        tmpArr.usePinned{
            memcpy(it.addressOf(0),this.firstPointer,this.limit.toULong())
        }

        return tmpArr
    }

    /**
     * Deep copy the buffer. All the data from the start to the capacity will be copied, the cursor and limit will be reset
     *
     * @return cloned buffer
     */
    override fun clone(): MultiplatformBuffer {
        if(this.destroyed) throw BufferDestroyedException()
        val tmp = NativeMultiplatformBuffer(this.capacity)
        memcpy(tmp.firstPointer,this.firstPointer,this.capacity.toULong())
        tmp.limit = this.limit
        return tmp
    }

    /**
     * Used only by the JVM to synchronize the MultiplatformBuffer state with the ByteBuffer state
     *
     * @param index cursor
     */
    override fun setCursorImpl(index: Int) {
        //does nothing on Kotlin/Native
    }

    /**
     * Used only by the JVM to synchronize the MultiplatformBuffer state with the ByteBuffer state
     *
     * @param index limit
     */
    override fun setLimitImpl(index: Int) {
        //does nothing on Kotlin/Native
    }

    /**
     * Destroy the ByteBuffer, you cannot call any method on the buffer after calling destroy. This method MUST be called on native platforms
     * in order to free/unpin memory but you can skip it on any other platform
     */
    override fun destroy(){
        if(this.pinned == null){
            nativeHeap.free(this.firstPointer)
        }else{
            this.pinned.unpin()
        }
        this.destroyed = true
    }

    /**
     * Used internally to perform I/O on platform-specific sockets
     *
     * @return platform-specific Buffer
     */
    internal fun nativePointer() : CPointer<ByteVar>{
        if(this.destroyed) throw BufferDestroyedException()
        return this.firstPointer
    }

    /**
     * Endianess related private functions
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun swap(s: Short): Short = (((s.toInt() and 0xff) shl 8) or ((s.toInt() and 0xffff) ushr 8)).toShort()
    @Suppress("NOTHING_TO_INLINE")
    private inline fun swap(s: Int): Int = (swap((s and 0xffff).toShort()).toInt() shl 16) or (swap((s ushr 16).toShort()).toInt() and 0xffff)
    @Suppress("NOTHING_TO_INLINE")
    private inline fun swap(s: Long): Long = (swap((s and 0xffffffff).toInt()).toLong() shl 32) or (swap((s ushr 32).toInt()).toLong() and 0xffffffff)
    @Suppress("NOTHING_TO_INLINE")
    private inline fun swap(s: Float): Float = Float.fromBits(swap(s.toRawBits()))
    @Suppress("NOTHING_TO_INLINE")
    private inline fun swap(s: Double): Double = Double.fromBits(swap(s.toRawBits()))

}

/**
 * Allocate a new MultiplatformBuffer. Allocate a new MultiplatformBuffer. The buffer is not zeroed, be careful.
 *
 * @param size size of the buffer
 * @return allocated buffer
 */
actual fun allocMultiplatformBuffer(size :Int) : MultiplatformBuffer {
    return NativeMultiplatformBuffer(size)
}

/**
 * Wrap the array with a MultiplatformBuffer. The data will not be copied and the array will be linked to the
 * MultiplatformBuffer class
 *
 * @param array array to wrap
 * @return buffer
 */
actual fun wrapMultiplatformBuffer(array : ByteArray) : MultiplatformBuffer {
    return NativeMultiplatformBuffer(array)
}

package Sok.Buffer

import Sok.Buffer.MultiplatformBuffer
import kotlinx.cinterop.*
import platform.posix.memcpy
import platform.posix.size_t
import kotlin.experimental.and

class NativeMultiplatformBuffer : MultiplatformBuffer{

    //endianness of the platform
    private val isBigEndian = ByteOrder.BIG_ENDIAN === ByteOrder.nativeOrder()

    //pointer to the first item (C-like array)
    val firstPointer : CPointer<ByteVar>

    constructor(size : Int) : super(size){
        this.firstPointer = nativeHeap.allocArray<ByteVar>(size)
    }

    constructor(array : ByteArray) : super(array.size){
        //pin the array in memory (to have a stable pointer)
        val pinned = array.pin()
        this.firstPointer = pinned.addressOf(0)
    }


    override fun getByteImpl(index: Int?): Byte {
        return this.firstPointer[index?: this.cursor]
    }

    override fun getBytesImpl(length: Int, index: Int?): ByteArray {
        val realIndex = index ?: this.cursor
        val arr = ByteArray(length)
        arr.usePinned{
            memcpy(it.addressOf(0),this.firstPointer+realIndex,(this.limit-realIndex).signExtend<size_t>())
        }
        return arr
    }

    override fun getUByteImpl(index: Int?): Short {
        return (this.getByteImpl(index).toShort() and 0xFF)
    }

    override fun getShortImpl(index: Int?): Short {
        var v = (this.firstPointer + (index ?: this.cursor))!!.reinterpret<ShortVar>()[0]
        if(!this.isBigEndian) v = this.swap(v)

        return v
    }

    override fun getUShortImpl(index: Int?): Int {
        return (this.getShortImpl(index).toInt() and 0xFFFF)
    }

    override fun getIntImpl(index: Int?): Int {
        var v = (this.firstPointer + (index ?: this.cursor))!!.reinterpret<IntVar>()[0]
        if(!this.isBigEndian) v = this.swap(v)

        return v
    }

    override fun getUIntImpl(index: Int?): Long {
        return (this.getIntImpl(index).toLong() and 0xFFFFFFFF)
    }

    override fun getLongImpl(index: Int?): Long {
        var v = (this.firstPointer + (index ?: this.cursor))!!.reinterpret<LongVar>()[0]
        if(!this.isBigEndian) v = this.swap(v)

        return v
    }

    override fun putBytesImpl(array: ByteArray, index: Int?) {
        val realIndex = index ?: this.cursor
        array.usePinned {
            val address = it.addressOf(0)
            memcpy(this.firstPointer + realIndex, address, it.get().size.signExtend<size_t>())
        }
    }

    override fun putByteImpl(value: Byte, index: Int?) {
        this.firstPointer[index ?: this.cursor] = value
    }

    override fun putShortImpl(value: Short, index: Int?) {
        //swap if the endianness is wrong
        var v = value
        if (!isBigEndian) v = swap(value)

        //interpret the index as a short and write
        (this.firstPointer + (index ?: this.cursor))!!.reinterpret<ShortVar>()[0] = v
    }

    override fun putIntImpl(value: Int, index: Int?) {
        //swap if the endianness is wrong
        var v = value
        if (!isBigEndian) v = swap(value)

        //interpret the index as a short and write
        (this.firstPointer + (index ?: this.cursor))!!.reinterpret<IntVar>()[0] = v
    }

    override fun putLongImpl(value: Long, index: Int?) {
        //swap if the endianness is wrong
        var v = value
        if (!isBigEndian) v = swap(value)

        //interpret the index as a short and write
        (this.firstPointer + (index ?: this.cursor))!!.reinterpret<LongVar>()[0] = v
    }

    /*

    override fun getUByte(): Short {
        this.checkAccess(this.cursor,1)

        val v = this.firstPointer[this.cursor++]

        //in order to have the same behavior across all MultiplatformBuffer implementation we have to copy the read value
        val allocated = nativeHeap.allocArray<ByteVar>(2)
        memcpy(allocated, cValuesOf(v),2)
        return allocated.reinterpret<ShortVar>()[0]
    }

    override fun getUShort(): Int {
        this.checkAccess(this.cursor,2)

        var v = (this.firstPointer + this.cursor)!!.reinterpret<ShortVar>()[0]
        if(!this.isBigEndian) v = this.swap(v)

        this.cursor += 2

        //in order to have the same behavior across all MultiplatformBuffer implementation we have to copy the read value
        val allocated = nativeHeap.allocArray<ShortVar>(2)
        memcpy(allocated, cValuesOf(v),2)
        val int = allocated.reinterpret<IntVar>()[0]

        return int

    }

    override fun getUInt(): Long {
        this.checkAccess(this.cursor,4)

        var v = (this.firstPointer + this.cursor)!!.reinterpret<IntVar>()[0]
        if(!this.isBigEndian) v = this.swap(v)

        this.cursor += 4

        //in order to have the same behavior across all MultiplatformBuffer implementation we have to copy the read value
        val allocated = nativeHeap.allocArray<IntVar>(2)
        memcpy(allocated, cValuesOf(v),4)
        val int = allocated.reinterpret<LongVar>()[0]

        return int
    }

  */

    override fun toArray(): ByteArray {
        val tmpArr = ByteArray(this.limit)
        tmpArr.usePinned{
            memcpy(it.addressOf(0),this.firstPointer,this.limit.signExtend<size_t>())
        }

        return tmpArr
    }

    override fun clone(): MultiplatformBuffer {
        val tmp = NativeMultiplatformBuffer(this.capacity)
        memcpy(tmp.firstPointer,this.firstPointer,this.capacity.signExtend<size_t>())
        tmp.limit = this.limit
        return tmp
    }

    fun nativePointer() : CPointer<ByteVar>{
        return this.firstPointer
    }

    override fun setCursorImpl(index: Int) {
        //does nothing on Kotlin/Native
    }

    override fun setLimitImpl(index: Int) {
        //does nothing on Kotlin/Native
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
    private inline fun swap(s: Float): Float = Float.fromBits(swap(s.bits()))
    @Suppress("NOTHING_TO_INLINE")
    private inline fun swap(s: Double): Double = Double.fromBits(swap(s.bits()))

}

actual fun allocMultiplatformBuffer(size :Int) : MultiplatformBuffer {
    return NativeMultiplatformBuffer(size)
}

actual fun wrapMultiplatformBuffer(array : ByteArray) : MultiplatformBuffer {
    return NativeMultiplatformBuffer(array)
}

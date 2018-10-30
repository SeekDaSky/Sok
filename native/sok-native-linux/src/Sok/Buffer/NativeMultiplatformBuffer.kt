package Sok.Buffer

import Sok.Buffer.MultiplatformBuffer
import kotlinx.cinterop.*
import platform.posix.memcpy
import platform.posix.size_t
import kotlin.experimental.and
import Sok.Exceptions.BufferDestroyedException

/**
 * MultiplatformBuffer class of the Native platform. As Kotlin/Native does not include any kind of ByteBuffer (as JVM or Node.JS) we have
 * to implement one ourself. I use a ByteArray, which is mapped on a native array of byte after compilation (great for performances). We detect
 * the Endianess of the platform and swap it if needed (as the network is big Endian).
 */
class NativeMultiplatformBuffer : MultiplatformBuffer{

    //endianness of the platform
    private val isBigEndian = ByteOrder.BIG_ENDIAN === ByteOrder.nativeOrder()

    //pointer to the first item (C-like array)
    val firstPointer : CPointer<ByteVar>

    //pinned object (that should be fr
    val pinned : Pinned<ByteArray>?

    constructor(size : Int) : super(size){
        this.firstPointer = nativeHeap.allocArray<ByteVar>(size)
        this.pinned = null
    }

    constructor(array : ByteArray) : super(array.size){
        //pin the array in memory (to have a stable pointer)
        this.pinned = array.pin()
        this.firstPointer = pinned.addressOf(0)
    }


    override fun getByteImpl(index: Int?): Byte {
        return this.firstPointer[index?: this.cursor]
    }

    override fun getBytesImpl(length: Int, index: Int?): ByteArray {
        val realIndex = index ?: this.cursor
        val arr = ByteArray(length)
        arr.usePinned{
            memcpy(it.addressOf(0),this.firstPointer+realIndex,(this.limit-realIndex).toULong())
        }
        return arr
    }

    override fun getUByteImpl(index: Int?): UByte {
        return this.getByteImpl(index).toUByte()
    }

    override fun getShortImpl(index: Int?): Short {
        var v = (this.firstPointer + (index ?: this.cursor))!!.reinterpret<ShortVar>()[0]
        if(!this.isBigEndian) v = this.swap(v)

        return v
    }

    override fun getUShortImpl(index: Int?): UShort {
        return this.getShortImpl(index).toUShort()
    }

    override fun getIntImpl(index: Int?): Int {
        var v = (this.firstPointer + (index ?: this.cursor))!!.reinterpret<IntVar>()[0]
        if(!this.isBigEndian) v = this.swap(v)

        return v
    }

    override fun getUIntImpl(index: Int?): UInt {
        return this.getIntImpl(index).toUInt()
    }

    override fun getLongImpl(index: Int?): Long {
        var v = (this.firstPointer + (index ?: this.cursor))!!.reinterpret<LongVar>()[0]
        if(!this.isBigEndian) v = this.swap(v)

        return v
    }

    override fun getULongImpl(index: Int?): ULong {
        return this.getLongImpl(index).toULong()
    }

    override fun putBytesImpl(array: ByteArray, index: Int?) {
        val realIndex = index ?: this.cursor
        array.usePinned {
            val address = it.addressOf(0)
            memcpy(this.firstPointer + realIndex, address, it.get().size.toULong())
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

    override fun toArray(): ByteArray {
        if(this.destroyed) throw BufferDestroyedException()
        val tmpArr = ByteArray(this.limit)
        tmpArr.usePinned{
            memcpy(it.addressOf(0),this.firstPointer,this.limit.toULong())
        }

        return tmpArr
    }

    override fun clone(): MultiplatformBuffer {
        if(this.destroyed) throw BufferDestroyedException()
        val tmp = NativeMultiplatformBuffer(this.capacity)
        memcpy(tmp.firstPointer,this.firstPointer,this.capacity.toULong())
        tmp.limit = this.limit
        return tmp
    }

    fun nativePointer() : CPointer<ByteVar>{
        if(this.destroyed) throw BufferDestroyedException()
        return this.firstPointer
    }

    override fun destroy(){
        if(this.pinned == null){
            nativeHeap.free(this.firstPointer)
        }else{
            this.pinned.unpin()
        }
        this.destroyed = true
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
    private inline fun swap(s: Float): Float = Float.fromBits(swap(s.toRawBits()))
    @Suppress("NOTHING_TO_INLINE")
    private inline fun swap(s: Double): Double = Double.fromBits(swap(s.toRawBits()))

}

actual fun allocMultiplatformBuffer(size :Int) : MultiplatformBuffer {
    return NativeMultiplatformBuffer(size)
}

actual fun wrapMultiplatformBuffer(array : ByteArray) : MultiplatformBuffer {
    return NativeMultiplatformBuffer(array)
}

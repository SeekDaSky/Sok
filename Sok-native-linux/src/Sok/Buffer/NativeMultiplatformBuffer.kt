package Sok.Buffer

import Sok.Buffer.MultiplateformBuffer
import kotlinx.cinterop.*
import platform.posix.memcpy
import platform.posix.size_t

class NativeMultiplatformBuffer : MultiplateformBuffer{

    //endianness of the platform
    private val isBigEndian = ByteOrder.BIG_ENDIAN === ByteOrder.nativeOrder()

    //pointer to the first item (C-like array)
    val firstPointer : CPointer<ByteVar>
    val capacity : Int
    var limit : Int
    var cursor = 0

    constructor(size : Int){
        this.firstPointer = nativeHeap.allocArray<ByteVar>(size)
        this.capacity = size
        this.limit = size
    }

    constructor(array : ByteArray){
        //pin the array in memory (to have a stable pointer)
        val pinned = array.pin()
        this.firstPointer = pinned.addressOf(0)
        this.capacity = array.size
        this.limit = array.size
    }

    override fun get(index:Int): Byte {
        this.checkAccess(index,1)
        return this.firstPointer[index]
    }

    override fun getByte(): Byte {
        this.checkAccess(this.cursor,1)
        return this.firstPointer[this.cursor++]
    }

    override fun getBytes(length : Int) : ByteArray{
        this.checkAccess(this.cursor,length)

        val arr = ByteArray(length)
        arr.usePinned{
            memcpy(it.addressOf(0),this.firstPointer+this.cursor,(this.limit-this.cursor).signExtend<size_t>())
        }

        this.cursor += this.limit-this.cursor
        return arr
    }

    override fun getBytes(offset :Int, length : Int) : ByteArray{
        this.checkAccess(offset,length)

        val arr = ByteArray(length)
        arr.usePinned{
            memcpy(it.addressOf(0),this.firstPointer+offset,(this.limit-offset).signExtend<size_t>())
        }

        return arr
    }

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

    override fun getShort(): Short {
        this.checkAccess(this.cursor,2)

        var v = (this.firstPointer + this.cursor)!!.reinterpret<ShortVar>()[0]
        this.cursor += 2
        if(!this.isBigEndian) v = this.swap(v)

        return v
    }

    override fun getInt(): Int {
        this.checkAccess(this.cursor,4)

        var v = (this.firstPointer + this.cursor)!!.reinterpret<IntVar>()[0]
        this.cursor += 4
        if(!this.isBigEndian) v = this.swap(v)

        return v
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

    override fun getLong(): Long {
        this.checkAccess(this.cursor,8)

        var v = (this.firstPointer + this.cursor)!!.reinterpret<LongVar>()[0]
        this.cursor += 8
        if(!this.isBigEndian) v = this.swap(v)

        return v
    }

    override fun putBytes(arr : ByteArray){
        this.checkAccess(this.cursor,arr.size)

        arr.usePinned {
            val address = it.addressOf(0)
            memcpy(this.firstPointer + this.cursor, address, it.get().size.signExtend<size_t>())
        }

        this.cursor += arr.size
    }

    override fun putByte(value: Byte) {
        this.checkAccess(this.cursor,1)
        this.firstPointer[this.cursor++] = value
    }

    override fun putShort(value: Short) {
        this.checkAccess(this.cursor,2)

        //swap if the endianness is wrong
        var v = value
        if (!isBigEndian) v = swap(value)

        //interpret the index as a short and write
        (this.firstPointer + this.cursor)!!.reinterpret<ShortVar>()[0] = v
        this.cursor += 2
    }

    override fun putInt(value: Int) {
        this.checkAccess(this.cursor,4)

        //swap if the endianness is wrong
        var v = value
        if (!isBigEndian) v = swap(value)

        //interpret the index as a short and write
        (this.firstPointer + this.cursor)!!.reinterpret<IntVar>()[0] = v
        this.cursor += 4
    }

    override fun putLong(value:Long) {
        this.checkAccess(this.cursor,8)

        //swap if the endianness is wrong
        var v = value
        if (!isBigEndian) v = swap(value)

        //interpret the index as a short and write
        (this.firstPointer + this.cursor)!!.reinterpret<LongVar>()[0] = v
        this.cursor += 8
    }

    override fun toArray(): ByteArray {
        val tmpArr = ByteArray(this.limit)
        tmpArr.usePinned{
            memcpy(it.addressOf(0),this.firstPointer,this.limit.signExtend<size_t>())
        }

        return tmpArr
    }

    override fun size():Int {
        return this.limit
    }

    override fun setCursor(index:Int) {
        require(index <= this.limit)
        this.cursor = index
    }

    override fun getCursor() :Int {
        return this.cursor
    }

    override fun clone(): MultiplateformBuffer {
        val tmp = NativeMultiplatformBuffer(this.capacity)
        memcpy(tmp.firstPointer,this.firstPointer,this.capacity.signExtend<size_t>())
        tmp.limit = this.limit
        return tmp
    }

    override fun reset() {
        this.cursor = 0
    }

    override fun remaining(): Int {
        return this.limit - this.cursor
    }

    private fun checkAccess(index : Int, toGet : Int){
        if(index + toGet > this.limit) throw Exception("Incorrect index")
    }

    fun capacity() : Int{
        return this.capacity
    }

    fun setLimit(limit : Int){
        this.limit = limit
    }

    fun nativePointer() : CPointer<ByteVar>{
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
    private inline fun swap(s: Float): Float = Float.fromBits(swap(s.bits()))
    @Suppress("NOTHING_TO_INLINE")
    private inline fun swap(s: Double): Double = Double.fromBits(swap(s.bits()))

}

actual fun allocMultiplateformBuffer(size :Int) : MultiplateformBuffer {
    return NativeMultiplatformBuffer(size)
}

actual fun wrapMultiplateformBuffer(array : ByteArray) : MultiplateformBuffer {
    return NativeMultiplatformBuffer(array)
}

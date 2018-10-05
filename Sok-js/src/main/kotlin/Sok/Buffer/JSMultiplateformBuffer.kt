package Sok.Buffer

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

class JSMultiplatformBuffer : MultiplatformBuffer{

    private var backBuffer : Buffer

    constructor(size : Int) : super(size){
        this.backBuffer = Buffer.alloc(size)
        this.limit = size
    }

    constructor(array : ByteArray) : super(array.size){
        this.backBuffer = Buffer.from(array.toTypedArray())
        this.limit = array.size
    }

    override fun getByteImpl(index: Int?): Byte {
        return this.backBuffer.readInt8(index ?: this.cursor)
    }

    override fun getBytesImpl(length: Int, index: Int?): ByteArray {
        val realIndex = index ?: this.cursor
        val tmpArr = Uint8Array(length)
        this.backBuffer.copy(tmpArr,0,realIndex,realIndex+length)
        return Int8Array(tmpArr.buffer).unsafeCast<ByteArray>()
    }

    override fun getUByteImpl(index: Int?): Short {
        return this.backBuffer.readUInt8(index ?: this.cursor)
    }

    override fun getShortImpl(index: Int?): Short {
        return this.backBuffer.readInt16BE(index ?: this.cursor)
    }

    override fun getUShortImpl(index: Int?): Int {
        return this.backBuffer.readUInt16BE(index ?: this.cursor)
    }

    override fun getIntImpl(index: Int?): Int {
        return this.backBuffer.readInt32BE(index ?: this.cursor)
    }

    override fun getUIntImpl(index: Int?): Long {
        return this.backBuffer.readUInt32BE(index ?: this.cursor)
    }

    override fun getLongImpl(index: Int?): Long {
        //as the native buffer dosen't have a method to retreive Longs, we have to trick with two ints
        val int1 = this.backBuffer.readInt32BE(index ?: this.cursor)
        val int2 = this.backBuffer.readInt32BE(index?.plus(4) ?: this.cursor+4)

        //let's assume Kotlin wrap its Longs and bypass the JS limit of 53 bits
        return int2 + (int1.toLong().shl(32))
    }

    override fun putBytesImpl(array: ByteArray, index: Int?) {
        //seriously node, why can't you copy a bunch of data inside a buffer?
        array.forEachIndexed {i,byte ->
            this.putByteImpl(byte,index?.plus(i) ?: this.cursor+i)
        }
    }

    override fun putByteImpl(value: Byte, index: Int?) {
        this.backBuffer.writeInt8(value,index ?: this.cursor)
    }

    override fun putShortImpl(value: Short, index: Int?) {
        this.backBuffer.writeInt16BE(value,index ?: this.cursor)
    }

    override fun putIntImpl(value: Int, index: Int?) {
        this.backBuffer.writeInt32BE(value,index ?: this.cursor)
    }

    override fun putLongImpl(value: Long, index: Int?) {
        //erase the first 32 bits
        val int1 = (value.shl(32).shr(32)).toInt()
        //keep only the last 32 bits
        val int2 = (value.shr(32)).toInt()

        this.backBuffer.writeInt32BE(int2,index ?: this.cursor)
        this.backBuffer.writeInt32BE(int1,index?.plus(4) ?: this.cursor+4)
    }

    override fun setCursorImpl(index: Int) {
        //does nothing on Node.js
    }

    override fun setLimitImpl(index: Int) {
        //does nothing on Node.js
    }

    override fun toArray(): ByteArray {
        val tmp = Uint8Array(this.limit)
        this.backBuffer.copy(tmp,0,0,this.limit)

        return Int8Array(tmp.buffer).unsafeCast<ByteArray>()
    }

    override fun clone(): MultiplatformBuffer {
        val tmp = JSMultiplatformBuffer(this.capacity)
        tmp.backBuffer = Buffer.from(this.toArray().toTypedArray())

        return tmp
    }

    fun nativeBuffer() : Buffer {
        return this.backBuffer
    }
}

actual fun allocMultiplatformBuffer(size : Int) : MultiplatformBuffer {
    return JSMultiplatformBuffer(size)
}

actual fun wrapMultiplatformBuffer(array : ByteArray) : MultiplatformBuffer {
    return JSMultiplatformBuffer(array)
}
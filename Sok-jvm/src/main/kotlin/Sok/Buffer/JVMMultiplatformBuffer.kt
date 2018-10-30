package Sok.Buffer

import Sok.Exceptions.BufferDestroyedException
import java.nio.ByteBuffer

class JVMMultiplatformBuffer : MultiplatformBuffer {

    private val backBuffer : ByteBuffer

    constructor(size : Int) : super(size){
        this.backBuffer = ByteBuffer.allocate(size)
    }

    constructor(array : ByteArray) : super(array.size){
        this.backBuffer = ByteBuffer.wrap(array)
    }

    constructor(array : ByteBuffer) : super(array.capacity()){
        this.backBuffer = array
    }

    override fun getByteImpl(index: Int?): Byte {
        return this.backBuffer.get(index ?: this.cursor)
    }

    override fun getBytesImpl(length: Int, index: Int?): ByteArray {
        val array = ByteArray(length)
        this.backBuffer.position(index ?: this.cursor)
        this.backBuffer.get(array)
        return array
    }

    override fun getUByteImpl(index: Int?): UByte {
        return this.backBuffer.get(index ?: this.cursor).toUByte()
    }

    override fun getShortImpl(index: Int?): Short {
        return this.backBuffer.getShort(index ?: this.cursor)
    }

    override fun getUShortImpl(index: Int?): UShort {
        return this.backBuffer.getShort(index ?: this.cursor).toUShort()
    }

    override fun getIntImpl(index: Int?): Int {
        return this.backBuffer.getInt(index ?: this.cursor)
    }

    override fun getUIntImpl(index: Int?): UInt {
        return this.backBuffer.getInt(index ?: this.cursor).toUInt()
    }

    override fun getLongImpl(index: Int?): Long {
        return this.backBuffer.getLong(index ?: this.cursor)
    }

    override fun getULongImpl(index: Int?): ULong {
        return this.backBuffer.getLong(index ?: this.cursor).toULong()
    }

    override fun putBytesImpl(array: ByteArray, index: Int?) {
        this.backBuffer.position(index ?: this.cursor)
        this.backBuffer.put(array)
    }

    override fun putByteImpl(value: Byte, index: Int?) {
        this.backBuffer.put(index ?: this.cursor,value)
    }

    override fun putShortImpl(value: Short, index: Int?) {
        this.backBuffer.putShort(index ?: this.cursor,value)
    }

    override fun putIntImpl(value: Int, index: Int?) {
        this.backBuffer.putInt(index ?: this.cursor,value)
    }

    override fun putLongImpl(value: Long, index: Int?) {
        this.backBuffer.putLong(index ?: this.cursor,value)
    }

    override fun toArray(): ByteArray {
        if(this.destroyed) throw BufferDestroyedException()
        if(this.backBuffer.capacity() != this.backBuffer.limit() || !this.backBuffer.hasArray()){
            val tmp = ByteArray(this.limit)
            //backup cursor
            val cursor = this.backBuffer.position()

            this.backBuffer.position(0)
            this.backBuffer.get(tmp)

            this.backBuffer.position(cursor)
            return tmp
        }else{
            return this.backBuffer.array().clone()
        }
    }

    override fun clone(): MultiplatformBuffer {
        if(this.destroyed) throw BufferDestroyedException()
        return JVMMultiplatformBuffer(this.toArray())
    }

    override fun setCursorImpl(index: Int) {
        this.backBuffer.position(index)
    }

    override fun setLimitImpl(index: Int) {
        this.backBuffer.limit(index)
    }

    override fun destroy() {
        this.destroyed = true
    }

    fun nativeBuffer() : ByteBuffer{
        if(this.destroyed) throw BufferDestroyedException()
        return this.backBuffer
    }
}

actual fun allocMultiplatformBuffer(size :Int) : MultiplatformBuffer {
    return JVMMultiplatformBuffer(size)
}

fun allocDirectMultiplatformBuffer(size :Int) : MultiplatformBuffer {
    return JVMMultiplatformBuffer(ByteBuffer.allocateDirect(size))
}

actual fun wrapMultiplatformBuffer(array : ByteArray) : MultiplatformBuffer {
    return JVMMultiplatformBuffer(array)
}

fun wrapMultiplatformBuffer(array : ByteBuffer) : MultiplatformBuffer {
    return JVMMultiplatformBuffer(array)
}

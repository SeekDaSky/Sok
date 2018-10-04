package Sok.Buffer

import java.nio.ByteBuffer
import kotlin.math.min


class JVMMultiplateformBuffer : MultiplateformBuffer {

    @Volatile
    private var backBuffer : ByteBuffer

    constructor(size : Int){
        this.backBuffer = ByteBuffer.allocate(size)
    }

    constructor(array : ByteArray){
        this.backBuffer = ByteBuffer.wrap(array)
    }

    constructor(array : ByteBuffer){
        this.backBuffer = array
    }

    override fun get(index:Int): Byte {
        return this.backBuffer.get(index)
    }

    override fun getByte(): Byte {
        return this.backBuffer.get()
    }

    override fun getBytes(length : Int) : ByteArray{
        val tmp = ByteArray(length)
        this.backBuffer.get(tmp)

        return tmp
    }

    override fun getBytes(offset :Int, length : Int) : ByteArray{
        val tmp = ByteArray(length)
        val cursorPosition = this.backBuffer.position()
        this.backBuffer.position(offset)
        this.backBuffer.get(tmp,0,length)
        this.backBuffer.position(cursorPosition)

        return tmp
    }

    override fun getUByte(): Short {
        return (this.backBuffer.get().toInt() and 0xFF).toShort()
    }

    override fun getUShort(): Int {
        return (this.backBuffer.getShort().toInt() and 0xFFFF)
    }

    override fun getShort(): Short {
        return this.backBuffer.getShort()
    }

    override fun getInt(): Int {
        return this.backBuffer.getInt()
    }

    override fun getUInt(): Long {
        return (this.backBuffer.getInt().toLong() and 0xFFFFFFFF)
    }

    override fun getLong(): Long {
        return this.backBuffer.getLong()
    }

    override fun putBytes(arr : ByteArray){
        this.backBuffer.put(arr)
    }

    override fun putByte(value: Byte) {
        this.backBuffer.put(value)
    }

    override fun putShort(value: Short) {
        this.backBuffer.putShort(value)
    }

    override fun putInt(value: Int) {
        this.backBuffer.putInt(value)
    }

    override fun putLong(value:Long) {
        this.backBuffer.putLong(value)
    }

    override fun toArray(): ByteArray {
        if(this.backBuffer.capacity() != this.backBuffer.limit() || !this.backBuffer.hasArray()){
            val tmp = ByteArray(this.size())
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

    override fun size():Int {
        return this.backBuffer.limit()
    }

    override fun setCursor(index:Int) {
        this.backBuffer.position(index)
    }

    override fun getCursor() :Int {
        return this.backBuffer.position()
    }

    override fun clone(): MultiplateformBuffer {
        val tmp = JVMMultiplateformBuffer(this.backBuffer.capacity())
        tmp.backBuffer.limit(this.backBuffer.limit())
        tmp.putBytes(this.toArray())
        tmp.setCursor(0)
        return tmp
    }

    override fun reset() {
        this.backBuffer.clear()
    }

    override fun remaining(): Int {
        return this.backBuffer.remaining()
    }

    fun nativeBuffer() : ByteBuffer{
        return this.backBuffer
    }
}

actual fun allocMultiplateformBuffer(size :Int) : MultiplateformBuffer {
    return JVMMultiplateformBuffer(size)
}

fun allocDirectMultiplateformBuffer(size :Int) : MultiplateformBuffer {
    return JVMMultiplateformBuffer(ByteBuffer.allocateDirect(size))
}

actual fun wrapMultiplateformBuffer(array : ByteArray) : MultiplateformBuffer {
    return JVMMultiplateformBuffer(array)
}

fun wrapMultiplateformBuffer(array : ByteBuffer) : MultiplateformBuffer {
    return JVMMultiplateformBuffer(array)
}

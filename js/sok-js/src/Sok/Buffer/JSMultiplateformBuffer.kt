package Sok.Buffer

import Sok.Exceptions.BufferDestroyedException
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

/**
 * JS implementation of the `MultiplatformBuffer` class
 *
 * @property backBuffer Node.js Buffer class internally used
 */
class JSMultiplatformBuffer : MultiplatformBuffer{

    private var backBuffer : Buffer

    /**
     * Allocate a new MultiplatformBuffer
     *
     * @param size size of the buffer
     */
    constructor(size : Int) : super(size){
        this.backBuffer = Buffer.allocUnsafe(size)
        this.limit = size
    }

    /**
     * Create a new MultiplatformBuffer wrapping a byteArray , thus avoiding copy
     *
     * @param array array wrapped
     */
    constructor(array : ByteArray) : super(array.size){
        this.backBuffer = Buffer.from(array.toTypedArray())
        this.limit = array.size
    }

    /**
     * Get the byte at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the byte, buffer.cursor is used if the index is null
     * @return byte
     */
    override fun getByteImpl(index: Int?): Byte {
        return this.backBuffer.readInt8(index ?: this.cursor)
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
        val realIndex = index ?: this.cursor
        val tmpArr = Uint8Array(length)
        this.backBuffer.copy(tmpArr,0,realIndex,realIndex+length)
        return Int8Array(tmpArr.buffer).unsafeCast<ByteArray>()
    }

    /**
     * Get the unsigned byte at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the byte, buffer.cursor is used if the index is null
     * @return byte
     */
    override fun getUByteImpl(index: Int?): UByte {
        return this.backBuffer.readUInt8(index ?: this.cursor).toUByte()
    }

    /**
     * Get the short at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the short, buffer.cursor is used if the index is null
     * @return short
     */
    override fun getShortImpl(index: Int?): Short {
        return this.backBuffer.readInt16BE(index ?: this.cursor)
    }

    /**
     * Get the unsigned short at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the short, buffer.cursor is used if the index is null
     * @return short
     */
    override fun getUShortImpl(index: Int?): UShort {
        return this.backBuffer.readUInt16BE(index ?: this.cursor).toUShort()
    }

    /**
     * Get the integer at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the int, buffer.cursor is used if the index is null
     * @return int
     */
    override fun getIntImpl(index: Int?): Int {
        return this.backBuffer.readInt32BE(index ?: this.cursor)
    }

    /**
     * Get the unsigned integer at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the int, buffer.cursor is used if the index is null
     * @return int
     */
    override fun getUIntImpl(index: Int?): UInt {
        return this.backBuffer.readUInt32BE(index ?: this.cursor).toUInt()
    }

    /**
     * Get the long at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the long, buffer.cursor is used if the index is null
     * @return int
     */
    override fun getLongImpl(index: Int?): Long {
        //as the native buffer dosen't have a method to retreive Longs, we have to trick with two ints
        val int1 = this.backBuffer.readInt32BE(index ?: this.cursor)
        val int2 = this.backBuffer.readInt32BE(index?.plus(4) ?: this.cursor+4)

        //let's assume Kotlin wrap its Longs and bypass the JS limit of 53 bits
        return int2 + (int1.toLong().shl(32))
    }

    /**
     * Get the unsigned long at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the long, buffer.cursor is used if the index is null
     * @return long
     */
    override fun getULongImpl(index: Int?): ULong {
        //as the native buffer dosen't have a method to retreive Longs, we have to trick with two ints
        val int1 = this.backBuffer.readInt32BE(index ?: this.cursor)
        val int2 = this.backBuffer.readInt32BE(index?.plus(4) ?: this.cursor+4)

        //let's assume Kotlin wrap its Longs and bypass the JS limit of 53 bits
        return (int2 + (int1.toLong().shl(32))).toULong()
    }

    /**
     * Put the given byte array inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param array data to put in the buffer
     * @param index index of the first byte, buffer.cursor is used if the index is null
     */
    override fun putBytesImpl(array: ByteArray, index: Int?) {
        //seriously node, why can't you copy a bunch of data inside a buffer?
        array.forEachIndexed {i,byte ->
            this.putByteImpl(byte,index?.plus(i) ?: this.cursor+i)
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
        this.backBuffer.writeInt8(value,index ?: this.cursor)
    }

    /**
     * Put the given short inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value short to put in the buffer
     * @param index index of the short, buffer.cursor is used if the index is null
     */
    override fun putShortImpl(value: Short, index: Int?) {
        this.backBuffer.writeInt16BE(value,index ?: this.cursor)
    }

    /**
     * Put the given integer inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value int to put in the buffer
     * @param index index of the int, buffer.cursor is used if the index is null
     */
    override fun putIntImpl(value: Int, index: Int?) {
        this.backBuffer.writeInt32BE(value,index ?: this.cursor)
    }

    /**
     * Put the given long inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value long to put in the buffer
     * @param index index of the long, buffer.cursor is used if the index is null
     */
    override fun putLongImpl(value: Long, index: Int?) {
        //erase the first 32 bits
        val int1 = (value.shl(32).shr(32)).toInt()
        //keep only the last 32 bits
        val int2 = (value.shr(32)).toInt()

        this.backBuffer.writeInt32BE(int2,index ?: this.cursor)
        this.backBuffer.writeInt32BE(int1,index?.plus(4) ?: this.cursor+4)
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
        val tmp = Uint8Array(this.limit)
        this.backBuffer.copy(tmp,0,0,this.limit)

        return Int8Array(tmp.buffer).unsafeCast<ByteArray>()
    }

    /**
     * Deep copy the buffer. All the data from the start to the capacity will be copied, the cursor and limit will be reset
     *
     * @return cloned buffer
     */
    override fun clone(): MultiplatformBuffer {
        if(this.destroyed) throw BufferDestroyedException()
        val tmp = JSMultiplatformBuffer(this.capacity)
        tmp.backBuffer = Buffer.from(this.toArray().toTypedArray())

        return tmp
    }

    /**
     * Used only by the JVM to synchronize the MultiplatformBuffer state with the ByteBuffer state
     *
     * @param index cursor
     */
    override fun setCursorImpl(index: Int) {
        //does nothing on Node.js
    }

    /**
     * Used only by the JVM to synchronize the MultiplatformBuffer state with the ByteBuffer state
     *
     * @param index limit
     */
    override fun setLimitImpl(index: Int) {
        //does nothing on Node.js
    }

    /**
     * Destroy the ByteBuffer, you cannot call any method on the buffer after calling destroy. This method MUST be called on native platforms
     * in order to free/unpin memory but you can skip it on any other platform
     */
    override fun destroy() {
        this.destroyed = true
    }

    /**
     * Used internally to perform I/O on platform-specific sockets
     *
     * @return platform-specific Buffer
     */
    internal fun nativeBuffer() : Buffer {
        if(this.destroyed) throw BufferDestroyedException()
        return this.backBuffer
    }
}

/**
 * Allocate a new MultiplatformBuffer. Allocate a new MultiplatformBuffer. The buffer is not zeroed, be careful.
 *
 * @param size size of the buffer
 * @return allocated buffer
 */
actual fun allocMultiplatformBuffer(size : Int) : MultiplatformBuffer {
    return JSMultiplatformBuffer(size)
}

/**
 * Wrap the array with a MultiplatformBuffer. The data will not be copied and the array will be linked to the
 * MultiplatformBuffer class
 *
 * @param array array to wrap
 * @return buffer
 */
actual fun wrapMultiplatformBuffer(array : ByteArray) : MultiplatformBuffer {
    return JSMultiplatformBuffer(array)
}
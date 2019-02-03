package Sok.Buffer

import Sok.Exceptions.BufferDestroyedException
import java.nio.ByteBuffer

/**
 * JVM implementation of the `MultiplatformBuffer` class
 *
 * @property backBuffer internal NIO buffer
 */
class JVMMultiplatformBuffer : MultiplatformBuffer {

    private val backBuffer : ByteBuffer

    /**
     * Allocate a new MultiplatformBuffer
     *
     * @param size size of the buffer
     */
    constructor(size : Int) : super(size){
        this.backBuffer = ByteBuffer.allocate(size)
    }

    /**
     * Create a new MultiplatformBuffer wrapping a byteArray , thus avoiding copy
     *
     * @param array array wrapped
     */
    constructor(array : ByteArray) : super(array.size){
        this.backBuffer = ByteBuffer.wrap(array)
    }

    /**
     * Create a MultiplatformBuffer with a specific NIO ByteBuffer
     *
     * @param NIO ByteBuffer
     */
    constructor(array : ByteBuffer) : super(array.capacity()){
        this.backBuffer = array
        this.cursor = array.position()
        this.limit = array.limit()
    }

    /**
     * Get the byte at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the byte, buffer.cursor is used if the index is null
     * @return byte
     */
    override fun getByteImpl(index: Int?): Byte {
        return this.backBuffer.get(index ?: this.cursor)
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
        val array = ByteArray(length)
        this.backBuffer.position(index ?: this.cursor)
        this.backBuffer.get(array)
        return array
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
        this.backBuffer.position(index ?: this.cursor)
        this.backBuffer.get(array,destinationOffset,length)
    }

    /**
     * Get the unsigned byte at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the byte, buffer.cursor is used if the index is null
     * @return byte
     */
    override fun getUByteImpl(index: Int?): UByte {
        return this.backBuffer.get(index ?: this.cursor).toUByte()
    }

    /**
     * Get the short at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the short, buffer.cursor is used if the index is null
     * @return short
     */
    override fun getShortImpl(index: Int?): Short {
        return this.backBuffer.getShort(index ?: this.cursor)
    }

    /**
     * Get the unsigned short at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the short, buffer.cursor is used if the index is null
     * @return short
     */
    override fun getUShortImpl(index: Int?): UShort {
        return this.backBuffer.getShort(index ?: this.cursor).toUShort()
    }

    /**
     * Get the integer at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the int, buffer.cursor is used if the index is null
     * @return int
     */
    override fun getIntImpl(index: Int?): Int {
        return this.backBuffer.getInt(index ?: this.cursor)
    }

    /**
     * Get the unsigned integer at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the int, buffer.cursor is used if the index is null
     * @return int
     */
    override fun getUIntImpl(index: Int?): UInt {
        return this.backBuffer.getInt(index ?: this.cursor).toUInt()
    }

    /**
     * Get the long at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the long, buffer.cursor is used if the index is null
     * @return int
     */
    override fun getLongImpl(index: Int?): Long {
        return this.backBuffer.getLong(index ?: this.cursor)
    }

    /**
     * Get the unsigned long at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the long, buffer.cursor is used if the index is null
     * @return long
     */
    override fun getULongImpl(index: Int?): ULong {
        return this.backBuffer.getLong(index ?: this.cursor).toULong()
    }

    /**
     * Put the given byte array inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param array data to put in the buffer
     * @param index index of the first byte, buffer.cursor is used if the index is null
     */
    override fun putBytesImpl(array: ByteArray, index: Int?) {
        this.backBuffer.position(index ?: this.cursor)
        this.backBuffer.put(array)
    }

    /**
     * Put the given byte inside the buffer at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value byte to put in the buffer
     * @param index index of the byte, buffer.cursor is used if the index is null
     */
    override fun putByteImpl(value: Byte, index: Int?) {
        this.backBuffer.put(index ?: this.cursor,value)
    }

    /**
     * Put the given short inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value short to put in the buffer
     * @param index index of the short, buffer.cursor is used if the index is null
     */
    override fun putShortImpl(value: Short, index: Int?) {
        this.backBuffer.putShort(index ?: this.cursor,value)
    }

    /**
     * Put the given integer inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value int to put in the buffer
     * @param index index of the int, buffer.cursor is used if the index is null
     */
    override fun putIntImpl(value: Int, index: Int?) {
        this.backBuffer.putInt(index ?: this.cursor,value)
    }

    /**
     * Put the given long inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value long to put in the buffer
     * @param index index of the long, buffer.cursor is used if the index is null
     */
    override fun putLongImpl(value: Long, index: Int?) {
        this.backBuffer.putLong(index ?: this.cursor,value)
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

    /**
     * Deep copy the buffer. All the data from the start to the capacity will be copied, the cursor and limit will be reset
     *
     * @return cloned buffer
     */
    override fun clone(): MultiplatformBuffer {
        if(this.destroyed) throw BufferDestroyedException()
        return JVMMultiplatformBuffer(this.toArray())
    }

    /**
     * Used only by the JVM to synchronize the MultiplatformBuffer state with the ByteBuffer state
     *
     * @param index cursor
     */
    override fun setCursorImpl(index: Int) {
        this.backBuffer.position(index)
    }

    /**
     * Used only by the JVM to synchronize the MultiplatformBuffer state with the ByteBuffer state
     *
     * @param index limit
     */
    override fun setLimitImpl(index: Int) {
        this.backBuffer.limit(index)
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
    internal fun nativeBuffer() : ByteBuffer{
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
actual fun allocMultiplatformBuffer(size :Int) : MultiplatformBuffer {
    return JVMMultiplatformBuffer(size)
}

/**
 * Allocate a new MultiplatformBuffer. The buffer is not zeroed, be careful.
 *
 * The create buffer will use a DirectByteBuffer instead of a HeapByteBuffer, please read the Java NIO documentation
 * to know the difference between the two
 *
 * @param size size of the buffer
 * @return allocated buffer
 */
fun allocDirectMultiplatformBuffer(size :Int) : MultiplatformBuffer {
    return JVMMultiplatformBuffer(ByteBuffer.allocateDirect(size))
}

/**
 * Wrap the array with a MultiplatformBuffer. The data will not be copied and the array will be linked to the
 * MultiplatformBuffer class
 *
 * @param array array to wrap
 * @return buffer
 */
actual fun wrapMultiplatformBuffer(array : ByteArray) : MultiplatformBuffer {
    return JVMMultiplatformBuffer(array)
}

/**
 * Make a MultiplatformBuffer that use a given ByteBuffer.
 *
 * @param array array to wrap
 * @return buffer
 */
fun wrapMultiplatformBuffer(array : ByteBuffer) : MultiplatformBuffer {
    return JVMMultiplatformBuffer(array)
}

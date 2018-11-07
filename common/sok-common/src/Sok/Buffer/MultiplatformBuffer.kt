package Sok.Buffer

import Sok.Exceptions.BufferDestroyedException
import Sok.Exceptions.BufferOverflowException
import Sok.Exceptions.BufferUnderflowException

/**
 * A `MultiplatformBuffer` is the primitive type of Sok, you will use it to receive, send and manipulate data
 *
 * `MultiplatformBuffer` is a class each platform must implement to abstract native buffer types (ByteBuffer on JVM, Buffer on Node.js
 * and ByteArray on Native). In order to have the most consistent behaviour and avoid platform-specific exception leak the below class
 * implement all the behavioural code (cursor management, exception throwing) and adds abstract protected methods for platform specific code.
 *
 * THIS CLASS IS NOT THREAD SAFE but you should obviously not be working with the same buffer on two different threads at the same time
 *
 * @property capacity capacity of the buffer
 * @constructor create an empty buffer with a cursor set to 0 and a limit set to the capacity
 */
abstract class MultiplatformBuffer(
    val capacity: Int
) {
    /**
     * cursor keeping track of the position inside the buffer. The property must respect the property 0 <= cursor <= limit <= capacity
     */
    var cursor : Int = 0
        set(value) {
            if(this.destroyed) throw BufferDestroyedException()
            require(value <= this.capacity && value <= this.limit && value >= 0)
            //on the JVM the setter must also modify the ByteBuffer state too, setCursorImpl does that
            this.setCursorImpl(value)
            field = value
        }

    /**
     * limit set for the buffer. The property must respect the property 0 <= cursor <= limit <= capacity
     */
    var limit : Int = this.capacity
        set(value) {
            if(this.destroyed) throw BufferDestroyedException()
            require(value <= this.capacity && value >= this.cursor && value >= 0)
            //on the JVM the setter must also modify the ByteBuffer state too, setCursorImpl does that
            this.setLimitImpl(value)
            field = value
        }

    /**
     * is the buffer still usable, useful on Native platforms to know when the memory is freed and avoid any use-after-free
     * operation.
     */
    var destroyed = false
        protected set

    /**
     * Get a byte with its index in the buffer, the cursor will not be modified
     *
     * @param index index of the byte
     * @return byte
     */
    operator fun get(index : Int) : Byte{
        if(this.destroyed) throw BufferDestroyedException()
        return this.getByte(index)
    }

    /**
     * Get the byte at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the byte, buffer.cursor is used if the index is null
     * @return byte
     */
    fun getByte(index : Int? = null) : Byte{
        if(this.destroyed) throw BufferDestroyedException()
        this.checkBounds(1,OperationType.Read,index)
        val byte = this.getByteImpl(index)
        if(index == null) this.cursor++
        return byte
    }
    /**
     * Get the byte at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the byte, buffer.cursor is used if the index is null
     * @return byte
     */
    protected abstract fun getByteImpl(index : Int? = null) : Byte

    /**
     * Get an array of bytes of a given length starting at the current buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param length amount of data to get
     * @param index index of the first byte, buffer.cursor is used if the index is null
     *
     * @return data copied from the buffer
     */
    fun getBytes(length : Int, index : Int? = null) : ByteArray{
        if(this.destroyed) throw BufferDestroyedException()
        this.checkBounds(length,OperationType.Read,index)
        val array = this.getBytesImpl(length,index)
        if(index == null) this.cursor += length
        return array
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
    protected abstract fun getBytesImpl(length : Int, index : Int? = null) : ByteArray

    /**
     * Get the unsigned byte at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the byte, buffer.cursor is used if the index is null
     * @return byte
     */
    fun getUByte(index : Int? = null) : UByte{
        if(this.destroyed) throw BufferDestroyedException()
        this.checkBounds(1,OperationType.Read,index)
        val byte = this.getUByteImpl(index)
        if(index == null) this.cursor++
        return byte
    }
    /**
     * Get the unsigned byte at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the byte, buffer.cursor is used if the index is null
     * @return byte
     */
    protected abstract fun getUByteImpl(index : Int? = null) : UByte

    /**
     * Get the short at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the short, buffer.cursor is used if the index is null
     * @return short
     */
    fun getShort(index : Int? = null) : Short{
        if(this.destroyed) throw BufferDestroyedException()
        this.checkBounds(2,OperationType.Read,index)
        val short = this.getShortImpl(index)
        if(index == null) this.cursor += 2
        return short
    }
    /**
     * Get the short at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the short, buffer.cursor is used if the index is null
     * @return short
     */
    protected abstract fun getShortImpl(index : Int? = null) : Short

    /**
     * Get the unsigned short at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the short, buffer.cursor is used if the index is null
     * @return short
     */
    fun getUShort(index : Int? = null) : UShort{
        if(this.destroyed) throw BufferDestroyedException()
        this.checkBounds(2,OperationType.Read,index)
        val short = this.getUShortImpl(index)
        if(index == null) this.cursor += 2
        return short
    }
    /**
     * Get the unsigned short at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the short, buffer.cursor is used if the index is null
     * @return short
     */
    protected abstract fun getUShortImpl(index : Int? = null) : UShort

    /**
     * Get the integer at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the int, buffer.cursor is used if the index is null
     * @return int
     */
    fun getInt(index : Int? = null) : Int{
        if(this.destroyed) throw BufferDestroyedException()
        this.checkBounds(4,OperationType.Read,index)
        val int = this.getIntImpl(index)
        if(index == null) this.cursor += 4
        return int
    }
    /**
     * Get the integer at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the int, buffer.cursor is used if the index is null
     * @return int
     */
    protected abstract fun getIntImpl(index : Int? = null) : Int

    /**
     * Get the unsigned integer at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the int, buffer.cursor is used if the index is null
     * @return int
     */
    fun getUInt(index : Int? = null) : UInt{
        if(this.destroyed) throw BufferDestroyedException()
        this.checkBounds(4,OperationType.Read,index)
        val int = this.getUIntImpl(index)
        if(index == null) this.cursor += 4
        return int
    }
    /**
     * Get the unsigned integer at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     *
     * @param index index of the int, buffer.cursor is used if the index is null
     * @return int
     */
    protected abstract fun getUIntImpl(index : Int? = null) : UInt

    /**
     * Get the long at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the long, buffer.cursor is used if the index is null
     * @return long
     */
    fun getLong(index : Int? = null) : Long{
        if(this.destroyed) throw BufferDestroyedException()
        this.checkBounds(8,OperationType.Read,index)
        val long = this.getLongImpl(index)
        if(index == null) this.cursor += 8
        return long
    }
    /**
     * Get the long at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the long, buffer.cursor is used if the index is null
     * @return long
     */
    protected abstract fun getLongImpl(index : Int? = null) : Long

    /**
     * Get the unsigned long at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the long, buffer.cursor is used if the index is null
     * @return long
     */
    fun getULong(index : Int? = null) : ULong{
        if(this.destroyed) throw BufferDestroyedException()
        this.checkBounds(8,OperationType.Read,index)
        val long = this.getULongImpl(index)
        if(index == null) this.cursor += 8
        return long
    }
    /**
     * Get the unsigned long at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     *
     * @param index index of the long, buffer.cursor is used if the index is null
     * @return long
     */
    protected abstract fun getULongImpl(index : Int? = null) : ULong

    /**
     * Put the given byte array inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param array data to put in the buffer
     * @param index index of the first byte, buffer.cursor is used if the index is null
     */
    fun putBytes(array : ByteArray, index : Int? = null){
        if(this.destroyed) throw BufferDestroyedException()
        this.checkBounds(array.size,OperationType.Write,index)
        this.putBytesImpl(array,index)
        if(index == null) this.cursor += array.size
    }
    /**
     * Put the given byte array inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param array data to put in the buffer
     * @param index index of the first byte, buffer.cursor is used if the index is null
     */
    protected abstract fun putBytesImpl(array : ByteArray, index : Int? = null)

    /**
     * Put the given byte inside the buffer at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value byte to put in the buffer
     * @param index index of the byte, buffer.cursor is used if the index is null
     */
    fun putByte(value : Byte, index : Int? = null){
        if(this.destroyed) throw BufferDestroyedException()
        this.checkBounds(1,OperationType.Write,index)
        this.putByteImpl(value,index)
        if(index == null) this.cursor ++
    }
    /**
     * Put the given byte inside the buffer at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value byte to put in the buffer
     * @param index index of the byte, buffer.cursor is used if the index is null
     */
    protected abstract fun putByteImpl(value : Byte, index : Int? = null)

    /**
     * Put the given short inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value short to put in the buffer
     * @param index index of the short, buffer.cursor is used if the index is null
     */
    fun putShort(value : Short, index : Int? = null){
        if(this.destroyed) throw BufferDestroyedException()
        this.checkBounds(2,OperationType.Write,index)
        this.putShortImpl(value,index)
        if(index == null) this.cursor += 2
    }
    /**
     * Put the given short inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value short to put in the buffer
     * @param index index of the short, buffer.cursor is used if the index is null
     */
    protected abstract fun putShortImpl(value : Short, index : Int? = null)

    /**
     * Put the given integer inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value int to put in the buffer
     * @param index index of the int, buffer.cursor is used if the index is null
     */
    fun putInt(value : Int, index : Int? = null){
        if(this.destroyed) throw BufferDestroyedException()
        this.checkBounds(4,OperationType.Write,index)
        this.putIntImpl(value,index)
        if(index == null) this.cursor += 4
    }
    /**
     * Put the given integer inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value int to put in the buffer
     * @param index index of the int, buffer.cursor is used if the index is null
     */
    protected abstract fun putIntImpl(value : Int, index : Int? = null)

    /**
     * Put the given long inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value long to put in the buffer
     * @param index index of the long, buffer.cursor is used if the index is null
     */
    fun putLong(value : Long, index : Int? = null){
        if(this.destroyed) throw BufferDestroyedException()
        this.checkBounds(8,OperationType.Write,index)
        this.putLongImpl(value,index)
        if(index == null) this.cursor += 8
    }
    /**
     * Put the given long inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     *
     * @param value long to put in the buffer
     * @param index index of the long, buffer.cursor is used if the index is null
     */
    protected abstract fun putLongImpl(value : Long, index : Int? = null)

    /**
     * Get all the data between the start of the buffer and its limit, the data is copied and is not linked to the content
     * of the buffer. WARNING this behaviour is different from the ByteBuffer array() method, please read the documentation
     * carefully
     *
     * @return data copied from the start fo the buffer to the limit
     */
    abstract fun toArray() : ByteArray

    /**
     * Deep copy the buffer. All the data from the start to the capacity will be copied, the cursor and limit will be reset
     *
     * @return cloned buffer
     */
    abstract fun clone() : MultiplatformBuffer

    /**
     * Reset the buffer cursor and limit
     */
    fun reset(){
        if(this.destroyed) throw BufferDestroyedException()
        this.limit = this.capacity
        this.cursor = 0
    }

    /**
     * Get the space available between the cursor and the limit of the buffer
     *
     * @return space available between the cursor and the limit
     */
    fun remaining() : Int{
        if(this.destroyed) throw BufferDestroyedException()
        return this.limit - this.cursor
    }

    /**
     * Return true if there is space between the cursor and the limit
     *
     * @return true if buffer.remaining() > 0
     */
    fun hasRemaining() : Boolean{
        if(this.destroyed) throw BufferDestroyedException()
        return this.remaining() != 0
    }

    /**
     * Destroy the ByteBuffer, you cannot call any method on the buffer after calling destroy. This method MUST be called on native platforms
     * in order to free/unpin memory but you can skip it on any other platform
     */
    abstract fun destroy()

    /**
     * Used only by the JVM to synchronize the MultiplatformBuffer state with the ByteBuffer state
     *
     * @param index cursor
     */
    protected abstract fun setCursorImpl(index : Int)

    /**
     * Used only by the JVM to synchronize the MultiplatformBuffer state with the ByteBuffer state
     *
     * @param index limit
     */
    protected abstract fun setLimitImpl(index : Int)

    /**
     * Mthod used before doing any get/put to check if the buffer is big enough, it throws a buffer overflow or underflow
     * depending on the operation we want to do
     */
    private fun checkBounds(numberOfBytes : Int, opType : OperationType, absoluteIndex : Int? = null){
        val index = absoluteIndex ?: this.cursor

        when(opType){
            OperationType.Read -> if(index + numberOfBytes > this.limit) throw BufferUnderflowException()
            OperationType.Write -> if(index + numberOfBytes > this.limit) throw BufferOverflowException()
        }
    }

    /**
     * Enum used to represent what type of operation we are trying to make while checking bounds
     * Basically it is used to know whether to throw Overflow or Underflow exceptions
     */
    private enum class OperationType{
        Read,Write
    }
}

/**
 * Allocate a new MultiplatformBuffer. The buffer is not zeroed, be careful.
 *
 * @param size size of the buffer
 * @return allocated buffer
 */
expect fun allocMultiplatformBuffer(size :Int) : MultiplatformBuffer

/**
 * Wrap the array with a MultiplatformBuffer. The data will not be copied and the array will be linked to the
 * MultiplatformBuffer class
 *
 * @param array array to wrap
 * @return buffer
 */
expect fun wrapMultiplatformBuffer(array : ByteArray) : MultiplatformBuffer
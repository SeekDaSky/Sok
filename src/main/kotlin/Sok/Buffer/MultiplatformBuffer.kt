package Sok.Buffer

import Sok.Exceptions.BufferOverflowException
import Sok.Exceptions.BufferUnderflowException

/**
 * MultiplatformBuffer is a class each platform must implement to abstract native buffer types (ByteBuffer on JVM, Buffer on Node.js
 * and a simple ByteArray on Native). In order to have the most consistent behaviour and avoid platform-specific exception leak the below class
 * implement all the behavioural code (cursor management, exception throwing) and adds abstract methods for platform specific code.
 *
 * THIS CLASS IS NOT THREAD SAFE but you should obviously not be working with the same buffer on two different threads at the same time
 */
abstract class MultiplatformBuffer(
    //allocated capaity of the buffer
    val capacity: Int
) {
    //cursor keeping track of the position inside the buffer. Te setter must check the property 0 <= cursor <= limit <= capacity
    //on the JVM the setter must also modify the ByteBuffer state too
    var cursor : Int = 0
        set(value) {
            require(value <= this.capacity && value <= this.limit && value >= 0)
            this.setCursorImpl(value)
            field = value
        }

    //limit set for the buffer. The setter must check the property 0 <= cursor <= limit <= capacity
    //on the JVM the setter must also modify the ByteBuffer state too
    var limit : Int = this.capacity
        set(value) {
            require(value <= this.capacity && value >= this.cursor && value >= 0)
            this.setLimitImpl(value)
            field = value
        }

    /**
     * Get a byte with its index in the buffer, the cursor will not be modified
     */
    operator fun get(index : Int) : Byte{
        return this.getByte(index)
    }

    /**
     * Get the byte at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     */
    fun getByte(index : Int? = null) : Byte{
        this.checkBounds(1,OperationType.Read,index)
        val byte = this.getByteImpl(index)
        if(index == null) this.cursor++
        return byte
    }
    protected abstract fun getByteImpl(index : Int? = null) : Byte

    /**
     * Get an array of bytes of a given length starting at the current buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     */
    fun getBytes(length : Int, index : Int? = null) : ByteArray{
        this.checkBounds(length,OperationType.Read,index)
        val array = this.getBytesImpl(length,index)
        if(index == null) this.cursor += length
        return array
    }
    protected abstract fun getBytesImpl(length : Int, index : Int? = null) : ByteArray

    /**
     * Get the unsigned byte at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     */
    fun getUByte(index : Int? = null) : Short{
        this.checkBounds(1,OperationType.Read,index)
        val byte = this.getUByteImpl(index)
        if(index == null) this.cursor++
        return byte
    }
    protected abstract fun getUByteImpl(index : Int? = null) : Short

    /**
     * Get the short at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     */
    fun getShort(index : Int? = null) : Short{
        this.checkBounds(2,OperationType.Read,index)
        val short = this.getShortImpl(index)
        if(index == null) this.cursor += 2
        return short
    }
    protected abstract fun getShortImpl(index : Int? = null) : Short

    /**
     * Get the unsigned short at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     */
    fun getUShort(index : Int? = null) : Int{
        this.checkBounds(2,OperationType.Read,index)
        val short = this.getUShortImpl(index)
        if(index == null) this.cursor += 2
        return short
    }
    protected abstract fun getUShortImpl(index : Int? = null) : Int

    /**
     * Get the integer at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     */
    fun getInt(index : Int? = null) : Int{
        this.checkBounds(4,OperationType.Read,index)
        val int = this.getIntImpl(index)
        if(index == null) this.cursor += 4
        return int
    }
    protected abstract fun getIntImpl(index : Int? = null) : Int

    /**
     * Get the unsigned integer at the current cursor position. If the index parameter is provided, the cursor will be ignored and
     * not modified
     */
    fun getUInt(index : Int? = null) : Long{
        this.checkBounds(4,OperationType.Read,index)
        val int = this.getUIntImpl(index)
        if(index == null) this.cursor += 4
        return int
    }
    protected abstract fun getUIntImpl(index : Int? = null) : Long

    /**
     * Get the long at the current cursor position. If the index parameter is provided, the cursor will be ignored and not modified
     */
    fun getLong(index : Int? = null) : Long{
        this.checkBounds(8,OperationType.Read,index)
        val long = this.getLongImpl(index)
        if(index == null) this.cursor += 8
        return long
    }
    protected abstract fun getLongImpl(index : Int? = null) : Long

    /**
     * Put the given byte array inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     */
    fun putBytes(array : ByteArray, index : Int? = null){
        this.checkBounds(array.size,OperationType.Write,index)
        this.putBytesImpl(array,index)
        if(index == null) this.cursor += array.size
    }
    protected abstract fun putBytesImpl(array : ByteArray, index : Int? = null)

    /**
     * Put the given byte inside the buffer at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     */
    fun putByte(value : Byte, index : Int? = null){
        this.checkBounds(1,OperationType.Write,index)
        this.putByteImpl(value,index)
        if(index == null) this.cursor ++
    }
    protected abstract fun putByteImpl(value : Byte, index : Int? = null)

    /**
     * Put the given short inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     */
    fun putShort(value : Short, index : Int? = null){
        this.checkBounds(2,OperationType.Write,index)
        this.putShortImpl(value,index)
        if(index == null) this.cursor += 2
    }
    protected abstract fun putShortImpl(value : Short, index : Int? = null)

    /**
     * Put the given integer inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     */
    fun putInt(value : Int, index : Int? = null){
        this.checkBounds(4,OperationType.Write,index)
        this.putIntImpl(value,index)
        if(index == null) this.cursor += 4
    }
    protected abstract fun putIntImpl(value : Int, index : Int? = null)

    /**
     * Put the given long inside the buffer starting at the buffer cursor position. If the index parameter is provided, the
     * cursor will be ignored and not modified
     */
    fun putLong(value : Long, index : Int? = null){
        this.checkBounds(8,OperationType.Write,index)
        this.putLongImpl(value,index)
        if(index == null) this.cursor += 8
    }
    protected abstract fun putLongImpl(value : Long, index : Int? = null)

    /**
     * Get all the data between the start of the buffer and its limit, the data is copied and is not linked to the content
     * of the buffer. WARNING this behaviour is different from the ByteBuffer array() method, please read the documentation
     * carefully
     */
    abstract fun toArray() : ByteArray

    /**
     * Deep copy the buffer. All the data from the start to the capacity will be copied, the cursor and limit will be reset
     */
    abstract fun clone() : MultiplatformBuffer

    /**
     * Reset the buffer cursor and limit
     */
    fun reset(){
        this.limit = this.capacity
        this.cursor = 0
    }

    /**
     * Get the space available between the cursor and the limit of the buffer
     */
    fun remaining() : Int{
        return this.limit - this.cursor
    }

    /**
     * Return true if there is space between the cursor and the limit
     */
    fun hasRemaining() : Boolean{
        return this.remaining() != 0
    }

    /**
     * Used only by the JVM to synchronize the MultiplatformBuffer state with the ByteBuffer state
     */
    protected abstract fun setCursorImpl(index : Int)
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

expect fun allocMultiplatformBuffer(size :Int) : MultiplatformBuffer

expect fun wrapMultiplatformBuffer(array : ByteArray) : MultiplatformBuffer
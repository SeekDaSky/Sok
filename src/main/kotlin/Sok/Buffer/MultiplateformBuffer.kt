package Sok.Buffer

interface MultiplateformBuffer {

    //get the byte at a certain index without taking in account the cursor
    operator fun get(index :Int) : Byte

    //get data and increment the cursor
    fun getByte() : Byte
    fun getBytes(length : Int) : ByteArray
    fun getBytes(offset :Int, length : Int) : ByteArray
    fun getUByte() : Short
    fun getUShort() : Int
    fun getShort() : Short
    fun getInt() : Int
    fun getUInt() : Long
    fun getLong() : Long

    //put data and increment the cursor
    fun putBytes(arr : ByteArray)
    fun putByte(value : Byte)
    fun putShort(value : Short)
    fun putInt(value : Int)
    fun putLong(value :Long)

    /**
     * Get all the data contained inside the buffer. The data will be copied, modifications of the returned ByteArray
     * won't affect the MultiplatformBuffer. The returned array size will be the same as given by the size() method.
     */
    fun toArray() : ByteArray

    //return the size of the buffer
    fun size() :Int

    //cursor operations
    fun setCursor(index :Int)
    fun getCursor() :Int

    //clone the buffer
    fun clone() : MultiplateformBuffer

    //reset the cursor position
    fun reset()

    //how many bytes between the cursor and the size limit
    fun remaining() : Int

}

expect fun allocMultiplateformBuffer(size :Int) : MultiplateformBuffer

expect fun wrapMultiplateformBuffer(array : ByteArray) : MultiplateformBuffer
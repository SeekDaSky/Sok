package Sok.Buffer

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

class JSMultiplateformBuffer : MultiplateformBuffer{

    /**
     * The following code allow the JSMultiplatformBuffer to be lazy when allocating memory, it allows the Socket classes to replace the
     * back buffer with the buffer returned by read(), thus avoiding memory copy
     */
    private var backBuffer : Buffer
        //the buffer must reaturn either the lazy buffer or the buffer already allocated
        get(){
            if(this.isLazy){
                return this.lazyBuffer
            }else{
                return this.nonLazyBuffer!!
            }
        }
        //if the buffer is manually set the MultiplatformBuffer become non-lazy
        set(buffer){
            this.isLazy = false
            this.nonLazyBuffer = buffer
        }

    private val lazyBuffer by lazy {
        Buffer.alloc(lazySize)
    }
    private val lazySize : Int
    private var isLazy : Boolean
    private var nonLazyBuffer : Buffer?

    private var start = 0
    private var limit = 0

    var cursor : Int = 0

    constructor(size : Int){
        this.lazySize = size
        this.isLazy = true
        this.nonLazyBuffer = null
        this.limit = size
    }

    constructor(array : ByteArray){
        this.lazySize = array.size
        this.isLazy = false
        this.nonLazyBuffer = Buffer.from(array.toTypedArray())
        this.limit = array.size
    }

    constructor(array : Buffer, start : Int? = null, limit : Int? = null){
        this.isLazy = false
        this.nonLazyBuffer = array
        if (start != null && limit != null){
            this.start = start
            this.limit = limit
            this.lazySize = limit-start
            this.cursor = start
        }else{
            this.lazySize = array.length
            this.limit = array.length
        }
    }

    fun swapBackBuffers(buffer : Buffer, start : Int? = null, limit : Int? = null){
        this.backBuffer = buffer
        this.isLazy = false
        if (start != null && limit != null){
            this.start = start
            this.limit = limit
            this.cursor = start
        }else{
            this.limit = buffer.length
        }
    }

    fun getLazySize() : Int{
        return this.lazySize
    }

    private fun checkAccess(index : Int, toGet : Int){
        if(index + toGet > this.limit) throw Exception("Incorrect index")
    }

    override fun get(index: Int): Byte {
        val readIndex = index+this.start
        this.checkAccess(readIndex,1)
        return this.backBuffer.readInt8(readIndex)
    }

    override fun getByte(): Byte {
        this.checkAccess(this.cursor,1)
        return this.backBuffer.readInt8(this.cursor++)
    }

    override fun getBytes(length: Int): ByteArray {
        this.checkAccess(this.cursor,1)

        if(this.cursor+length > this.size()){
            throw IndexOutOfBoundsException()
        }

        val tmpArr = Uint8Array(length)
        this.backBuffer.copy(tmpArr,0,this.cursor,this.cursor+length)
        this.cursor += length
        return Int8Array(tmpArr.buffer).unsafeCast<ByteArray>()
    }

    override fun getBytes(offset: Int, length: Int): ByteArray {
        val realOffset = offset+this.start
        this.checkAccess(realOffset,length)

        val tmpArr = Uint8Array(length)
        this.backBuffer.copy(tmpArr,0,realOffset,realOffset+length)
        return Int8Array(tmpArr.buffer).unsafeCast<ByteArray>()
    }

    override fun getUByte(): Short {
        this.checkAccess(this.cursor,1)
        return this.backBuffer.readUInt8(this.cursor++)
    }

    override fun getUShort(): Int {
        this.checkAccess(this.cursor,2)
        return this.backBuffer.readUInt16BE(this.cursor).let {
            this.cursor += 2
            it
        }
    }

    override fun getShort(): Short {
        this.checkAccess(this.cursor,2)
        return this.backBuffer.readInt16BE(this.cursor).let {
            this.cursor += 2
            it
        }
    }

    override fun getInt(): Int {
        this.checkAccess(this.cursor,4)
        return this.backBuffer.readInt32BE(this.cursor).let {
            this.cursor += 4
            it
        }
    }

    override fun getUInt(): Long {
        this.checkAccess(this.cursor,4)
        return this.backBuffer.readUInt32BE(this.cursor).let {
            this.cursor += 4
            it
        }
    }

    override fun getLong(): Long {
        this.checkAccess(this.cursor,8)
        //as the native buffer dosen't have a method to retreive Ints, we have to trick with two int
        val int1 = this.getInt().unsafeCast<Int>()
        val int2 = this.getInt().unsafeCast<Int>()

        //let's assume Kotlin wrap its Ints and bypass the JS limit of 53 bits
        return int2 + (int1.toLong().shl(32))
    }

    override fun putBytes(arr: ByteArray) {
        this.checkAccess(this.cursor,arr.size)
        arr.forEach {
            this.putByte(it)
        }
    }

    override fun putByte(value: Byte) {
        this.checkAccess(this.cursor,1)
        this.backBuffer.writeInt8(value,this.cursor++)
    }

    override fun putShort(value: Short) {
        this.checkAccess(this.cursor,2)
        this.backBuffer.writeInt16BE(value,this.cursor)
        this.cursor += 2
    }

    override fun putInt(value: Int) {
        this.checkAccess(this.cursor,4)
        this.backBuffer.writeInt32BE(value,this.cursor)
        this.cursor += 4
    }

    override fun putLong(value: Long) {
        this.checkAccess(this.cursor,8)
        //erase the first 32 bits
        val int1 = (value.shl(32).shr(32))
        //keep only the last 32 bits
        val int2 = (value.shr(32))

        this.putInt(int2.toInt())
        this.putInt(int1.toInt())
    }

    override fun toArray(): ByteArray {
        val tmp = Uint8Array(this.size())
        this.backBuffer.copy(tmp,0,this.start,this.limit)

        return Int8Array(tmp.buffer).unsafeCast<ByteArray>()
    }

    override fun size(): Int {
        //avoid allocating the buffer to get its size
        if(this.isLazy){
            return this.lazySize
        }else{
            return this.limit - this.start
        }
    }

    override fun setCursor(index: Int) {
        this.cursor = index+this.start
    }

    override fun getCursor(): Int {
        return this.cursor-this.start
    }

    override fun clone(): MultiplateformBuffer {
        val tmp = JSMultiplateformBuffer(this.lazySize)
        tmp.nonLazyBuffer = Buffer.from(this.toArray().toTypedArray())
        tmp.isLazy = false

        return tmp
    }

    override fun reset() {
        this.setCursor(0)
    }

    override fun remaining(): Int {
        return this.size() - this.getCursor()
    }

    fun nativeBuffer() : Buffer {
        return this.backBuffer
    }
}

actual fun allocMultiplateformBuffer(size : Int) : MultiplateformBuffer {
    return JSMultiplateformBuffer(size)
}

actual fun wrapMultiplateformBuffer(array : ByteArray) : MultiplateformBuffer {
    return JSMultiplateformBuffer(array)
}
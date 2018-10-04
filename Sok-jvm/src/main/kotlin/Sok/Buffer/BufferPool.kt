package Sok.Buffer

import java.nio.ByteBuffer

class BufferPool(size : Int, bufferSize : Int) : ObjectPool<ByteBuffer>(size,{ByteBuffer.allocateDirect(bufferSize)}) {

    //default buffer pool
    companion object {
        val defaultBufferPool by lazy { BufferPool(8,8192) }
    }

    /**
     * pop a buffer out of the channel
     */
    suspend fun requestBuffer() : ByteBuffer{
        return this.requestObject()
    }

    /**
     * the only difference with the generic ObjectPool is that we clear the buffer before putting it back
     */
    suspend fun freeBuffer(buffer : ByteBuffer){
        //clear the buffer in case it has not been
        buffer.clear()
        this.freeObject(buffer)
    }
}
package Sok.Buffer

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.experimental.channels.Channel

/**
 * In order to avoid Garbage collection pressure it is a common practice to pre-allocate objects that are known to be long-living
 * for a later use, Kotlin channels give us a great way to implement a quite straightforward object pool. The pool will allocate
 * all the buffers lazily when needed, this means that the pool will never suspend until reaching the maximum size.
 *
 * This class is of course thread safe
 *
 * We need to use a factory lambda to let the developer use a DirectByteBuffer pool on the JVM instead of a HeapByteBuffer one
 */
open class BufferPool(val maximumNumberOfBuffer : Int, val bufferSize : Int, val bufferBuilder : (bufferSize : Int) -> MultiplatformBuffer = { allocMultiplatformBuffer(bufferSize)}) {

    private val allocatedBuffers = atomic(0)

    private val bufferChannel = Channel<MultiplatformBuffer>(this.maximumNumberOfBuffer)

    /**
     * Fetch a buffer from the pool. If the pool as not reached its maximum size and that the channel si empty we can allocate
     * the buffer instead of suspending.
     */
    suspend fun requestBuffer() : MultiplatformBuffer{
        if(this.allocatedBuffers.value < this.maximumNumberOfBuffer && this.bufferChannel.isEmpty){
            this.allocatedBuffers.incrementAndGet()
            return bufferBuilder(this.bufferSize)
        }

        return this.bufferChannel.receive()
    }

    /**
     * Free the object in order to let another coroutine have it. If you forget to call this method the pool will empty
     * itself and starve, blocking all coroutines calling requestBuffer()
     */
    suspend fun freeBuffer(obj : MultiplatformBuffer){
        //clear the buffer
        obj.reset()

        this.bufferChannel.send(obj)
    }
}
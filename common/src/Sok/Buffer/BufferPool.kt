package Sok.Buffer

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel

/**
 * In order to avoid garbage collection pressure it is a common practice to pre-allocate objects that are known to be long-living
 * for a later use, Kotlin channels give us a great way to implement a quite straightforward object pool. The pool will allocate
 * all the buffers lazily when needed, this means that the pool will never suspend until reaching the maximum size.
 *
 * This class is of course thread safe
 *
 * We need to use a factory lambda to let the developer use a DirectByteBuffer pool on the JVM instead of a HeapByteBuffer one
 *
 * @property maximumNumberOfBuffer  maximum number of buffer that the pool will allocate
 * @property bufferSize             parameter passed to the factory lambda, used to determine the size of the buffer to allocate
 * @property bufferBuilder          lambda called when the pool need to allocate a buffer
 *
 * @constructor Creates an empty pool that will grow to the given maximum number of buffer if needed
 */
class BufferPool(val maximumNumberOfBuffer : Int, val bufferSize : Int, val bufferBuilder : (bufferSize : Int) -> MultiplatformBuffer = { allocMultiplatformBuffer(bufferSize) }) {

    //atomic value tracking the number of buffer already allocated
    private val allocatedBuffers = atomic(0)

    //channel containing all the free buffers
    private val bufferChannel = Channel<MultiplatformBuffer>(this.maximumNumberOfBuffer)

    /**
     * Fetch a buffer from the pool. If the pool as not reached its maximum size and that the channel si empty we can allocate
     * the buffer instead of suspending.
     *
     * @return the requested buffer
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
     *
     * @param obj Buffer to free
     */
    suspend fun freeBuffer(obj : MultiplatformBuffer){
        //clear the buffer
        obj.reset()

        this.bufferChannel.send(obj)
    }
}
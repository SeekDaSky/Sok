package Sok.Buffer

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.experimental.channels.Channel

/**
 * In order to avoid Garbage collection pressure it is a common practice to pre-allocate objects that are known to be long-living
 * for a later use, Kotlin channels give us a great way to implement a really straightforward object pool. The pool will allocate
 * all the buffers lazily when needed, this means that the pool will never suspend until reaching the maximum size.
 *
 * We have to store an overridable factory lambda to let the developer use a DirectByteBuffer pool on the JVM instead of a HeapByteBuffer one
 */
open class BufferPool(val maximumNumberOfBuffer : Int, val bufferSize : Int, val bufferBuilder : (bufferSize : Int) -> MultiplatformBuffer = { allocMultiplatformBuffer(bufferSize)}) {

    private val allocatedBuffers = atomic(0)

    private val bufferChannel = Channel<MultiplatformBuffer>(this.maximumNumberOfBuffer)

    /**
     * Fetch an object from the pool. The method is suspending in case of starvation
     */
    suspend fun requestObject() : MultiplatformBuffer{
        //if the channel is empty and that we have not reached the maximum pool size, allocated one
        if(this.allocatedBuffers.value < this.maximumNumberOfBuffer && this.bufferChannel.isEmpty){
            this.allocatedBuffers.incrementAndGet()
            return bufferBuilder(this.bufferSize)
        }

        return this.bufferChannel.receive()
    }

    /**
     * Free the object in order to let another coroutine have it. If you forget to call this method the pool will empty
     * itself and starve, blocking all coroutines calling requestObject()
     */
    suspend fun freeObject(obj : MultiplatformBuffer){
        //clear the buffer
        obj.reset()

        this.bufferChannel.send(obj)
    }
}
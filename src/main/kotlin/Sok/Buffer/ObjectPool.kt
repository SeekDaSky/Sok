package Sok.Buffer

import kotlinx.coroutines.experimental.channels.Channel

/**
 * In order to avoid Garbage collection pressure it is a common practice to pre-allocate objects that are known to be long-living
 * for a later use, Kotlin channels give us a great way to implement a really straightforward object pool. The only issues with that implementation
 * are that the pool have a fixed size and that an object MUST be passed to the method freeObject() or else the pool will starve
 */
open class ObjectPool<T> {

    private val bufferChannel : Channel<T>

    constructor(maxSize : Int, initializer : (Int) -> T){

        this.bufferChannel = Channel(maxSize)

        (1..maxSize).forEach{
            this.bufferChannel.offer(initializer(it))
        }

    }

    /**
     * Fetch an object from the pool. The method is suspending in case of starvation
     */
    suspend fun requestObject() : T{
        return this.bufferChannel.receive()
    }

    /**
     * Free the object in order to let another coroutine have it. If you forget to call this method the pool will empty
     * itself and starve, blocking all coroutines calling requestObject()
     */
    suspend fun freeObject(obj : T){
        //clear the buffer in case it has not been
        this.bufferChannel.send(obj)
    }
}
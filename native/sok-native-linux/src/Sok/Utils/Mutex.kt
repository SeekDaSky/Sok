package Sok.Utils

import kotlinx.cinterop.*
import platform.posix.*

class Mutex{
    private val mutex = nativeHeap.allocArray<pthread_mutex_t>(1)

    init {
        pthread_mutex_init(this.mutex,null)
    }

    fun withLock(operation : () -> Unit){
        pthread_mutex_lock(this.mutex)
        operation.invoke()
        pthread_mutex_unlock(this.mutex)
    }

    fun destroy(){
        nativeHeap.free(this.mutex)
    }
}
package Sok.Selector

import platform.posix.*
import kotlinx.atomicfu.atomic
import kotlin.experimental.or
import kotlin.coroutines.experimental.*
import konan.worker.ensureNeverFrozen

class SelectionKey(val socket : Int,
                   val selector : Selector){
    private val readRegistered = atomic(false)
    private val writeRegistered = atomic(false)

    private val isClosed = atomic(false)

    @Volatile
    var OP_WRITE : Continuation<Boolean>? = null
    @Volatile
    var alwaysSelectWrite : SelectAlways? = null

    @Volatile
    var OP_READ : Continuation<Boolean>? = null
    @Volatile
    var alwaysSelectRead : SelectAlways? = null

    fun getPollEvents() : Short{
        require(!this.isClosed.value)

        var e : Short = 0
        if(readRegistered.value){
            e = e.or(POLLIN.toShort())
        }
        if(writeRegistered.value){
            e = e.or(POLLOUT.toShort())
        }

        return e
    }

    suspend fun select(interests: Interests){
        require(!this.isClosed.value)

        suspendCoroutine<Boolean>{
            when(interests){
                Interests.OP_READ -> {
                    this.OP_READ = it
                    this.readRegistered.value = true
                }
                Interests.OP_WRITE ->{
                    this.OP_WRITE = it
                    this.writeRegistered.value = true
                }
            }

        }
    }

    suspend fun selectAlways(interests: Interests, operation : () -> Boolean){
        require(!this.isClosed.value)

        suspendCoroutine<Boolean>{
            when(interests){
                Interests.OP_READ -> {
                    this.OP_READ = it
                    this.alwaysSelectRead = SelectAlways(operation)
                    this.readRegistered.value = true
                }
                Interests.OP_WRITE ->{
                    this.OP_WRITE = it
                    this.alwaysSelectWrite = SelectAlways(operation)
                    this.writeRegistered.value = true
                }
            }

        }
    }

    /**
     * Called ONLY by the selector to update the selection state coherently
     */
    fun unsafeUnregister(interests: Interests){
        when(interests){
            Interests.OP_READ ->{
                this.readRegistered.value = false
                this.alwaysSelectRead = null
                this.OP_READ = null
            }
            Interests.OP_WRITE ->{
                this.writeRegistered.value = false
                this.alwaysSelectWrite = null
                this.OP_WRITE = null
            }
        }
    }

    fun close(){
        if(this.isClosed.compareAndSet(false,true)){
            //close native socket
            close(this.socket)

            //resume all coroutines
            this.OP_WRITE?.resumeWithException(Exception("Socket was closed by an external caller"))
            this.OP_READ?.resumeWithException(Exception("Socket was closed by an external caller"))

            //unregister every interest
            this.unsafeUnregister(Interests.OP_WRITE)
            this.unsafeUnregister(Interests.OP_READ)

            //unregister from the selector
            this.selector.unregister(this)
        }

    }
}

enum class Interests(val interest : Short){
    OP_READ(POLLIN.toShort()),
    OP_WRITE(POLLOUT.toShort())
}
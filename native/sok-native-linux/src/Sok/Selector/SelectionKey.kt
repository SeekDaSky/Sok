package Sok.Selector

import platform.posix.*
import kotlinx.atomicfu.atomic
import kotlin.experimental.or
import kotlin.coroutines.*

/**
 * A `SelectionKey` is generated when a socket is registered to a selector. It is used to perform the suspention
 * needed by the Client/Server socket. The Selector class use it to get the Continuation and resume it while updating
 * the key interest accordingly
 *
 * @property socket file descriptor of the socket
 * @property selector selector handling the socket
 *
 * @constructor return a SelectionKey handling the socket
 */
internal class SelectionKey(val socket : Int,
                   val selector : Selector){

    //is a read event registred
    private val readRegistered = atomic(false)
    //is a write event registred
    private val writeRegistered = atomic(false)

    //is the key closed
    private val isClosed = atomic(false)

    //continuation to resume when a write event comes
    var OP_WRITE : Continuation<Boolean>? = null
    //object containing the operation to call in case of a SelectAlways
    var alwaysSelectWrite : SelectAlways? = null

    //continuation to resume when a read event comes
    var OP_READ : Continuation<Boolean>? = null
    //object containing the operation to call in case of a SelectAlways
    var alwaysSelectRead : SelectAlways? = null

    /**
     * Return the events that the `SelectionKey` is interested in as defined in [poll.h](https://github.com/torvalds/linux/blob/master/include/uapi/asm-generic/poll.h)
     *
     * @return short value representing the key interests
     */
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

    /**
     * Called by a socket to register an event once and suspend until it happens
     *
     * @param interests interest the socket want to register
     */
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

    /**
     * Called by a socket to register an event an undefined number of time and suspend until it happens
     *
     * @param interests interest the socket want to register
     * @param operation lambda to call when an event is detected
     */
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
     * Called ONLY by the `Selector` to update the selection state coherently
     *
     * @param interests Interest to unregister
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

    /**
     * Close the selection key, cancelling every suspention registered and unregistering from the selector
     */
    fun close(){
        if(this.isClosed.compareAndSet(false,true)){
            //resume all coroutines
            this.OP_WRITE?.resumeWithException(Exception("Socket was closed by an external caller"))
            this.OP_READ?.resumeWithException(Exception("Socket was closed by an external caller"))

            //unregister every interest
            this.unsafeUnregister(Interests.OP_WRITE)
            this.unsafeUnregister(Interests.OP_READ)

            //unregister from the selector
            this.selector.unregister(this)

            //close native socket
            close(this.socket)
        }

    }
}

/**
 * Enum representing the possible interests of a `SelectionKey`
 *
 * @property interest raw interest value defined in poll.h
 */
enum class Interests(val interest : Short){
    /**
     * Equivalent to linux POLLIN event, fired if there is data to read or a socket to accept
     */
    OP_READ(POLLIN.toShort()),

    /**
     * Equivalent to linux POLLOUT event, fired when the socket is readable
     */
    OP_WRITE(POLLOUT.toShort())
}
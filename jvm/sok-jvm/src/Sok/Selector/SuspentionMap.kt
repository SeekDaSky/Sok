package Sok.Selector

import Sok.Exceptions.PeerClosedException
import Sok.Exceptions.handleException
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import java.lang.IllegalArgumentException
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import kotlin.Exception

/**
 * A SuspentionMap will be used by the sockets to simply and efficiently use the Selector class.
 *
 * The map CAN ONLY ADD interests, the selector will take care of the un-register operations (using
 * the unsafeUnregister method). Every field is volatile (or immutable) because the object bounce from
 * thread to thread (socket/selector).
 *
 * In order to reduc the number of registrations, the SuspentionMap allow the registering of interest
 * for an undefined number of time, those registrations are represented with the `SelectAlways` class.
 * This kind of registration allow the developer to register a lambda to be executed by the `Selector` when
 * an event comes, the lambda will return whether the interest should be unregistered.
 *
 * @property selector `Selector` managing this suspention map
 * @property channel NIO channel registered
 */
internal class SuspentionMap(
        private val selector : Selector,

        private val channel : SelectableChannel,

        internal val exceptionHandler: CoroutineExceptionHandler
){
    private val isClosed = atomic(false)

    val interest = atomic(0)

    @Volatile
    var OP_WRITE : CancellableContinuation<Boolean>? = null
    @Volatile
    var alwaysSelectWrite : SelectAlways? = null

    @Volatile
    var OP_READ : CancellableContinuation<Boolean>? = null
    @Volatile
    var alwaysSelectRead : SelectAlways? = null

    @Volatile
    var OP_ACCEPT : CancellableContinuation<Boolean>? = null
    @Volatile
    var alwaysSelectAccept : SelectAlways? = null

    @Volatile
    var OP_CONNECT : CancellableContinuation<Boolean>? = null
    @Volatile
    var alwaysSelectConnect : SelectAlways? = null

    val selectionKey = this.selector.register(this@SuspentionMap.channel,0,this@SuspentionMap)

    /**
     * Register an interest for one selection
     */
    suspend fun selectOnce(interest: Int){
        require(this.interest.value.and(interest) != interest)

        this.interest.plusAssign(interest)

        this.suspend(interest)
    }

    /**
     * Register an interest for an undefined number of selection
     */
    suspend fun selectAlways(interest: Int, operation : () -> Boolean){
        require(this.interest.value.and(interest) != interest)

        this.interest.plusAssign(interest)

        val request = SelectAlways(operation)

        when(interest){
            SelectionKey.OP_READ -> this.alwaysSelectRead = request
            SelectionKey.OP_WRITE -> this.alwaysSelectWrite = request
            SelectionKey.OP_ACCEPT -> this.alwaysSelectAccept = request
            SelectionKey.OP_CONNECT -> this.alwaysSelectConnect = request
            else -> throw IllegalArgumentException("The interest is not valid")
        }

        this.suspend(interest)

    }

    /**
     * Wait for the selector to select our channel
     */
    private suspend fun suspend(interest: Int){

        //track if an exception was thrown during the registration coroutine
        var exc : Throwable? = null

        try {
            suspendCancellableCoroutine<Boolean> {
                when(interest){
                    SelectionKey.OP_READ -> this@SuspentionMap.OP_READ = it
                    SelectionKey.OP_WRITE -> this@SuspentionMap.OP_WRITE = it
                    SelectionKey.OP_ACCEPT -> this@SuspentionMap.OP_ACCEPT = it
                    SelectionKey.OP_CONNECT -> this@SuspentionMap.OP_CONNECT = it
                    else -> throw IllegalArgumentException("The interest is not valid")
                }

                this.selector.updateInterest(this.selectionKey, this.interest.value)
            }
        }catch (e : Exception){
            exc = e
            this@SuspentionMap.exceptionHandler.handleException(e)
        }

        //if an exception was thrown, bring it back to the caller
        if(exc != null){
            throw exc!!
        }
    }

    /**
     * Method used ONLY by the Selector class to unregister interests before resuming the coroutine
     */
    fun unsafeUnregister(interest : Int){
        this.interest.minusAssign(interest)

        try {
            this.selectionKey.interestOps(this.interest.value)
        }catch (e : CancelledKeyException){
            //if the socket closed in the meantime
        }

        when(interest){
            SelectionKey.OP_READ -> {
                this.alwaysSelectRead = null
                this.OP_READ = null
            }
            SelectionKey.OP_WRITE -> {
                this.alwaysSelectWrite = null
                this.OP_WRITE = null
            }
            SelectionKey.OP_ACCEPT -> {
                this.alwaysSelectAccept = null
                this.OP_ACCEPT = null
            }
            SelectionKey.OP_CONNECT -> {
                this.alwaysSelectConnect = null
                this.OP_CONNECT = null
            }
            else -> throw IllegalArgumentException("The interest is not valid")
        }
    }

    /**
     * close the suspention map, thus cancelling any registered socket.
     *
     * @param exception exception given to the continuations when cancelling
     */
    fun close(exception : Throwable = PeerClosedException()){
        if(this.isClosed.compareAndSet(false,true)){
            this.selectionKey.cancel()

            this.OP_ACCEPT?.cancel(exception)
            this.OP_READ?.cancel(exception)
            this.OP_WRITE?.cancel(exception)
            this.OP_CONNECT?.cancel(exception)

            //as they run on the selector scope, wakeup the selector to let the continuation cancel and notify the selection key change
            this.selector.wakeup()
        }
    }
}
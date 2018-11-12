package Sok.Selector

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.lang.IllegalArgumentException
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey

/**
 * A SuspentionMap will be used by the sockets to simply and efficiently use NIO Selectors.
 *
 * The suspention/resumption is made basic coroutine suspention.
 * The map CAN ONLY ADD interests, the selector will take care of the un-register operations (using
 * the unsafeUnregister method). Every field is volatile (or immutable) because the object bounce from
 * thread to thread (socket/selector).
 *
 * As the registering of an interest for an undefined number of selection is supported, the SuspentionMap
 * also contains those ongoing requests. For details about the mechanism, please look at the Selector class
 * comments.
 *
 * @property selector `Selector` managing this suspention map
 * @property channel NIO channel registered
 */
internal class SuspentionMap(
        private val selector : Selector,

        private val channel : SelectableChannel,

        internal val exceptionHandler: CoroutineExceptionHandler
){

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

    val selectionKey: SelectionKey

    init {
        this.selectionKey = runBlocking(Dispatchers.Unconfined+this.exceptionHandler) {
            this@SuspentionMap.selector.register(this@SuspentionMap.channel,0,this@SuspentionMap)
        }
    }

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

        val job = this.selector.coroutineScope.launch(this.exceptionHandler) {
            this@SuspentionMap.selectionKey.interestOps(this@SuspentionMap.interest.value)

            suspendCancellableCoroutine<Boolean> {
                try {
                    when(interest){
                        SelectionKey.OP_READ -> this@SuspentionMap.OP_READ = it
                        SelectionKey.OP_WRITE -> this@SuspentionMap.OP_WRITE = it
                        SelectionKey.OP_ACCEPT -> this@SuspentionMap.OP_ACCEPT = it
                        SelectionKey.OP_CONNECT -> this@SuspentionMap.OP_CONNECT = it
                        else -> throw IllegalArgumentException("The interest is not valid")
                    }
                }catch (e : ClosedReceiveChannelException){
                    //the suspention map was closed
                }
            }
        }

        if(this.selector.isInSelection){
            this.selector.wakeup()
        }
        job.join()
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
            SelectionKey.OP_READ -> this.alwaysSelectRead = null
            SelectionKey.OP_WRITE -> this.alwaysSelectWrite = null
            SelectionKey.OP_ACCEPT -> this.alwaysSelectAccept = null
            SelectionKey.OP_CONNECT -> this.alwaysSelectConnect = null
            else -> throw IllegalArgumentException("The interest is not valid")
        }
    }

    /**
     * close the suspention map, thus cancelling any registered socket
     */
    fun close(){
        this.selectionKey.cancel()

        this.OP_ACCEPT?.cancel()
        this.OP_READ?.cancel()
        this.OP_WRITE?.cancel()
        this.OP_CONNECT?.cancel()

    }
}
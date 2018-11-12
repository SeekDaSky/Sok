package Sok.Selector

import Sok.Exceptions.handleException
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Class wrapping the NIO Selector class for a more "coroutine-friendly" approach. Each `SelectableCHannel` will have a `SuspentionMap` as
 * attachment, this map contains all teh Continuations/Lambda to resume/call when an event come in.
 *
 * Sockets register quite frequently and as the NIO Selector class blocks any registration while selecting we have to implement a way to
 * synchronize registrations and the Selector class. This is done by sharing the `CoroutineScope` of the Selector to the `SuspentionMap`
 * classes, this way the `SuspentionMap` is able to launch coroutine on the Selector Thread, thus ensuring mutual-exclusion.
 *
 * To reduce the number of registration, a socket may register for a unknown number of event. If so the selector will call a lambda after
 * each event and this lambda will return if the Selector should keep the registration or not. This method slow down the selector but
 * the performance gain is worth it.
 *
 * @property selector NIO selector
 * @property numberOfChannel number fo channel registered to this selector, used for load balancing
 * @property _isClosed Atomic backing field of isClosed
 * @property isClosed is the Selector still running
 * @property isInSelection is the selector in selection
 * @property thread `SingleThreadExecutor` used as a `CoroutineDispatcher` to run the selection/registration operations
 * @property coroutineScope scope given to the `SuspentionMap` classes to run registration coroutine on the right thread
 * @property mainLoop main selection loop
 *
 * @constructor Initialize a selector and launch its main selection loop
 */
class Selector {

    /**
     * Contain the default selector or selector pool used by all the sockets. A `Client` socket will try to use a pool, if no pool
     * are initialized it will fallback to a single default selector to avoid the allocation of all the selectors/threads of a pool.
     * A `Server` socket will always use a pool and initialise it if needed
     */
    companion object {
        /**
         * is the selector pool initialized.
         */
        var isSelectorPoolInit = false

        /**
         * selector pool
         */
        val defaultSelectorPool by lazy {
            isSelectorPoolInit = true
            SelectorPool(Runtime.getRuntime().availableProcessors())
        }

        /**
         * selector
         */
        val defaultSelector by lazy { Selector() }
    }

    private val selector = Selector.open()

    @Volatile
    var numberOfChannel = 0
        private set

    private val _isClosed : AtomicRef<Boolean> =  atomic<Boolean>(false)

    var isClosed : Boolean
        private set(value){
            this._isClosed.value = value
        }
        get() = this._isClosed.value

    @Volatile
    var isInSelection = true
        private set

    private val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    val coroutineScope = CoroutineScope(this.thread)

    private val mainLoop : Job

    init{
        this.mainLoop = this.coroutineScope.launch{
            loop()
        }
    }

    /**
     * The main loop is the core of the Selector. It will perform the selection actions, resume the coroutines or execute the operation lambda then will yield in order
     * to let possible registration coroutines run before the next selection cycle
     *
     * For performances purposes It is possible to register an interest for an undefined number of selection. In this case the "alwaysSelectXXX" properties of the
     * SuspentionMap are used. In order to guaranty the atomicity of those types of selection an operation is registered and the selector will execute this lambda
     * synchronously before continuing its loop. Because of that the registered operation NEEDS to be non-suspending and non blocking as well as being not
     * computation-intensive. The operation will return true if the selector need to select again and will return false when the selector need to unregister the
     * interest and resume the coroutine.
     */
    private suspend fun loop(){
        while (!this.isClosed){
            //as the loop have its own thread, we can block it with endless selection without worry
            this.selector.select()

            //update state
            this.isInSelection = false

            //if the selection returns, it means that we have movement on the channels or that the registering actor woke the selector up
            for(it in this.selector.selectedKeys()) {
                //get the suspention map
                val suspentionMap = it.attachment() as SuspentionMap
                //catch any exception to send it back to the exception handler of the Socket
                try {
                    /*
                    The following blocks all have the same pattern thus only the first one will be commented

                    The SuspentionMap implementation guaranty that the continuation will not be null and we need to unregister the interest BEFORE
                    resuming the coroutine to avoid state incoherence
                     */
                    if (it.isValid && it.isReadable) {
                        //check if there is an ongoing unlimited selection request
                        if(suspentionMap.alwaysSelectRead == null) {
                            //if not, unregister then resume the coroutine
                            suspentionMap.unsafeUnregister(SelectionKey.OP_READ)
                            suspentionMap.OP_READ!!.resume(true)
                        }else {
                            val request = suspentionMap.alwaysSelectRead!!
                            //if the operation returns false, we can unregister
                            if (!request.operation.invoke()) {
                                suspentionMap.unsafeUnregister(SelectionKey.OP_READ)
                                suspentionMap.OP_READ!!.resume(true)
                            }
                        }
                    }

                    if (it.isValid && it.isWritable) {
                        if(suspentionMap.alwaysSelectWrite == null) {
                            suspentionMap.unsafeUnregister(SelectionKey.OP_WRITE)
                            suspentionMap.OP_WRITE!!.resume(true)
                        }else {
                            val request = suspentionMap.alwaysSelectWrite!!
                            if (!request.operation.invoke()) {
                                //same as a SelectOnce request
                                suspentionMap.unsafeUnregister(SelectionKey.OP_WRITE)
                                suspentionMap.OP_WRITE!!.resume(true)
                            }
                        }
                    }
                    if (it.isValid && it.isAcceptable) {
                        if(suspentionMap.alwaysSelectAccept == null) {
                            suspentionMap.unsafeUnregister(SelectionKey.OP_ACCEPT)
                            suspentionMap.OP_ACCEPT!!.resume(true)
                        }else {
                            val request = suspentionMap.alwaysSelectAccept!!
                            if (!request.operation.invoke()) {
                                //same as a SelectOnce request
                                suspentionMap.unsafeUnregister(SelectionKey.OP_ACCEPT)
                                suspentionMap.OP_ACCEPT!!.resume(true)
                            }
                        }
                    }
                    if (it.isValid && it.isConnectable) {
                        if(suspentionMap.alwaysSelectConnect == null) {
                            suspentionMap.unsafeUnregister(SelectionKey.OP_CONNECT)
                            suspentionMap.OP_CONNECT!!.resume(true)
                        }else {
                            val request = suspentionMap.alwaysSelectConnect!!
                            if (!request.operation.invoke()) {
                                //same as a SelectOnce request
                                suspentionMap.unsafeUnregister(SelectionKey.OP_CONNECT)
                                suspentionMap.OP_CONNECT!!.resume(true)
                            }
                        }
                    } else {
                        continue
                    }
                }catch (e : Exception){
                    suspentionMap.exceptionHandler.handleException(e)
                }

            }

            //clear keys
            this.selector.selectedKeys().clear()

            //update state before yielding to avoid state incoherence
            this.isInSelection = true

            //yield to let registrations coroutine run
            yield()

            //update the number of socket registered
            this.numberOfChannel = this.selector.keys().size
        }
    }

    /**
     * Wake the NIO selector up, thus making registrations non-blocking
     */
    fun wakeup(){
        this.selector.wakeup()
    }

    /**
     * Register a `SelectableChannel` the NIO `Selector` and bind the attachment (`SuspentionMap`)
     *
     * @param channel Channel to register
     * @param interest Initial interest (0)
     * @param attachment `SuspentionMap` attached to the channel
     *
     * @return NIO SelectionKey used by the SuspentionMap for registrations
     */
    suspend fun register(channel : SelectableChannel, interest : Int, attachment : Any?) : SelectionKey{
        require(attachment is SuspentionMap)
        val def = this.coroutineScope.async {
            channel.register(this@Selector.selector,interest,attachment)
        }
        this.selector.wakeup()
        return def.await()
    }

    /**
     * Close the selector, close all SuspentionMap, cancel the main loop and close the thread
     */
    fun close(){
        if(this._isClosed.compareAndSet(false,true)){
            this.selector.keys().forEach {
                (it.attachment() as SuspentionMap).close()
            }
            this.wakeup()
            this.mainLoop.cancel()
            this.thread.close()
        }
    }

}

internal class SelectAlways(val operation : () -> Boolean)

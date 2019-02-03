package Sok.Selector

import Sok.Exceptions.PeerClosedException
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.coroutines.*
import kotlin.math.min

/**
 *
 * The Sok Selector class helps to go from a crappy blocking NIO Selector interface to a nice coroutine based interface.
 * The Selector will take care of registrations to the underlying NIO Selector in a non-blocking and non-suspending way
 * in order to have the best performances possible. In order to do that (and because the NIO Selector is blocking) the
 * Selector have a single thread executor to which we will give "ticks" tasks, when a socket wants to register we will
 * pause the ticking, register and send a resume task to the executor, if the registration task is fast enough the ticking
 * will not be paused.
 *
 * The Selector uses the SuspentionMap class to get and resume the suspention or execute the callback. There is two kinds
 * of operation for the SuspentionMap, resume the continuation and unregister the interest directly (for single read operations)
 * or execute a callback that will return whether or not we should unregister. The goal of those two "modes" is to reduce the
 * number of (expensive) interest update operations. The counterpart is that the Selector now execute user-code which can
 * greatly slow down the ticking rate, the developer should be careful.

 * @property numberOfChannel number fo channel registered to this selector, used for load balancing
 * @property isClosed is the Selector still running
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
         * default selector pool
         */
        val defaultSelectorPool by lazy {
            isSelectorPoolInit = true
            SelectorPool(min(1,Runtime.getRuntime().availableProcessors()/2))
        }

        /**
         * default selector
         */
        val defaultSelector by lazy { Selector() }
    }

    /**
     * NIO selector
     */
    private val selector = Selector.open()

    /**
     * Used for load balancing by the SelectorPool
     */
    @Volatile
    var numberOfChannel = 0
        private set

    /**
     * Backing property of the selector state
     */
    private val _isClosed : AtomicRef<Boolean> =  atomic<Boolean>(false)

    var isClosed : Boolean
        private set(value){
            this._isClosed.value = value
        }
        get() = this._isClosed.value

    /**
     * If the selector is in selection, there is a good chance that any registering/update operation will block
     * this property is used to know when we should wakeup the selector before register
     */
    private val isInSelection = atomic(true)

    /**
     * When someone wants to register, it set the shouldPause atomic to true and wakeup the selector, there is a
     * chance that the registration is fast enough to set the shouldPause property to false while the selector is
     * finishing the tick, allowing it to continue without pause.
     */
    private val shouldPause = atomic(false)

    /**
     * Atomic keeping track of the number of socket in the process of registering/updating, it is used to reduce
     * the number of call the pauseLoop() and resumeLoop()
     */
    private val inRegister = atomic(0)

    /**
     * Executor
     */
    private val thread = Executors.newSingleThreadExecutor()

    /**
     * Future representing the last tick of the Selector
     */
    private var tickFuture : Future<*>


    init{
        this.tickFuture = this.thread.submit {
            this.nextTick()
        }
    }

    /**
     * Schedule the next Selector tick
     */
    private fun scheduleNextTick(){
        this.tickFuture = this.thread.submit {
            try {
                this.nextTick()
            }catch (e : Exception){
                //e.printStackTrace()
            }
        }
    }


    /**
     * Main method of the class, it will do a select operation and process the events
     */
    private fun nextTick(){
        /**
         * We should check the shouldPause property here in case a socket came to register while the tick task was submitted
         */
        if(!this.shouldPause.value){
            this.selector.select()
        }else{
            this.selector.selectNow()
        }

        //update state
        this.isInSelection.value = false

        //create the sync barrier, this allows all the "selectAlways" requests to be dispatched in the main coroutine dispatcher
        //and synchronize with their completion efficiently
        val barrier = CountDownLatch(this.selector.selectedKeys().size)

        for(it in this.selector.selectedKeys()) {
            //get the suspention map
            val suspentionMap = it.attachment() as SuspentionMap
            /*
            The following blocks all have the same pattern thus only the first one will be commented

            The SuspentionMap implementation guaranty that the continuation will not be null and we need to unregister the interest BEFORE
            resuming the coroutine to avoid state incoherence
             */
            if (it.isValid && it.isReadable) {
                val cont = suspentionMap.OP_READ!!
                //check if there is an ongoing unlimited selection request
                if(suspentionMap.alwaysSelectRead == null) {
                    //if not, unregister then resume the coroutine
                    suspentionMap.unsafeUnregister(SelectionKey.OP_READ)
                    cont.resume(true)

                    //notify completion (useless in the case of selectOnce requests but mandatory)
                    barrier.countDown()
                }else {
                    Dispatchers.Default.dispatch(EmptyCoroutineContext, Runnable {
                        try{
                            val request = suspentionMap.alwaysSelectRead!!
                            //if the operation returns false, we can unregister
                            if (!request.operation()) {
                                suspentionMap.unsafeUnregister(SelectionKey.OP_READ)
                                cont.resume(true)
                            }
                        }catch (e : Exception){
                            //propagate the exception
                            suspentionMap.unsafeUnregister(SelectionKey.OP_READ)
                            cont.resumeWithException(e)
                        }finally {
                            //notify completion
                            barrier.countDown()
                        }
                    })
                }
            }

            if (it.isValid && it.isWritable) {
                val cont = suspentionMap.OP_WRITE!!
                if(suspentionMap.alwaysSelectWrite == null) {
                    suspentionMap.unsafeUnregister(SelectionKey.OP_WRITE)
                    cont.resume(true)

                    barrier.countDown()
                }else {
                    Dispatchers.Default.dispatch(EmptyCoroutineContext, Runnable {
                        try {
                            val request = suspentionMap.alwaysSelectWrite!!
                            if (!request.operation()) {
                                suspentionMap.unsafeUnregister(SelectionKey.OP_WRITE)
                                cont.resume(true)
                            }
                        }catch (e : Exception){
                            suspentionMap.unsafeUnregister(SelectionKey.OP_WRITE)
                            cont.resumeWithException(e)
                        }finally {
                            barrier.countDown()
                        }

                    })
                }
            }
            if (it.isValid && it.isAcceptable) {
                val cont = suspentionMap.OP_ACCEPT!!
                if(suspentionMap.alwaysSelectAccept == null) {
                    suspentionMap.unsafeUnregister(SelectionKey.OP_ACCEPT)
                    cont.resume(true)

                    barrier.countDown()
                }else {
                    Dispatchers.Default.dispatch(EmptyCoroutineContext, Runnable {
                        try {
                            val request = suspentionMap.alwaysSelectAccept!!
                            if (!request.operation()) {
                                suspentionMap.unsafeUnregister(SelectionKey.OP_ACCEPT)
                                cont.resume(true)
                            }
                        }catch (e : Exception){
                            suspentionMap.unsafeUnregister(SelectionKey.OP_ACCEPT)
                            cont.resumeWithException(e)
                        }finally {
                            barrier.countDown()
                        }
                    })
                }
            }
            if (it.isValid && it.isConnectable) {
                val cont = suspentionMap.OP_CONNECT!!
                if(suspentionMap.alwaysSelectConnect == null) {
                    suspentionMap.unsafeUnregister(SelectionKey.OP_CONNECT)
                    cont.resume(true)

                    barrier.countDown()
                }else {
                    Dispatchers.Default.dispatch(EmptyCoroutineContext, Runnable {
                        try {
                            val request = suspentionMap.alwaysSelectConnect!!
                            if (!request.operation()) {
                                suspentionMap.unsafeUnregister(SelectionKey.OP_CONNECT)
                                cont.resume(true)
                            }
                        }catch (e : Exception){
                            suspentionMap.unsafeUnregister(SelectionKey.OP_CONNECT)
                            cont.resumeWithException(e)
                        }finally {
                            barrier.countDown()
                        }
                    })
                }
            }

        }

        //clear keys
        this.selector.selectedKeys().clear()

        //await for everything to complete
        barrier.await()

        //update the number of socket registered (for load balancing purposes)
        this.numberOfChannel = this.selector.keys().size

        //If no pause are required and that the selector is still open, schedule next tick
        if(!this.shouldPause.value && !this.isClosed){
            this.isInSelection.value = true

            this.scheduleNextTick()
        }
    }

    /**
     * Wake the NIO selector up if needed to allow registrations
     */
    fun wakeup(){
        if(this.isInSelection.compareAndSet(true,false)) {
            this.selector.wakeup()
        }
    }

    /**
     * Set the shouldPause property and wake the selector
     */
    private fun pauseLoop(){
        this.shouldPause.value = true
        this.wakeup()
    }

    /**
     * Set all the properties back to normal and try to schedule the next tick if it wasn't already. We must send the
     * tick scheduling to the executor to acheive mutual exclusion between the possibly ongoing tick that could pause
     * or not and the resumeLoop() call
     */
    private fun resumeLoop(){
        this.isInSelection.value = true
        this.shouldPause.value = false

        this.thread.submit {
            if(this.tickFuture.isDone){
                this.scheduleNextTick()
            }
        }
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
    fun register(channel : SelectableChannel, interest : Int, attachment : Any?) : SelectionKey{
        require(attachment is SuspentionMap)

        if(this.inRegister.getAndIncrement() == 0){
            this.pauseLoop()
        }

        val sk = channel.register(this@Selector.selector,interest,attachment)

        if(this.inRegister.decrementAndGet() == 0){
            this.resumeLoop()
        }

        return sk
    }

    /**
     * Update the interest of a `SelectionKey` with the given `interest`
     *
     * @param sk SelectionKey to update
     * @param interest Interest to set
     */
    fun updateInterest(sk : SelectionKey, interest: Int){

        if(this.inRegister.getAndIncrement() == 0){
            this.pauseLoop()
        }

        try {
            sk.interestOps(interest)
        }catch (e : CancelledKeyException){
            throw PeerClosedException()
        }finally {
            if(this.inRegister.decrementAndGet() == 0){
                this.resumeLoop()
            }
        }


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
            this.thread.shutdownNow()
        }
    }

}

internal class SelectAlways(val operation : () -> Boolean)

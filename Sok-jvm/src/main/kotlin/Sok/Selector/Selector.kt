package Sok.Selector

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

class Selector {

    //default Selector
    companion object {
        val defaultSelector by lazy { Selector() }
    }

    //NIO selector
    private val selector = Selector.open()

    //number of channel the selector is managing
    @Volatile
    private var numberOfChannel = 0

    //registering actor
    private val registeringChannel = Channel<RegisteringRequest>(Channel.UNLIMITED)

    //selector states, the public property is backed by the atomic _isClosed
    private val _isClosed : AtomicRef<Boolean> =  atomic<Boolean>(false)

    var isClosed
        private set(value){
            this._isClosed.value = value
        }
        get() = this._isClosed.value

    @Volatile
    private var isInSelection = true

    //selection thread and coroutine
    private val thread = newSingleThreadContext("SelectorThread")
    private val coroutineScope = CoroutineScope(this.thread)
    private val mainLoop : Job

    //rendez-vous channel between the registering actor and the selection loop
    private val waitChannel = Channel<CompletableDeferred<Boolean>>(0)

    //launch the selection loop and the registering actor
    init{
            this.mainLoop = this.coroutineScope.launch{
                loop()
            }
    }

    /**
     * The main loop is the core of the Selector. It will perform the selection actions, update the interests in case of selection and resume the selecting coroutines
     * using the SuspentionMap attached to the channel.
     *
     * For performances purposes It is possible to register an interest for an undefined number of selection. In this case the "alwaysSelectXXX" properties of the
     * SuspentionMap are used. In order to guaranty the atomicity of those types of selection an operation is registered and the selector will wait for this
     * operation to complete before resuming its loop. Because of that the registered operation NEEDS to be non-suspending and non blocking as well as being not
     * computation-intensive. The operation will return true if the selector need to select again and will return false when the selector need to unregister the
     * interest and resume the coroutine. This mechanism can lead to a slow down of the Selector loop rate, thus needing an extended SelectorPool.
     *
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

                /*
                The following blocks all have the same pattern thus only the first one will be commented

                The SuspentionMap implementation guaranty that the continuation will not be null and we need to unregister the interest BEFORE
                resuming the coroutine to avoid state incoherence
                 */
                if (it.isReadable) {
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

                if (it.isWritable) {
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
                if (it.isAcceptable) {
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
                if (it.isConnectable) {
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
            }

            //clear keys
            this.selector.selectedKeys().clear()

            //update state before iterating through the channel for state coherence purposes
            this.isInSelection = true

            //check the registering channel for new requests
            while(!this.registeringChannel.isEmpty){
                val msg = this.registeringChannel.receive()
                when(msg){

                    //new channels must be registered once to build the suspention map
                    is NewRegisteringRequest -> {
                        msg.deferred.complete(msg.channel.register(this@Selector.selector,0,msg.suspentionMap))
                    }

                    //use the suspentionMap to update the selectionKey
                    is ExistingRegisteringRequest -> {
                        if(msg.suspentionMap.selectionKey.interestOps() != msg.suspentionMap.interest.value){
                            msg.suspentionMap.selectionKey.interestOps(msg.suspentionMap.interest.value)
                        }
                    }
                }
            }

            //update the number of socket registered
            this.numberOfChannel = this.selector.keys().size
        }
    }

    fun close(){
        if(this._isClosed.compareAndSet(false,true)){
            try {
                this.waitChannel.close()
            }catch (e : ClosedReceiveChannelException){
                //meh
            }
            this.mainLoop.cancel()
            this.thread.close()
        }
    }

    /**
     * this method is called by new SuspentionMaps that do not have a SelectionKey yet, it will block the calling thread while the selector
     * wakes up and compute the request
     */
    internal fun register(channel: SelectableChannel,suspentionMap: SuspentionMap) : SelectionKey = runBlocking(Dispatchers.IO) {
        val deferred = CompletableDeferred<SelectionKey>()
        this@Selector.registeringChannel.send(NewRegisteringRequest(channel,deferred,suspentionMap))

        //if the selector is blocked in a selection, wake it up
        if(isInSelection){
            selector.wakeup()
        }

        deferred.await()
    }

    /**
     * used by already registered SuspentionMaps that only want to update their interests
     */
    internal  fun register(suspentionMap: SuspentionMap){
        this@Selector.registeringChannel.offer(ExistingRegisteringRequest(suspentionMap))

        //if the selector is blocked in a selection, wake it up
        if(isInSelection){
            selector.wakeup()
        }
    }

    /**
     * used by the SelectorPool to order them
     */
    fun numberOfChannel() : Int{
        return this.numberOfChannel
    }
}

/**
 * classes related to the registering actor requests
 */
internal sealed class RegisteringRequest
internal class NewRegisteringRequest(val channel: SelectableChannel, val deferred: CompletableDeferred<SelectionKey>, val suspentionMap: SuspentionMap) : RegisteringRequest()
internal class ExistingRegisteringRequest(val suspentionMap: SuspentionMap) : RegisteringRequest()

class SelectAlways(val operation : () -> Boolean)

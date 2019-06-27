package Sok.Selector

import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import platform.posix.*
import kotlinx.cinterop.*
import kotlin.experimental.and
import kotlinx.coroutines.*
import kotlin.coroutines.*
import Sok.Exceptions.*
import platform.linux.*

/**
 * As Companion objects are frozen by default on K/N, we have to move the actual data
 * outside of the Companion so we don't get exception when lazily getting the selectors
 * at runtime
 */
private val _defaultSelector : AtomicRef<Selector?> = atomic(null)
private val _defaultScope : AtomicRef<CoroutineScope> = atomic(GlobalScope)

/**
 * Class acting as a Java NIO Selector (with less features/limitations). The class will perform the poll() calls, keep track of the registered
 * sockets and resume/call the continuations/lambda
 *
 * Like the Sok-JVM Selector class, a socket may register for a unknown number of event. If so the selector will call a lambda after
 * each event and this lambda will return if the Selector should keep the registration or not. But as the Sok-native Selector class
 * is a lot more simple than the Java NIO one, this feature doesn't give as much of a performances gain as Sok-JVM.
 *
 * As Kotlinx.coroutines does not support multithreading right now, the Whole Sok-Native library is single threaded and running on the same
 * event loop, simplifying mutual-exclusion but also forcing the selector to timeout to let other coroutines run. In such configuration a
 * Selector Pool is useless.
 *
 * @property scope Scope the selector is running on
 * @property isClosed state of the selector
 * @property isInSelection is the selector in a selection loop
 */
class Selector private constructor() {

    companion object {
        /**
         * Default selector, lazily created, used by the whole library.
         */
        public val defaultSelector : Selector
            get() {
                _defaultSelector.compareAndSet(null,Selector())
                return _defaultSelector.value!!
            }

        /**
         * Bind the Selector to a particular CoroutineScope, mainly used in order to prevent the
         * event loop from exiting while te selector is still running
         *
         * exemple:
         * ```kotlin
         *
         * runBlocking(){
         *      Selector.setDefaultScope(this)
         *      //the runBlocking statement will now not return until the Selector.closeSelectorAndWait() method is called
         * }
         * ```
         */
        fun setDefaultScope(scope : CoroutineScope){
            _defaultScope.value = scope
        }

        /**
         * Close the selector
         */
        suspend fun closeSelectorAndWait(){
            _defaultSelector.value?.close()
            _defaultSelector.value = null
        }
    }

    private val _isClosed: AtomicBoolean = atomic(false)

    var isClosed : Boolean
        get() {
            return this._isClosed.value
        }
        private set(value) {
            this._isClosed.value = value
        }

    private val isStarted: AtomicBoolean = atomic(false)

    private val _isInSelection: AtomicBoolean = atomic(false)

    var isInSelection : Boolean
        get() {
            return this._isInSelection.value
        }
        private set(value) {
            this._isInSelection.value = value
        }


    private val NBR_SOCKET_PER_TICK = 64;
    private val epollStruct = nativeHeap.allocArray<epoll_event>(NBR_SOCKET_PER_TICK)
    private var epollFD = 0
    private val selectionKeys = mutableMapOf<Int,SelectionKey>()

    private val sleepContinuation: AtomicRef<CancellableContinuation<Unit>?> = atomic(null)

    private val internalExceptionHandler = CoroutineExceptionHandler{_,_ ->
        this.isStarted.value = false
        this.start()
    }

    private var loop: Job? = null

    private fun start() {
        if(this.isStarted.compareAndSet(false,true)){
            this.epollFD = epoll_create1(0);
            this.loop = _defaultScope.value.launch(this.internalExceptionHandler) {
                while (!this@Selector.isClosed) {

                    if(!this@Selector.selectionKeys.isEmpty()){
                        this@Selector.select(1)
                        //allow continuations to execute
                        yield()
                    }else{
                        suspendCancellableCoroutine<Unit>{
                            this@Selector.sleepContinuation.value = it
                        }
                    }
                }
            }
        }
    }

    /**
     * register a socket to the selector and return the created selection key
     */
    internal fun register(socket: Int): SelectionKey {
        require(!this.isClosed)

        if(this.isStarted.value == false){
            this.start()
        }

        val sk = SelectionKey(socket, this)
        epoll_ctl(this.epollFD, EPOLL_CTL_ADD,socket,sk.epollEvent.ptr)
        this.selectionKeys[socket] = sk

        //this.registeredSockets.add(sk)
        val cont = this.sleepContinuation.getAndSet(null)
        if(cont != null){
            cont.resume(Unit)
        }
        return sk
    }

    internal fun notifyInterestUpdate(selectionKey : SelectionKey){
        selectionKey.epollEvent.events = selectionKey.getPollEvents()
        epoll_ctl(this.epollFD, EPOLL_CTL_MOD,selectionKey.socket,selectionKey.epollEvent.ptr)
    }

    /**
     * Unregister a sleection key from the selector
     */
    internal fun unregister(selectionKey : SelectionKey){
        if(!this.isClosed){
            epoll_ctl(this.epollFD, EPOLL_CTL_DEL,selectionKey.socket,null)
        }
    }

    /**
     * perform a blocking poll() call. Once the call is completed, check the result set:
     *  - Closed socket errors unregister the socket from the selector and are not dispatched
     *  - Wakeup file descriptor readable event are read and if its the only selected file descriptor, return an empty list
     */
    private fun select(timeout: Int) {
        if (!this._isInSelection.compareAndSet(false, true)) {
            throw Exception("Selector already in selection")
        }

        //perform call
        val result = epoll_wait(this.epollFD, this.epollStruct,this.NBR_SOCKET_PER_TICK,timeout)

        //update state
        this.isInSelection = false

        when (result) {
            -1 -> throw Exception("The poll call failed")
            0 -> return
        }

        //iterate through the registered sockets and
        for (i in (0 until result)) {
            val struct: epoll_event = this.epollStruct[i].reinterpret()
            val sk = this@Selector.selectionKeys[struct.data.fd]!!

            if (struct.events.and(EPOLLIN.toUInt()) == EPOLLIN.toUInt()) {
                val cont = sk.OP_READ!!
                if (sk.alwaysSelectRead == null) {
                    //if not, unregister then resume the coroutine
                    sk.unsafeUnregister(Interests.OP_READ)
                    cont.resume(true)
                } else {
                    val request = sk.alwaysSelectRead!!
                    //if the operation returns false, we can unregister
                    try {
                        if (!request.operation.invoke()) {
                            sk.unsafeUnregister(Interests.OP_READ)
                            cont.resume(true)
                        }
                    }catch (e : Exception){
                        sk.unsafeUnregister(Interests.OP_READ)
                        cont.resumeWithException(e)
                    }
                }
            }

            if (struct.events.and(EPOLLOUT.toUInt()) == EPOLLOUT.toUInt()) {
                val cont = sk.OP_WRITE!!
                if (sk.alwaysSelectWrite == null) {
                    //if not, unregister then resume the coroutine
                    sk.unsafeUnregister(Interests.OP_WRITE)
                    cont.resume(true)
                } else {
                    val request = sk.alwaysSelectWrite!!
                    //if the operation returns false, we can unregister
                    try {
                        if (!request.operation.invoke()) {
                            sk.unsafeUnregister(Interests.OP_WRITE)
                            cont.resume(true)
                        }
                    }catch (e : Exception){
                        sk.unsafeUnregister(Interests.OP_WRITE)
                        cont.resumeWithException(e)
                    }
                }
            }

            if (struct.events.and(EPOLLHUP.toUInt()) == EPOLLHUP.toUInt() || struct.events.and(EPOLLERR.toUInt()) == EPOLLERR.toUInt()) {
                sk.close(PeerClosedException())
            }
        }
    }

    suspend fun close() {
        if (this._isClosed.compareAndSet(false, true)) {
            this.selectionKeys.forEach{
                it.value.close()
            }
            this.sleepContinuation.value?.cancel()
            this.loop?.join()
            nativeHeap.free(this.epollStruct)
        }
    }

}

internal class SelectAlways(val operation : () -> Boolean)
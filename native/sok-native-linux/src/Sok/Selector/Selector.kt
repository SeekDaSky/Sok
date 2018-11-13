package Sok.Selector

import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import platform.posix.*
import kotlinx.cinterop.*
import kotlin.experimental.and
import kotlinx.coroutines.*
import kotlin.coroutines.*

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

    private var pollArrayStruct = nativeHeap.allocArray<pollfd>(1)
    private var allocatedStructs = 1

    private val registeredSockets = mutableListOf<SelectionKey>()

    private val sleepContinuation: AtomicRef<CancellableContinuation<Unit>?> = atomic(null)

    private val internalExceptionHandler = CoroutineExceptionHandler{_,_ ->
        this.isStarted.value = false
        this.start()
    }

    private var loop: Job? = null

    private fun start() {
        if(this.isStarted.compareAndSet(false,true)){
            this.loop = _defaultScope.value.launch(this.internalExceptionHandler) {
                while (!this@Selector.isClosed) {

                    if(this@Selector.registeredSockets.size != 0){
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
     * register a socket to the sleector and return the created selection key
     */
    internal fun register(socket: Int): SelectionKey {
        require(!this.isClosed)

        if(this.isStarted.value == false){
            this.start()
        }

        val sk = SelectionKey(socket, this)
        this.registeredSockets.add(sk)
        val cont = this.sleepContinuation.getAndSet(null)
        if(cont != null){
            cont.resume(Unit)
        }
        return sk
    }

    /**
     * Unregister a sleection key from the selector
     */
    internal fun unregister(selectionKey : SelectionKey){
        if(!this.isClosed){
            this.registeredSockets.remove(selectionKey)
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


        //if the number of registered socket changed, realloc the memory (I could not make realloc work so I just free/alloc). TODO: make this damned realloc work
        if (this.registeredSockets.size > this.allocatedStructs) {
            nativeHeap.free(this.pollArrayStruct)
            this.pollArrayStruct = nativeHeap.allocArray<pollfd>(this.registeredSockets.size)
            this.allocatedStructs = this.registeredSockets.size
        }

        //update all the structs, first iterate through the registered sockets, then register the wakeup file descriptor
        for (i in (0 until this.registeredSockets.size)) {
            with(this.pollArrayStruct[i]) {
                fd = this@Selector.registeredSockets[i].socket
                events = this@Selector.registeredSockets[i].getPollEvents()
                revents = 0
            }
        }

        //perform call
        val result = poll(this.pollArrayStruct, this.registeredSockets.size.toULong(), timeout)

        //update state
        this.isInSelection = false

        when (result) {
            -1 -> throw Exception("The poll call failed")
            0 -> return
        }

        //iterate through the registered sockets and
        for (i in (0 until this.registeredSockets.size)) {
            val struct: pollfd = this.pollArrayStruct[i].reinterpret()
            val sk = this@Selector.registeredSockets[i]

            //println("registered size: ${this.registeredSockets.size} \t\tsocket number: ${struct.fd} \t\tstruct.revents: ${struct.revents}")

            if (struct.revents.and(POLLIN.toShort()) == POLLIN.toShort()) {
                val cont = sk.OP_READ!!
                if (sk.alwaysSelectRead == null) {
                    //if not, unregister then resume the coroutine
                    sk.unsafeUnregister(Interests.OP_READ)
                    cont.resume(true)
                } else {
                    val request = sk.alwaysSelectRead!!
                    //if the operation returns false, we can unregister
                    if (!request.operation.invoke()) {
                        sk.unsafeUnregister(Interests.OP_READ)
                        cont.resume(true)
                    }
                }
            }

            if (struct.revents.and(POLLOUT.toShort()) == POLLOUT.toShort()) {
                val cont = sk.OP_WRITE!!
                if (sk.alwaysSelectWrite == null) {
                    //if not, unregister then resume the coroutine
                    sk.unsafeUnregister(Interests.OP_WRITE)
                    cont.resume(true)
                } else {
                    val request = sk.alwaysSelectWrite!!
                    //if the operation returns false, we can unregister
                    if (!request.operation.invoke()) {
                        sk.unsafeUnregister(Interests.OP_WRITE)
                        cont.resume(true)
                    }
                }
            }

            if (struct.revents.and(POLLHUP.toShort()) == POLLHUP.toShort() ||
                struct.revents.and(POLLERR.toShort()) == POLLERR.toShort()) {
                sk.close()
            }
        }
    }

    suspend fun close() {
        if (this._isClosed.compareAndSet(false, true)) {
            this.registeredSockets.forEach{
                it.close()
            }
            this.sleepContinuation.value?.cancel()
            this.loop?.join()
            nativeHeap.free(this.pollArrayStruct)
        }
    }

}

internal class SelectAlways(val operation : () -> Boolean)
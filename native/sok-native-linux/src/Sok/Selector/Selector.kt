package Sok.Selector

import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import platform.posix.*
import kotlinx.cinterop.*
import kotlin.experimental.and
import kotlinx.coroutines.*
import kotlin.coroutines.*

private val _defaultSelector : AtomicRef<Selector?> = atomic(null)
private val _defaultScope : AtomicRef<CoroutineScope> = atomic(GlobalScope)

class Selector private constructor (val scope : CoroutineScope) {

    companion object {
        public val defaultSelector : Selector
            get() {
                _defaultSelector.compareAndSet(null,Selector(_defaultScope.value))

                return _defaultSelector.value!!
            }

        fun setDefaultScope(scope : CoroutineScope){
            _defaultScope.value = scope
        }

        suspend fun closeSelectorAndWait(){
            _defaultSelector.value?.close()
            _defaultSelector.value = null
        }
    }

    private val isWakeupSent: AtomicBoolean = atomic(false)
    private val isClosed: AtomicBoolean = atomic(false)
    private val isStarted: AtomicBoolean = atomic(false)
    private val isInSelection: AtomicBoolean = atomic(false)

    private var pollArrayStruct = nativeHeap.allocArray<pollfd>(1)
    private var allocatedStructs = 1

    private val registeredSockets = mutableListOf<SelectionKey>()

    private val sleepContinuation: AtomicRef<Continuation<Unit>?> = atomic(null)

    private lateinit var loop: Job

    private fun start() {
        if(this.isStarted.compareAndSet(false,true)){
            this.loop = scope.launch {
                while (!this@Selector.isClosed()) {

                    if(this@Selector.registeredSockets.size != 0){
                        this@Selector.select(1)
                        //allow continuations to execute
                        yield()
                    }else{
                        suspendCoroutine<Unit>{
                            this@Selector.sleepContinuation.value = it
                        }
                    }

                }
            }
        }
    }

    fun register(socket: Int): SelectionKey {
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

    fun unregister(selectionKey : SelectionKey){
        if(!this.isClosed.value){
            this.registeredSockets.remove(selectionKey)
        }
    }

    /**
     * perform a blocking poll() call. Once the call is completed, check the result set:
     *  - Closed socket errors unregister the socket from the selector and are not dispatched
     *  - Wakeup file descriptor readable event are read and if its the only selected file descriptor, return an empty list
     */
    private fun select(timeout: Int) {
        if (!this.isInSelection.compareAndSet(false, true)) {
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
        this.isInSelection.value = false

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
        if (this.isClosed.compareAndSet(false, true)) {
            this.registeredSockets.forEach{
                it.close()
            }
            this.loop.join()
            nativeHeap.free(this.pollArrayStruct)
        }
    }

    fun isInSelection(): Boolean {
        return this.isInSelection.value
    }

    fun isClosed(): Boolean {
        return this.isClosed.value
    }

}

class SelectAlways(val operation : () -> Boolean)
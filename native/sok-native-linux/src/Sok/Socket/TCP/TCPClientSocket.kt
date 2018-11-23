package Sok.Socket.TCP

import Sok.Buffer.*
import Sok.Selector.*
import Sok.Exceptions.*
import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.cinterop.*
import platform.posix.*
import Sok.Socket.Options.Options
import Sok.Socket.Options.SocketOption
import Sok.Exceptions.OptionNotSupportedException

/**
 * Class representing a client socket. You can use it to perform any I/O operation. Keep in mind that this class keep an internal
 * queue for write operations thus storing data until written so you should have some kind of backpressure mechanism to prevent
 * the accumulation of too many data.
 *
 * @property isClosed Keep track of the socket status
 * @property exceptionHandler Lambda that will be called when an exception resulting in the closing of the socket is thrown. This
 * happen when calling the `close` or `forceClose` method, if the peer close the socket or if something wrong happen internally.
 * Exception that does not affect the socket state will not be received by this handler (exception in the `bulkRead` operation
 * lambda for example)
 */
actual class TCPClientSocket{

    /**
     * Used to only allow one read at a time
     */
    private val isReading : AtomicBoolean = atomic(false)

    /**
     * Atomic backing the isClosed property
     */
    private val _isClosed : AtomicBoolean = atomic(false)

    actual var isClosed : Boolean
        get() {
            return this._isClosed.value
        }
        private set(value) {
            this._isClosed.value = value
        }

    actual var exceptionHandler : (exception : Throwable) -> Unit = {}

    /**
     * Exception handler used to catch everything that comes from the internal coroutines
     */
    private val internalExceptionHandler = CoroutineExceptionHandler{_,e ->
        if(e is CloseException && !this.isCloseExceptionSent.compareAndSet(false,true)) return@CoroutineExceptionHandler
        this.exceptionHandler(e)
        this.forceClose()
    }
    private val isCloseExceptionSent = atomic(false)

    /**
     * Selection key of the socket
     */
    private val selectionKey : SelectionKey

    /**
     * Actor managing the write operations
     */
    private val writeActor : SendChannel<WriteActorRequest>

    /**
     * Construtor used by the Server socket to build the client socket
     *
     * @param socket file descriptor of the socket
     * @param selector Selector managing the socket
     */
    internal constructor(socket : Int,selector : Selector){
        this.selectionKey = selector.register(socket)
        this.selectionKey.exceptionHandler = this.internalExceptionHandler
        this.writeActor = this.createWriteActor(this.selectionKey, this.getOption<Int>(Options.SO_SNDBUF).value)
    }

    /**
     * Constructor used by the createTCPClientSocket function, as the function already create and use a SelectionKey
     * we reuse it.
     *
     * @param selectionKey selection key managing the socket
     */
    internal constructor(selectionKey : SelectionKey){
        this.selectionKey = selectionKey
        this.selectionKey.exceptionHandler = this.internalExceptionHandler
        this.writeActor = this.createWriteActor(this.selectionKey, this.getOption<Int>(Options.SO_SNDBUF).value)
    }

    /**
     * gracefully stops the socket. The method suspends as it waits for all the writing requests in the channel to be
     * executed before effectively closing the channel. Once the socket is closed a `NormalCloseException` will be passed
     * to the exception handler and to any ongoing read method call
     */
    actual suspend fun close(){
        /**
         * Channels are fair, coroutine dispatching is also fair, but consider the "Client wait for the end of the send queue before close"
         * test case, the buffers are all written asynchronously, thus launching coroutines but not suspending. In this case the execution will continue
         * until the close() call, it's only when we do that that we suspend and let all the launched coroutine to be dispatched. We need to
         * yield() before doing anything else to let the possibly not dispatched coroutine to be so.
         */
        yield()

        if(this._isClosed.compareAndSet(false,true)) {
            val deferred = CompletableDeferred<Boolean>()
            this.writeActor.send(CloseRequest(deferred))
            this.writeActor.close()
            deferred.await()
            this.selectionKey.close(NormalCloseException())
        }
    }

    /**
     * forcefully closes the channel without checking the writing request queue. Once the socket is closed a `ForceCloseException`
     * will be passed to the exception handler and to any ongoing read method call
     */
    actual fun forceClose(){
        if(this._isClosed.compareAndSet(false,true)){
            this.writeActor.close()
            this.selectionKey.close(ForceCloseException())
            this.internalExceptionHandler.handleException(ForceCloseException())
        }
    }

    /**
     * Used to do efficient read-intensive loops, it will basically execute the operation each time there is data to be read
     * and avoid registrations/allocation between each iteration. The passed lambda must return true to continue the loop or
     * false to exit. The call will suspend as long as the loop is running.
     *
     * THE OPERATION MUST NOT BE COMPUTATION INTENSIVE OR BLOCKING as the internal selector will call it synchronously and wait
     * for it to return before processing any other event. The buffer cursor will be reset between each iteration so you should
     * not use it between two iterations and must avoid leaking it to exterior coroutines/threads. each iteration will read
     * n bytes ( 0 < n <= buffer.limit ) and set the cursor to 0, the read parameter of the operation is the amount of data read.
     *
     * If an exception is thrown in the operation lambda, the exception will not close the socket and will not be received by the
     * exception handler, it will instead be thrown directly by the method
     *
     * @throws PeerClosedException
     * @throws SocketClosedException
     * @throws BufferOverflowException
     * @throws ConcurrentReadingException

     *
     * @param buffer buffer used to store the data read. the cursor will be reset after each iteration. The limit of the buffer remains
     * untouched so the developer can chose the amout of data to read.
     * @param operation lambda called after each read event. The first argument will be the buffer and the second the amount of data read
     *
     * @return Total number of byte read
     */
    actual suspend fun bulkRead(buffer : MultiplatformBuffer, operation : (buffer : MultiplatformBuffer, read : Int) -> Boolean) : Long {
        if(this.isClosed) throw SocketClosedException()
        if(buffer.remaining() <= 0) throw BufferOverflowException()
        if(!this.isReading.compareAndSet(false,true)) throw ConcurrentReadingException()

        buffer as NativeMultiplatformBuffer
        var read : Long = 0

        //store the exception thrown by the operation lambda
        var exc : Throwable? = null
        this.selectionKey.selectAlways(Interests.OP_READ){

            val result = read(this.selectionKey.socket,buffer.nativePointer(),buffer.limit.toULong()).toInt()

            if(result == -1 || result == 0){
                throw PeerClosedException("Read call failed")
            }else{
                read += result
                buffer.cursor = 0
                try {
                    operation(buffer,result)
                }catch (e : Exception){
                    exc = e
                    false
                }
            }
        }

        //throw the operation lambda exception back to the caller
        if(exc != null){
            throw exc!!
        }

        this.isReading.value = false
        return read
    }

    /**
     * Perform a suspending read, the method will read n bytes ( 0 < n <= buffer.remaining() ) and update the cursor. If the peer
     * closes the socket while reading, a `PeerClosedException` will be thrown. If the socket is manually closed while reading,
     * either `NormalCloseException` or `ForceCloseException` will be thrown
     *
     * @throws PeerClosedException
     * @throws SocketClosedException
     * @throws BufferOverflowException
     * @throws ConcurrentReadingException
     *
     * @param buffer buffer used to store the data read
     *
     * @return Number of byte read
     */
    actual suspend fun read(buffer: MultiplatformBuffer) : Int {
        if(this.isClosed) throw SocketClosedException()
        if(buffer.remaining() <= 0) throw BufferOverflowException()
        if(!this.isReading.compareAndSet(false,true)) throw ConcurrentReadingException()

        try {
            this.selectionKey.select(Interests.OP_READ)

            buffer as NativeMultiplatformBuffer
            val result = read(this.selectionKey.socket,buffer.nativePointer()+buffer.cursor,buffer.remaining().toULong()).toInt()

            if(result == -1 || result == 0){
                throw PeerClosedException()
            }

            buffer.cursor = result

            this.isReading.value = false

            return result
        }catch (e : Exception){
            this.internalExceptionHandler.handleException(e)
            throw e
        }
    }

    /**
     * Perform a suspending read, the method will read n bytes ( minToRead < n <= buffer.remaining() ) and update the cursor If the peer
     * closes the socket while reading, a `PeerClosedException` will be thrown. If the socket is manually closed while reading,
     * either `NormalCloseException` or `ForceCloseException` will be thrown
     *
     * @throws PeerClosedException
     * @throws SocketClosedException
     * @throws BufferOverflowException
     * @throws ConcurrentReadingException
     *
     * @param buffer buffer used to store the data read
     *
     * @return Number of byte read
     */
    actual suspend fun read(buffer: MultiplatformBuffer, minToRead : Int) : Int {
        if(this.isClosed) throw SocketClosedException()
        if(buffer.remaining() < minToRead) throw BufferOverflowException()
        if(!this.isReading.compareAndSet(false,true)) throw ConcurrentReadingException()
        
        buffer as NativeMultiplatformBuffer

        var read = 0

        this.selectionKey.selectAlways(Interests.OP_READ){
            val result = read(this.selectionKey.socket,buffer.nativePointer()+buffer.cursor,buffer.remaining().toULong()).toInt()

            if(result == -1 || result == 0){
                throw PeerClosedException()
            }else{
                buffer.cursor = buffer.cursor + result
                read += result
                read < minToRead
            }
        }

        this.isReading.value = false

        return read
    }

    /**
     * Perform a suspending write, the method will not return until all the data between buffer.cursor and buffer.limit are written.
     * The socket use an internal write queue, allowing multiple threads to concurrently write. Backpressure mechanisms
     * should be implemented by the developer to avoid having too much data in the queue. If the peer
     * closes the socket while reading, a `PeerClosedException` will be thrown. If the socket is manually closed while reading,
     * either `NormalCloseException` or `ForceCloseException` will be thrown
     *
     * @throws SocketClosedException
     * @throws BufferUnderflowException
     * @throws SokException
     * @throws PeerClosedException
     *
     * @param buffer data to write
     *
     * @return Success of the operation
     */
    actual suspend fun write(buffer: MultiplatformBuffer) : Boolean {
        if(this.isClosed || this.writeActor.isClosedForSend) throw SocketClosedException()
        if(buffer.remaining() == 0) throw BufferUnderflowException()

        val deferred = CompletableDeferred<Boolean>()
        this.writeActor.send(WriteRequest(buffer,deferred))
        return deferred.await()
    }

    /**
     * Create the actor managing write oeprations
     */
    private fun createWriteActor(selectionKey: SelectionKey, sendBufferSize : Int) : SendChannel<WriteActorRequest> {
        val channel = Channel<WriteActorRequest>()
        GlobalScope.launch(this.internalExceptionHandler){
            for(request in channel){

                if(request is CloseRequest){
                    request.deferred.complete(true)
                    throw NormalCloseException()
                }

                request as WriteRequest
                val buffer = request.data as NativeMultiplatformBuffer

                try {
                    if(buffer.limit > sendBufferSize){
                        selectionKey.selectAlways(Interests.OP_WRITE){
                            val result = write(selectionKey.socket,buffer.nativePointer()+buffer.cursor,buffer.remaining().toULong()).toInt()
                            if(result == -1){
                                throw PeerClosedException()

                            }else{
                                buffer.cursor += result
                                buffer.hasRemaining()
                            }
                        }
                        request.deferred.complete(true)
                    }else{
                        while (buffer.remaining() > 0){

                            val result = write(selectionKey.socket,buffer.nativePointer()+buffer.cursor,buffer.remaining().toULong()).toInt()

                            if(result == -1){
                                throw PeerClosedException()
                            }

                            buffer.cursor = buffer.cursor + result

                            if(buffer.remaining() >= 0) selectionKey.select(Interests.OP_WRITE)
                        }
                        request.deferred.complete(true)
                    }
                }catch (e : Exception){
                    request.deferred.completeExceptionally(e)
                    throw e
                }

            }
        }
        return channel
    }

    /**
     * get a socket option and try to convert it to the given type, throw an exception if the option is not of the correct type
     *
     * @param name Option to get
     * @return the socket option
     */
    @Suppress("UNCHECKED_CAST")
    actual fun <T>getOption(name : Options) : SocketOption<T>{
        return memScoped{
            when(name){
                Options.SO_RCVBUF -> {
                    val value = alloc<IntVar>()
                    getsockopt (this@TCPClientSocket.selectionKey.socket, SOL_SOCKET, SO_RCVBUF, value.ptr, cValuesOf(sizeOf<IntVar>().toUInt()))
                    SocketOption(Options.SO_RCVBUF,value.value as T)
                }
                Options.SO_SNDBUF -> {
                    val value = alloc<IntVar>()
                    getsockopt (this@TCPClientSocket.selectionKey.socket, SOL_SOCKET, SO_SNDBUF, value.ptr, cValuesOf(sizeOf<IntVar>().toUInt()))
                    SocketOption(Options.SO_RCVBUF,value.value as T)
                }
                Options.SO_KEEPALIVE -> {
                    val value = alloc<IntVar>()
                    getsockopt (this@TCPClientSocket.selectionKey.socket, SOL_SOCKET, SO_KEEPALIVE, value.ptr, cValuesOf(sizeOf<IntVar>().toUInt()))
                    SocketOption(Options.SO_RCVBUF,(value.value == 1) as T)
                }
                Options.TCP_NODELAY -> {
                    val value = alloc<IntVar>()
                    getsockopt (this@TCPClientSocket.selectionKey.socket, SOL_SOCKET, TCP_NODELAY, value.ptr, cValuesOf(sizeOf<IntVar>().toUInt()))
                    SocketOption(Options.SO_RCVBUF,(value.value == 1) as T)
                }
            }
        }
    }

    /**
     * set a socket option
     *
     * @param option option to set
     * @return success of the operation
     */
    @Suppress("UNCHECKED_CAST")
    actual fun <T>setOption(option : SocketOption<T>) : Boolean{
        return memScoped {
            when (option.name) {
                Options.SO_RCVBUF -> {
                    val value = alloc<IntVar>()
                    value.value = option.value as Int
                    setsockopt (this@TCPClientSocket.selectionKey.socket, SOL_SOCKET, SO_RCVBUF, value.ptr, sizeOf<IntVar>().toUInt()) != -1
                }
                Options.SO_SNDBUF -> {
                    val value = alloc<IntVar>()
                    value.value = option.value as Int
                    setsockopt (this@TCPClientSocket.selectionKey.socket, SOL_SOCKET, SO_SNDBUF, value.ptr, sizeOf<IntVar>().toUInt()) != -1
                }
                Options.SO_KEEPALIVE -> {
                    val value = alloc<IntVar>()
                    value.value = if((option.value as Boolean)){1}else{0}
                    setsockopt (this@TCPClientSocket.selectionKey.socket, SOL_SOCKET, SO_KEEPALIVE, value.ptr, sizeOf<IntVar>().toUInt()) != -1
                }
                Options.TCP_NODELAY -> {
                    val value = alloc<IntVar>()
                    value.value = if((option.value as Boolean)){1}else{0}
                    setsockopt (this@TCPClientSocket.selectionKey.socket, SOL_SOCKET, TCP_NODELAY, value.ptr, sizeOf<IntVar>().toUInt()) != -1
                }
            }
        }
    }
}

/**
 * Create a client socket with the given address and port. This function will throw a `ConnectionRefusedException` if the socket
 * failed to connect.
 *
 * @param address IP or domain to connect to
 * @param port port to connect to
 *
 * @return connected socket
 */
actual suspend fun createTCPClientSocket(address : String, port : Int ) : TCPClientSocket {
    var socket : Int = 0

    memScoped{
        val hints = alloc<addrinfo>()
        val result = allocPointerTo<addrinfo>()

        //set hints
        with(hints) {
            memset(this.ptr, 0, addrinfo.size.toULong())
            this.ai_family = AF_UNSPEC
            this.ai_socktype = SOCK_STREAM
            this.ai_flags = 0
            this.ai_protocol = 0
        }

        //try to resolve address
        if (getaddrinfo(address, port.toString(), hints.ptr, result.ptr) != 0) {
            throw ConnectionRefusedException()
        }

        //loop until one result works
        with(result){
            var next : addrinfo? = this.pointed
            while(next != null){
                socket = socket(next.ai_family, next.ai_socktype,next.ai_protocol)
                Sok.Utils.makeNonBlocking(socket)

                if(socket == -1) continue

                val r = connect(socket, next.ai_addr, next.ai_addrlen)

                if(r == 0 || posix_errno() == EINPROGRESS) return@with

                close(socket)

                next = next.ai_next?.pointed
            }
            throw ConnectionRefusedException()
        }


        val error = alloc<IntVar>()
        val len = alloc<socklen_tVar>()
        len.value = sizeOf<IntVar>().toUInt()
        val retval = getsockopt (socket, SOL_SOCKET, SO_ERROR, error.ptr, len.ptr)

        if(retval == -1 || error.value != 0){
            throw ConnectionRefusedException()
        }

    }

    //register socket and wait for it to be ready
    val selectionKey = Selector.defaultSelector.register(socket)
    selectionKey.select(Interests.OP_WRITE)

    return TCPClientSocket(socket,Selector.defaultSelector)
}

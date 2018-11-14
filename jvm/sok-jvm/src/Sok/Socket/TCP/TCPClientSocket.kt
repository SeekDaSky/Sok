package Sok.Socket.TCP

import Sok.Buffer.*
import Sok.Exceptions.*
import Sok.Selector.Selector
import Sok.Selector.SelectorPool
import Sok.Selector.SuspentionMap
import Sok.Socket.Options.Options
import Sok.Socket.Options.SocketOption
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

/**
 * Class representing a client socket. You can use it to perform any I/O operation. Keep in mind that this class keep an internal
 * queue for write operations thus storing data until written so you should have some kind of backpressure mechanism to prevent
 * the accumulation of too many data.
 *
 * @property isClosed Keep track of the socket status
 * @property exceptionHandler Lambda that will be called when a fatal exception is thrown within the library, for further information
 * look at the "Exception model" part of the documentation
 */
actual class TCPClientSocket {

    /**
     * NIO channel
     */
    private val channel: SocketChannel

    /**
     * Actor handling write operations
     */
    private val writeActor: SendChannel<WriteActorRequest>

    /**
     * Atomic property backing isClosed
     */
    private val _isClosed = atomic(true)

    actual var isClosed : Boolean
        private set(value){
            this._isClosed.value = value
        }
        get() = this._isClosed.value

    actual var exceptionHandler : (exception : Throwable) -> Unit = {}

    /**
     * Exception handler used to catch everything that comes from the internal coroutines
     */
    private val internalExceptionHandler = CoroutineExceptionHandler{_,e ->
        this.forceClose()
        this.exceptionHandler(e)
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO+this.internalExceptionHandler)

    /**
     * Suspention map used by the socket for 
     */
    private val suspentionMap : SuspentionMap

    /**
     * Helper contrutor that simply get the less busy selector from the pool then use the "standard" constructor
     *
     * @param channel NIO channel
     * @param selectorPool Selector pool to use
     */
    constructor(channel : SocketChannel, selectorPool: SelectorPool) : this(channel,runBlocking(Dispatchers.Unconfined){selectorPool.getLessbusySelector()})

    /**
     * Wrap a NIO channel with a Sok client socket class
     *
     * @param channel NIO channel
     * @param selector Selector used to track the NIO channel
     */
    constructor(channel : SocketChannel, selector: Selector){

        this.channel = channel
        //configure the socket for NIO use
        channel.configureBlocking(false)
        channel.setOption(StandardSocketOptions.TCP_NODELAY,true)

        //create suspention map
        this.suspentionMap = SuspentionMap(selector,channel,this.internalExceptionHandler)

        //update state
        this.isClosed = false

        //launch write/read actor
        this.writeActor = this.writeActor(this.suspentionMap,this.channel)

    }

    /**
     * gracefully stops the socket. The method suspends as it waits for all the writing requests in the channel to be
     * executed before effectively closing the channel
     */
    actual suspend fun close(){
        /** If some coroutines are launched in the same scope as the server scope
         * and that those coroutines want to write, the server might close before them
         * even if they were launched before the actual close() statement, in order to
         * avoid that, we yield first
         */
        yield()

        if(this._isClosed.compareAndSet(false,true)){
            //wait for the write actor to consume everything in the channel
            val deferred = CompletableDeferred<Boolean>()
            this.writeActor.send(CloseRequest(deferred))
            this.writeActor.close()
            deferred.await()

            this.suspentionMap.close()
            this.channel.close()
        }
    }

    /**
     * forcefully closes the channel without checking the writing request queue
     */
    actual fun forceClose(){
        if(this._isClosed.compareAndSet(false,true)){
            this@TCPClientSocket.writeActor.close()
            this.suspentionMap.close()
            this.channel.close()
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
     * @throws SokException
     * @throws SocketClosedException
     * @throws BufferOverflowException
     * @throws ConcurrentReadingException

     *
     * @param buffer buffer used to store the data read. the cursor will be reset after each iteration. The limit of the buffer remains
     * untouched so the developer can chose the amout of data to read.
     *
     * @param operation lambda called after each read event. The first argument will be the buffer and the second the amount of data read
     *
     * @return Total number of byte read
     */
    actual suspend fun bulkRead(buffer : MultiplatformBuffer, operation : (buffer : MultiplatformBuffer, read : Int) -> Boolean) : Long{
        if(this.isClosed) throw SocketClosedException()
        if(buffer.remaining() <= 0) throw BufferOverflowException()
        if(this.suspentionMap.OP_READ != null) throw ConcurrentReadingException()

        (buffer as JVMMultiplatformBuffer)
        var read : Long = 0

        this.suspentionMap.selectAlways(SelectionKey.OP_READ){
            buffer.cursor = 0

            val tmpRead = this.channel.read(buffer.nativeBuffer())
            if(tmpRead > 0){
                read += tmpRead

                buffer.cursor = 0

                //let the operation registered by the user tell if we need another selection
                operation.invoke(buffer,tmpRead)
            }else{
                read = -1
                false
            }
        }

        if(read == -1.toLong()){
            this.close()
        }

        return read
    }

    /**
     * Perform a suspending read, the method will read n bytes ( 0 < n <= buffer.remaining() ) and update the cursor
     *
     * @throws SokException
     * @throws SocketClosedException
     * @throws BufferOverflowException
     * @throws ConcurrentReadingException
     *
     * @param buffer buffer used to store the data read
     *
     * @return Number of byte read
     */
    actual suspend fun read(buffer: MultiplatformBuffer) : Int{
        if(this.isClosed) throw SocketClosedException()
        if(buffer.remaining() <= 0) throw BufferOverflowException()
        if(this.suspentionMap.OP_READ != null) throw ConcurrentReadingException()

        var read = 0
        withContext(Dispatchers.IO){
            //wait for the selector to detect data then read
            this@TCPClientSocket.suspentionMap.selectOnce(SelectionKey.OP_READ)

            (buffer as JVMMultiplatformBuffer)
            read = channel.read(buffer.nativeBuffer())

            if(read > 0){
                buffer.cursor += read
            }
        }


        //if the channel returns -1 it means that the channel has been closed
        if(read == -1){
            this.close()
        }
        return read
    }

    /**
     * Perform a suspending read, the method will read n bytes ( minToRead < n <= buffer.remaining() ) and update the cursor
     *
     * @throws SokException
     * @throws SocketClosedException
     * @throws BufferOverflowException
     * @throws ConcurrentReadingException
     *
     * @param buffer buffer used to store the data read
     *
     * @return Number of byte read
     */
    actual suspend fun read(buffer: MultiplatformBuffer, minToRead : Int) : Int{
        if(this.isClosed) throw SocketClosedException()
        if(buffer.remaining() < minToRead) throw BufferOverflowException()
        if(this.suspentionMap.OP_READ != null) throw ConcurrentReadingException()

        (buffer as JVMMultiplatformBuffer)
        var read = 0

        this.suspentionMap.selectAlways(SelectionKey.OP_READ){

            val tmpRead = this.channel.read(buffer.nativeBuffer())
            if(tmpRead > 0){
                read += tmpRead
                read < minToRead
            }else{
                read = -1
                false
            }
        }

        buffer.cursor += read

        if(read == -1){
            this.close()
        }

        return read
    }

    /**
     * Perform a suspending write, the method will not return until all the data between buffer.cursor and buffer.limit are written.
     * The socket use an internal write queue, allowing multiple threads to concurrently write. Backpressure mechanisms
     * should be implemented by the developer to avoid having too much data in the queue.
     *
     * @throws SocketClosedException
     * @throws BufferUnderflowException
     * @throws SokException
     *
     * @param buffer data to write
     *
     * @return Success of the operation
     */
    actual suspend fun write(buffer: MultiplatformBuffer) : Boolean{
        if(this.isClosed || this.writeActor.isClosedForSend) throw SocketClosedException()
        if(buffer.remaining() == 0) throw BufferUnderflowException()

        val deferred = CompletableDeferred<Boolean>()
        this.writeActor.send(WriteRequest(buffer, deferred))

        return deferred.await()
    }

    /**
     * Create a standalone write actor
     */
    private fun writeActor(selectorManager: SuspentionMap, channel: SocketChannel) = this.coroutineScope.actor<WriteActorRequest>{
        for (request in this.channel){
            if(request is CloseRequest){
                request.deferred.complete(true)
                throw NormalCloseException()
            }

            request as WriteRequest
            try {
                val buf = (request.data as JVMMultiplatformBuffer).nativeBuffer()

                //if the buffer is larger than our TCP send buffer, there is a good chance we will have to register the OP_WRITE interest
                //a few times, so we use the selectAlways() method to optimise that and loop while there still is data to write
                if(request.data.limit >= channel.getOption(StandardSocketOptions.SO_SNDBUF)){
                    suspentionMap.selectAlways(SelectionKey.OP_WRITE){
                        try {
                            channel.write(buf)
                            buf.hasRemaining()
                        }catch (e : Exception){
                            val exc = SokException(e.toString())
                            request.deferred.completeExceptionally(exc)
                            //we can throw exceptions here as the Selector send exceptions back to us
                            throw exc
                        }
                    }
                }else{
                    //even if our buffer is smaller than our TCP send buffer, a single write might not be enough
                    while (buf.hasRemaining()){
                        try {
                            channel.write(buf)
                        }catch (e : Exception){
                            val exc = SokException(e.toString())
                            request.deferred.completeExceptionally(exc)
                            //we can throw exceptions here as the Selector send exceptions back to us
                            throw exc
                        }

                        if(buf.hasRemaining()) selectorManager.selectOnce(SelectionKey.OP_WRITE)
                    }
                }

                request.data.cursor = request.data.limit
                request.deferred.complete(true)
            }catch (e : Exception){
                val exc = SokException(e.toString())
                request.deferred.completeExceptionally(exc)
                //we can throw exceptions here as the Selector send exceptions back to us
                throw exc
            }
        }
    }
    /**
     * get a socket option and try to convert it to the given type
     *
     * @param name Option to get
     * @return the socket option
     */
    @Suppress("UNCHECKED_CAST")
    actual fun <T>getOption(name : Options) : SocketOption<T> {
        return when(name){
            Options.SO_RCVBUF -> SocketOption(Options.SO_RCVBUF,this.channel.getOption(StandardSocketOptions.SO_RCVBUF) as T)
            Options.SO_SNDBUF -> SocketOption(Options.SO_SNDBUF,this.channel.getOption(StandardSocketOptions.SO_RCVBUF) as T)
            Options.SO_KEEPALIVE -> SocketOption(Options.SO_KEEPALIVE,this.channel.getOption(StandardSocketOptions.SO_KEEPALIVE) as T)
            Options.TCP_NODELAY -> SocketOption(Options.TCP_NODELAY,this.channel.getOption(StandardSocketOptions.TCP_NODELAY) as T)
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
        return when(option.name){
            Options.SO_RCVBUF -> {
                this.channel.setOption(StandardSocketOptions.SO_RCVBUF,option.value as Int)
                true
            }
            Options.SO_SNDBUF -> {
                this.channel.setOption(StandardSocketOptions.SO_SNDBUF,option.value as Int)
                true
            }
            Options.SO_KEEPALIVE -> {
                this.channel.setOption(StandardSocketOptions.SO_KEEPALIVE,option.value as Boolean)
                true
            }
            Options.TCP_NODELAY -> {
                this.channel.setOption(StandardSocketOptions.TCP_NODELAY,option.value as Boolean)
                true
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
actual suspend fun createTCPClientSocket(address: String, port: Int) : TCPClientSocket {
    val socket = SocketChannel.open()
    socket.configureBlocking(false)
    socket.connect(InetSocketAddress(address,port))

    //create temporary suspention map, You should not close as the selection key would be cancelled
    val suspentionMap = SuspentionMap(Selector.defaultSelector,socket, CoroutineExceptionHandler{_,_ -> })
    suspentionMap.selectOnce(SelectionKey.OP_CONNECT)

    try{
        socket.finishConnect()
    }catch (e : java.lang.Exception){
        throw ConnectionRefusedException()
    }

    if(Selector.isSelectorPoolInit){
        return Sok.Socket.TCP.TCPClientSocket(socket, Selector.defaultSelectorPool)
    }else{
        return Sok.Socket.TCP.TCPClientSocket(socket, Selector.defaultSelector)
    }

}
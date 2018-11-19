package Sok.Socket.TCP

import Sok.Buffer.*
import Sok.Exceptions.*
import Sok.Internal.net
import Sok.Socket.Options.Options
import Sok.Socket.Options.SocketOption
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import org.khronos.webgl.Uint8Array
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Class representing a client socket. You can use it to perform any I/O operation. Keep in mind that this class keep an internal
 * queue for write operations thus storing data until written so you should have some kind of backpressure mechanism to prevent
 * the accumulation of too many data.
 *
 * @property isClosed Keep track of the socket status
 * @property exceptionHandler Lambda that will be called when a fatal exception is thrown within the library, for further information
 * look at the "Exception model" part of the documentation
 */
actual class TCPClientSocket{

    actual var isClosed = true
        private set

    actual var exceptionHandler : (exception : Throwable) -> Unit = {}

    /**
     * Actor managing write operations
     */
    private val writeChannel : SendChannel<WriteActorRequest>

    /**
     * Node.js socket
     */
    private val socket : dynamic

    /**
     * index in the internal stream buffer
     */
    private var indexInStream = 0

    /**
     * Continuation to resume when there is data to read. This continuation must be cancelled when the socket is closed
     */
    private var readingContinuation : CancellableContinuation<Unit>? = null

    /**
     * Because Node.js does not give us ways to get what option are set on TCP socket (but on UDP socket you can, go figure)
     * so we do it ourself
     */
    private val optionMap = mutableMapOf<Options,Any>(
            Pair(Options.SO_KEEPALIVE,false),
            Pair(Options.TCP_NODELAY,true)
    )

    /**
     * Exception handler used to catch everything that comes from the internal coroutines
     */
    private val internalExceptionHandler = CoroutineExceptionHandler{_,e ->
        if(e is CloseException && this.isCloseExceptionSent) return@CoroutineExceptionHandler
        this.isCloseExceptionSent = true
        this.forceClose()
        this.exceptionHandler(e)
    }
    private var isCloseExceptionSent = false

    /**
     * Wrap a Node.js socket with Sok Client socket class
     *
     * @param socket Node.js socket class
     */
    constructor(socket : dynamic){
        //store the socket
        this.socket = socket

        /**
         * Register all close-related events, at this point Sok does make a difference between normal and abnormal close event
         * and the close related operations will be executed only once so we can call close() multiple times without problem
         */
        socket.on("end"){
            val exc = PeerClosedException()
            this.readingContinuation?.cancel(exc)
            this.internalExceptionHandler.handleException(exc)
        }

        socket.on("error"){ e ->
            val exc = PeerClosedException(e.toString())
            this.readingContinuation?.cancel(exc)
            this.internalExceptionHandler.handleException(exc)
        }

        //start the write actor and bind the operation in case of failure (we close the socket)
        this.writeChannel = this.writeActor(this.socket)

        //update state
        this.isClosed = false
    }

    /**
     * gracefully stops the socket. The method suspends as it waits for all the writing requests in the channel to be
     * executed before effectively closing the channel
     */
    actual suspend fun close(){
        //let the waiting coroutines execute (in case they want to write something) before closing
        yield()
        if(!this.isClosed){
            this.isClosed = true

            val deferred = CompletableDeferred<Boolean>()
            this.writeChannel.send(CloseRequest(deferred))
            this.writeChannel.close()
            deferred.await()

            this.readingContinuation?.cancel(NormalCloseException())
            this.socket.end()
        }
    }

    /**
     * forcefully closes the channel without checking the writing request queue
     */
    actual fun forceClose(){
        if(!this.isClosed){
            this.isClosed = true
            this.writeChannel.close()
            this.readingContinuation?.cancel(ForceCloseException())
            this.internalExceptionHandler.handleException(ForceCloseException())
            this.socket.end()
        }
    }

    /**
     * As nodeJS directly give us a buffer when we read from a socket, we have to copy data from the given buffer into
     * the user buffer. This method does that while respecting the cursor/limit properties
     *
     * @param buffer buffer to fill
     *
     * @return number of byte copied
     */
    private fun readInto(buffer : JSMultiplatformBuffer) : Int{
        if(buffer.remaining() == 0) throw BufferOverflowException()
        //read from the socket, with a minimum or not
        val internalBuffer : Buffer? = this.socket.read()

        if(internalBuffer == null){
            return -1
        }

        //backup cursor
        val cursor = buffer.cursor

        //if we did not unshit data and that the buffer is small enough to fit in the MultiplatformBuffer, swap the backbuffer
        if(internalBuffer.length <= buffer.remaining() && this.indexInStream == 0){
            buffer.cursor += internalBuffer.copy(buffer.nativeBuffer(),buffer.cursor,0,internalBuffer.length)
        }else{
            //transfer data
            val read = internalBuffer.copy(buffer.nativeBuffer(),buffer.cursor,this.indexInStream,min(internalBuffer.length,buffer.remaining()))
            buffer.cursor += read
            //if we read all the internal buffer, discard, else unshift it and update indexInStream
            if(this.indexInStream + read != internalBuffer.length){
                this.indexInStream += buffer.limit
                this.socket.unshift(internalBuffer)
            }else{
                this.indexInStream = 0
            }
        }

        return buffer.cursor - cursor
    }

    /**
     * Suspend the caller until a "readable" event is received, when received the "operation" block is called. This
     * block returns either true if we need to listen for this event one more time, are false to unregister
     *
     * @param operation lambda to call after each event
     */
    private suspend fun registerReadable(operation : () -> Boolean ){
        suspendCancellableCoroutine<Unit> {
            this.readingContinuation = it
            socket.on("readable"){
                if(!operation.invoke()){
                    this.readingContinuation = null
                    socket.removeAllListeners("readable")
                    it.resume(Unit)
                }
            }
            //if the continuation is cancelled, we have to unregister the listener on readable to prevent any unwanted call to the
            //operation lambda
            it.invokeOnCancellation {
                this.readingContinuation = null
                socket.removeAllListeners("readable")
                Unit
            }
            Unit
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
        if(this.readingContinuation != null) throw ConcurrentReadingException()

        var total = -1L
        (buffer as JSMultiplatformBuffer)

        var exc : Throwable? = null
        this.registerReadable{
            //read while there is data
            buffer.cursor = 0
            while (this.readInto(buffer) != -1) {
                total += buffer.cursor
                val read = buffer.cursor
                buffer.cursor = 0
                //if the operation returns false, unregister
                try {
                    if (!operation(buffer, read)) {
                        return@registerReadable false
                    }
                }catch (e : Exception){
                    exc = e
                    return@registerReadable false
                }
            }
            !this.isClosed
        }

        if(exc != null){
            throw exc!!
        }


        return total
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
        if(this.readingContinuation != null) throw ConcurrentReadingException()

        var read = -1
        this.registerReadable {
            (buffer as JSMultiplatformBuffer)
            read = this.readInto(buffer)
            read == -1
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
        if(this.readingContinuation != null) throw ConcurrentReadingException()

        var read : Int = 0

        this.registerReadable {
            (buffer as JSMultiplatformBuffer)
            //if we successfully read some byte and that then the read fails the read value wont be -1 if we add it directly
            if(buffer.hasRemaining()){
                val tmp = this.readInto(buffer)
                read += tmp
                tmp != -1 && read < minToRead && !this.isClosed
            }else{
                false
            }
        }

        if(read < minToRead) throw PeerClosedException()

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
        if(this.isClosed || this.writeChannel.isClosedForSend) throw SocketClosedException()
        if(buffer.remaining() == 0) throw BufferUnderflowException()

        val deferred = CompletableDeferred<Boolean>()
        this.writeChannel.send(WriteRequest(buffer, deferred))
        return deferred.await()
    }

    /**
     * Create an actor managing write operations and return the channel to which send the requests
     */
    private fun writeActor(socket : dynamic) : SendChannel<WriteActorRequest>{
        val channel = Channel<WriteActorRequest>()
        val job = GlobalScope.launch(this.internalExceptionHandler){
            for (request in channel){

                //close signal received
                if(request is CloseRequest){
                    request.deferred.complete(true)
                    throw NormalCloseException("The write actor gracefully closed the socket")
                }

                request as WriteRequest

                try {
                    /**
                     * If all the buffer is supposed to be written, don't copy anything and write, else do a copy of the portion to write
                     */
                    val buf : Uint8Array
                    if(request.data.cursor == 0 && request.data.limit == request.data.capacity){
                        buf = (request.data as JSMultiplatformBuffer).nativeBuffer()
                    }else{
                        buf = (request.data as JSMultiplatformBuffer).nativeBuffer().subarray(request.data.cursor,request.data.limit)
                    }

                    suspendCoroutine<Unit> {
                        socket.write(buf){
                            if(!request.deferred.isCompleted){
                                request.data.cursor += buf.length
                                request.deferred.complete(true)
                                it.resume(Unit)
                            }
                        }
                        Unit
                    }

                }catch (e : dynamic){
                    val exc = SokException(e.toString())
                    request.deferred.completeExceptionally(exc)
                    throw exc
                }catch (e : Exception){
                    val exc = SokException(e.toString())
                    request.deferred.completeExceptionally(exc)
                    throw exc
                }
            }
        }

        channel.invokeOnClose {
            job.cancel()
        }

        return channel
    }

    /**
     * get a socket option and try to convert it to the given type
     *
     * @param name Option to get
     * @return the socket option
     */
    @Suppress("UNCHECKED_CAST")
    actual fun <T>getOption(name : Options) : SocketOption<T>{
        return when(name){
            Options.SO_KEEPALIVE -> SocketOption(Options.SO_KEEPALIVE,this.optionMap[Options.SO_KEEPALIVE] as T)
            Options.TCP_NODELAY -> SocketOption(Options.TCP_NODELAY,this.optionMap[Options.TCP_NODELAY] as T)

            else -> throw OptionNotSupportedException()
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
            Options.SO_KEEPALIVE -> {
                this.optionMap[Options.SO_KEEPALIVE] = option.value as Boolean
                this.socket.setKeepAlive(option.value as Boolean)
                true
            }
            Options.TCP_NODELAY -> {
                this.optionMap[Options.TCP_NODELAY] = option.value as Boolean
                this.socket.setNoDelay(option.value as Boolean)
                true
            }

            else -> false
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
    return Sok.Socket.TCP.TCPClientSocket(
            suspendCoroutine {
                //create a socket but don't connect it yet
                val socket = net.Socket(js("{allowHalfOpen:false,readable:true,writable:true}"))

                //bind the error listener to catch connetion refused errors
                socket.on("error") {
                    it.resumeWithException(ConnectionRefusedException())
                }

                //connect and wait for connection event
                socket.connect(port, address)
                socket.on("connect") {
                    //remove connection refused handler
                    socket.removeAllListeners("error")
                    it.resume(socket)
                }
            }
    )
}
package Sok.Socket.TCP

import Sok.Buffer.*
import Sok.Exceptions.ConnectionRefusedException
import Sok.Internal.net
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
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
 */
actual class TCPClientSocket{

    actual var isClosed = true
        private set

    /**
     * Actor managing write operations
     */
    private val writeChannel : SendChannel<WriteRequest>

    /**
     * Node.js socket
     */
    private val socket : dynamic

    /**
     * index in the internal stream buffer
     */
    private var indexInStream = 0

    /**
     * Close lambda to call when the socket closes
     */
    private var onClose : () -> Unit = {}

    /**
     * Continuation to resume when there is data to read. This continuation must be cancelled when the socket is closed
     */
    private var readingContinuation : CancellableContinuation<Unit>? = null

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
            GlobalScope.launch {
                this@TCPClientSocket.close()
            }
        }

        socket.on("close"){
            GlobalScope.launch {
                this@TCPClientSocket.close()
            }
        }

        socket.on("error"){
            GlobalScope.launch {
                this@TCPClientSocket.close()
            }
        }

        //start the write actor and bind the operation in case of failure (we close the socket)
        this.writeChannel = this.writeActor(this.socket){
            GlobalScope.launch(Dispatchers.Unconfined) {
                this@TCPClientSocket.close()
            }
        }

        //update state
        this.isClosed = false
    }

    /**
     * handler called when the socket close (expectantly or not)
     *
     * @param handler lambda called when the socket is closed
     */
    actual fun bindCloseHandler(handler : () -> Unit){
        this.onClose = handler
    }

    /**
     * gracefully stops the socket. The method suspends as it waits for all the writing requests in the channel to be
     * executed before effectively closing the channel
     */
    actual suspend fun close(){
        if(!this.isClosed){
            this.isClosed = true

            val deferred = CompletableDeferred<Boolean>()
            this.writeChannel.send(WriteRequest(allocMultiplatformBuffer(0), deferred))
            this.writeChannel.close()
            deferred.await()

            this.socket.end()
            this.readingContinuation?.cancel()
            this.onClose()
        }
    }

    /**
     * forcefully closes the channel without checking the writing request queue
     */
    actual fun forceClose(){
        if(!this.isClosed){
            this.isClosed = true
            this.readingContinuation?.cancel()
            this.writeChannel.close()
            this.socket.end()
            this.onClose()
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
     * @param buffer buffer used to store the data read. the cursor will be reset after each iteration. The limit of the buffer remains
     * untouched so the developer can chose the amout of data to read.
     *
     * @param operation lambda called after each read event. The first argument will be the buffer and the second the amount of data read
     *
     * @return Total number of byte read
     */
    actual suspend fun bulkRead(buffer : MultiplatformBuffer, operation : (buffer : MultiplatformBuffer, read : Int) -> Boolean) : Long{
        if(this.isClosed) return -1

        require(this.readingContinuation == null)

        var total = 0L
        (buffer as JSMultiplatformBuffer)

        buffer.cursor = 0
        this.registerReadable{
            //read while there is data
            while (this.readInto(buffer) != -1) {
                total += buffer.cursor
                val read = buffer.cursor
                buffer.cursor = 0
                //if the operation returns false, unregister
                if (!operation(buffer, read)) {
                    return@registerReadable false
                }
            }

            true
        }

        return total
    }

    /**
     * Perform a suspending read, the method will read n bytes ( 0 < n <= buffer.remaining() ) and update the cursor
     *
     * @param buffer buffer used to store the data read
     *
     * @return Number of byte read
     */
    actual suspend fun read(buffer: MultiplatformBuffer) : Int{
        if(this.isClosed) return -1
        require(buffer.remaining() > 0)
        require(this.readingContinuation == null)

        var read = -1
        this.registerReadable {
            (buffer as JSMultiplatformBuffer)
            read = this.readInto(buffer)
            false
        }

        return read
    }

    /**
     * Perform a suspending read, the method will read n bytes ( minToRead < n <= buffer.remaining() ) and update the cursor
     *
     * @param buffer buffer used to store the data read
     *
     * @return Number of byte read
     */
    actual suspend fun read(buffer: MultiplatformBuffer, minToRead : Int) : Int{
        if(this.isClosed) return -1

        require(buffer.remaining() >= minToRead)
        require(this.readingContinuation == null)

        val cursor = buffer.cursor

        this.registerReadable {
            (buffer as JSMultiplatformBuffer)
            this.readInto(buffer)

            buffer.cursor - cursor < minToRead
        }

        //if we read less than the min, something went wrong
        return if(buffer.cursor - cursor < minToRead){buffer.cursor - cursor}else{-1}
    }

    /**
     * Perform a suspending write, the method will not return until all the data between buffer.cursor and buffer.limit are written.
     * The socket use an internal write queue, allowing multiple threads to concurrently write. Backpressure mechanisms
     * should be implemented by the developer to avoid having too much data in the queue.
     *
     * @param buffer data to write
     *
     * @return Success of the operation
     */
    actual suspend fun write(buffer: MultiplatformBuffer) : Boolean{
        if(this.isClosed) return false

        val deferred = CompletableDeferred<Boolean>()
        this.writeChannel.send(WriteRequest(buffer, deferred))
        return deferred.await()
    }

    /**
     * Create an actor managing write operations and return the channel to which send the requests
     */
    private fun writeActor(socket : dynamic, onClose : () -> Unit) : SendChannel<WriteRequest>{
        val channel = Channel<WriteRequest>(Channel.UNLIMITED)
        GlobalScope.launch{
            for (request in channel){

                //close signal received
                if(request.data.capacity == 0){
                    request.deferred.complete(true)
                    continue
                }

                request.data.cursor = 0
                try {
                    val buf = (request.data as JSMultiplatformBuffer).nativeBuffer().subarray(request.data.cursor,request.data.limit)

                    suspendCancellableCoroutine<Unit> {
                        socket.write(buf){
                            request.data.cursor += buf.length
                            it.resume(Unit)
                        }
                    }
                    request.deferred.complete(true)
                }catch (e : dynamic){
                    request.deferred.complete(false)
                    onClose()
                }catch (e : Exception){
                    request.deferred.complete(false)
                    onClose()
                }
            }
        }

        return channel
    }

}

internal class WriteRequest(val data: MultiplatformBuffer, val deferred: CompletableDeferred<Boolean>)

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
                val socket = net.Socket(js("{allowHalfOpen:true,readable:true,writable:true}"))

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
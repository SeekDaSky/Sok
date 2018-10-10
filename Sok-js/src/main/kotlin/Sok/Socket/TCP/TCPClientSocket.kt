package Sok.Socket.TCP

import Sok.Buffer.*
import Sok.Exceptions.ConnectionRefusedException
import Sok.Sok.net
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlin.coroutines.experimental.suspendCoroutine
import kotlin.math.min

/**
 * Use the Net.socket package, as the sockets in node are also a Duplex Stream we use the stream oriented
 * approach rather that the event oriented one
 */
actual class TCPClientSocket{

    //client IP
    actual val clientIP = ""

    //socket state
    actual var isClosed = true
        private set

    //write actor (while writing those line kotlinx.coroutine does not support the actor() builder so we use a good old Job + channel
    private val writeChannel = Channel<WriteRequest>(Channel.UNLIMITED)
    private val writeCoroutine : Job

    //Node socket
    val socket : dynamic

    //index in the internal stream buffer
    var indexInStream = 0

    //close callback
    var onClose : () -> Unit = {}

    //reading/writing continuation (must be cancelled after closing socket)
    var readingContinuation : CancellableContinuation<Unit>? = null
    var writingContinuation : CancellableContinuation<Unit>? = null

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
        this.writeCoroutine = this.writeActor(this.socket,this.writeChannel){
            GlobalScope.launch(Dispatchers.Unconfined) {
                this@TCPClientSocket.close()
            }
        }

        //update state
        this.isClosed = false
    }

    /**
     * bind the function to call when the socket closes
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
            this.writingContinuation?.cancel()
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
            this.writingContinuation?.cancel()
            this.writeChannel.close()
            this.socket.end()
            this.onClose()
        }
    }

    /**
     * As nodeJS directly give us a buffer when we read from a socket, we have to copy data from the given buffer into
     * the user buffer.
     */
    private fun readInto(buffer : JSMultiplatformBuffer) : Boolean{
        //read from the socket, with a minimum or not
        val internalBuffer : Buffer? = this.socket.read()

        if(internalBuffer == null){
            return false
        }

        //if we did not unshit data and that the buffer is small enough to fit in the MultiplatformBuffer, swap the backbuffer
        if(internalBuffer.length <= buffer.limit && this.indexInStream == 0){
            buffer.limit = internalBuffer.copy(buffer.nativeBuffer(),buffer.cursor,0,internalBuffer.length) + buffer.cursor
            buffer.cursor = buffer.limit
        }else{
            //transfer data
            buffer.limit = internalBuffer.copy(buffer.nativeBuffer(),buffer.cursor,this.indexInStream,min(internalBuffer.length,buffer.remaining())) + buffer.cursor
            buffer.cursor = buffer.limit
            //if we read all the internal buffer, discard, else unshift it and update indexInStream
            if(this.indexInStream + buffer.limit != internalBuffer.length){
                this.indexInStream += buffer.limit
                this.socket.unshift(internalBuffer)
            }else{
                this.indexInStream = 0
            }
        }

        return true
    }

    /**
     * Suspend the caller until a "readable" event is received, when received the "operation" block is called. This
     * block returns either true if we need to listen for this event one more time, are false to unregister
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
     * As the register/unregister of event is expensive, if the developer wants to read large amount of data, instead
     * of doing a read() loop, it can use the bulkRead method to register a function that will stay registered to the
     * "readable" event and that will be called each time there is data tor read
     */
    actual suspend fun bulkRead(buffer : MultiplatformBuffer, operation : (buffer : MultiplatformBuffer) -> Boolean) : Long{
        if(this.isClosed) return -1

        var total = 0L
        (buffer as JSMultiplatformBuffer)

        this.registerReadable{
            //read while there is data
            while (this.readInto(buffer)) {
                total += buffer.limit
                //if the operation returns false, unregister
                if (!operation(buffer)) {
                    return@registerReadable false
                } else {
                    buffer.reset()
                }
            }

            true
        }

        return total
    }

    /**
     * read data into the given buffer
     */
    actual suspend fun read(buffer: MultiplatformBuffer) : Int{
        if(this.isClosed) return -1

        this.registerReadable {
            (buffer as JSMultiplatformBuffer)
            this.readInto(buffer)
            false
        }

        return buffer.limit
    }

    actual suspend fun read(buffer: MultiplatformBuffer, minToRead : Int) : Int{
        if(this.isClosed) return -1

        //backup buffer limit
        val limit = buffer.limit
        this.registerReadable {
            //if we are reading in multiple time, we have to set the limit to the original limit of the buffer each time
            buffer.limit = limit
            (buffer as JSMultiplatformBuffer)
            this.readInto(buffer)

            buffer.limit < minToRead
        }

        return buffer.limit
    }

    actual suspend fun write(buffer: MultiplatformBuffer) : Boolean{
        if(this.isClosed) return false

        val deferred = CompletableDeferred<Boolean>()
        if(!this.writeChannel.offer(WriteRequest(buffer, deferred))) deferred.complete(false)
        return deferred.await()
    }

    /**
     * Create a standalone write actor
     */
    private fun writeActor(socket : dynamic, channel: Channel<WriteRequest>, onClose : () -> Unit) = GlobalScope.launch{
        for (request in channel){

            //close signal received
            if(request.data.capacity == 0){
                request.deferred.complete(true)
                continue
            }

            request.data.cursor = 0
            try {
                val buf = (request.data as JSMultiplatformBuffer).nativeBuffer()

                suspendCancellableCoroutine<Unit> {
                    this@TCPClientSocket.writingContinuation = it
                    socket.write(buf){
                        this@TCPClientSocket.writingContinuation = null
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

}

class WriteRequest(val data: MultiplatformBuffer, val deferred: CompletableDeferred<Boolean>)

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
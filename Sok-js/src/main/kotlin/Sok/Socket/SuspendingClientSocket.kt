package Sok.Socket

import Sok.Buffer.Buffer
import Sok.Buffer.JSMultiplateformBuffer
import Sok.Buffer.MultiplateformBuffer
import Sok.Buffer.allocMultiplateformBuffer
import Sok.Exceptions.ConnectionRefusedException
import Sok.Sok.net
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.coroutines.experimental.suspendCoroutine
import kotlin.math.min

/**
 * Use the Net.socket package, as the sockets in node are also a Duplex Stream we use the stream oriented
 * approach rather that the event oriented one
 */
actual class SuspendingClientSocket{

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
                this@SuspendingClientSocket.close()
            }
        }

        socket.on("close"){
            GlobalScope.launch {
                this@SuspendingClientSocket.close()
            }
        }

        socket.on("error"){
            GlobalScope.launch {
                this@SuspendingClientSocket.close()
            }
        }

        //start the write actor and bind the operation in case of failure (we close the socket)
        this.writeCoroutine = this.writeActor(this.socket,this.writeChannel){
            GlobalScope.launch(Dispatchers.Unconfined) {
                this@SuspendingClientSocket.close()
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
            this.writeChannel.send(WriteRequest(allocMultiplateformBuffer(0),deferred))
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
     * the user buffer. In order to avoid data copy, the MultiplatformBuffer is lazy and will wait to be explicitely used to allocate
     * memory. Because of this behavior we can directly put the buffer returned by read() into the multiplatform buffer
     * and still be efficient. But as the developer "allocated" a specific amount of memory we can't use this trick if the
     * read buffer is larger than the user buffer. In this case we copy the needed data, store at what point we are in this buffer
     * and put the buffer back in the socket with the "unshift()" method.
     *
     * TODO: implement a way to use only parts of the buffer in the multiplatform buffer to avoid copies even more
     */
    private fun readInto(buffer : JSMultiplateformBuffer, minToRead: Int = 0) : Boolean{
        //read from the socket, with a minimum or not
        val internalBuffer : Buffer
        if(minToRead != 0){
            internalBuffer = this.socket.read(minToRead + this.indexInStream)
        }else{
            internalBuffer = this.socket.read()
        }

        //if nothing was read, return false
        if(internalBuffer == null){
            return false
        }

        //if we did not unshit data and that the buffer is small enough to fit in the MultiplatformBuffer, swap the backbuffer
        if(internalBuffer.length <= buffer.getLazySize() && this.indexInStream == 0){
            buffer.swapBackBuffers(internalBuffer)
        }else{
            //transfer data
            buffer.swapBackBuffers(internalBuffer,this.indexInStream,min(internalBuffer.length,buffer.getLazySize()+this.indexInStream))

            //if we read all the internal buffer, discard, else unshift it and update indexInStream
            if(this.indexInStream + buffer.size() != internalBuffer.length){
                this.indexInStream += buffer.size()
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
    actual suspend fun bulkRead(buffer : MultiplateformBuffer, operation : (buffer : MultiplateformBuffer) -> Boolean) : Long{
        if(this.isClosed) return -1

        var total = 0L
        (buffer as JSMultiplateformBuffer)

        this.registerReadable{
            //read while there is data
            while (this.readInto(buffer)) {
                total += buffer.size()
                //if the operation returns false, unregister
                if (!operation(buffer)) {
                    return@registerReadable false
                } else {
                    buffer.setCursor(0)
                }
            }

            true
        }

        return total
    }

    /**
     * read data into the given buffer
     */
    actual suspend fun read(buffer: MultiplateformBuffer) : Int{
        if(this.isClosed) return -1

        this.registerReadable {
            (buffer as JSMultiplateformBuffer)
            this.readInto(buffer)
            false
        }

        return buffer.size()
    }

    actual suspend fun read(buffer: MultiplateformBuffer, minToRead : Int) : Int{
        if(this.isClosed) return -1

        //as read(min) will return null if the socket does not contain enough data, we loop until the data is here
        var remaining = minToRead
        var tmpBuf = Buffer.alloc(0)
        this.registerReadable {
            //most unefficient line in history but I can't find any other way
            tmpBuf = Buffer.concat(listOf(tmpBuf,this.socket.read(min(remaining,this.socket.readableLength))).toTypedArray())
            remaining = minToRead - tmpBuf.length

            tmpBuf.length < minToRead
        }

        (buffer as JSMultiplateformBuffer)
        buffer.swapBackBuffers(tmpBuf)
        return buffer.size()
    }

    actual fun asynchronousRead(buffer: MultiplateformBuffer) : Deferred<Int>{
        return GlobalScope.async {
            this@SuspendingClientSocket.read(buffer)
        }
    }

    actual fun asynchronousRead(buffer: MultiplateformBuffer,  minToRead : Int) : Deferred<Int>{
        return GlobalScope.async {
            this@SuspendingClientSocket.read(buffer,minToRead)
        }
    }

    actual suspend fun write(buffer: MultiplateformBuffer) : Boolean{
        if(this.isClosed) return false

        return this.asynchronousWrite(buffer).await()
    }

    actual fun asynchronousWrite(buffer: MultiplateformBuffer) : Deferred<Boolean>{
        val deferred = CompletableDeferred<Boolean>()
        if(!this.writeChannel.offer(WriteRequest(buffer,deferred))) deferred.complete(false)
        return deferred
    }

    /**
     * Create a standalone write actor
     */
    private fun writeActor(socket : dynamic, channel: Channel<WriteRequest>, onClose : () -> Unit) = GlobalScope.launch{
        for (request in channel){

            //close signal received
            if(request.data.size() == 0){
                request.deferred.complete(true)
                continue
            }

            request.data.setCursor(0)
            try {
                val buf = (request.data as JSMultiplateformBuffer).nativeBuffer()

                suspendCancellableCoroutine<Unit> {
                    this@SuspendingClientSocket.writingContinuation = it
                    socket.write(buf){
                        this@SuspendingClientSocket.writingContinuation = null
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

class WriteRequest(val data: MultiplateformBuffer, val deferred: CompletableDeferred<Boolean>)

actual suspend fun createSuspendingClientSocket(address : String, port : Int ) : SuspendingClientSocket{
    return SuspendingClientSocket(
        suspendCoroutine {
            //create a socket but don't connect it yet
            val socket = net.Socket(js("{allowHalfOpen:true,readable:true,writable:true}"))

            //bind the error listener to catch connetion refused errors
            socket.on("error"){
                it.resumeWithException(ConnectionRefusedException())
            }

            //connect and wait for connection event
            socket.connect(port,address)
            socket.on("connect"){
                //remove connection refused handler
                socket.removeAllListeners("error")
                it.resume(socket)
            }
        }
    )
}
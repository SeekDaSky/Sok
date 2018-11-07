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

/**
 * Class representing a client socket. You can use it to perform any I/O operation. Keep in mind that this class keep an internal
 * queue for write operations thus storing data until written so you should have some kind of backpressure mechanism to prevent
 * the accumulation of too many data.
 *
 * @property isClosed Keep track of the socket status
 */
actual class TCPClientSocket{

    /**
     * Lmbda called when the socket closes
     */
    private val closeHandler : AtomicRef<() -> Unit> = atomic({})

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

    /**
     * Selection key of the socket
     */
    private val selectionKey : SelectionKey

    /**
     * Actor managing the write operations
     */
    private val writeChannel : Channel<WriteRequest>

    /**
     * Construtor used by the Server socket to build the client socket
     *
     * @param socket file descriptor of the socket
     * @param selector Selector managing the socket
     */
    constructor(socket : Int,selector : Selector){
        this.selectionKey = selector.register(socket)
        this.writeChannel = this.createWriteActor(this.selectionKey, this.getSendBufferSize()){
            GlobalScope.launch{
                this@TCPClientSocket.close()
            }
        }
    }

    /**
     * Constructor used by the createTCPClientSocket function, as the function already create and use a SelectionKey
     * we reuse it.
     *
     * @param selectionKey selection key managing the socket
     */
    internal constructor(selectionKey : SelectionKey){
        this.selectionKey = selectionKey
        this.writeChannel = this.createWriteActor(this.selectionKey, this.getSendBufferSize()){
            GlobalScope.launch{
                this@TCPClientSocket.close()
            }
        }
    }

    /**
     * TODO: replace this by a proper way to get/set socket options
     */
    private fun getSendBufferSize() : Int{
        return memScoped{
            val size = alloc<IntVar>()
            val len = alloc<socklen_tVar>()
            len.value = sizeOf<IntVar>().toUInt()
            getsockopt(this@TCPClientSocket.selectionKey.socket, SOL_SOCKET, SO_SNDBUF, size.ptr, len.ptr)
            size.value
        }
    }

    /**
     * handler called when the socket close (expectantly or not)
     *
     * @param handler lambda called when the socket is closed
     */
    actual fun bindCloseHandler(handler : () -> Unit){
        this.closeHandler.value = handler
    }

    /**
     * gracefully stops the socket. The method suspends as it waits for all the writing requests in the channel to be
     * executed before effectively closing the channel
     */
    actual suspend fun close(){
        if(this._isClosed.compareAndSet(false,true)) {
            /**
             * Channels are fair, coroutine dispatching is also fair, but consider the "Client wait for the end of the send queue before close"
             * test case, the buffers are all written asynchronously, thus launching coroutines but not suspending. In this case the execution will continue
             * until the close() call, it's only when we do that that we suspend and let all the launched coroutine to be dispatched. We need to
             * yield() before doing anything else to let the possibly not dispatched coroutine to be so. If you wonder how much time I spent on this
             * bug, assume that it's a lot
             */
            yield()


            val deferred = CompletableDeferred<Boolean>()
            this.writeChannel.send(WriteRequest(allocMultiplatformBuffer(0),deferred))
            this.writeChannel.close()
            deferred.await()
            this.selectionKey.close()
            this.closeHandler.value()
        }
    }

    /**
     * forcefully closes the channel without checking the writing request queue
     */
    actual fun forceClose(){
        if(this._isClosed.compareAndSet(false,true)){
            this.writeChannel.cancel()
            this.selectionKey.close()
            this.closeHandler.value()
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
    actual suspend fun bulkRead(buffer : MultiplatformBuffer, operation : (buffer : MultiplatformBuffer, read : Int) -> Boolean) : Long {
        if(this.isClosed) return -1

        require(!this.isReading.value)
        this.isReading.value = true

        buffer as NativeMultiplatformBuffer
        var read : Long = 0
        this.selectionKey.selectAlways(Interests.OP_READ){

            val result = read(this.selectionKey.socket,buffer.nativePointer(),buffer.limit.toULong()).toInt()

            if(result == -1 || result == 0){
                read = -1
                false
            }else{
                read += result
                buffer.cursor = 0
                operation(buffer,result)
            }
        }

        if(read == -1.toLong()){
            this.close()
        }

        this.isReading.value = false
        return read
    }

    /**
     * Perform a suspending read, the method will read n bytes ( 0 < n <= buffer.remaining() ) and update the cursor
     *
     * @param buffer buffer used to store the data read
     *
     * @return Number of byte read
     */
    actual suspend fun read(buffer: MultiplatformBuffer) : Int {
        if(this.isClosed) return -1

        require(buffer.remaining() > 0)

        require(!this.isReading.value)
        this.isReading.value = true

        this.selectionKey.select(Interests.OP_READ)

        buffer as NativeMultiplatformBuffer

        val result = read(this.selectionKey.socket,buffer.nativePointer()+buffer.cursor,buffer.remaining().toULong()).toInt()

        if(result == -1 || result == 0){
            this.close()
            return -1
        }

        buffer.cursor = result

        this.isReading.value = false

        return result
    }

    /**
     * Perform a suspending read, the method will read n bytes ( minToRead < n <= buffer.remaining() ) and update the cursor
     *
     * @param buffer buffer used to store the data read
     *
     * @return Number of byte read
     */
    actual suspend fun read(buffer: MultiplatformBuffer, minToRead : Int) : Int {
        if(this.isClosed) return -1

        require(buffer.remaining() >= minToRead)

        require(!this.isReading.value)
        this.isReading.value = true

        buffer as NativeMultiplatformBuffer

        var read = 0

        this.selectionKey.selectAlways(Interests.OP_READ){
            val result = read(this.selectionKey.socket,buffer.nativePointer()+buffer.cursor,buffer.remaining().toULong()).toInt()

            if(result == -1){
                read = -1
                false
            }else{
                buffer.cursor = buffer.cursor + result
                read += result
                read < minToRead
            }
        }

        if(read == -1){
            this.close()
            return -1
        }

        this.isReading.value = false

        return read
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
    actual suspend fun write(buffer: MultiplatformBuffer) : Boolean {
        if(this.isClosed) return false

        val deferred = CompletableDeferred<Boolean>()
        this.writeChannel.send(WriteRequest(buffer,deferred))
        return deferred.await()
    }

    /**
     * Create the actor managing write oeprations
     */
    private fun createWriteActor(selectionKey: SelectionKey, sendBufferSize : Int, onError: () -> Unit) : Channel<WriteRequest> {
        val channel = Channel<WriteRequest>()
        GlobalScope.launch{
            for(request in channel){

                val buffer = request.data as NativeMultiplatformBuffer
                buffer.cursor = 0

                //fail fast for empty buffers
                if(buffer.capacity == 0){
                    request.deferred.complete(true)
                    continue
                }

                if(buffer.limit > sendBufferSize){
                    selectionKey.selectAlways(Interests.OP_WRITE){
                        val result = write(selectionKey.socket,buffer.nativePointer()+buffer.cursor,buffer.remaining().toULong()).toInt()

                        if(result == -1){
                            request.deferred.complete(false)
                            false
                        }else{
                            buffer.cursor = buffer.cursor+result
                            buffer.remaining() > 0
                        }
                    }

                    if(!request.deferred.isCompleted){
                        request.deferred.complete(true)
                    }else{
                        onError()
                    }
                }else{
                    while (buffer.remaining() > 0){

                        val result = write(selectionKey.socket,buffer.nativePointer()+buffer.cursor,buffer.remaining().toULong()).toInt()

                        if(result == -1){
                            request.deferred.complete(false)
                            onError()
                            continue
                        }

                        buffer.cursor = buffer.cursor + result

                        if(buffer.remaining() >= 0) selectionKey.select(Interests.OP_WRITE)
                    }
                    request.deferred.complete(true)
                }
            }
        }
        return channel
    }
}

internal class WriteRequest(val data : MultiplatformBuffer, val deferred : CompletableDeferred<Boolean>)

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

        //can't figure out how to convert result to the righ type yet
        //freeaddrinfo(result.ptr)

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

    return TCPClientSocket(selectionKey)
}

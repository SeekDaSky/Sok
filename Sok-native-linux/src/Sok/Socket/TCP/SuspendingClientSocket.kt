package Sok.Socket.TCP

import Sok.Buffer.*
import Sok.Selector.*
import Sok.Exceptions.*
import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.cinterop.*
import platform.posix.*

actual class TCPClientSocket{

    actual val clientIP : String = ""

    private val closeHandler : AtomicRef<() -> Unit> = atomic({})

    private val _isClosed : AtomicBoolean = atomic(false)
    actual var isClosed : Boolean
        get() {
            return this._isClosed.value
        }
        private set(value) {
            this._isClosed.value = value
        }

    private val selectionKey : SelectionKey

    private val writeChannel = Channel<WriteRequest>()
    private val writeActor : Job

    constructor(socket : Int,selector : Selector){
        this.selectionKey = selector.register(socket)
        this.writeActor = this.createWriteActor(this.writeChannel,this.selectionKey, this.getSendBufferSize()){
            GlobalScope.launch{
                this@TCPClientSocket.close()
            }
        }
    }

    constructor(selectionKey : SelectionKey){
        this.selectionKey = selectionKey
        this.writeActor = this.createWriteActor(this.writeChannel,this.selectionKey, this.getSendBufferSize()){
            GlobalScope.launch{
                this@TCPClientSocket.close()
            }
        }
    }

    private fun getSendBufferSize() : Int{
        return memScoped{
            val size = alloc<IntVar>()
            val len = alloc<socklen_tVar>()
            len.value = sizeOf<IntVar>().toInt()
            getsockopt(this@TCPClientSocket.selectionKey.socket, SOL_SOCKET, SO_SNDBUF, size.ptr, len.ptr)
            size.value
        }
    }

    actual fun bindCloseHandler(handler : () -> Unit){
        this.closeHandler.value = handler
    }

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
            this.writeActor.cancel()
            this.selectionKey.close()
            this.closeHandler.value()
        }
    }

    actual fun forceClose(){
        if(this._isClosed.compareAndSet(false,true)){
            this.writeChannel.cancel()
            this.selectionKey.close()
            this.closeHandler.value()
        }
    }

    actual suspend fun bulkRead(buffer : MultiplatformBuffer, operation : (buffer : MultiplatformBuffer) -> Boolean) : Long {
        if(this.isClosed) return -1

        buffer as NativeMultiplatformBuffer
        var read : Long = 0
        this.selectionKey.selectAlways(Interests.OP_READ){

            buffer.reset()
            val result = read(this.selectionKey.socket,buffer.nativePointer(),buffer.capacity.signExtend<size_t>()).toInt()

            if(result == -1 && posix_errno() != EAGAIN){
                read = -1
                false
            }else{
                read += result
                buffer.limit = result
                buffer.cursor = result
                operation(buffer)
            }
        }

        if(read == -1.toLong()){
            this.close()
        }

        return read
    }

    actual suspend fun read(buffer: MultiplatformBuffer) : Int {
        if(this.isClosed) return -1

        this.selectionKey.select(Interests.OP_READ)

        buffer as NativeMultiplatformBuffer

        val result = read(this.selectionKey.socket,buffer.nativePointer()+buffer.cursor,(buffer.limit-buffer.cursor).signExtend<size_t>()).toInt()

        if(result == -1 || result == 0){
            this.close()
            return -1
        }

        buffer.limit = result
        buffer.cursor = result
        return result
    }

    actual suspend fun read(buffer: MultiplatformBuffer, minToRead : Int) : Int {
        if(this.isClosed) return -1

        buffer as NativeMultiplatformBuffer

        var read = 0

        this.selectionKey.selectAlways(Interests.OP_READ){
            val result = read(this.selectionKey.socket,buffer.nativePointer()+buffer.cursor,(buffer.limit-buffer.cursor).signExtend<size_t>()).toInt()

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

        buffer.limit = read

        return read
    }

    actual fun asynchronousRead(buffer: MultiplatformBuffer) : Deferred<Int> {
        return GlobalScope.async{
            this@TCPClientSocket.read(buffer)
        }
    }

    actual fun asynchronousRead(buffer: MultiplatformBuffer,  minToRead : Int) : Deferred<Int> {
        return GlobalScope.async{
            this@TCPClientSocket.read(buffer,minToRead)
        }
    }

    actual suspend fun write(buffer: MultiplatformBuffer) : Boolean {
        if(this.isClosed) return false

        val deferred = CompletableDeferred<Boolean>()
        this.writeChannel.send(WriteRequest(buffer,deferred))
        return deferred.await()
    }

    actual fun asynchronousWrite(buffer: MultiplatformBuffer) : Deferred<Boolean> {
        return GlobalScope.async{
            this@TCPClientSocket.write(buffer)
        }
    }

    private fun createWriteActor(channel : Channel<WriteRequest>, selectionKey: SelectionKey, sendBufferSize : Int, onError: () -> Unit) = GlobalScope.launch{
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
                    val result = write(selectionKey.socket,buffer.nativePointer()+buffer.cursor,buffer.remaining().signExtend<size_t>()).toInt()

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

                    val result = write(selectionKey.socket,buffer.nativePointer()+buffer.cursor,buffer.remaining().signExtend<size_t>()).toInt()

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
}

private class WriteRequest(val data : MultiplatformBuffer, val deferred : CompletableDeferred<Boolean>)


actual suspend fun createTCPClientSocket(address : String, port : Int ) : TCPClientSocket {
    var socket : Int = 0

    memScoped{
        val hints = alloc<addrinfo>()
        val result = allocPointerTo<addrinfo>()

        //set hints
        with(hints) {
            memset(this.ptr, 0, addrinfo.size)
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
        len.value = sizeOf<IntVar>().toInt()
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

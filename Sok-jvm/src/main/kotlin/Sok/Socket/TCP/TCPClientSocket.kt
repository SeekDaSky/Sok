package Sok.Socket.TCP

import Sok.Buffer.*
import Sok.Exceptions.ConnectionRefusedException
import Sok.Selector.Selector
import Sok.Selector.SelectorPool
import Sok.Selector.SuspentionMap
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

/**
 * The actual documentation and comments are located in the common module class
 */
actual class TCPClientSocket {

    //socket related data
    private val channel: SocketChannel

    //write/read actors
    private val writeActor: SendChannel<WriteRequest>

    //socket state (I have to force the use of AtomicRef because of a weird bug in AtomicFU)
    //the public property isClosed is backup by this atomic
    private val _isClosed : AtomicRef<Boolean> =  atomic<Boolean>(true)

    actual var isClosed
        private set(value){
            this._isClosed.value = value
        }
        get() = this._isClosed.value

    // callbacks
    @Volatile
    private var onClose : () -> Unit = {}

    //suspention map
    private val suspentionMap : SuspentionMap

    //helper constructor
    constructor(channel : SocketChannel, selectorPool: SelectorPool) : this(channel,runBlocking(Dispatchers.Unconfined){selectorPool.getLessbusySelector()})

    constructor(channel : SocketChannel, selector: Selector){

        //store channel and IP (because you can't get remoteIP if channel is closed, it's better to store it directly)
        this.channel = channel
        //configure the socket for NIO use
        channel.configureBlocking(false)
        channel.setOption(StandardSocketOptions.TCP_NODELAY,true)

        //create suspention map
        this.suspentionMap = SuspentionMap(selector,channel)

        //update state
        this.isClosed = false

        //launch write/read actor
        this.writeActor = this.writeActor(this.suspentionMap,this.channel){
            runBlocking(Dispatchers.IO) {
                this@TCPClientSocket.close()
            }
        }

    }

    actual fun bindCloseHandler(handler : () -> Unit){
        this.onClose = handler
    }

    actual suspend fun close(){
        if(this._isClosed.compareAndSet(false,true)){
            /** If some coroutines are launched in the same scope as the server scope
             * and that those coroutines want to write, the server might close before them
             * even if they were launched before the actual close() statement, in order to
             * avoid that, we yield first
             */
            yield()

            //wait for the write actor to consume everything in the channel
            val deferred = CompletableDeferred<Boolean>()
            this.writeActor.send(WriteRequest(allocMultiplatformBuffer(0),deferred))
            this.writeActor.close()
            deferred.await()

            this.suspentionMap.close()
            this.channel.close()
            this.onClose()
        }
    }

    actual fun forceClose(){
        if(this._isClosed.compareAndSet(false,true)){
            this@TCPClientSocket.writeActor.close()
            this.suspentionMap.close()
            this.channel.close()
            this.onClose()
        }
    }

    actual suspend fun bulkRead(buffer : MultiplatformBuffer, operation : (buffer : MultiplatformBuffer) -> Boolean) : Long{
        if(this.isClosed){
            return -1
        }

        (buffer as JVMMultiplatformBuffer)
        var read : Long = 0

        this.suspentionMap.selectAlways(SelectionKey.OP_READ){
            buffer.reset()

            val tmpRead = this.channel.read(buffer.nativeBuffer())
            if(tmpRead > 0){
                read += tmpRead

                buffer.nativeBuffer().flip()
                buffer.limit = buffer.nativeBuffer().limit()
                buffer.cursor = 0

                //let the operation registered by the user tell if we need another selection
                operation.invoke(buffer)
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

    actual suspend fun read(buffer: MultiplatformBuffer) : Int{
        if(this.isClosed){
            return -1
        }

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

    actual suspend fun read(buffer: MultiplatformBuffer, minToRead : Int) : Int{
        require(buffer.remaining() >= minToRead)
        if(this.isClosed){
            return -1
        }

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

    actual suspend fun write(buffer: MultiplatformBuffer) : Boolean{
        if(this.writeActor.isClosedForSend){
            return false
        }

        val deferred = CompletableDeferred<Boolean>()
        this.writeActor.send(WriteRequest(buffer, deferred))

        return deferred.await()
    }

    /**
     * Create a standalone write actor
     */
    private fun writeActor(selectorManager: SuspentionMap, channel: SocketChannel, onClose : () -> Unit) = GlobalScope.actor<WriteRequest>(Dispatchers.IO){
        for (request in this.channel){

            request.data.cursor = 0
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
                            onClose()
                            false
                        }
                    }
                }else{
                    //even if our buffer is smaller than our TCP send buffer, a single write might not be enough
                    while (buf.hasRemaining()){
                        try {
                            channel.write(buf)
                        }catch (e : Exception){
                            onClose()
                            continue
                        }

                        if(buf.hasRemaining()) selectorManager.selectOnce(SelectionKey.OP_WRITE)
                    }
                }

                request.data.cursor = request.data.limit
                request.deferred.complete(true)
            }catch (e : Exception){
                request.deferred.complete(false)
                onClose()
            }
        }
    }

}

class WriteRequest(val data: MultiplatformBuffer, val deferred: CompletableDeferred<Boolean>)

/**
 * This function will create a client socket with either the default selector pool if it is initialized of with a single default Selector
 * The default SelectorPool is only initialized if a ServerSocket is create at some point, the goal is that a ClientSocket use the minimum
 * number of selector possible
 */
actual suspend fun createTCPClientSocket(address: String, port: Int) : TCPClientSocket {
    val socket = SocketChannel.open()
    socket.configureBlocking(false)
    socket.connect(InetSocketAddress(address,port))

    //create temporary suspention map, You should not close as the selection key would be cancelled
    val suspentionMap = SuspentionMap(Selector.defaultSelector,socket)
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
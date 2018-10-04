package Sok.Socket

import Sok.Buffer.*
import Sok.Exceptions.ConnectionRefusedException
import Sok.Selector.Selector
import Sok.Selector.SelectorPool
import Sok.Selector.SuspentionMap
import com.sun.org.apache.xpath.internal.operations.Bool
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

/**
 * The actual documentation and comments are located in the common module class
 */
actual class SuspendingClientSocket {

    //socket related data
    private val channel: SocketChannel
    actual val clientIP: String

    //write/read actors
    private val writeActor: SendChannel<WriteRequest>
    private val readActor: SendChannel<ReadRequest>

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
        this.clientIP = channel.remoteAddress.toString()
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
                this@SuspendingClientSocket.close()
            }
        }

        this.readActor = this.readActor(this.suspentionMap,this.channel){
            runBlocking(Dispatchers.IO) {
                this@SuspendingClientSocket.close()
            }
        }

    }

    actual fun bindCloseHandler(handler : () -> Unit){
        this.onClose = handler
    }

    actual suspend fun close(){
        if(this._isClosed.compareAndSet(false,true)){
            //wait for the write actor to consume everything in the channel
            runBlocking(Dispatchers.IO) {
                val deferred = this@SuspendingClientSocket.asynchronousWrite(allocMultiplatformBuffer(0))
                this@SuspendingClientSocket.writeActor.close()
                deferred.await()
            }

            this.suspentionMap.close()
            this.channel.close()
            this.onClose()
        }
    }

    actual fun forceClose(){
        if(this._isClosed.compareAndSet(false,true)){
            this@SuspendingClientSocket.writeActor.close()
            this.suspentionMap.close()
            this.channel.close()
            this.onClose()
        }
    }

    actual suspend fun bulkRead(buffer : MultiplatformBuffer, operation : (buffer : MultiplatformBuffer) -> Boolean) : Long{
        if(this.isClosed){
            return -1
        }

        val deferred = CompletableDeferred<Long>()
        this.readActor.send(ReadAlwaysRequest(buffer,deferred,true,operation))
        return deferred.await()
    }

    actual suspend fun read(buffer: MultiplatformBuffer) : Int{
        if(this.isClosed){
            return -1
        }

        val deferred = CompletableDeferred<Int>()
        this.readActor.send(ReadOnceRequest(buffer,deferred))
        return deferred.await()
    }

    actual suspend fun read(buffer: MultiplatformBuffer, minToRead : Int) : Int{
        if(this.isClosed){
            return -1
        }

        val deferred = CompletableDeferred<Long>()

        //loop until the cursor has passed the minimum
        val request = ReadAlwaysRequest(buffer,deferred,false){
            if(it.cursor >= minToRead){
                (it as JVMMultiplatformBuffer).nativeBuffer().flip()
                it.limit = it.nativeBuffer().limit()
                it.cursor = 0
                true
            }else{
                false
            }
        }

        this.readActor.send(request)
        return deferred.await().toInt()
    }

    actual fun asynchronousRead(buffer: MultiplatformBuffer) : Deferred<Int>{
        if(this.isClosed){
            return CompletableDeferred(-1)
        }

        val deferred = CompletableDeferred<Int>()
        this.readActor.sendBlocking(ReadOnceRequest(buffer,deferred))
        return deferred
    }

    actual fun asynchronousRead(buffer: MultiplatformBuffer, minToRead : Int) : Deferred<Int>{
        if(this.isClosed){
            return CompletableDeferred(-1)
        }

        val deferred = CompletableDeferred<Long>()

        //loop until the cursor has passed the minimum
        val request = ReadAlwaysRequest(buffer,deferred,false){
            if(it.cursor >= minToRead){
                (it as JVMMultiplatformBuffer).nativeBuffer().flip()
                it.limit = it.nativeBuffer().limit()
                it.cursor = 0
                true
            }else{
                false
            }
        }
        this.readActor.sendBlocking(request)

        //TODO: change this really ugly trick
        return GlobalScope.async(Dispatchers.IO) {
            deferred.await().toInt()
        }
    }

    actual suspend fun write(buffer: MultiplatformBuffer) : Boolean{
        if(this.writeActor.isClosedForSend){
            return false
        }

        val deferred = CompletableDeferred<Boolean>()
        this.writeActor.send(WriteRequest(buffer,deferred))

        return deferred.await()
    }

    actual fun asynchronousWrite(buffer: MultiplatformBuffer) : Deferred<Boolean>{
        if(this.writeActor.isClosedForSend){
            return CompletableDeferred(false)
        }

        val deferred = CompletableDeferred<Boolean>()
        this.writeActor.sendBlocking(WriteRequest(buffer,deferred))

        return deferred
    }

    /**
     * Create a standalone read actor, the actor support single read and bulkReads. A bulkRead will use the selectAlways() method of the suspention
     * map that will then register the interest for an undefined number of selection, thus avoiding multiple register
     */
    private fun readActor(selectorManager: SuspentionMap, channel: SocketChannel, onClose : () -> Unit) = GlobalScope.actor<ReadRequest>(Dispatchers.IO){
        for (request in this.channel){
            when(request){
                is ReadOnceRequest -> {
                    //wait for the selector to detect data then read
                    selectorManager.selectOnce(SelectionKey.OP_READ)
                    val read = channel.read((request.buffer as JVMMultiplatformBuffer).nativeBuffer())

                    //if the channel returns -1 it means that the channel has been closed
                    if(read == -1){
                        onClose()
                    }

                    request.deferred.complete(read)
                }

                is ReadAlwaysRequest -> {
                    //total number of bytes read. As multiple reads may be made, the final size may exceed the max size of an Int
                    var totalRead = 0L

                    //select an interest for an undefined number of selection and the operation to call for each selection
                    selectorManager.selectAlways(SelectionKey.OP_READ){

                        //when reading a minimum number of bytes we don't want to reset the buffer at each selection
                        if(request.shouldResetBuffer){
                            (request.buffer as JVMMultiplatformBuffer).nativeBuffer().clear()
                        }

                        //read and call the close lambda in case of a closed channel
                        val read = channel.read((request.buffer as JVMMultiplatformBuffer).nativeBuffer())
                        if(read != 0){
                            totalRead += read

                            if(request.shouldResetBuffer){
                                request.buffer.nativeBuffer().flip()
                                request.buffer.limit = request.buffer.nativeBuffer().limit()
                                request.buffer.cursor = 0
                            }

                            //let the operation registered by the user tell if we need another selection
                            request.operation.invoke(request.buffer)
                        }else{
                            onClose()
                            false
                        }
                    }
                    request.deferred.complete(totalRead)
                }
            }
        }
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

                request.deferred.complete(true)
            }catch (e : Exception){
                request.deferred.complete(false)
                onClose()
            }
        }
    }

}

class WriteRequest(val data: MultiplatformBuffer, val deferred: CompletableDeferred<Boolean>)

sealed class ReadRequest(val buffer: MultiplatformBuffer)
class ReadOnceRequest(buffer: MultiplatformBuffer, val deferred: CompletableDeferred<Int>) : ReadRequest(buffer)
class ReadAlwaysRequest(buffer: MultiplatformBuffer, val deferred: CompletableDeferred<Long>, val shouldResetBuffer : Boolean, val operation : (buffer : MultiplatformBuffer) -> Boolean) : ReadRequest(buffer)

/**
 * This function will create a client socket with that use the default selector, it's fine as long as the selector
 * is ont saturated. If so it's important to create a SelectorPool and use it
 */
actual suspend fun createSuspendingClientSocket(address : String, port : Int ) : SuspendingClientSocket{

    return createSuspendingClientSocket(address,port, Selector.defaultSelector)
}

/**
 * Create a client socket from scratch
 */
suspend fun createSuspendingClientSocket(address: String, port: Int, selector: Selector) : SuspendingClientSocket{
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

    return SuspendingClientSocket(socket, selector)
}
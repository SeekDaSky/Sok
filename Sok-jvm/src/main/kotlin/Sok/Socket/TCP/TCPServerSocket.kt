package Sok.Socket.TCP

import Sok.Buffer.BufferPool
import Sok.Selector.Selector
import Sok.Selector.SelectorPool
import Sok.Selector.SuspentionMap
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.experimental.*
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel

actual class TCPServerSocket {

    //default buffer size
    val BUFSIZE = 65536

    // callbacks
    @Volatile
    private var onClose : () -> Unit = {}

    //socket state
    private val _isClosed = atomic<Boolean>(true)

    actual var isClosed
        private set(value){
            this._isClosed.value = value
        }
        get() = this._isClosed.value

    //channel
    private val channel : ServerSocketChannel

    //selector pool
    private val selectorPool : SelectorPool

    //suspention map
    private val suspentionMap : SuspentionMap

    //bufferPool
    private val bufferPool : BufferPool

    actual constructor(address : String, port : Int) : this(address,port,16)

    constructor(address : String, port : Int, bufferPoolSize : Int){
        //open channel and register it
        this.channel = ServerSocketChannel.open()
        this.channel.bind(InetSocketAddress(address,port))
        this.channel.configureBlocking(false)

        //update state
        this.isClosed = false

        //build selector pool
        this.selectorPool = Selector.defaultSelectorPool

        //create suspention map
        val selector = runBlocking(Dispatchers.Unconfined) {
            selectorPool.getLessbusySelector()
        }

        this.suspentionMap = SuspentionMap(selector,this@TCPServerSocket.channel)

        //build bufferPool
        this.bufferPool = BufferPool(bufferPoolSize,BUFSIZE)

    }

    actual suspend fun accept() : TCPClientSocket {
        return withContext(Dispatchers.IO){
            this@TCPServerSocket.suspentionMap.selectOnce(SelectionKey.OP_ACCEPT)
            try{
                val channel = this@TCPServerSocket.channel.accept()
                channel.setOption(StandardSocketOptions.SO_RCVBUF,BUFSIZE)
                Sok.Socket.TCP.TCPClientSocket(channel, this@TCPServerSocket.selectorPool)

            }catch (e : ClosedChannelException){
                this@TCPServerSocket.close()
                throw e
            }

        }
    }

    actual fun bindCloseHandler(handler : () -> Unit){
        this.onClose = handler
    }

    actual fun close(){
        //get the state and set it to false, if it is already closed, do nothing
        if(!this._isClosed.getAndSet(true)){
            //stop selection loop
            this.suspentionMap.close()
            this.channel.close()
            this.onClose.invoke()
        }
    }
}

actual fun createTCPServer(address: String, port: Int, scope : CoroutineScope, serverFunction : suspend (server : TCPServerSocket) -> Unit ){
    val server = TCPServerSocket(address,port)
    scope.launch{
        try {
            serverFunction.invoke(server)
        } finally {
            server.close()
        }
    }
}
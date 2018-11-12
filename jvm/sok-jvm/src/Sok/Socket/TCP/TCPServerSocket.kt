package Sok.Socket.TCP

import Sok.Buffer.BufferPool
import Sok.Exceptions.NormalCloseException
import Sok.Exceptions.handleException
import Sok.Selector.Selector
import Sok.Selector.SelectorPool
import Sok.Selector.SuspentionMap
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel

/**
 * Class representing a listening socket. You can use it to perform accept() operation only.
 *
 * @property isClosed keep track of the socket state
 */
actual class TCPServerSocket {

    /**
     * Lambda called when the socket closes
     */
    @Volatile
    private var onClose : () -> Unit = {}

    /**
     * Atomic backing the isClosed property
     */
    private val _isClosed = atomic<Boolean>(true)

    actual var isClosed : Boolean
        private set(value){
            this._isClosed.value = value
        }
        get() = this._isClosed.value

    /**
     * NIO channel
     */
    private val channel : ServerSocketChannel

    /**
     * Suspention map managing the socket
     */
    private val suspentionMap : SuspentionMap

    var exceptionHandler : (exception : Throwable) -> Unit = {}

    /**
     * Exception handler used to catch everything that comes from the internal coroutines
     */
    private val internalExceptionHandler = CoroutineExceptionHandler{_,e ->
        this.close()
        this.exceptionHandler(e)
    }

    /**
     * Start a listening socket on the given address (or alias) and port
     *
     * @param address IP to listen to
     * @param port port to listen to
     *
     */
    actual constructor(address : String, port : Int){
        //open channel and register it
        this.channel = ServerSocketChannel.open()
        this.channel.bind(InetSocketAddress(address,port))
        this.channel.configureBlocking(false)

        //update state
        this.isClosed = false

        //create suspention map
        val selector = runBlocking(Dispatchers.Unconfined) {
            Selector.defaultSelectorPool.getLessbusySelector()
        }

        this.suspentionMap = SuspentionMap(selector,this@TCPServerSocket.channel,this.internalExceptionHandler)
    }

    /**
     * Accept a client socket. The method will suspend until there is a client to accept
     *
     * @return accepted socket
     */
    actual suspend fun accept() : TCPClientSocket {
        return withContext(Dispatchers.IO+this.internalExceptionHandler){
            this@TCPServerSocket.suspentionMap.selectOnce(SelectionKey.OP_ACCEPT)
            try{
                val channel = this@TCPServerSocket.channel.accept()
                Sok.Socket.TCP.TCPClientSocket(channel, Selector.defaultSelectorPool)

            }catch (e : ClosedChannelException){
                this@TCPServerSocket.close()
                throw e
            }

        }
    }

    /**
     * handler called when the socket close (expectedly or not)
     *
     * @param handler lambda called when the socket is closed
     */
    actual fun bindCloseHandler(handler : () -> Unit){
        this.onClose = handler
    }

    /**
     * close the server socket
     */
    actual fun close(){
        //get the state and set it to false, if it is already closed, do nothing
        if(!this._isClosed.getAndSet(true)){
            //stop selection loop
            this.suspentionMap.close()
            this.channel.close()
            this.onClose.invoke()
            this.internalExceptionHandler.handleException(NormalCloseException())
        }
    }
}
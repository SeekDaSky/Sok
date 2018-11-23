package Sok.Socket.TCP

import Sok.Buffer.BufferPool
import Sok.Exceptions.*
import Sok.Selector.Selector
import Sok.Selector.SelectorPool
import Sok.Selector.SuspentionMap
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import java.net.BindException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.AlreadyBoundException
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel

/**
 * Class representing a listening socket. You can use it to perform accept() operation only.
 *
 * @property isClosed keep track of the socket state
 * @property exceptionHandler Lambda that will be called when an exception resulting in the closing of the socket is thrown,
 * for further information look at the "Exception model" part of the README
 */
actual class TCPServerSocket{

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
    private val channel : ServerSocketChannel = ServerSocketChannel.open()

    /**
     * Suspention map managing the socket
     */
    private val suspentionMap : SuspentionMap

    actual var exceptionHandler : (exception : Throwable) -> Unit = {}

    /**
     * Exception handler used to catch everything that comes from the internal coroutines
     */
    private val internalExceptionHandler = CoroutineExceptionHandler{_,e ->
        if(e is CloseException && !this.isCloseExceptionSent.compareAndSet(false,true)) return@CoroutineExceptionHandler

        this.close()
        this.exceptionHandler(e)
    }
    private val isCloseExceptionSent = atomic(false)

    /**
     * Start a listening socket on the given address (or alias) and port
     *
     * @param address IP to listen to
     * @param port port to listen to
     *
     */
    internal constructor(address : String, port : Int) {
        //convert the exception
        try{
            this.channel.bind(InetSocketAddress(address,port))
        }catch (e : BindException){
            val exc = AddressInUseException()
            this.internalExceptionHandler.handleException(exc)
            throw exc
        }

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
     * @throws NormalCloseException
     * @throws SocketClosedException
     *
     * @return accepted socket
     */
    actual suspend fun accept() : TCPClientSocket {
        if(this.isClosed) throw SocketClosedException()
        return withContext(Dispatchers.IO){
            try {
                this@TCPServerSocket.suspentionMap.selectOnce(SelectionKey.OP_ACCEPT)
                val channel = this@TCPServerSocket.channel.accept()
                Sok.Socket.TCP.TCPClientSocket(channel, Selector.defaultSelectorPool)
            }catch (e : Exception){
                this@TCPServerSocket.internalExceptionHandler.handleException(e)
                throw e
            }
        }
    }

    /**
     * close the server socket
     */
    actual fun close(){
        //get the state and set it to false, if it is already closed, do nothing
        if(!this._isClosed.getAndSet(true)){
            this.internalExceptionHandler.handleException(NormalCloseException())
            //stop selection loop
            this.suspentionMap.close(NormalCloseException())
            this.channel.close()
        }
    }
}

/**
 * Start a listening socket on the given address (or alias) and port
 *
 * @param address IP to listen to
 * @param port port to listen to
 *
 */
actual suspend fun createTCPServerSocket(address: String,port: Int) : TCPServerSocket{
    return TCPServerSocket(address,port)
}
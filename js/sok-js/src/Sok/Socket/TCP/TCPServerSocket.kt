package Sok.Socket.TCP

import Sok.Exceptions.*
import Sok.Internal.net
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Class representing a listening socket. You can use it to perform accept() operation only.
 *
 * @property isClosed keep track of the socket state
 * @property exceptionHandler Lambda that will be called when an exception resulting in the closing of the socket is thrown,
 * for further information look at the "Exception model" part of the README
 */
actual class TCPServerSocket{

    actual var isClosed = true
        private set

    /**
     * Node.js socket
     */
    private val socket : dynamic

    /**
     * Channel used to store the accepted client
     */
    private val acceptChannel = Channel<TCPClientSocket>(Channel.UNLIMITED)


    actual var exceptionHandler : (exception : Throwable) -> Unit = {}

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
    internal constructor(socket : dynamic){
        //create the server and bind the accept listener
        this.socket = socket

        this.socket.on("connection"){ client ->
            //pause the socket before everything
            client.pause()
            this.acceptChannel.offer(Sok.Socket.TCP.TCPClientSocket(client))
        }

        this.socket.on("error"){ error ->
            this.internalExceptionHandler.handleException(SokException(error.toString()))
        }

        this.isClosed = false
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

        return this.acceptChannel.receive()
    }

    /**
     * close the server socket
     */
    actual fun close(){
        if(!this.isClosed){
            this.isClosed = true
            this.acceptChannel.close(NormalCloseException())
            this.socket.close{
                this.internalExceptionHandler.handleException(NormalCloseException())
            }
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
actual suspend fun createTCPServerSocket(address: String, port: Int) : TCPServerSocket{
    val socket = net.createServer<dynamic>{}
    suspendCancellableCoroutine<Unit> {
        socket.listen(port,address){
            it.resume(Unit)
        }

        socket.on("error"){error ->
            if(error.code == "EADDRINUSE"){
                it.resumeWithException(AddressInUseException())
            }
        }

        it.invokeOnCancellation { socket.close() }

        Unit
    }

    socket.removeAllListeners("error")
    socket.removeAllListeners("connection")

    return TCPServerSocket(socket)
}
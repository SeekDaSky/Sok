package Sok.Socket.TCP

import Sok.Internal.net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Class representing a listening socket. You can use it to perform accept() operation only.
 *
 * @property isClosed keep track of the socket state
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

    /**
     * Lambda to call when the socket closes
     */
    private var onClose : () -> Unit = {}

    /**
     * Start a listening socket on the given address (or alias) and port
     *
     * @param address IP to listen to
     * @param port port to listen to
     *
     */
    actual constructor(address : String, port : Int){

        //create the server and bind the accept listener
        this.socket = net.createServer<dynamic>{ socket ->
            //pause the socket before everything
            socket.pause()
            this.acceptChannel.offer(Sok.Socket.TCP.TCPClientSocket(socket))
        }

        //start server
        this.socket.listen(port,address)
        this.isClosed = false
    }

    /**
     * Accept a client socket. The method will suspend until there is a client to accept
     *
     * @return accepted socket
     */
    actual suspend fun accept() : TCPClientSocket {
        require(!this.isClosed){
            "the Socket is closed"
        }

        return this.acceptChannel.receive()
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
        if(!this.isClosed){
            this.isClosed = true
            this.socket.close{
                this.onClose()
            }
        }
    }
}
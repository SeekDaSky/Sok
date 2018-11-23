package Sok.Socket.TCP

import Sok.Exceptions.*

/**
 * Class representing a listening socket. You can use it to perform accept() operation only.
 *
 * @property isClosed keep track of the socket state
 * @property exceptionHandler Lambda that will be called when an exception resulting in the closing of the socket is thrown,
 * for further information look at the "Exception model" part of the README
 */
expect class TCPServerSocket{

    var isClosed : Boolean
        private set

    var exceptionHandler : (exception : Throwable) -> Unit

    /**
     * Accept a client socket. The method will suspend until there is a client to accept
     *
     * @throws NormalCloseException
     * @throws SocketClosedException
     *
     * @return accepted socket
     */
    suspend fun accept() : TCPClientSocket

    /**
     * close the server socket
     */
    fun close()
}

/**
 * Start a listening socket on the given address (or alias) and port
 *
 * @param address IP to listen to
 * @param port port to listen to
 *
 */
expect suspend fun createTCPServerSocket(address : String, port : Int) : TCPServerSocket
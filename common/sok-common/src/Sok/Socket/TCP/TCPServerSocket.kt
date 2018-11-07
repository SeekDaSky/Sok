package Sok.Socket.TCP

/**
 * Class representing a listening socket. You can use it to perform accept() operation only.
 *
 * @property isClosed keep track of the socket state
 */
expect class TCPServerSocket{

    var isClosed : Boolean
        private set

    /**
     * Start a listening socket on the given address (or alias) and port
     *
     * @param address IP to listen to
     * @param port port to listen to
     *
     */
    constructor(address : String, port : Int)

    /**
     * Accept a client socket. The method will suspend until there is a client to accept
     *
     * @return accepted socket
     */
    suspend fun accept() : TCPClientSocket

    /**
     * handler called when the socket close (expectedly or not)
     *
     * @param handler lambda called when the socket is closed
     */
    fun bindCloseHandler(handler : () -> Unit)

    /**
     * close the server socket
     */
    fun close()
}
package Sok.Socket.TCP

expect class TCPServerSocket{

    /** state of the socket */
    var isClosed : Boolean
        private set

    /**
     * Start a listening socket on the given address (or alias) and port
     */
    constructor(address : String, port : Int)

    /**
     * Accept a client socket. The method will suspend until there is a client to accept
     */
    suspend fun accept() : TCPClientSocket

    /**
     * handler called when the socket close (expectedly or not)
     */
    fun bindCloseHandler(handler : () -> Unit)

    /**
     * close the server socket
     */
    fun close()
}
package Sok.Socket.TCP

import Sok.Buffer.MultiplatformBuffer
import kotlinx.coroutines.experimental.Deferred

expect class TCPClientSocket{

    /** state of the socket */
    var isClosed : Boolean
        private set

    /**
     * handler called when the socket close (expectantly or not)
     */
    fun bindCloseHandler(handler : () -> Unit)

    /**
     * Wait for the send queue to be empty then close the socket
     */
    suspend fun close()

    /**
     * Close the socket whether there is pending write/read or not
     */
    fun forceClose()

    /**
     * Used to do efficient read-intensive loops, it will basically execute the operation each time there is data to be read
     * and avoid registrations/allocation between each iteration. The passed lambda must return true to continue the loop or
     * false to exit. The call will suspend as long as the loop is running.
     *
     * THE OPERATION MUST NOT BE COMPUTATION INTENSIVE OR BLOCKING as the internal selector will call it synchronously and wait
     * for it to return before processing any other event. The passed buffer cursor will be reset between each iteration so you should
     * not use the buffer cursor between two iterations and must avoid leaking it to exterior coroutines/threads. each
     * iteration will read n bytes ( 0 < n <= buffer.limit ) and set the cursor to 0, the read parameter of the operation is the
     * amount of data read.
     *
     * @return Total number of byte read
     */
    suspend fun bulkRead(buffer : MultiplatformBuffer, operation : (buffer : MultiplatformBuffer, read : Int) -> Boolean) : Long

    /**
     * Perform a suspending read, the method will read n bytes ( 0 < n <= buffer.remaining() ) and update the cursor
     *
     * @return Number of byte read
     */
    suspend fun read(buffer: MultiplatformBuffer) : Int

    /**
     * Perform a suspending read, the method will read n bytes ( minToRead < n <= buffer.remaining() ) and update the cursor
     *
     * @return Number of byte read
     */
    suspend fun read(buffer: MultiplatformBuffer, minToRead : Int) : Int

    /**
     * Perform a suspending write, the method will not return until all the buffer is written. The socket use an internal write
     * queue, allowing multiple threads to concurrently write. Backpressure mechanisms should be implemented by the developper
     * to avoid having too much data in the queue.
     *
     * @return Success of the operation
     */
    suspend fun write(buffer: MultiplatformBuffer) : Boolean

}

/**
 * Create a client socket with the given address and port.
 */
expect suspend fun createTCPClientSocket(address : String, port : Int ) : TCPClientSocket
package Sok.Socket

import Sok.Buffer.MultiplateformBuffer
import kotlinx.coroutines.experimental.Deferred

expect class SuspendingClientSocket{

    /** IP address of the client */
    val clientIP : String

    /** state of the socket */
    var isClosed : Boolean
        private set

    /**
     * handler called when the socket close (expectedly or not)
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
     * Used to do efficient read-intensive loop. The passed lambda must return true to continue the loop or false to exit.
     * The call will suspend as long as the loop is running. Calling this method instead of a regular loop give roughly 50%
     * more bandwidth. THE LOOP MUST NOT BE COMPUTATION INTENSIVE OR BLOCKING as the internal selector will call it synchronously and wait
     * for it to return to continue its operations. The passed buffer will get reset at each iteration so you should use
     * the buffer cursor position between two iterations. each iteration will read n bytes ( 0 < n <= buffer.size() )
     *
     * @return Number of byte read
     */
    suspend fun bulkRead(buffer : MultiplateformBuffer, operation : (buffer : MultiplateformBuffer) -> Boolean) : Long

    /**
     * Perform a suspending read, the method will read n bytes ( 0 < n <= buffer.size() )
     *
     * @return Number of byte read
     */
    suspend fun read(buffer: MultiplateformBuffer) : Int

    /**
     * Perform a suspending read, the method will read n bytes ( minToRead < n <= buffer.size() )
     *
     * @return Number of byte read
     */
    suspend fun read(buffer: MultiplateformBuffer, minToRead : Int) : Int

    /**
     * Perform an asynchronous read, the method will read n bytes ( 0 < n <= buffer.size() )
     *
     * @return Deferred containing the number of byte read
     */
    fun asynchronousRead(buffer: MultiplateformBuffer) : Deferred<Int>

    /**
     * Perform an asynchronous read, the method will read n bytes ( minToRead < n <= buffer.size() )
     *
     * @return Deferred containing the number of byte read
     */
    fun asynchronousRead(buffer: MultiplateformBuffer,  minToRead : Int) : Deferred<Int>

    /**
     * Perform a suspending write, the method will not resume until all the buffer is written
     *
     * @return Success of the operation
     */
    suspend fun write(buffer: MultiplateformBuffer) : Boolean

    /**
     * Perform an asynchronous write, the method will not complete the deferred until all the bytes are written
     *
     * @return Success of the operation
     */
    fun asynchronousWrite(buffer: MultiplateformBuffer) : Deferred<Boolean>
}

/**
 * Create a client socket with the given address and port.
 */
expect suspend fun createSuspendingClientSocket(address : String, port : Int ) : SuspendingClientSocket
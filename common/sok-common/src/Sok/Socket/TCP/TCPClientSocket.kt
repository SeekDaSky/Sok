package Sok.Socket.TCP

import Sok.Buffer.MultiplatformBuffer
import Sok.Exceptions.*
import Sok.Socket.Options.Options
import Sok.Socket.Options.SocketOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Deferred

/**
 * Class representing a client socket. You can use it to perform any I/O operation. Keep in mind that this class keep an internal
 * queue for write operations thus storing data until written so you should have some kind of backpressure mechanism to prevent
 * the accumulation of too many data.
 *
 * @property isClosed Keep track of the socket status
 * @property exceptionHandler Lambda that will be called when an exception resulting in the closing of the socket is thrown. This
 * happen when calling the `close` or `forceClose` method, if the peer close the socket or if something wrong happen internally.
 * Exception that does not affect the socket state will not be received by this handler (exception in the `bulkRead` operation
 * lambda for example)
 */
expect class TCPClientSocket{

    var isClosed : Boolean
        private set

    var exceptionHandler : (exception : Throwable) -> Unit

    /**
     * gracefully stops the socket. The method suspends as it waits for all the writing requests in the channel to be
     * executed before effectively closing the channel. Once the socket is closed a `NormalCloseException` will be passed
     * to the exception handler and to any ongoing read method call
     */
    suspend fun close()

    /**
     * forcefully closes the channel without checking the writing request queue. Once the socket is closed a `ForceCloseException`
     * will be passed to the exception handler and to any ongoing read method call
     */
    fun forceClose()

    /**
     * Used to do efficient read-intensive loops, it will basically execute the operation each time there is data to be read
     * and avoid registrations/allocation between each iteration. The passed lambda must return true to continue the loop or
     * false to exit. The call will suspend as long as the loop is running.
     *
     * THE OPERATION MUST NOT BE COMPUTATION INTENSIVE OR BLOCKING as the internal selector will call it synchronously and wait
     * for it to return before processing any other event. The buffer cursor will be reset between each iteration so you should
     * not use it between two iterations and must avoid leaking it to exterior coroutines/threads. each iteration will read
     * n bytes ( 0 < n <= buffer.limit ) and set the cursor to 0, the read parameter of the operation is the amount of data read.
     *
     * If an exception is thrown in the operation lambda, the exception will not close the socket and will not be received by the
     * exception handler, it will instead be thrown directly by the method
     *
     * @throws PeerClosedException
     * @throws SocketClosedException
     * @throws BufferOverflowException
     * @throws ConcurrentReadingException

     *
     * @param buffer buffer used to store the data read. the cursor will be reset after each iteration. The limit of the buffer remains
     * untouched so the developer can chose the amout of data to read.
     * @param operation lambda called after each read event. The first argument will be the buffer and the second the amount of data read
     *
     * @return Total number of byte read
     */
    suspend fun bulkRead(buffer : MultiplatformBuffer, operation : (buffer : MultiplatformBuffer, read : Int) -> Boolean) : Long

    /**
     * Perform a suspending read, the method will read n bytes ( 0 < n <= buffer.remaining() ) and update the cursor. If the peer
     * closes the socket while reading, a `PeerClosedException` will be thrown. If the socket is manually closed while reading,
     * either `NormalCloseException` or `ForceCloseException` will be thrown
     *
     * @throws PeerClosedException
     * @throws SocketClosedException
     * @throws BufferOverflowException
     * @throws ConcurrentReadingException
     *
     * @param buffer buffer used to store the data read
     *
     * @return Number of byte read
     */
    suspend fun read(buffer: MultiplatformBuffer) : Int

    /**
     * Perform a suspending read, the method will read n bytes ( minToRead < n <= buffer.remaining() ) and update the cursor If the peer
     * closes the socket while reading, a `PeerClosedException` will be thrown. If the socket is manually closed while reading,
     * either `NormalCloseException` or `ForceCloseException` will be thrown
     *
     * @throws PeerClosedException
     * @throws SocketClosedException
     * @throws BufferOverflowException
     * @throws ConcurrentReadingException
     *
     * @param buffer buffer used to store the data read
     *
     * @return Number of byte read
     */
    suspend fun read(buffer: MultiplatformBuffer, minToRead : Int) : Int

    /**
     * Perform a suspending write, the method will not return until all the data between buffer.cursor and buffer.limit are written.
     * The socket use an internal write queue, allowing multiple threads to concurrently write. Backpressure mechanisms
     * should be implemented by the developer to avoid having too much data in the queue. If the peer
     * closes the socket while reading, a `PeerClosedException` will be thrown. If the socket is manually closed while reading,
     * either `NormalCloseException` or `ForceCloseException` will be thrown
     *
     * @throws SocketClosedException
     * @throws BufferUnderflowException
     * @throws SokException
     * @throws PeerClosedException
     *
     * @param buffer data to write
     *
     * @return Success of the operation
     */
    suspend fun write(buffer: MultiplatformBuffer) : Boolean

    /**
     * get a socket option and try to convert it to the given type, throw an exception if the option is not of the correct type
     * exemple:
     *
     * ```kotlin
     * client.getOption<Int>(Options.SO_RCVBUF)
     * ```
     *
     * @param name Option to get
     * @return the socket option
     */
    fun <T>getOption(name : Options) : SocketOption<T>

    /**
     * set a socket option
     * exemple:
     *
     * ```kotlin
     * client.setOption(SocketOption(Options.SO_KEEPALIVE,true))
     * ```
     *
     * @param option option to set
     * @return success of the operation
     */
    fun <T>setOption(option : SocketOption<T>) : Boolean

}

/**
 * Create a client socket with the given address and port. This function will throw a `ConnectionRefusedException` if the socket
 * failed to connect.
 *
 * @param address IP or domain to connect to
 * @param port port to connect to
 *
 * @return connected socket
 */
expect suspend fun createTCPClientSocket(address : String, port : Int ) : TCPClientSocket

/**
 * Sealed class used to communicate with the internal write actor
 */
internal sealed class WriteActorRequest(val deferred: CompletableDeferred<Boolean>)
internal class CloseRequest(deferred: CompletableDeferred<Boolean>) : WriteActorRequest(deferred)
internal class WriteRequest(val data: MultiplatformBuffer, deferred: CompletableDeferred<Boolean>) : WriteActorRequest(deferred)
package Sok.Exceptions

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Parent class of any exception thrown by Sok
 *
 * @param message Message given by the exception when thrown
 */
open class SokException(message : String = "Something unexpected happened") : Exception(message)



/**
 * Parent class of the exceptions thrown when gracefully closing a socket
 *
 * @param message Message given by the exception when thrown
 */
open class CloseException(message : String = "The socket was closed") : SokException(message)

/**
 * Exception passed to the socket exception handler when the socket is gracefully closed by a call to the `close` method
 *
 * @param message Message given by the exception when thrown
 */
class NormalCloseException(message : String = "The socket was gracefully closed") : CloseException(message)

/**
 * Exception passed to the socket exception handler whent the socket is forcefully closed by a call to the `forceClose` method
 *
 * @param message Message given by the exception when thrown
 */
class ForceCloseException(message : String = "The socket was forcefully closed") : CloseException(message)



/**
 * Exception thrown when a client tries to connect and fails
 *
 * @param message Message given by the exception when thrown
 */
class ConnectionRefusedException(message : String = "The peer refused the connection") : SokException(message)

/**
 * Exception thrown when trying to get/set a socket option not supported on the platform
 *
 * @param message Message given by the exception when thrown
 */
class OptionNotSupportedException(message: String = "The option is not supported on this platform") : SokException(message)

/**
 * Exception thrown when trying to perform an I/O on a closed socket
 *
 * @param message Message given by the exception when thrown
 */
class SocketClosedException(message: String = "The socket is closed") : SokException(message)

/**
 * Exception thrown when trying to read the socket while another read call is executing
 *
 * @param message Message given by the exception when thrown
 */
class ConcurrentReadingException(message: String = "The socket si already being read") : SokException(message)



/**
 * Exception thrown when trying to put a value too large for the remaining space in the buffer
 *
 * @param message Message given by the exception when thrown
 */
class BufferOverflowException(message : String = "There is not enough remaining space in the buffer to perform the write operation") : SokException(message)

/**
 * Exception thrown when trying to read a value too large for the remaining space in the buffer
 *
 * @param message Message given by the exception when thrown
 */
class BufferUnderflowException(message : String = "There is not enough remaining data in the buffer to perform the read operation") : SokException(message)

/**
 * Exception thrown when trying to modify the buffer after the destroy() method was called
 *
 * @param message Message given by the exception when thrown
 */
class BufferDestroyedException(message : String = "The buffer is destroyed an no operation should be done with it") : SokException(message)



/**
 * Extension function used to throw an exception in a particular `CoroutineExceptionHandler` without having to pass
 * an `EmptyCoroutineContext`
 */
fun CoroutineExceptionHandler.handleException(exception : Throwable){
    this.handleException(EmptyCoroutineContext,exception)
}
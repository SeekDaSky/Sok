package Sok.Exceptions

/**
 * Exception thrown when a client tries to connect and fails
 *
 * @param message Message given by the exception when thrown
 */
class ConnectionRefusedException(message : String = "") : Exception(message)

/**
 * Exception thrown when trying to put a value too large for the remaining space in the buffer
 *
 * @param message Message given by the exception when thrown
 */
class BufferOverflowException(message : String = "") : Exception(message)

/**
 * Exception thrown when trying to read a value too large for the remaining space in the buffer
 *
 * @param message Message given by the exception when thrown
 */
class BufferUnderflowException(message : String = "") : Exception(message)

/**
 * Exception thrown when trying to modify the buffer after the destroy() method was called
 *
 * @param message Message given by the exception when thrown
 */
class BufferDestroyedException(message : String = "") : Exception(message)
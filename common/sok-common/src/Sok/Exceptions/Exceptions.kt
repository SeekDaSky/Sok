package Sok.Exceptions

class ConnectionRefusedException(message : String = "") : Exception(message)
class BufferOverflowException(message : String = "") : Exception(message)
class BufferUnderflowException(message : String = "") : Exception(message)
class BufferDestroyedException(message : String = "") : Exception(message)
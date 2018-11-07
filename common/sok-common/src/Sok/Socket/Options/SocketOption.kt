package Sok.Socket.Options

/**
 * Class representing a socket option and its type
 *
 * @property name Name of the option
 * @property value value of the option
 *
 * @constructor new option
 */
class SocketOption<T>(val name : Options, val value : T)
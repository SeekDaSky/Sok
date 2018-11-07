package Sok.Socket.Options

/**
 * Enum representing all the available options. Please keep in mind that all options are not supported by al the platforms
 */
enum class Options{
    /**
     * Receive buffer size, this option is hint given to the system and may not apply depending on the configuration
     * of the system. Not available on Node.JS
     */
    SO_RCVBUF,
    /**
     * Send buffer size, this option is hint given to the system and may not apply depending on the configuration
     * of the system. Not available on Node.JS
     */
    SO_SNDBUF,
    /**
     * socket keepalive
     */
    SO_KEEPALIVE,
    /**
     * Naggle's algorithm
     */
    TCP_NODELAY
}
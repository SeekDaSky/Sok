package Sok.Socket.TCP

import Sok.Selector.*
import Sok.Exceptions.*
import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.*
import platform.posix.*
import kotlinx.coroutines.*
import kotlinx.cinterop.cValuesOf

/**
 * Class representing a listening socket. You can use it to perform accept() operation only.
 *
 * @property isClosed keep track of the socket state
 * @property exceptionHandler Lambda that will be called when an exception resulting in the closing of the socket is thrown,
 * for further information look at the "Exception model" part of the README
 */
actual class TCPServerSocket{

    /**
     * Atomic field backing the isClosed property
     */
    private val _isClosed : AtomicBoolean = atomic(false)
    actual var isClosed : Boolean = false
        get() {
            return this._isClosed.value
        }
        private set


    /**
     * Selection key managing the socket
     */
    private val selectionKey : SelectionKey

    actual var exceptionHandler : (exception : Throwable) -> Unit = {}

    /**
     * Exception handler used to catch everything that comes from the internal coroutines
     */
    private val internalExceptionHandler = CoroutineExceptionHandler{_,e ->
        if(e is CloseException && !this.isCloseExceptionSent.compareAndSet(false,true)) return@CoroutineExceptionHandler
        this.close()
        this.exceptionHandler(e)
    }
    private val isCloseExceptionSent = atomic(false)

    /**
     * Start a listening socket on the given address (or alias) and port
     *
     * @param address IP to listen to
     * @param port port to listen to
     *
     */
    internal constructor(address : String, port : Int){

        var socket : Int = 0

        memScoped{
            val hints = alloc<addrinfo>()
            val result = allocPointerTo<addrinfo>()

            //set hints
            with(hints) {
                memset(this.ptr, 0, addrinfo.size.toULong())
                this.ai_family = AF_UNSPEC
                this.ai_socktype = SOCK_STREAM
                this.ai_flags = 0
                this.ai_protocol = 0
            }

            //try to resolve address
            if (getaddrinfo(address, port.toString(), hints.ptr, result.ptr) != 0) {
                throw SokException("Something went wrong during getaddrinfo (TODO: pattern match error")
            }

            //loop until one result works
            with(result){
                var next : addrinfo? = this.pointed
                while(next != null){
                    socket = socket(next.ai_family, next.ai_socktype,next.ai_protocol)

                    if(socket == -1) continue

                    //disable ipv6-only
                    setsockopt (socket, IPPROTO_IPV6, IPV6_V6ONLY, cValuesOf(0), sizeOf<IntVar>().toUInt())

                    //set addr reuse
                    setsockopt (socket, SOL_SOCKET, SO_REUSEADDR, cValuesOf(1), sizeOf<IntVar>().toUInt())

                    if (bind(socket, next.ai_addr, next.ai_addrlen) == 0){
                        return@with
                    }else if (posix_errno() == EADDRINUSE){
                        throw AddressInUseException()
                    }

                    close(socket)

                    next = next.ai_next?.pointed
                }

                throw SokException("Could not create socket")
            }

            if(listen(socket, Short.MAX_VALUE.toInt()) != 0){
                throw SokException("Socket listening error ${posix_errno()}")
            }

            Sok.Utils.makeNonBlocking(socket)
        }

        //register the socket in the selector
        this.selectionKey = Selector.defaultSelector.register(socket)
        this.selectionKey.exceptionHandler = this.internalExceptionHandler

    }

    /**
     * Accept a client socket. The method will suspend until there is a client to accept
     *
     * @throws NormalCloseException
     * @throws SocketClosedException
     *
     * @return accepted socket
     */
    actual suspend fun accept() : TCPClientSocket {
        //wait for event
        this.selectionKey.select(Interests.OP_READ)

        val clientSocket = accept(this.selectionKey.socket,null,null)
        Sok.Utils.makeNonBlocking(clientSocket)

        return TCPClientSocket(clientSocket,Selector.defaultSelector)

    }

    /**
     * close the server socket
     */
    actual fun close() {
        if(this._isClosed.compareAndSet(false,true)){
            this.selectionKey.close(NormalCloseException())
        }
    }
}

/**
 * Start a listening socket on the given address (or alias) and port
 *
 * @param address IP to listen to
 * @param port port to listen to
 *
 */
actual suspend fun createTCPServerSocket(address : String, port : Int) : TCPServerSocket{
    return TCPServerSocket(address,port)
}
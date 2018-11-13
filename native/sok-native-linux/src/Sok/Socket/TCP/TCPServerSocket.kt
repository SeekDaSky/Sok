package Sok.Socket.TCP

import Sok.Selector.*
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
     * lambda to call when the socket closes
     */
    private var onClose : () -> Unit = {}

    /**
     * Selection key managing the socket
     */
    private val selectionKey : SelectionKey

    /**
     * Start a listening socket on the given address (or alias) and port
     *
     * @param address IP to listen to
     * @param port port to listen to
     *
     */
    actual constructor(address : String, port : Int){

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
                throw Exception("Something went wrong during getaddrinfo (TODO: pattern match error")
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
                        throw Exception("Address already in use")
                    }

                    close(socket)

                    next = next.ai_next?.pointed
                }

                throw Exception("Could not create socket")
            }

            //can't figure out how to convert result to the righ type yet
            //freeaddrinfo(result.ptr)

            if(listen(socket, Short.MAX_VALUE.toInt()) != 0){
                throw Exception("Socket listening error ${posix_errno()}")
            }

            Sok.Utils.makeNonBlocking(socket)
        }

        //register the socket in the selector
        this.selectionKey = Selector.defaultSelector.register(socket)

    }

    /**
     * Accept a client socket. The method will suspend until there is a client to accept
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
     * handler called when the socket close (expectedly or not)
     *
     * @param handler lambda called when the socket is closed
     */
    actual fun bindCloseHandler(handler : () -> Unit){
        this.onClose = handler
    }

    /**
     * close the server socket
     */
    actual fun close() {
        if(this._isClosed.compareAndSet(false,true)){
            this.selectionKey.close()
            this.onClose.invoke()
        }
    }
}
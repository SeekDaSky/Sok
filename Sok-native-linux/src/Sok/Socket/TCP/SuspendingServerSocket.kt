package Sok.Socket.TCP

import Sok.Selector.*
import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.*
import platform.posix.*
import kotlinx.coroutines.experimental.*

actual class TCPServerSocket{

    private val _isClosed : AtomicBoolean = atomic(false)
    actual var isClosed : Boolean = false
        get() {
            return this._isClosed.value
        }
        private set

    private var onClose : () -> Unit = {}

    private val selectionKey : SelectionKey

    actual constructor(address : String, port : Int){

        var socket : Int = 0

        memScoped{
            val hints = alloc<addrinfo>()
            val result = allocPointerTo<addrinfo>()

            //set hints
            with(hints) {
                memset(this.ptr, 0, addrinfo.size)
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

                    if (bind(socket, next.ai_addr, next.ai_addrlen) == 0) return@with

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

    actual suspend fun accept() : TCPClientSocket {
        //wait for event
        this.selectionKey.select(Interests.OP_READ)

        val clientSocket = accept(this.selectionKey.socket,null,null)
        Sok.Utils.makeNonBlocking(clientSocket)

        return TCPClientSocket(clientSocket,Selector.defaultSelector)

    }

    actual fun bindCloseHandler(handler : () -> Unit){
        this.onClose = handler
    }

    actual fun close() {
        if(this._isClosed.compareAndSet(false,true)){
            close(this.selectionKey.socket)
            this.onClose.invoke()
        }
    }
}

actual fun createTCPServer(address: String, port: Int, scope : CoroutineScope, serverFunction : suspend (server : TCPServerSocket) -> Unit ){
    Selector.setDefaultScope(scope)
    val server = TCPServerSocket(address,port)
    scope.launch{
        try {
            serverFunction.invoke(server)
        } finally {
            server.close()
            Selector.closeSelectorAndWait()
            Selector.setDefaultScope(GlobalScope)
        }
    }
}
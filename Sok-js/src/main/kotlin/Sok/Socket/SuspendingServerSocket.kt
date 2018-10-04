package Sok.Socket

import Sok.Sok.net
import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.launch

actual class SuspendingServerSocket{

    //socket state
    actual var isClosed = true
        private set

    //Node socket
    val socket : dynamic

    //used to pass from a callback based accept() to a suspending one
    val acceptCoroutineScope = CoroutineScope(Dispatchers.Unconfined)
    val acceptChannel = Channel<SuspendingClientSocket>()

    //on close handler
    var onClose : () -> Unit = {}

    actual constructor(address : String, port : Int){

        //create the server and bind the accept listener
        this.socket = net.createServer<dynamic>{ socket ->
            //pause the socket before everything
            socket.pause()
            this.acceptCoroutineScope.launch {
                this@SuspendingServerSocket.acceptChannel.send(SuspendingClientSocket(socket))
            }
        }

        //start server
        this.socket.listen(port,address)
        this.isClosed = false
    }

    actual suspend fun accept() : SuspendingClientSocket{
        require(!this.isClosed){
            "the Socket is closed"
        }

        return this.acceptChannel.receive()
    }

    actual fun bindCloseHandler(handler : () -> Unit){
        this.onClose = handler
    }

    actual fun close(){
        if(!this.isClosed){
            this.isClosed = true
            this.socket.close{
                this.onClose()
            }
        }
    }
}
package TCPSockets

import Sok.Buffer.allocMultiplatformBuffer
import Sok.Exceptions.*
import Sok.Internal.runTest
import Sok.Socket.Options.Options
import Sok.Socket.Options.SocketOption
import Sok.Socket.TCP.TCPServerSocket
import Sok.Socket.TCP.createTCPClientSocket
import Sok.Socket.TCP.createTCPServerSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.js.JsName
import kotlin.test.*

class ExceptionsTests {

    val address = "localhost"
    val port = 9999

    @Test
    @JsName("ServerSocketThrowsAddressInUse")
    fun `Server socket throws address in use`() = runTest{
        val server = createTCPServerSocket(address,port)
        server.exceptionHandler = {
            assertTrue { it is NormalCloseException }
        }

        try {
            createTCPServerSocket(address,port).close()
        }catch (e : Exception){
            assertTrue { e is AddressInUseException }
        }
        server.close()
    }


    @Test
    @JsName("AcceptThrowsWhenServerCloses")
    fun `Accept throws when server closes`() = runTest{
        val server = createTCPServerSocket(address,port)
        server.exceptionHandler = {
            assertTrue { it is NormalCloseException }
        }
        val job = it.launch {
            try {
                server.accept()
                fail("SocketClosedException should have been thrown")
            }catch (e : Exception){
                assertTrue { e is NormalCloseException }
            }
        }
        delay(10)
        server.close()
        job.join()
    }


    @Test
    @JsName("ClientSocketThrowsConnectionRefused")
    fun `Client socket throws connection refused`() = runTest {
        createTCPServerSocket(address,port).close()
        try {
            createTCPClientSocket(address, port)
            fail("exception not raised")
        }catch (e : Exception){
            assertTrue { e is ConnectionRefusedException }
        }
    }


    @Test
    @JsName("ClientExceptionHandlerReceiveCloseExceptions")
    fun `Client exception handler receive close exceptions`() = runTest{
        val server = createTCPServerSocket(address,port)
        val job = it.launch {
            repeat(2){
                server.accept()
            }
        }

        val c1 = createTCPClientSocket(address, port)
        val c1received = CompletableDeferred<Boolean>()
        c1.exceptionHandler = {
            assertTrue { it is NormalCloseException }
            c1received.complete(true)
        }

        val c2 = createTCPClientSocket(address,port)
        val c2received = CompletableDeferred<Boolean>()
        c2.exceptionHandler = {
            assertTrue { it is ForceCloseException }
            c2received.complete(true)
        }

        c1.close()
        c2.forceClose()

        assertEquals(true,c1received.await())
        assertEquals(true,c2received.await())

        job.join()
        server.close()
    }


    @Test
    @JsName("ClientSocketThrowsWhenReadingOrWritingWhileClosing")
    fun `Client socket throws when reading or writing while closing`() = runTest{scope ->
        createTCPServer(address,port){
            val server = it
            //channel used to synchronize close
            val channel = Channel<Boolean>()
            val job = scope.launch {
                repeat(4){
                    val client = server.accept()
                    channel.receive()
                    client.close()
                }
            }

            //bulkRead
            val client1 = createTCPClientSocket(address,port)
            client1.exceptionHandler = {
                assertTrue { it is PeerClosedException }
            }
            val c1 = scope.launch {
                try {
                    client1.bulkRead(allocMultiplatformBuffer(1)){_,_ ->true}
                    fail("exception not raised")
                }catch (e : Exception){
                    assertTrue { e is PeerClosedException }
                }
            }
            channel.send(true)
            c1.join()
            client1.close()

            //read
            val client2 = createTCPClientSocket(address,port)
            client2.exceptionHandler = {
                assertTrue { it is PeerClosedException }
            }
            val c2 = scope.launch {
                try {
                    client2.read(allocMultiplatformBuffer(1))
                    fail("exception not raised")
                }catch (e : Exception){
                    assertTrue { e is PeerClosedException }
                }
            }
            channel.send(true)
            c2.join()
            client2.close()

            //read with min
            val client3 = createTCPClientSocket(address,port)
            client3.exceptionHandler = {
                assertTrue { it is PeerClosedException }
            }
            val c3 = scope.launch {
                try {
                    client3.read(allocMultiplatformBuffer(1),1)
                    fail("exception not raised")
                }catch (e : Exception){
                    assertTrue { e is PeerClosedException }
                }
            }
            channel.send(true)
            c3.join()
            client3.close()

            //write
            val client4 = createTCPClientSocket(address,port)
            client4.exceptionHandler = {
                assertTrue { it is PeerClosedException }
            }
            val c4 = scope.launch {
                try {
                    //make sure the buffer can not be written in one time
                    var bufSize : Int
                    var isPlatformJS = false
                    try {
                        bufSize = client4.getOption<Int>(Options.SO_SNDBUF).value*10
                    }catch (e : OptionNotSupportedException){
                        isPlatformJS = true
                        bufSize = 65_654*10
                    }

                    client4.write(allocMultiplatformBuffer(bufSize))

                    //Node.js can't give us a bloody thing about the result of the write operation, we can not test it
                    if(!isPlatformJS){
                        fail("exception not raised")
                    }
                }catch (e : Exception){
                    assertTrue { e is PeerClosedException }
                }
            }
            channel.send(true)
            c4.join()
            //we must yield to let the server close the socket, delay is used as the JS dispatcher is might not
            //actually yield to a next tick tight now
            delay(10)
            client4.close()

            job.join()
            channel.close()
        }
    }


    @Test
    @JsName("SocketBulkReadThrowsExceptionThrownFromOperation")
    fun `Socket bulkRead throws exception thrown from operation`() = runTest{scope ->
        createTCPServer(address,port){
            scope.launch {
                val client = it.accept()
                client.write(allocMultiplatformBuffer(5))
                client.close()
            }

            val client = createTCPClientSocket(address,port)
            try {
                client.bulkRead(allocMultiplatformBuffer(5)){_,_ ->
                    throw Exception("Thrown from the operation")
                }
                fail("exception not raised")
            }catch (e : Exception){
                assertEquals("Thrown from the operation",e.message)
            }
            client.close()
        }
    }


    @Test
    @JsName("SocketReadThrowsNormalCloseException")
    fun `Socket read throws normal close exception`() = runTest{scope ->
        createTCPServer(address,port){
            scope.launch {
                it.accept()
            }

            val client = createTCPClientSocket(address,port)
            val job = scope.launch {
                try {
                    client.read(allocMultiplatformBuffer(5))
                    fail("exception not raised")
                }catch (e : Exception){
                    assertTrue { e is NormalCloseException }
                }
            }
            //wait for client to start reading
            delay(100)
            client.close()
            job.join()
        }
    }
}
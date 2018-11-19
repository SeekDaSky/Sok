package TCPSockets

import Sok.Buffer.MultiplatformBuffer
import Sok.Buffer.allocMultiplatformBuffer
import Sok.Buffer.wrapMultiplatformBuffer
import Sok.Exceptions.NormalCloseException
import Sok.Exceptions.SocketClosedException
import Sok.Exceptions.SokException
import Sok.Socket.TCP.TCPServerSocket
import Sok.Socket.TCP.createTCPClientSocket
import Sok.Internal.runTest
import Sok.Socket.Options.Options
import Sok.Socket.Options.SocketOption
import Sok.Socket.TCP.createTCPServerSocket
import kotlinx.coroutines.*
import kotlin.js.JsName
import kotlin.math.min
import kotlin.test.*

class ClientTests {

    val address = "localhost"
    val port = 9999

    @Test
    @JsName("ClientCanConnectClose")
    fun `Client can connect and close`() = runTest{ scope ->
        createTCPServer(address,port){server ->
            val job = scope.launch {
                val client = server.accept()
                client.close()
            }
            val client = createTCPClientSocket(address, port)
            job.join()
            client.close()
        }
    }


    @Test
    @JsName("ClientCanReadOrWrite")
    fun `Client can read or write`() = runTest{ scope ->
        createTCPServer(address,port) { server ->
            val data = listOf<Byte>(1, 2, 3, 4, 5, 6, 7, 8, 9)

            val job = scope.launch {
                val socket = server.accept()
                val buf = allocMultiplatformBuffer(data.size)
                socket.read(buf)
                assertEquals(buf.toArray().toList(), data)
                assertEquals(data.size,buf.cursor)
                socket.close()
            }

            val client = createTCPClientSocket(address, port)

            val buffer = wrapMultiplatformBuffer(data.toByteArray())
            client.write(buffer)
            assertEquals(data.size,buffer.cursor)
            job.join()

            client.close()
        }
    }

    @Test
    @JsName("ClientCanReadOrWriteWithLimitsOnBuffer")
    fun `Client can read or write with limits on buffer`() = runTest{ scope ->
        createTCPServer(address,port) { server ->
            val data = listOf<Byte>(1, 2, 3, 4, 5, 6, 7, 8, 9)

            val job = scope.launch {
                val socket = server.accept()
                val buf = allocMultiplatformBuffer(data.size)
                buf.limit = socket.read(buf)
                assertTrue { data.containsAll(buf.toArray().toList()) }
                assertEquals(data.size-1,buf.cursor)
                socket.close()
            }

            val client = createTCPClientSocket(address, port)

            val buffer = wrapMultiplatformBuffer(data.toByteArray())
            buffer.limit--
            client.write(buffer)
            assertEquals(data.size-1,buffer.cursor)

            job.join()

            client.close()
        }
    }

    @Test
    @JsName("ClientCanReadWithMinimumNumberOfByte")
    fun `Client can read with a minimum number of byte`() = runTest{ scope ->
        createTCPServer(address,port) { server ->

            val job = scope.launch {
                val client = server.accept()
                val buf = allocMultiplatformBuffer(10)
                client.read(buf, 10)
                assertTrue { buf.cursor == 10 }
                client.close()
            }

            val client = createTCPClientSocket(address, port)

            //send 15 bytes with a delay between each one, the read should return when we sent 10 bytes, not waiting for the others
            val tmpBuf = allocMultiplatformBuffer(1)
            (1..10).forEach {
                tmpBuf.cursor = 0
                tmpBuf.putByte(it.toByte(),0)
                client.write(tmpBuf)
                delay(20)
            }

            job.join()
            client.close()

        }
    }

    @Test
    @JsName("ClientCanBulkReadWrite")
    fun `Client can bulk read write`() = runTest{ scope ->
        createTCPServer(address,port) { server ->

            // send a 10Mo chunk
            val data = ByteArray(10_000_000) {
                it.toByte()
            }

            scope.launch {
                //when a buffer is too large the library will internaly use bulk write
                val client = server.accept()
                client.write(wrapMultiplatformBuffer(data))
                client.close()
            }

            val client = createTCPClientSocket(address, port)

            var received = 0
            client.bulkRead(allocMultiplatformBuffer(65_536)) { buffer, read ->
                received += read
                buffer.limit = min(data.size - received,buffer.capacity)
                received != data.size
            }
            assertEquals(data.size, received)
            client.close()
        }
    }

    @Test
    @JsName("ClientWaitForTheEndOfTheSendQueueBeforeClose")
    fun `Client wait for the end of the send queue before close`() = runTest{ scope ->
        createTCPServer(address,port) { server ->
            val lastExpectedInt = 1_000

            val job = scope.launch {
                val socket = server.accept()
                val buffer = allocMultiplatformBuffer(4)
                socket.bulkRead(buffer){ b,_ ->
                    b.getInt(0) != lastExpectedInt
                }
                socket.close()
            }

            val client = createTCPClientSocket(address, port)

            //prepare all the buffers
            val buffers = mutableListOf<MultiplatformBuffer>()
            (1..lastExpectedInt).forEach {
                val buf = allocMultiplatformBuffer(4)
                buf.putInt(it,0)
                buffers.add(buf)
            }

            //async send them
            buffers.forEach {
                scope.async {
                    client.write(it)
                }
            }

            //close the client while the queue is not empty
            client.close()

            //wait for the server to read everything
            withTimeout(500){
                job.join()
            }
        }
    }

    @Test
    @JsName("ClientDoesntWaitForTheEndOfTheSendQueueWhenForceClose")
    fun `Client doesn't wait for the end of the send queue when force close`() = runTest{ scope ->
        createTCPServer(address,port) { server ->

            val job = scope.launch {
                val socket = server.accept()
                val buffer = allocMultiplatformBuffer(4)
                try {
                    socket.bulkRead(buffer){ _,_ ->
                        !socket.isClosed
                    }
                }catch (e : SokException){
                    socket.close()
                    //as the client does not wait for all write to complete, we should get an exception
                }
            }

            val client = createTCPClientSocket(address, port)

            //prepare all the buffers
            val lastExpectedInt = 1_000
            val buffers = mutableListOf<MultiplatformBuffer>()
            (1..lastExpectedInt).forEach {
                val buf = allocMultiplatformBuffer(4)
                buf.putInt(it)
                buffers.add(buf)
            }

            //async send them
            buffers.forEach {
                scope.launch {
                    try {
                        client.write(it)
                    }catch (e : SocketClosedException){
                        //that's normal as the forceClose doesn't wait for write to finish
                    }
                }
            }

            //close the client while the queue is not empty
            try {
                withTimeout(10) {
                    client.forceClose()
                }
            } catch (e: Exception) {
                fail("force close timed out")
            }
            assertTrue { client.isClosed }
            job.join()
        }
    }

    @Test
    @JsName("ClientCloseEventIsFiredOnce")
    fun `Client close event is fired once`() = runTest{ _ ->
        createTCPServer(address,port) { _ ->

            val client = createTCPClientSocket(address, port)

            var alreadyFired = false
            client.exceptionHandler = { exc ->
                assertTrue { exc is NormalCloseException }
                assertFalse { alreadyFired }
                alreadyFired = true
            }

            client.close()
            client.close()
        }
    }

    @Test
    @JsName("ClientCanSetAndGetOptions")
    fun `Client can set and get options`() = runTest{ scope ->
        createTCPServer(address,port){server ->
            val job = scope.launch {
                server.accept()
            }
            val client = createTCPClientSocket(address, port)
            job.join()

            // As SO_SNDBUF and SO_RCVBUF are not supported on Node.js, and are hints on other platforms they are quite complicated to test
            if(client.setOption(SocketOption(Options.SO_KEEPALIVE,true))){
                assertEquals(true, client.getOption<Boolean>(Options.SO_KEEPALIVE).value)
            }

            if(client.setOption(SocketOption(Options.TCP_NODELAY,true))){
                assertEquals(true, client.getOption<Boolean>(Options.TCP_NODELAY).value)
            }

            client.forceClose()
        }
    }
}

suspend fun createTCPServer(address : String, port : Int, test : suspend (TCPServerSocket) -> Unit){
    val server = createTCPServerSocket(address,port)
    assertTrue { !server.isClosed }
    try {
        test.invoke(server)
    }finally {
        server.close()
        assertTrue { server.isClosed }
    }
}
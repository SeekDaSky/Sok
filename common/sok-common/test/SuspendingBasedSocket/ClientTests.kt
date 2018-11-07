package SuspendingBasedSocket

import Sok.Buffer.MultiplatformBuffer
import Sok.Buffer.allocMultiplatformBuffer
import Sok.Buffer.wrapMultiplatformBuffer
import Sok.Exceptions.ConnectionRefusedException
import Sok.Socket.TCP.TCPServerSocket
import Sok.Socket.TCP.createTCPClientSocket
import Sok.Internal.runTest
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
                server.accept()
            }
            val client = createTCPClientSocket("localhost", 9999)
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
            }

            val client = createTCPClientSocket("localhost", 9999)

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
            }

            val client = createTCPClientSocket("localhost", 9999)

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
                val buf = allocMultiplatformBuffer(10)
                val client = server.accept()
                client.read(buf, 10)
                assertTrue { buf.cursor == 10 }
            }

            val client = createTCPClientSocket("localhost", 9999)

            //send 15 bytes with a delay between each one, the read should return when we sent 10 bytes, not waiting for the others
            (1..15).forEach {
                val tmpBuf = allocMultiplatformBuffer(1)
                tmpBuf.putByte(it.toByte())
                client.write(tmpBuf)
                delay(20)
            }

            client.close()
            job.join()

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
            }

            val client = createTCPClientSocket("localhost", 9999)

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

            val job = scope.launch {
                val socket = server.accept()
                val buffer = allocMultiplatformBuffer(4)
                while (!socket.isClosed) {
                    buffer.cursor = 0
                    socket.read(buffer)
                }
            }

            val client = createTCPClientSocket("localhost", 9999)

            //prepare all the buffers
            val lastExpectedInt = 1_000
            val buffers = mutableListOf<MultiplatformBuffer>()
            (1..lastExpectedInt).forEach {
                val buf = allocMultiplatformBuffer(4)
                buf.putInt(it)
                buffers.add(buf)
            }

            //async send them
            var lastDeferred: Deferred<Boolean> = CompletableDeferred(false)
            buffers.forEach {
                lastDeferred = scope.async {
                    client.write(it)
                }
            }

            //close the client while the queue is not empty
            client.close()
            assertTrue { lastDeferred.isCompleted }
            job.join()
        }
    }

    @Test
    @JsName("ClientDoesntWaitForTheEndOfTheSendQueueWhenForceClose")
    fun `Client doesn't wait for the end of the send queue when force close`() = runTest{ scope ->
        createTCPServer(address,port) { server ->

            val job = scope.launch {
                val socket = server.accept()
                val buffer = allocMultiplatformBuffer(4)
                while (!socket.isClosed) {
                    buffer.cursor = 0
                    socket.read(buffer)
                }
            }

            val client = createTCPClientSocket("localhost", 9999)

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
                    client.write(it)
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

            val client = createTCPClientSocket("localhost", 9999)

            var alreadyFired = false
            client.bindCloseHandler {
                assertFalse { alreadyFired }
                alreadyFired = true
            }

            client.close()
            client.close()
        }
    }

    @Test
    @JsName("ClientReadWriteReturnMinus1WhenSocketClosed")
    fun `Client read write return -1 when socket closed`() = runTest{ _ ->
        createTCPServer(address,port) { server ->

            val job = GlobalScope.launch {
                assertEquals(-1, server.accept().read(allocMultiplatformBuffer(1)))
            }

            val client = createTCPClientSocket("localhost", 9999)
            client.close()
            job.join()

            assertEquals(false, client.write(allocMultiplatformBuffer(1)))
            assertEquals(-1, client.read(allocMultiplatformBuffer(1)))
            assertEquals(-1, client.read(allocMultiplatformBuffer(1),1))
            assertEquals(-1, client.bulkRead(allocMultiplatformBuffer(1)){ _,_ -> false})
        }
    }

    @Test
    @JsName("ClientThrowExceptionIfConnectionRefused")
    fun `Client throw exception if connection refused`() = runTest { _ ->
        try {
            createTCPClientSocket("localhost", 9999)
            fail("exception not raised")
        }catch (e : Exception){
            assertTrue { e is ConnectionRefusedException }
        }
    }
}

suspend fun createTCPServer(address : String, port : Int, test : suspend (TCPServerSocket) -> Unit){
    val server = TCPServerSocket(address,port)
    assertTrue { !server.isClosed }
    try {
        test.invoke(server)
    }finally {
        server.close()
        assertTrue { server.isClosed }
    }
}
package SuspendingBasedSocket

import Sok.Buffer.MultiplatformBuffer
import Sok.Buffer.allocMultiplatformBuffer
import Sok.Buffer.wrapMultiplatformBuffer
import Sok.Exceptions.ConnectionRefusedException
import Sok.Socket.SuspendingServerSocket
import Sok.Socket.createSuspendingClientSocket
import Sok.Test.runTest
import kotlinx.coroutines.experimental.*
import Sok.Test.JsName
import kotlin.test.*

class ClientTests {

    val address = "localhost"
    val port = 9999

    @Test
    @JsName("ClientCanConnectClose")
    fun `Client can connect and close`() = runTest{
        val server = SuspendingServerSocket(address,port)
        val job = GlobalScope.launch {
            server.accept()
        }
        val client = createSuspendingClientSocket("localhost",9999)
        job.join()
        client.close()
        server.close()
    }


    @Test
    @JsName("ClientCanWrite")
    fun `Client can write`() = runTest{
        val server = SuspendingServerSocket(address,port)
        val data = listOf<Byte>(1,2,3,4,5,6,7,8,9)

        val job = GlobalScope.launch {
            val socket = server.accept()
            val buf = allocMultiplatformBuffer(data.size)
            socket.read(buf)
            assertEquals(buf.toArray().toList(),data)
        }

        val client = createSuspendingClientSocket("localhost",9999)

        client.write(wrapMultiplatformBuffer(data.toByteArray()))
        job.join()

        client.close()
        server.close()
    }

    @Test
    @JsName("ClientCanAsyncWrite")
    fun `Client can async write`() = runTest{
        val server = SuspendingServerSocket(address,port)
        val data = listOf<Byte>(1,2,3,4,5,6,7,8,9)

        val job = GlobalScope.launch {
            val buf = allocMultiplatformBuffer(data.size)
            server.accept().read(buf)
            assertEquals(buf.toArray().toList(),data)
        }

        val client = createSuspendingClientSocket("localhost",9999)

        client.asynchronousWrite(wrapMultiplatformBuffer(data.toByteArray())).await()
        job.join()

        client.close()
        server.close()
    }

    @Test
    @JsName("ClientCanRead")
    fun `Client can read`() = runTest{
        val server = SuspendingServerSocket(address,port)
        val data = listOf<Byte>(1,2,3,4,5,6,7,8,9)

        GlobalScope.launch {
            server.accept().write(wrapMultiplatformBuffer(data.toByteArray()))
        }

        val client = createSuspendingClientSocket("localhost",9999)
        val buf = allocMultiplatformBuffer(data.size)
        client.read(buf)
        assertEquals(buf.toArray().toList(),data)

        client.close()
        server.close()
    }

    @Test
    @JsName("ClientCanAsyncRead")
    fun `Client can async read`() = runTest{
        val server = SuspendingServerSocket(address,port)
        val data = listOf<Byte>(1,2,3,4,5,6,7,8,9)

        GlobalScope.launch {
            server.accept().write(wrapMultiplatformBuffer(data.toByteArray()))
        }

        val client = createSuspendingClientSocket("localhost",9999)
        val buf = allocMultiplatformBuffer(data.size)
        client.asynchronousRead(buf).await()
        assertEquals(buf.toArray().toList(),data)

        client.close()
        server.close()
    }

    @Test
    @JsName("ClientCanReadWithMinimumNumberOfByte")
    fun `Client can read with a minimum number of byte`() = runTest{
        val server = SuspendingServerSocket(address,port)

        val job = GlobalScope.launch {
            val buf = allocMultiplatformBuffer(10)
            val client = server.accept()
            client.read(buf,10)
            assertTrue { buf.limit == 10 }
        }

        val client = createSuspendingClientSocket("localhost",9999)

        //send 15 bytes with a delay between each one, the read should return when we sent 10 bytes, not waiting for the others
        (1..15).forEach {
            val tmpBuf = allocMultiplatformBuffer(1)
            tmpBuf.putByte(it.toByte())
            client.write(tmpBuf)
            delay(20)
        }

        client.close()
        job.join()

        server.close()
    }

    @Test
    @JsName("ClientCanAsyncReadWithMinimumNumberOfByte")
    fun `Client can async read with a minimum number of byte`() = runTest{
        val server = SuspendingServerSocket(address,port)

        val job = GlobalScope.launch {
            val buf = allocMultiplatformBuffer(10)
            server.accept().asynchronousRead(buf,10).await()
            assertTrue { buf.limit == 10 }
        }

        val client = createSuspendingClientSocket("localhost",9999)

        //send 15 bytes with a delay between each one, the read should return when we sent 10 bytes, not waiting for the others
        (1..15).forEach {
            val tmpBuf = allocMultiplatformBuffer(1)
            tmpBuf.putByte(it.toByte())
            client.write(tmpBuf)
            delay(10)
        }

        client.close()
        job.join()
        server.close()
    }

    @Test
    @JsName("ClientCanBulkReadWrite")
    fun `Client can bulk read write`() = runTest{
        val server = SuspendingServerSocket(address,port)

        // send a 10Mo chunk
        val data = ByteArray(10_000_000){
            it.toByte()
        }

        GlobalScope.launch {
            //when a buffer is too large the library will internaly use bulk write
            val client = server.accept()
            client.write(wrapMultiplatformBuffer(data))
        }

        val client = createSuspendingClientSocket("localhost",9999)

        var received = 0
        client.bulkRead(allocMultiplatformBuffer(65_536)){
            received += it.limit
            received != data.size
        }
        assertEquals(data.size,received)
        client.close()
        server.close()
    }

    @Test
    @JsName("ClientWaitForTheEndOfTheSendQueueBeforeClose")
    fun `Client wait for the end of the send queue before close`() = runTest{

        val server = SuspendingServerSocket(address,port)

        val job = GlobalScope.launch {
            val socket = server.accept()
            val buffer = allocMultiplatformBuffer(4)
            while(!socket.isClosed){
                buffer.cursor = 0
                socket.read(buffer)
            }
        }

        val client = createSuspendingClientSocket("localhost",9999)

        //prepare all the buffers
        val lastExpectedInt = 1_000
        val buffers = mutableListOf<MultiplatformBuffer>()
        (1..lastExpectedInt).forEach {
            val buf = allocMultiplatformBuffer(4)
            buf.putInt(it)
            buffers.add(buf)
        }

        //async send them
        var lastDeferred : Deferred<Boolean> = CompletableDeferred(false)
        buffers.forEach {
            lastDeferred = client.asynchronousWrite(it)
        }

        //close the client while the queue is not empty
        client.close()
        assertTrue { lastDeferred.isCompleted }
        job.join()
        server.close()
    }

    @Test
    @JsName("ClientDoesntWaitForTheEndOfTheSendQueueWhenForceClose")
    fun `Client doesn't wait for the end of the send queue when force close`() = runTest{
        val server = SuspendingServerSocket(address,port)

        val job = GlobalScope.launch {
            val socket = server.accept()
            val buffer = allocMultiplatformBuffer(4)
            while(!socket.isClosed){
                buffer.cursor = 0
                socket.read(buffer)
            }
        }

        val client = createSuspendingClientSocket("localhost",9999)

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
            client.asynchronousWrite(it)
        }

        //close the client while the queue is not empty
        try {
            withTimeout(10){
                client.forceClose()
            }
        }catch (e : Exception){
            fail("force close timed out")
        }
        assertTrue { client.isClosed }
        job.join()
        server.close()
    }

    @Test
    @JsName("ClientCloseEventIsFiredOnce")
    fun `Client close event is fired once`() = runTest{
        val server = SuspendingServerSocket(address,port)

        val client = createSuspendingClientSocket("localhost",9999)

        var alreadyFired = false
        client.bindCloseHandler {
            assertFalse { alreadyFired }
            alreadyFired = true
        }

        client.close()
        client.close()
        server.close()
    }

    @Test
    @JsName("ClientReadWriteReturnMinus1WhenSocketClosed")
    fun `Client read write return -1 when socket closed`() = runTest{
        val server = SuspendingServerSocket(address,port)

        val job = GlobalScope.launch {
            assertEquals(-1,server.accept().read(allocMultiplatformBuffer(1)))
        }

        val client = createSuspendingClientSocket("localhost",9999)
        client.close()

        assertEquals(false, client.write(allocMultiplatformBuffer(1)))
        assertEquals(-1, client.read(allocMultiplatformBuffer(1)))
        job.join()
        server.close()
    }

    @Test
    @JsName("ClientThrowExceptionIfConnectionRefused")
    fun `Client throw exception if connection refused`() = runTest {
        try {
            createSuspendingClientSocket("localhost", 9999)
            fail("exception not raised")
        }catch (e : Exception){
            assertTrue { e is ConnectionRefusedException }
        }
    }
}
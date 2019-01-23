package TCPSockets

import Sok.Buffer.allocMultiplatformBuffer
import Sok.Internal.runTest
import Sok.Socket.TCP.createTCPClientSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsSpecificClientTests{

    val address = "127.0.0.1"
    val port = 9999

    @Test
    @JsName("ClientCanChunkReadWhenLargeDataReceived")
    fun `Client can chunk read when large data received`() = runTest{ scope ->
        createTCPServer(address,port) { server ->

            val job = scope.launch {
                val client = server.accept()
                val buf = allocMultiplatformBuffer(30)
                client.write(buf)
            }

            val client = createTCPClientSocket(address, port)

            val buf = allocMultiplatformBuffer(10)

            assertEquals(10,client.read(buf))
            assertEquals(10,buf.cursor)

            buf.cursor = 0
            assertEquals(10,client.read(buf,5))
            assertEquals(10,buf.cursor)

            buf.cursor = 0
            val read = client.bulkRead(buf){_,_ ->
                false
            }

            assertEquals(10,read)

            job.join()
            client.close()

        }
    }
}

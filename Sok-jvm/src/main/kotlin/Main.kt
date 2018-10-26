import Sok.Buffer.BufferPool
import Sok.Buffer.MultiplatformBuffer
import Sok.Buffer.allocDirectMultiplatformBuffer
import Sok.Buffer.allocMultiplatformBuffer
import Sok.Socket.TCP.TCPServerSocket
import kotlinx.coroutines.experimental.*
val dataSize = 16777216
val bufferPool = BufferPool(16,65536){
    allocDirectMultiplatformBuffer(it)
}

fun main(args: Array<String>){

    val readSpeedList = mutableListOf<Double>()

    val server = TCPServerSocket("localhost", 9999)

    GlobalScope.launch {
        while(!server.isClosed) {

            val socket = server.accept()

            GlobalScope.launch {

                while(!socket.isClosed){

                    val buffer = bufferPool.requestBuffer()

                    val starttime = System.currentTimeMillis()
                    var received = 0

                    socket.bulkRead(buffer){ _, read ->

                        received += read

                        if(received >= dataSize){
                            received = 0
                            false
                        }else{
                            true
                        }
                    }
                    val stoptime = System.currentTimeMillis()

                    val time = (stoptime-starttime)/1000.0
                    val dataSizeMO = dataSize/1_000_000

                    readSpeedList.add(dataSizeMO/time)

                    //limit number of mesures
                    if(readSpeedList.size > 200){
                        readSpeedList.removeAt(0)
                    }


                    bufferPool.freeBuffer(buffer)
                }
            }
        }
    }

    server.bindCloseHandler {

    }

    while (true){
        try {
            Thread.sleep(500)
            println("Average read speed: ${readSpeedList.average()} Mo/s")
            println("-----------------------------")
        }catch (e : Exception){

        }
    }
}

fun stubBuffer() : MultiplatformBuffer{
    val buf = ByteArray(dataSize){
        it.toByte()
    }

    val bb = allocDirectMultiplatformBuffer(buf.size)
    bb.putBytes(buf)

    return bb
}
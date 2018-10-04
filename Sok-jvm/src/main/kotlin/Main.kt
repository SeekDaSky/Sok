import Sok.Buffer.BufferPool
import Sok.Buffer.MultiplatformBuffer
import Sok.Buffer.allocDirectMultiplatformBuffer
import Sok.Socket.SuspendingServerSocket
import kotlinx.coroutines.experimental.*
val dataSize = 16777216
val bufferPool = BufferPool(16,65536){
    allocDirectMultiplatformBuffer(it)
}

fun main(args: Array<String>){

    val readSpeedList = mutableListOf<Double>()

    val socket = SuspendingServerSocket("localhost",9999)

    GlobalScope.launch(Dispatchers.IO) {
        while(!socket.isClosed) {

            val socket = socket.accept()

            GlobalScope.launch(Dispatchers.IO) {

                while(!socket.isClosed){

                    val buffer = bufferPool.requestObject()

                    val starttime = System.currentTimeMillis()
                    var received = 0

                    socket.bulkRead(buffer){

                        received += it.limit

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


                    bufferPool.freeObject(buffer)
                }
            }
        }
    }

    socket.bindCloseHandler {

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
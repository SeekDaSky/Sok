package Sok

import Sok.Buffer.MultiplatformBuffer
import Sok.Buffer.allocMultiplatformBuffer
import Sok.Socket.TCP.TCPServerSocket
import Sok.Socket.TCP.createTCPClientSocket
import Sok.Sok.setTimeout
import kotlinx.coroutines.*
import kotlin.js.Date

val dataSize = 16777216
val readSpeedList = mutableListOf<Double>()
val writeSpeedList = mutableListOf<Double>()

fun nomain(){

    val server = TCPServerSocket("localhost", 9999)

    GlobalScope.launch(Dispatchers.Default) {
        while(!server.isClosed) {

            val socket = server.accept()

            GlobalScope.launch(Dispatchers.Default) {

                while(!socket.isClosed){

                    val buffer = allocMultiplatformBuffer(65536)

                    val starttime = Date.now()
                    var received = 0

                    socket.bulkRead(buffer){ _, read ->

                        received += read

                        received >= dataSize
                    }

                    val stoptime = Date.now()

                    val time = (stoptime-starttime)/1000.0

                    val dataSizeMO = dataSize/1_000_000

                    readSpeedList.add(dataSizeMO/time)

                    //limit number of mesures
                    if(readSpeedList.size > 200){
                        readSpeedList.removeAt(0)
                    }

                }
            }
        }
    }

    server.bindCloseHandler {

    }

    waitRead()
}

fun Notmain(){
    GlobalScope.launch {

        val numberOfClients = 1

        (1..numberOfClients).forEach {
            val s = createTCPClientSocket("localhost", 9999)

            launch {
                val buf = stubBuffer()

                (0..5000).forEach {
                    val start = Date.now()
                    s.write(buf)
                    val stop = Date.now()

                    val time = (stop - start) / 1000.0
                    val dataSizeMO = dataSize / 1_000_000

                    writeSpeedList.add(dataSizeMO / time)
                    if (writeSpeedList.size > 200) {
                        writeSpeedList.removeAt(0)
                    }
                }
                s.close()
                println("finished bench")
            }
        }

    }
    waitWrite()
}

fun waitRead(){
    setTimeout({
        println("Average read speed: ${readSpeedList.average()} Mo/s")
        println("-----------------------------")
        waitRead()
    },500)
}

fun waitWrite(){
    setTimeout({
        println("Average write speed: ${writeSpeedList.average()} Mo/s")
        println("-----------------------------")
        waitWrite()
    },500)
}

fun stubBuffer() : MultiplatformBuffer {
    val buf = ByteArray(dataSize){
        it.toByte()
    }

    val bb = allocMultiplatformBuffer(buf.size)
    bb.putBytes(buf)

    return bb
}
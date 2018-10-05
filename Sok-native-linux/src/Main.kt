import Sok.Buffer.*
import Sok.Selector.*
import Sok.Socket.SuspendingServerSocket
import kotlinx.coroutines.experimental.*
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval

val dataSize = 16777216

fun main(args: Array<String>) = runBlocking{
    Selector.setDefaultScope(this)

    val readSpeedList = mutableListOf<Double>()

    val server = SuspendingServerSocket("localhost",9999)

    val job = GlobalScope.launch() {
        while(!server.isClosed) {

            val socket = server.accept()

            GlobalScope.launch() {

                val buffer = allocMultiplatformBuffer(dataSize)

                val start = nativeHeap.alloc<timeval>()
                val stop = nativeHeap.alloc<timeval>()

                while(!socket.isClosed){

                    gettimeofday(start.ptr,null)
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
                    gettimeofday(stop.ptr,null)

                    val time = (stop.tv_usec-start.tv_usec)/1000.0
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

    GlobalScope.launch(){
        while(true){
            delay(500)
            println("Average read speed: ${readSpeedList.average()} Mo/s")
            println("-----------------------------")
        }
    }

    job.join()

    Selector.closeSelectorAndWait()
}

import Sok.Socket.TCP.createTCPClientSocket
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import java.lang.Exception


fun main(args: Array<String>) = runBlocking {

    val numberOfClients = 1
    val writeSpeedList = mutableListOf<Double>()

    (1..numberOfClients).forEach {
        val s = createTCPClientSocket("localhost",9999)

        launch {
            val buf = stubBuffer()

            (0..5000).forEach {
                val start = System.currentTimeMillis()
                s.write(buf)
                val stop = System.currentTimeMillis()

                val time = (stop-start)/1000.0
                val dataSizeMO = dataSize/1_000_000

                writeSpeedList.add(dataSizeMO/time)
                if(writeSpeedList.size > 200){
                    writeSpeedList.removeAt(0)
                }
            }
            s.close()
            println("finished bench")
        }
    }

    while (true){
        try {
            delay(500)
            println("Average write speed: ${writeSpeedList.average()} Mo/s")
            println("-----------------------------")
        }catch (e : Exception){

        }
    }
}
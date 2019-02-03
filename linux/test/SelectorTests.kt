import kotlin.test.*

import Sok.Selector.*
import Sok.Internal.runTest

import kotlinx.coroutines.*

import platform.posix.*
import platform.linux.eventfd

import kotlinx.cinterop.*

class SelectorTests{

    private fun withSelector(scope : CoroutineScope, function : suspend(selector : Selector) -> Unit){
        Selector.setDefaultScope(scope)
        scope.launch{
            try {
                function.invoke(Selector.defaultSelector)
            } finally {
                Selector.closeSelectorAndWait()
                Selector.setDefaultScope(GlobalScope)
            }
        }
    }

    @Test
    fun `Selector can select once`() = runTest{
        withSelector(it) { selector ->
            val socket = makeNonBlockingEventFD()
            val sk = selector.register(socket)

            //write some data
            val sent: Long = 2

            sk.select(Interests.OP_WRITE)
            write(socket, cValuesOf(sent), sizeOf<LongVar>().toULong())

            sk.select(Interests.OP_READ)
            memScoped {
                val read = allocArray<LongVar>(1)
                read(sk.socket, read, sizeOf<LongVar>().toULong())
                assertEquals(sent, read[0])
            }
        }
    }

    @Test
    fun `Selector can select a lot`() = runTest{
        withSelector(it) { selector ->
            val socket = makeNonBlockingEventFD()
            val sk = selector.register(socket)

            val numberOfExchange: Long = 200

            //write some data
            val def = GlobalScope.async {
                var sent: Long = 1
                do {
                    sk.select(Interests.OP_WRITE)
                    sent++
                    write(socket, cValuesOf(sent), sizeOf<LongVar>().toULong())

                } while (sent != numberOfExchange)
                true
            }

            var received: Long = 1
            do {
                sk.select(Interests.OP_READ)
                memScoped {
                    val read = allocArray<LongVar>(1)
                    read(sk.socket, read, sizeOf<LongVar>().toULong())
                    received = read[0]
                }
            } while (received != numberOfExchange)

            def.await()
        }
    }

    @Test
    fun `Selector can bulk select`() = runTest{
        withSelector(it) { selector ->
            val socket = makeNonBlockingEventFD()
            val sk = selector.register(socket)

            val numberOfExchange: Long = 200

            //write some data
            val def = GlobalScope.async {
                var sent: Long = 1
                sk.selectAlways(Interests.OP_WRITE) {
                    sent++
                    write(socket, cValuesOf(sent), sizeOf<LongVar>().toULong())

                    sent != numberOfExchange
                }
                true
            }

            var received: Long = 1
            sk.selectAlways(Interests.OP_READ) {
                memScoped {
                    val read = allocArray<LongVar>(1)
                    read(sk.socket, read, sizeOf<LongVar>().toULong())
                    received = read[0]
                }
                received != numberOfExchange
            }

            def.await()
        }
    }

    @Test
    fun `SelectionKey is closed when socket is closed`() = runTest{
        withSelector(it) { selector ->
            val socket = makeNonBlockingEventFD()
            val sk = selector.register(socket)

            sk.close()
            try {
                sk.select(Interests.OP_READ)
                fail("Exception not thrown")
            } catch (e: Exception) {
                //yay
            }
        }
    }

}

fun msleep(millis : Int){
    usleep((1000*millis).toUInt())
}

fun makeNonBlockingEventFD() : Int{
    val socket = eventfd(0,0)
    Sok.Utils.makeNonBlocking(socket)
    return socket
}
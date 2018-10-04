package Buffer

import Sok.Buffer.ObjectPool
import Sok.Test.runTest
import kotlinx.coroutines.experimental.*
import Sok.Test.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObjectPoolTests{

    @Test
    @JsName("PoolContainsTheRightNumberOfBuffer")
    fun `Pool contains the right number of buffer`() = runTest{
        val pool = ObjectPool(2){
            Any()
        }

        (0..1).forEach {
            withTimeout(100){
                pool.requestObject()
            }
        }
    }

    @Test
    @JsName("FreeingBufferDoNotProduceStarvation")
    fun `Freeing buffer do not produce starvation`() = runTest{
        val pool = ObjectPool(2){
            Any()
        }

        (0..10).forEach {
            withTimeout(100){
                pool.freeObject(pool.requestObject())

            }
        }
    }

    @Test
    @JsName("SuspendedCoroutineResumeWhenABufferIsAvailable")
    fun `Suspended coroutine resume when a buffer is available`() = runTest{
        val pool = ObjectPool(1){
            Any()
        }

        val buffer = pool.requestObject()

        GlobalScope.launch {
            withTimeout(100){
                pool.requestObject()
                assertTrue(true)
            }
        }

        delay(10)

        pool.freeObject(buffer)

    }

    @Test
    @JsName("PoolSuspendOnStarvation")
    fun `Pool suspend on starvation`() = runTest {
        val pool = ObjectPool(2){
            Any()
        }

        (0..2).forEach {
            try {
                withTimeout(50){
                    pool.requestObject()
                }
            }catch (e : Exception){
                assertEquals(2,it)
            }
        }
    }


}
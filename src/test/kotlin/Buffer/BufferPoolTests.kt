package Buffer

import Sok.Buffer.BufferPool
import Sok.Test.runTest
import kotlinx.coroutines.experimental.*
import Sok.Test.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BufferPoolTests{

    @Test
    @JsName("PoolAllocateBufferWhenEmpty")
    fun `Pool allocate buffer when empty`() = runTest{
        val pool = BufferPool(2,1)

        (0..1).forEach {
            withTimeout(10){
                pool.requestObject()
            }
        }
    }

    @Test
    @JsName("FreeingBufferDoesNotProduceStarvation")
    fun `Freeing buffer does not produce starvation`() = runTest{
        val pool = BufferPool(2,1)

        (0..10).forEach {
            withTimeout(100){
                pool.freeObject(pool.requestObject())
            }
        }
    }

    @Test
    @JsName("SuspendedCoroutineResumeWhenABufferIsAvailable")
    fun `Suspended coroutine resume when a buffer is available`() = runTest{
        val pool = BufferPool(1,1)

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
        val pool = BufferPool(2,1)

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
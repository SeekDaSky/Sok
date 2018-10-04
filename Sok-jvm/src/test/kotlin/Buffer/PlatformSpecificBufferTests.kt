package Buffer

import Sok.Buffer.allocDirectMultiplateformBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformSpecificBufferTests {

    @Test
    fun `Buffer get array with direct back buffer`(){
        val arr = listOf(1,2,255.toByte()).toByteArray()

        val buf = allocDirectMultiplateformBuffer(3)
        buf.putBytes(arr)

        //is the cursor at the right place
        assertEquals(3,buf.getCursor())

        //test if the array and the list are identical
        assertEquals(arr.toList(),buf.toArray().toList())

        //check that the cursor didn't move
        assertEquals(3,buf.getCursor())
    }
}
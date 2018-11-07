package Buffer

import Sok.Buffer.allocMultiplatformBuffer
import Sok.Buffer.wrapMultiplatformBuffer
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.*

class MultiplateformBufferTest {

    @Test
    @JsName("BufferChecksCursorAndLimitInputs")
    fun `Buffer checks cursor and limit inputs`(){
        //check allocate and wrap set cursor to 0 and limit to capacity
        var buf = allocMultiplatformBuffer(3)
        assertEquals(0,buf.cursor)
        assertEquals(3,buf.limit)

        buf = wrapMultiplatformBuffer(listOf<Byte>(1,2,3).toByteArray())
        assertEquals(0,buf.cursor)
        assertEquals(3,buf.limit)

        buf.limit = 2
        buf.cursor = 1
        assertEquals(2,buf.limit)
        assertEquals(1,buf.cursor)
        assertFails { buf.cursor = 3 }

        buf.limit = 3
        buf.cursor = 3

        assertFails { buf.limit = 2 }
        assertFails { buf.limit = 4 }
        assertFails { buf.cursor = 4 }

        buf.cursor = 2
        buf.limit = 2
        buf.reset()
        assertEquals(0,buf.cursor)
        assertEquals(3,buf.limit)

    }

    @Test
    @JsName("BufferGetPutByte")
    fun `Buffer get put byte`(){
        val buf = wrapMultiplatformBuffer(listOf<Byte>(1,2,3).toByteArray())

        //test absolute access
        assertEquals(1,buf[0])
        assertEquals(1,buf.getByte(0))
        assertEquals(0,buf.cursor)

        //test if relative get works
        buf.cursor++
        assertEquals(2,buf.getByte())
        assertEquals(2,buf.cursor)

        //test put byte
        buf.putByte(6)
        assertEquals(3,buf.cursor)
        assertEquals(6,buf.getByte(2))

        //test buffer overflow/underflow exceptions
        assertFails { buf.getByte() }
        assertFails { buf.getByte(3) }
        assertFails { buf.putByte(10) }
        assertFails { buf.putByte(10,3) }
    }

    @Test
    @JsName("BufferGetUByte")
    fun `Buffer get unsigned byte`(){
        val buf = wrapMultiplatformBuffer(listOf<Byte>(255.toByte(),0,254.toByte()).toByteArray())

        //test absolute access (toString because of a bug in Mocha/Node.JS/Kotlinx/JS, don't know)
        assertEquals(254.toString(),buf.getUByte(2).toString())
        assertEquals(0,buf.cursor)

        //test if relative get works (toString because of a bug in Mocha/Node.JS/Kotlinx/JS, don't know)
        assertEquals(255.toString(),buf.getUByte().toString())
        assertEquals(1,buf.cursor)

        //test buffer overflow/underflow exceptions
        buf.cursor = 3
        assertFails { buf.getUByte() }
        assertFails { buf.getUByte(3) }

    }

    @Test
    @JsName("BufferPutGetByteArray")
    fun `Buffer put get Byte array`(){
        val arr = listOf(1,2,255.toByte()).toByteArray()
        val arr2 = listOf(255.toByte(),2,1).toByteArray()

        val buf = allocMultiplatformBuffer(3)
        buf.putBytes(arr)
        assertEquals(3,buf.cursor)

        //test all bytes
        buf.cursor = 0
        assertEquals(1,buf.getByte())
        assertEquals(2,buf.getByte())
        //(toString because of a bug in Mocha/Node.JS/Kotlinx/JS, don't know)
        assertEquals(255.toString(),buf.getUByte().toString())
        assertEquals(3,buf.cursor)

        //test absolute write
        assertFails { buf.putBytes(arr2,2) }
        buf.putBytes(arr2,0)
        assertEquals(3,buf.cursor)
        buf.cursor = 0
        assertEquals(-1,buf.getByte())

        //get relative/absolute getBytes
        buf.cursor = 0
        assertEquals(arr2.toList(),buf.getBytes(3).toList())
        assertEquals(3,buf.cursor)
        assertEquals(arr2.toList(),buf.getBytes(3,0).toList())
    }

    @Test
    @JsName("BufferPutGetShort")
    fun `Buffer put get Short`(){
        val buf = wrapMultiplatformBuffer(listOf<Byte>(1,2).toByteArray())

        //test absolute access
        assertEquals(258,buf.getShort(0))
        assertEquals(0,buf.cursor)

        //test if relative get works
        assertEquals(258,buf.getShort())
        assertEquals(2,buf.cursor)

        //test put byte
        buf.cursor = 0
        buf.putShort(500)
        assertEquals(2,buf.cursor)
        assertEquals(500,buf.getShort(0))

        //test buffer overflow/underflow exceptions
        assertFails { buf.getShort() }
        assertFails { buf.getShort(3) }
        assertFails { buf.putShort(10) }
        assertFails { buf.putShort(10,3) }
    }

    @Test
    @JsName("BufferGetUnsignedShort")
    fun `Buffer get Unsigned Short`(){
        val buf = allocMultiplatformBuffer(2)
        buf.putShort(65535.toShort())
        buf.cursor = 0

        //test absolute access
        assertEquals(65535.toString(),buf.getUShort(0).toString())
        assertEquals(0,buf.cursor)

        //test if relative get works
        buf.cursor = 0
        assertEquals(65535.toString(),buf.getUShort().toString())
        assertEquals(2,buf.cursor)

        //test buffer overflow/underflow exceptions
        assertFails { buf.getUShort() }
        assertFails { buf.getUShort(3) }
    }

    @Test
    @JsName("BufferPutGetInteger")
    fun `Buffer put get Integer`(){
        val buf = wrapMultiplatformBuffer(listOf<Byte>(1,2,3,4).toByteArray())

        //test absolute access
        assertEquals(16_909_060,buf.getInt(0))
        assertEquals(0,buf.cursor)

        //test if relative get works
        assertEquals(16_909_060,buf.getInt())
        assertEquals(4,buf.cursor)

        //test put byte
        buf.cursor = 0
        buf.putInt(16_000_000)
        assertEquals(4,buf.cursor)
        assertEquals(16_000_000,buf.getInt(0))

        //test buffer overflow/underflow exceptions
        assertFails { buf.getInt() }
        assertFails { buf.getInt(3) }
        assertFails { buf.putInt(10) }
        assertFails { buf.putInt(10,3) }
    }

    @Test
    @JsName("BufferGetUnsignedInteger")
    fun `Buffer get Unsigned Integer`(){
        val buf = allocMultiplatformBuffer(4)
        buf.putInt(4294967295.toInt())
        buf.cursor = 0

        //test absolute access (we have to compare strings representation as mocha can't compare the primitives types for some reason
        assertEquals(4294967295.toString(),buf.getUInt(0).toString())
        assertEquals(0,buf.cursor)

        //test if relative get works
        buf.cursor = 0
        assertEquals(4294967295.toString(),buf.getUInt().toString())
        assertEquals(4,buf.cursor)

        //test buffer overflow/underflow exceptions
        assertFails { buf.getUInt() }
        assertFails { buf.getUInt(3) }
    }

    @Test
    @JsName("BufferPutGetLong")
    fun `Buffer put get Long`(){
        val buf = wrapMultiplatformBuffer(listOf<Byte>(1,1,1,1,1,1,1,1).toByteArray())

        //test absolute access
        assertEquals(72_340_172_838_076_673,buf.getLong(0))
        assertEquals(0,buf.cursor)

        //test if relative get works
        assertEquals(72_340_172_838_076_673,buf.getLong())
        assertEquals(8,buf.cursor)

        //test put byte
        buf.cursor = 0
        buf.putLong(72_000_000_000_000_000)
        assertEquals(8,buf.cursor)
        assertEquals(72_000_000_000_000_000,buf.getLong(0))

        //test buffer overflow/underflow exceptions
        assertFails { buf.getLong() }
        assertFails { buf.getLong(3) }
        assertFails { buf.putLong(10) }
        assertFails { buf.putLong(10,3) }
    }

    @Test
    @JsName("BufferGetArray")
    fun `Buffer get Array`(){
        val arr = listOf(1,2,255.toByte()).toByteArray()

        val buf = allocMultiplatformBuffer(3)
        buf.putBytes(arr)

        //test if the array and the list are identical
        assertEquals(arr.toList(),buf.toArray().toList())
        //test if method bypass cursor
        buf.cursor = 1
        assertEquals(arr.toList(),buf.toArray().toList())

        assertEquals(1,buf.cursor)
    }

    @Test
    @JsName("BufferCapacity")
    fun `Buffer Size`(){
        var buf = allocMultiplatformBuffer(3)
        assertEquals(3,buf.capacity)
        buf = wrapMultiplatformBuffer(listOf<Byte>(1,2,3,4).toByteArray())
        assertEquals(4,buf.capacity)
    }

    @Test
    @JsName("BufferClone")
    fun `Buffer clone`(){
        val arr = listOf(1,2,255.toByte()).toByteArray()

        val buf = allocMultiplatformBuffer(3)
        buf.putBytes(arr)
        val buf2 = buf.clone()
        assertEquals(0,buf2.cursor)
        assertEquals(3,buf2.limit)

        //check equals to clone
        assertEquals(buf.toArray().toList(),buf2.toArray().toList())
        //check data not linked
        buf2.putByte(10,1)
        assertNotEquals(buf.toArray().toList(),buf2.toArray().toList())
        assertEquals(buf.toArray().toList(),arr.toList())
    }

    @Test
    @JsName("BufferRemaining")
    fun `Buffer remaining`(){
        val buf = allocMultiplatformBuffer(3)

        assertEquals(3,buf.remaining())
        assertEquals(true,buf.hasRemaining())

        buf.limit = 2
        assertEquals(2,buf.remaining())

        buf.cursor = 1
        assertEquals(1,buf.remaining())

        buf.cursor = 2
        assertEquals(0,buf.remaining())
        assertFalse { buf.hasRemaining() }

    }
}
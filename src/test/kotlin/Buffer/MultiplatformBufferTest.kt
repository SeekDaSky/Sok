package Buffer

import Sok.Buffer.allocMultiplateformBuffer
import Sok.Test.JsName
import kotlin.test.Test
import kotlin.test.*

class MultiplateformBufferTest {

    @Test
    @JsName("BufferOverflow")
    fun `Buffer Overflow`(){
        //test buffer overflow with 0-size buffer
        var buf = allocMultiplateformBuffer(0)
        assertFails {
            buf.putByte(1)
        }

        //test buffer overflow when buffer size is not null but too small for the given value
        buf = allocMultiplateformBuffer(2)
        assertFails {
            buf.putLong(1)
        }

        //test buffer overflow when buffer size is not null but we try to push too many bytes
        buf = allocMultiplateformBuffer(2)
        assertFails {
            for(i in 0..3){
                buf.putByte(0)
            }
        }
    }

    @Test
    @JsName("BufferUnderflow")
    fun `Buffer Underflow`(){
        //with 0-sized buffer
        var buf = allocMultiplateformBuffer(0)
        assertFails {
            buf.getByte()
        }

        //with non-empty buffer but with not enough data
        buf = allocMultiplateformBuffer(4)
        buf.putInt(1)
        assertFails {
            buf.setCursor(0)
            buf.getLong()
        }

        //with non empty buffer with successive reads
        buf = allocMultiplateformBuffer(4)
        buf.putInt(1)
        assertFails {
            for(i in 0..4){
                buf.getByte()
            }
        }
    }

    @Test
    @JsName("BufferPutByte")
    fun `Buffer put byte`(){
        val buf = allocMultiplateformBuffer(3)
        buf.putByte(1)
        buf.putByte(2)
        buf.putByte(3)

        //test if absolute method works
        assertEquals(1,buf.get(0))

        //test if relative get works
        buf.setCursor(0)
        assertEquals(1,buf.getByte())

        //test if all the backbuffers work
        buf.setCursor(2)
        assertEquals(3,buf.getByte())
    }

    @Test
    @JsName("BufferPutUByte")
    fun `Buffer put unsigned byte`(){
        val buf = allocMultiplateformBuffer(3)
        buf.putByte(255.toByte())
        buf.putByte(0)
        buf.putByte(254.toByte())

        //test if cursor is at the end
        assertFails { buf.getByte() }

        //is the byte considered as signed
        buf.setCursor(0)
        assertEquals(-1,buf.getByte())

        //is the unsigned byte correct
        buf.setCursor(0)
        assertEquals(255,buf.getUByte())

        buf.setCursor(2)
        assertEquals(254,buf.getUByte())
    }

    @Test
    @JsName("BufferPutByteList")
    fun `Buffer put Byte List`(){
        val arr = listOf(1,2,255.toByte()).toByteArray()

        val buf = allocMultiplateformBuffer(3)
        buf.putBytes(arr)

        //is the cursor at the right place
        assertEquals(3,buf.getCursor())

        //test all bytes
        buf.setCursor(0)
        assertEquals(1,buf.getByte())
        assertEquals(2,buf.getByte())
        assertEquals(255,buf.getUByte())

        //test if the buffer correctly get short values from the byte array
        buf.setCursor(0)
        assertEquals(258,buf.getShort())
        buf.setCursor(0)
        assertEquals(258,buf.getUShort())

        //get all the array (we have to convert to lists so we can compare them)
        buf.setCursor(0)
        assertEquals(arr.toList(),buf.getBytes(3).toList())

        assertEquals(arr.toList(),buf.getBytes(0,3).toList())

        //test if the array and the buffer are not linked
        buf.setCursor(1)
        buf.putByte(5)
        assertEquals(2,arr[1])

    }

    @Test
    @JsName("BufferPutShort")
    fun `Buffer put Short`(){
        val buf = allocMultiplateformBuffer(2)

        //let's put our short in the middle of the buffer
        buf.putShort(258)

        //test if cursor is at the end
        assertFails { buf.getByte() }


        buf.setCursor(0)
        assertEquals(1,buf.getByte())
        assertEquals(2,buf.getByte())


        buf.setCursor(0)
        assertEquals(258,buf.getShort())

        buf.setCursor(0)
        assertEquals(258,buf.getUShort())
    }

    @Test
    @JsName("BufferPutUnsignedShort")
    fun `Buffer put Unsigned Short`(){
        val buf = allocMultiplateformBuffer(2)
        buf.putShort(65535.toShort())

        //test if cursor is at the end
        assertFails { buf.getByte() }

        buf.setCursor(0)
        assertEquals(-1,buf.getShort())

        buf.setCursor(0)
        assertEquals(65535,buf.getUShort())
    }

    @Test
    @JsName("BufferPutInteger")
    fun `Buffer put Integer`(){
        val buf = allocMultiplateformBuffer(4)
        buf.putInt(16909060)

        //test if cursor is at the end
        assertFails { buf.getByte() }


        buf.setCursor(0)
        assertEquals(1,buf.getByte())
        assertEquals(2,buf.getByte())
        assertEquals(3,buf.getByte())
        assertEquals(4,buf.getByte())


        buf.setCursor(0)
        assertEquals(16909060,buf.getInt())

        buf.setCursor(0)
        //fix a strange bug mith Mocha not being able of comparing the two
        assertEquals(16909060.toLong().toString(),buf.getUInt().toString())
    }

    @Test
    @JsName("BufferPutUnsignedInteger")
    fun `Buffer put Unsigned Integer`(){
        val buf = allocMultiplateformBuffer(4)
        buf.putInt(4294967295.toInt())

        //test if cursor is at the end
        assertFails { buf.getByte() }

        buf.setCursor(0)
        assertEquals(-1,buf.getInt())

        buf.setCursor(0)
        //fix a strange bug mith Mocha not being able of comparing the two
        assertEquals(4294967295.toString(),buf.getUInt().toString())
    }

    @Test
    @JsName("BufferPutLong")
    fun `Buffer put Long`(){
        val buf = allocMultiplateformBuffer(8)
        buf.putLong(72340172838076673)

        //test if cursor is at the end
        assertFails { buf.getByte() }

        //are all the bytes here?
        buf.setCursor(0)
        for(i in 0..7){
            assertEquals(1,buf.getByte())
        }

        buf.setCursor(0)
        assertEquals(72340172838076673,buf.getLong())

    }

    @Test
    @JsName("BufferPutByteArray")
    fun `Buffer put Byte array`(){
        val arr = listOf(1,2,255.toByte()).toByteArray()

        val buf = allocMultiplateformBuffer(3)
        buf.putBytes(arr)

        assertEquals(3,buf.getCursor())

        buf.setCursor(0)
        assertEquals(1,buf.getByte())
        assertEquals(2,buf.getByte())
        assertEquals(255,buf.getUByte())

        buf.setCursor(0)
        assertEquals(arr.toList(),buf.getBytes(3).toList())
    }

    @Test
    @JsName("BufferGetArray")
    fun `Buffer get Array`(){
        val arr = listOf(1,2,255.toByte()).toByteArray()

        val buf = allocMultiplateformBuffer(3)
        buf.putBytes(arr)

        //is the cursor at the right place
        assertEquals(3,buf.getCursor())

        //test if the array and the list are identical
        assertEquals(arr.toList(),buf.toArray().toList())

        //check that the cursor didn't move
        assertEquals(3,buf.getCursor())
    }

    @Test
    @JsName("BufferSize")
    fun `Buffer Size`(){
        var buf = allocMultiplateformBuffer(3)

        buf.putShort(2)
        //check if the putShort didn't modify the size
        assertEquals(3,buf.size())
    }

    @Test
    @JsName("BufferClone")
    fun `Buffer clone`(){
        val arr = listOf(1,2,255.toByte()).toByteArray()

        val buf = allocMultiplateformBuffer(3)
        buf.putBytes(arr)

        val buf2 = buf.clone()

        assertEquals(buf.toArray().toList(),buf2.toArray().toList())
        assertEquals(0,buf2.getCursor())


    }
}
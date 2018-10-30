package Sok.Buffer

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array

external class Buffer : Uint8Array{

    companion object {
        fun alloc(size : Int) : Buffer
        fun from(arr : Array<Byte>) : Buffer
        fun from(str : String, charset : String) : Buffer
        fun from(buf : ArrayBuffer) : Buffer
        fun from(str : String) : Buffer
        fun compare(buf1 : Buffer, buf2 : Buffer) : Int
        fun concat(buf1 : Array<Buffer>, totalLength : Int) : Buffer
        fun concat(buf1 : Array<Buffer>) : Buffer
    }

    fun readInt8(offset : Int) : Byte
    fun readUInt8(offset : Int) : Byte
    fun readInt16BE(offset: Int) : Short
    fun readUInt16BE(offset: Int) : Short
    fun readInt32BE(offset: Int) : Int
    fun readUInt32BE(offset: Int) : Int

    fun writeInt8(value : Byte , offset: Int)
    fun writeInt16BE(value : Short, offset: Int)
    fun writeInt32BE(value : Int , offset: Int)

    fun write(str : String, offset: Int, encoding : String) : Int

    fun toString(charset: String) : String

    fun copy(target : Uint8Array, targetStart : Int, sourceStart : Int, sourceEnd : Int) : Int
    fun copy(target : Buffer, targetStart : Int, sourceStart : Int, sourceEnd : Int) : Int
    fun copy(target : Buffer) : Int
    fun copy(target : Uint8Array) : Int

    fun slice(begin : Int, end : Int) : Buffer
}
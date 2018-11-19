# Sok

 ![](https://img.shields.io/badge/Kotlin-JVM-green.svg) ![](https://img.shields.io/badge/Kotlin-JS-green.svg)  ![](https://img.shields.io/badge/Kotlin-Native-green.svg) [![Build Status](https://travis-ci.com/SeekDaSky/Sok.svg?branch=master)](https://travis-ci.com/SeekDaSky/Sok)

## Introduction

 Sok is a multiplatform socket managment library, distributed under the MIT licence, that aims to be simple and safe to use. It is fully [coroutine-friendly](https://kotlinlang.org/docs/reference/coroutines-overview.html) and asynchronous with all its primitive being suspending functions. It allows the developper to use I/O code inside coroutines safely without any risk of blocking anything.

 For now the supported platforms are:

- JVM >= 7 (Android not tested but it should theoratically work)
- Node.js >=  9.4.0 (lower versions may work, not tested)
- Native - Linux x64

 There is no plan to support Windows or OSX/iOS platforms for now

 **Sok is still in its early stage of development, there is still a lot to be done and you will find the [list here](#plans-for-the-future)** 

 A complete documentation of the whole library is available [here](https://seekdasky.github.io/SokDoc/)

## Table of content

[I - Introduction](#introduction)

[II - Overview](#overview)

[III - Installation](#installation)

[IV - The Server class](#the-server-class)

[V - The Client class](#the-client-class)

​	[1 - Read](#read)

​	[2 - Write](#write)

​	[3 - BulkRead](#bulkread)

[VI - The Buffer class](#the-buffer-class)

[VII - Platform specific behaviours](#platform-specific-behaviours)

​	[1 - Java](#java)

​	[2 - Native](#native)

​	[3 - JS](#js)

[VIII - Plans for the future](#plans-for-the-future)

[IX - Contributing](#contributing)

## Overview

 Sok can manage server and client sockets, it is multithreaded on JVM and single-threaded on Native and JS. All platforms implement the same interface. There is a few platform-specific behaviour that [are listed here](#platform-specific-behaviours). The behaviour of the library is a quite similar to the [Java NIO technology](https://en.wikipedia.org/wiki/Non-blocking_I/O_(Java)):

- Everything is buffer based
- I/O operations update the buffer cursor and respect the buffer limit

An echo server socket code looks like this:

```kotlin
fun main(args: Array<String>) {
    //create the server socket
    val server = TCPServerSocket("localhost", 9999)
    //accept clients forever
    while(!server.isClosed) {
        //suspending accept
        val socket = server.accept()
        //launch a new coroutine to let the main loop continue
        GlobalScope.launch {
            //allocate a new buffer
            val buffer = allocMultiplatformBuffer(1024)
            //loop forever again
            while(!socket.isClosed){
                //we read at the start of the buffer
                buffer.cursor = 0
                socket.read(buffer)
                //as the buffer might not be full, we set the limit and reset the cursor
                buffer.limit = buffer.cursor
                buffer.cursor = 0
                socket.write(buffer)
            }
            //destroy the buffer, this operation is optional on JVM and JS
            buffer.destroy()
        }
    }
}
```

## Installation

 Sok is hosted on bintray. Simply add Sok as a dependency and you are good to go

Gradle:

```groovy
repositories {
    maven{ url "https://dl.bintray.com/seekdasky/sok" }
}

dependencies {
    // For common source
    compile 'seekdasky.sok:sok-common:0.12'
    
    // For JVM
    compile 'seekdasky.sok:sok-jvm:0.12'
    
    // For JS
    compile 'seekdasky.sok:sok-js:0.12'
    
    // For Native (please not that you must use Gradle 4.7)
    compile 'seekdasky.sok:sok-native-linux:0.12'
}
```

 Sok is not available on jCenter for now. If you use Sok on native project you MUST use Gradle 4.7 because of incompatibilities in the Gadle metadata format between each version.

## The Server class

 The server class allows the developper to create and manage a listening socket. It's primitives are quite straightforward.

```kotlin
class TCPServerSocket{

    /** state of the socket */
    var isClosed : Boolean
        private set

    /**
     * Start a listening socket on the given address (or alias) and port
     */
    constructor(address : String, port : Int)

    /**
     * Accept a client socket. The method will suspend until there is a client to accept
     */
    suspend fun accept() : TCPClientSocket

    /**
     * handler called when the socket close (expectedly or not)
     */
    fun bindCloseHandler(handler : () -> Unit)

    /**
     * close the server socket
     */
    fun close()
}
```

## The Client class

 The client class allows the developper to perform I/O actions on socket. This class is created when either accepting a client with the Server class or with the `createTCPClientSOcket` top-level function:

```kotlin
suspend fun createTCPClientSocket(address : String, port : Int ) : TCPClientSocket
```

 Like the Server class, you can bind a close handler that will be called when the socket is closed (expectedly or not). When closing a client socket you can either call `close` that will wait for any ongoing write operation to finish or `forceClose` that will close everything instantly and interupt any write operation.

 The three I/O primitives are:

### Read

 The two read methods signatures are:

```kotlin
suspend fun read(buffer: MultiplatformBuffer) : Int

suspend fun read(buffer: MultiplatformBuffer, minToRead : Int) : Int
```

 Both methods will return -1 if an error occured and the socket will be closed, calling the close handler. Those methods are not thread safe and only one can be performed at a time. Data will be read from the current buffer cursor position until the limit of the buffer. Without a `minToRead`parameter given, the minimum amount of data is 1 byte.

 Once the read operation is performed, the cursor of the buffer will be incremented by the number of byte read and the method will return that number.

### Write

 The write method signature is:

```kotlin
suspend fun write(buffer: MultiplatformBuffer) : Boolean
```

 Unlike read methods, a write operation is thread safe and multiple call can be performed concurrently. A write is atomic, meaning that the method will not return until all the data between the cursor and the limit are written. the client class will process each given buffer one by one in order.

### BulkRead

 This method allows the developper to perform a read-intesive loop efficiently. When using this method instead of a while loop, Sok can perform certain optimisations that can greatly improve I/O performances. The signature of the method is:

```kotlin
suspend fun bulkRead(buffer : MultiplatformBuffer, operation : (buffer : MultiplatformBuffer, read : Int) -> Boolean) : Long
```

 The method will suspend the calling coroutine and execute the `operation`every time there is data to be read. The `operation`will take as parameter the buffer containing the data, the amount of data and return a `true` if the loop should continue, `false` if the method should return. the `operation` MUST not be computation intesive or blocking as Sok execute it synchronously, thus blocking other sockets while doing so.

 The use case imagined for this method is the follwing:

```kotlin
while(!socket.isClosed){
    //read some headers/metadata and compute the length of the data to be recevied
    socket.read(...)
    val toReceive = ....
    val recevied = 0
    //read until we recevied everything we need
    socket.bulkRead(allocMultiplatformBuffer(1024)){ buffer, read ->
        received += read
        //process the data and store them somewhere
        ...
        //set the limit of the buffer to read the exact amount of data
        buffer.limit = min(toReceive - received, buffer.capacity)
        //return if we should continue the loop or not
        received != toReceive
    }
}
```

## The Buffer class

 The Buffer class is the heart of any I/O operation. It's behavior is similar to the [Java NIO ByteBuffer](https://docs.oracle.com/javase/7/docs/api/java/nio/ByteBuffer.html). it also include primitives to read unsigned types. Right now as Sok uses Kotlin 1.2.70 unsigned types are represented with bigger types (an unsigned Byte is a Short, an unsigned Short is an Int, etc...) but as soon as Kotlin 1.3 comes out the Buffer class will use native unsigned types.

 A Buffer can be created either by allocating memory or using an existing ByteArray as a back buffer, thus avoiding copy.

```kotlin
fun allocMultiplatformBuffer(size :Int) : MultiplatformBuffer

fun wrapMultiplatformBuffer(array : ByteArray) : MultiplatformBuffer
```

 On Native platforms it is important to destroy the buffer using the `destroy()` method when it is not needed anymore in order to free memory, note that if you destroy a buffer that use a back buffer, the ByteBuffer class will simply `unpin()` the array.

 Sok also provide a BufferPool class, usefull to recycle buffers and reduce garbage collection pressure

```kotlin
class BufferPool(val maximumNumberOfBuffer : Int, val bufferSize : Int) {
    /**
     * Fetch a buffer from the pool. If the pool as not reached its maximum size and that the channel si empty we can allocate
     * the buffer instead of suspending.
     */
    suspend fun requestBuffer() : MultiplatformBuffer
      
    /**
     * Free the object in order to let another coroutine have it. If you forget to call this method the pool will empty
     * itself and starve, blocking all coroutines calling requestBuffer()
     */
    suspend fun freeBuffer(obj : MultiplatformBuffer)
}
```

## Platform specific behaviours

 As Multiplatform and Coroutines are still experimental and that each platform have its own paradigm, making a library that have the same exact behaviour is hard. this section is dedicated to the behaviours that are not (yet) fixable and that the developer should keep in mind.

### Java

 Java is the primary target of Sok so its behaviour is the default one. The only thing that the JVM target have in addition compared to the other is the `allocDirectMultiplatformBuffer` function which allocate a direct ByteBuffer instead of a heap ByteBuffer. For more information about the difference between the two, [read this thread](https://stackoverflow.com/questions/5670862/bytebuffer-allocate-vs-bytebuffer-allocatedirect).

### Native

 Because the `kotlinx.coroutines` library does not support multithreading on native platform yet, Sok is single threaded. Because of this you MUST have a running event loop in your kotlin program, to acheive this the simplest way is making `runBlocking` the first statement of your `main` function. In addition to this you might want to bind the Sok Selector class scope to this event loop, this is optional but recomended if you don't want to prevent `runBlocking` from exiting thus closing the event loop. You can read more on Structured concurrency [here](https://medium.com/@elizarov/structured-concurrency-722d765aa952), and `kotlinx.coroutines` plans for multithreading [here](https://github.com/Kotlin/kotlinx.coroutines/issues/462)

 Example:

```kotlin
fun main(args: Array<String>) = runBlocking{
    //bind the selector to the coroutine scope
    Selector.setDefaultScope(this)
    //you are good to go now
    ...
}
```

### JS

 For now the JS implementation is really slow and you probably don't want to use this in production at all, and because this platform does not support `runBlocking` it makes it a bit trickier to use it on multiplatform projects.

The only known behavior difference is that Sok `write` method can't throw a `PeerClosedException` because Node.js does not give any information about the success of the write operation.

## Plans for the future

 Sok is not feature complete or stable yet, a lot is to be done and feedback on the API is welcome. The plans for Sok are:

- ~~Migrate everything to Kotlin 1.3~~
- ~~Publish the library on Bintray~~
- Enhance the test suite
- Think of a real exception model
- ~~Implement a way to set/get socket options~~
- Implement UDP sockets
- Fix JS performances (though I don't have any idea how)

## Contributing

 Feedback is greatly welcomed as I want to make Sok easy to use and consistent across platforms. If you want to contribute to the project, please make sure to open an issue first.

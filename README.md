# Sok

 ![](https://img.shields.io/badge/Kotlin-JVM-green.svg) ![](https://img.shields.io/badge/Kotlin-JS-green.svg)  ![](https://img.shields.io/badge/Kotlin-Native-green.svg) [![Build Status](https://travis-ci.com/SeekDaSky/Sok.svg?branch=master)](https://travis-ci.com/SeekDaSky/Sok)

## Introduction

Sok is a multiplatform socket management library, distributed under the MIT licence, that aims to be simple and safe to use. It is fully [coroutine-friendly](https://kotlinlang.org/docs/reference/coroutines-overview.html) and asynchronous with all its primitive being suspending functions. It allows the developer to use I/O code inside coroutines safely without any risk of blocking anything.

For now the supported platforms are:

- JVM >= 8 (JVM 7 and Android not tested but it should theoretically work)
- Node.js >=  9.4.0 (lower versions may work, not tested)
- Native - Linux x64

There is no plan to support Windows or OSX/iOS platforms for now

Your project should meet the following requirement for Sok to work:

- Kotlin 1.3
- Gradle 4.7 (for Native projects)

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

[VII - Exception model](#exception-model)

[VIII - Platform specific behaviours](#platform-specific-behaviours)

​	[1 - Java](#java)

​	[2 - Native](#native)

​	[3 - JS](#js)

[IX - Plans for the future](#plans-for-the-future)

[X - Contributing](#contributing)

## Overview

 Sok can manage server and client sockets, it is multi-threaded on JVM and single-threaded on Native and JS. All platforms implement the same interface. There is a few known platform-specific behavior that [are listed here](#platform-specific-behaviours). The behavior of the library is a quite similar to the [Java NIO technology](https://en.wikipedia.org/wiki/Non-blocking_I/O_(Java)):

- Everything is buffer based
- I/O operations update the buffer cursor and respect the buffer limit

An echo server socket code looks like this:

```kotlin
fun main(args: Array<String>) = runBlocking {
    //create the server socket
    val server = createTCPServerSocket("localhost", 9999)
    //accept clients forever
    while(!server.isClosed) {
        //suspending accept
        val socket = server.accept()
        //launch a new coroutine to let the main loop continue
        launch {
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
            //destroy the buffer (optional on JVM and JS)
            buffer.destroy()
        }
    }
}
```

## Installation

 Sok is hosted on Bintray. Simply add Sok as a dependency and you are good to go

Gradle:

```groovy
repositories {
    maven{ url "https://dl.bintray.com/seekdasky/sok" }
}

dependencies {
    // For common source
    implementation 'seekdasky.sok:sok-common:0.22.0'
    // For JVM
    implementation 'seekdasky.sok:sok-jvm:0.22.0'
    // For JS
    implementation 'seekdasky.sok:sok-js:0.22.0'
    // For Native (please not that you must use Gradle 4.7)
    implementation 'seekdasky.sok:sok-native-linux:0.22.0'
}
```

 Sok is not available on jCenter for now. If you use Sok on native project you MUST use Gradle 4.7 because of incompatibilities in the Gadle metadata format between each version.

## The Server class

You can create a new listening socket with the top-level function `createTCPServerSocket`

```kotlin
suspend fun createTCPServerSocket(address : String, port : Int) : TCPServerSocket
```

The TCPServerSocket class only have two interesting primitives: `accept` and `close`

## The Client class

 The client class allows the developer to perform I/O actions on socket. This class is created when either accepting a client with the Server class method `accept` or with the `createTCPClientSocket` top-level function:

```kotlin
suspend fun createTCPClientSocket(address : String, port : Int ) : TCPClientSocket
```

 When closing a client socket you can either call `close` that will wait for any ongoing write operation to finish or `forceClose` that will close everything instantly and interrupt any write operation.

 The three I/O primitives are:

### Read

 The two read methods signatures are:

```kotlin
suspend fun read(buffer: MultiplatformBuffer) : Int

suspend fun read(buffer: MultiplatformBuffer, minToRead : Int) : Int
```

 Both methods are not thread safe and only one can be called at a time. Data will be read from the current buffer cursor position until the limit of the buffer. Without a `minToRead`parameter given, the minimum amount of data is 1 byte.

 Once the read operation is performed, the cursor of the buffer will be incremented by the number of byte read and the method will return that number.

### Write

 The write method signature is:

```kotlin
suspend fun write(buffer: MultiplatformBuffer) : Boolean
```

 Unlike read methods, a write operation is thread safe and multiple call can be performed concurrently. A write is atomic, meaning that the method will not return until all the data between the cursor and the limit are written or until an exception happen (`PeerClosedException`). The client class will process each given buffer one by one in order. The developer should implement a back-pressure mechanism in order to reduce the amount of data stored in the queue.

### BulkRead

 This method allows the developper to perform a read-intesive loop efficiently. When using this method instead of a while loop, Sok can perform certain optimisations that can greatly improve I/O performances. The signature of the method is:

```kotlin
suspend fun bulkRead(buffer : MultiplatformBuffer, operation : (buffer : MultiplatformBuffer, read : Int) -> Boolean) : Long
```

 The method will suspend the calling coroutine and execute the `operation`every time there is data to be read. The `operation`will take as parameter the buffer containing the data (with its cursor set to 0) and the amount of data, the operation must return `true` if the loop should continue, `false` if the method should return. the `operation` MUST not be computation intensive or blocking as Sok execute it synchronously, thus blocking other sockets while doing so.

 The use case imagined for this method is the following:

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

 The Buffer class is the heart of any I/O operation. It's behavior is similar to the [Java NIO ByteBuffer](https://docs.oracle.com/javase/7/docs/api/java/nio/ByteBuffer.html). it also include primitives to read unsigned types.  There is currently no method supporting `float` and `double` types and sothey must be transferred as strings.

 A Buffer can be created either by allocating memory or using an existing ByteArray as a back buffer, thus avoiding copy.

```kotlin
fun allocMultiplatformBuffer(size :Int) : MultiplatformBuffer

fun wrapMultiplatformBuffer(array : ByteArray) : MultiplatformBuffer
```

 On Native platforms it is important to destroy the buffer using the `destroy()` method when it is not needed anymore in order to free memory, note that on Kotlin/Native if you destroy a buffer that wraps a ByteArray, the ByteBuffer class will simply `unpin()` the array.

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

## Exception model

`Client` and `Server` classes both have an `exceptionHandler` property containing a lambda that will be called when an `Exeption` **resulting in the closing of the socket** is thrown. This handler is useful to have a centralized way of tracking the state of the socket.

All the exceptions class that Sok can throw are referenced [here](https://github.com/SeekDaSky/Sok/blob/master/common/sok-common/src/Sok/Exceptions/Exceptions.kt).

If something is wrong, every primitive (`accept`,`read`,`bulkRead`,`write`) will throw the same exception as the one passed to the exception handler.

Closing the socket using `close` and `forceClose` will result in a `NormalCloseException` or a `ForceCloseException` to be thrown. Only the first `CloseException` will be sent to the exception handler, meaning that if you call `close` on a socket after the peer closed it (`PeerClosedException`) there won't be any `NormalCloseException` thrown.

if a call is made to a primitive of a closed socket a `SocketClosedException` will be thrown.

When using `bulkRead`, if the passed `operation` lambda throw an exception, the socket won't be closed thus the exception won't be send to the exception handler but the exception will be thrown by the `bulkRead` method.

## Platform specific behaviours

 As Multiplatform and Coroutines are still experimental and that each platform have its own paradigm, making a library that have the same exact behaviour is hard. this section is dedicated to the behaviours that are not (yet) fixable and that the developer should keep in mind.

### Java

 Java is the primary target of Sok so its behaviour is the default one. The only thing that the JVM target have in addition compared to the other is the `allocDirectMultiplatformBuffer` function which allocate a direct ByteBuffer instead of a heap ByteBuffer. For more information about the difference between the two, [read this thread](https://stackoverflow.com/questions/5670862/bytebuffer-allocate-vs-bytebuffer-allocatedirect).

### Native

 Because the `kotlinx.coroutines` library does not support multithreading on native platform yet, Sok is single threaded. Because of this you MUST have a running event loop in your kotlin program, to acheive this the simplest way is making `runBlocking` the first statement of your `main` function. In addition to this you might want to bind the Sok  `Selector` class scope to this event loop, this is optional but recommended if you want to prevent `runBlocking` from returning, thus closing the event loop. You can read more on Structured concurrency [here](https://medium.com/@elizarov/structured-concurrency-722d765aa952), and `kotlinx.coroutines` plans for multi-threading [here](https://github.com/Kotlin/kotlinx.coroutines/issues/462)

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

 For now the JS implementation is really slow and you probably don't want to use this in production at all. Because this platform does not support `runBlocking` it makes it a bit trickier to use it on multiplatform projects.

The only known behavior difference is that the `write` method can't throw a `PeerClosedException` because Node.js does not give any information about the success of the write operation.

## Plans for the future

 Sok is not feature complete and is getting more and more stable. It is not advised to use Sok in production without a proper testing. Performances are decent but the suspending model implies a lot of register/unregister to the inner `Selector` which can lead to slow downs if doing intensive `read` calls. To address this problem and allow the use of Sok on latency-critical code, I will implement an "event-based" class that will work a bit like what `Node.js` have in its `Net` package.

The event paradigm will allow fewer registrations and a better memory consumption at the price of a diminished raw bandwidth and code clarity compared to the suspending version.

I also plan to use the latest `Multiplatform`gradle plugin and `Kotlin DSL`but I want to wait for other projects to do so to see how this new plugin integrates with the current publishing flow.

## Contributing

 Feedback is greatly welcomed as I want to make Sok easy to use and consistent across platforms. If you want to contribute to the project, please make sure to open an issue first.

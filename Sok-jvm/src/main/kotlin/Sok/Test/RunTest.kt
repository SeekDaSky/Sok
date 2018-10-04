package Sok.Test

import kotlinx.coroutines.experimental.runBlocking

actual fun runTest(block: suspend () -> Unit) = runBlocking { block() }
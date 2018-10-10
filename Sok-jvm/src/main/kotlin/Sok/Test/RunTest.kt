package Sok.Test

import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.runBlocking

actual fun runTest(block: suspend (scope : CoroutineScope) -> Unit) = runBlocking { block(this) }
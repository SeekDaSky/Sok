package Sok.Test

import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.promise

actual fun runTest(block: suspend (scope : CoroutineScope) -> Unit): dynamic = GlobalScope.promise { block(this) }
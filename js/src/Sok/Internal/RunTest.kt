package Sok.Internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

actual fun runTest(block: suspend (scope : CoroutineScope) -> Unit): dynamic = GlobalScope.promise { block(this) }
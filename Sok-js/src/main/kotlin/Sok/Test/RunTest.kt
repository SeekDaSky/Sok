package Sok.Test

import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.promise

actual fun runTest(block: suspend () -> Unit): dynamic = GlobalScope.promise { block() }
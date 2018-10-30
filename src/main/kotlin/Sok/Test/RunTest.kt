package Sok.Test

import kotlinx.coroutines.CoroutineScope

/**
 * Workaround to use suspending functions in unit tests
 */
expect fun runTest(block: suspend (scope : CoroutineScope) -> Unit)
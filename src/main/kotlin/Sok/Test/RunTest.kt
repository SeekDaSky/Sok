package Sok.Test

/**
 * Workaround to use suspending functions in unit tests
 */
expect fun runTest(block: suspend () -> Unit)
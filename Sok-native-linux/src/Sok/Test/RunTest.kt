package Sok.Test

import kotlinx.coroutines.experimental.*
import kotlinx.cinterop.memScoped

actual fun runTest(block: suspend (scope : CoroutineScope) -> Unit){
    runBlocking {
        val scope = this
        memScoped {
            block.invoke(scope)
        }
    }
}
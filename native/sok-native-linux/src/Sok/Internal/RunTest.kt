package Sok.Internal

import kotlinx.coroutines.*
import kotlinx.cinterop.memScoped
import Sok.Selector.*

actual fun runTest(block: suspend (scope : CoroutineScope) -> Unit){
    runBlocking {
        val scope = this
        Selector.setDefaultScope(scope)
        try {
            memScoped {
                block.invoke(scope)
            }
        } finally {
            Selector.closeSelectorAndWait()
            Selector.setDefaultScope(GlobalScope)
        }
    }
}
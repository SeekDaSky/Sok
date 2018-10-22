package Sok.Test

import kotlinx.coroutines.experimental.*
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
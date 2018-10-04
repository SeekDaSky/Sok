package Sok.Test

import kotlinx.coroutines.experimental.*
import kotlinx.cinterop.memScoped
import Sok.Selector.*

actual fun runTest(block: suspend () -> Unit){
    runBlocking {
        Selector.setDefaultScope(this)
        try {
            memScoped {
                block.invoke()
                Selector.closeSelectorAndWait()
                Unit
            }
        } catch (e: Exception) {
            Selector.closeSelectorAndWait()
            throw e
        }
        Selector.setDefaultScope(GlobalScope)
    }
}
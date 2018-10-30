package Sok.Selector

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

/**
 * A SelectorPool will order the Selectors by the number of channels registered. This allow us to do a lightweight load balancing between all the Selectors
 */
class SelectorPool(private val NbrOfSelector : Int) {

    //pool state
    var isClosed = false
        private set

    //internal actor
    private val actor : SendChannel<CompletableDeferred<Selector>> = createActor()

    //selector pool
    private val pool =  MutableList(NbrOfSelector){
        Selector()
    }

    //close the pool and all the selector insed it
    fun close(){
        this.actor.close()
        this.pool.forEach {
            it.close()
        }
    }

    //sort the selectors by number of channel registered
    private fun sortPool(){
        this.pool.sortWith(
                compareBy({it.numberOfChannel()})
        )
    }

    //method called by new channels not yet bound to a specific selector
    suspend fun getLessbusySelector() : Selector{
        val def = CompletableDeferred<Selector>()
        this.actor.send(def)
        return def.await()
    }

    //this actor only serve concurrency-related purposes
    private fun createActor() = GlobalScope.actor<CompletableDeferred<Selector>>(Dispatchers.IO) {
        for(deferred in this.channel){

            this@SelectorPool.sortPool()

            deferred.complete(this@SelectorPool.pool.first())
        }
    }
}
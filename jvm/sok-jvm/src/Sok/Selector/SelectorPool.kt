package Sok.Selector

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor

/**
 * A SelectorPool will order the Selectors by the number of channels registered. This allow us to do a basic load balancing between all the Selectors
 *
 * @property NbrOfSelector number of selector managed by this pool
 * @property isClosed state of the pool
 * @property actor actor managing the sorting and distribution of the selectors
 * @property pool list contianing all the selectors, this list is sorted by the selectors number of channel registered
 */
class SelectorPool(private val NbrOfSelector : Int) {

    var isClosed = false
        private set

    private val actor : SendChannel<CompletableDeferred<Selector>> = createActor()

    private val pool =  MutableList(NbrOfSelector){
        Selector()
    }

    /**
     * Close the pool and all the selectors inside it
     */
    fun close(){
        this.actor.close()
        this.pool.forEach {
            it.close()
        }
    }

    /**
     * Sort the selector pool
     */
    private fun sortPool(){
        this.pool.sortWith(
                compareBy({it.numberOfChannel})
        )
    }

    /**
     * Get the less busy selector (first in the pool list), the method is suspending because an actor is used behind the scene to
     * allow multiple threads accessing the pool and still get the right selector. This may not be the most efficient way to do it
     * but a socket get a selector once so it is not critical.
     *
     * @return the less busy selector
     */
    suspend fun getLessbusySelector() : Selector{
        val def = CompletableDeferred<Selector>()
        this.actor.send(def)
        return def.await()
    }

    /**
     * Create the actor managing the selectors
     */
    private fun createActor() = GlobalScope.actor<CompletableDeferred<Selector>>(Dispatchers.IO) {
        //we sort the pool after sending the selector so the caller get its selector faster in most cases
        for(deferred in this.channel){
            deferred.complete(this@SelectorPool.pool.first())
            this@SelectorPool.sortPool()
        }
    }
}
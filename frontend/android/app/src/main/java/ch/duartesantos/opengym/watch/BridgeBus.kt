package ch.duartesantos.opengym.watch

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object BridgeBus {
    data class Snapshot(
        val link: String = "starting",
        val detail: String = "Starting bridge",
        val lastSync: Long? = null
    )

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var current = Snapshot()

    fun snapshot(): Snapshot = current

    fun subscribe(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        main.post { listener(current) }
    }

    fun unsubscribe(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun publish(link: String, detail: String, lastSync: Long? = null) {
        val next = Snapshot(link, detail, lastSync ?: current.lastSync)
        current = next
        main.post {
            listeners.toList().forEach { it(next) }
        }
    }
}

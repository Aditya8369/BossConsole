package ai.rever.boss.startup

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Retain diagnostics until a window exists; pace delivery across all window collectors. */
internal class StartupNoticeQueue {
    private val channel = Channel<String>(Channel.UNLIMITED)
    private val delivery = Mutex()
    val notices =
        flow {
            while (true) {
                delivery.withLock {
                    emit(channel.receive())
                    delay(12_000)
                }
            }
        }

    fun report(message: String) {
        channel.trySend(message)
    }
}

internal val kernelStartupNotices = StartupNoticeQueue()

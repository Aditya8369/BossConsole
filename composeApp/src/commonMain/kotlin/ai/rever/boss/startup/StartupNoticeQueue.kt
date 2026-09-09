package ai.rever.boss.startup

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Keep startup diagnostics until an authenticated window can display them, once per process. */
internal class StartupNoticeQueue {
    private val channel = Channel<String>(Channel.CONFLATED)
    val notices = channel.receiveAsFlow()

    fun report(message: String) {
        channel.trySend(message)
    }
}

internal val kernelStartupNotices = StartupNoticeQueue()

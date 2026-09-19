package app.d2lock.widget

/** Widget clicks are delivered as separate launches, so pair them by elapsed time. */
class DoubleTap {
    private var last = -1L
    fun tap(now: Long): Boolean {
        val delta = if (last >= 0) now - last else -1
        val matched = delta in 60..900
        last = if (matched) -1 else now
        return matched
    }
}

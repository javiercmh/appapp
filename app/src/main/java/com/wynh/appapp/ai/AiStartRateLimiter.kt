package com.wynh.appapp.ai

/** Sliding-window cap on generation starts per subapp, so a scripted loop cannot burn usage. */
class AiStartRateLimiter(
    private val maxStarts: Int = 6,
    private val windowMillis: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val starts = HashMap<String, ArrayDeque<Long>>()

    init { require(maxStarts > 0 && windowMillis > 0) }

    /** Records a start and returns true, or returns false without recording when over the limit. */
    @Synchronized
    fun tryAcquire(appId: String): Boolean {
        val time = now()
        val recent = starts.getOrPut(appId) { ArrayDeque() }
        while (recent.isNotEmpty() && time - recent.first() >= windowMillis) recent.removeFirst()
        if (recent.size >= maxStarts) return false
        recent.addLast(time)
        return true
    }

    @Synchronized
    fun forget(appId: String) { starts.remove(appId) }
}

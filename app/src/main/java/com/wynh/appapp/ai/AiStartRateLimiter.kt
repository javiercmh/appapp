package com.wynh.appapp.ai

/**
 * Sliding-window cap on generation starts per subapp, so a scripted loop cannot burn usage.
 * [maxStarts] is the user's setting (Settings → AI → Request limit per app); [UNLIMITED] turns the cap off.
 */
class AiStartRateLimiter(
    maxStarts: Int = DEFAULT_STARTS,
    private val windowMillis: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val DEFAULT_STARTS = 6
        const val UNLIMITED = 0
        /** The choices Settings offers, in display order. */
        val CHOICES = listOf(DEFAULT_STARTS, 12, 30, 60, UNLIMITED)
        fun label(value: Int): String = when (value) {
            UNLIMITED -> "No limit"
            DEFAULT_STARTS -> "$value (default)"
            else -> "$value"
        }
    }

    private val starts = HashMap<String, ArrayDeque<Long>>()

    @Volatile var maxStarts: Int = maxStarts
        set(value) { require(value >= 0); field = value }

    init { require(maxStarts >= 0 && windowMillis > 0) }

    /** Records a start and returns true, or returns false without recording when over the limit. */
    @Synchronized
    fun tryAcquire(appId: String): Boolean {
        val time = now()
        val recent = starts.getOrPut(appId) { ArrayDeque() }
        while (recent.isNotEmpty() && time - recent.first() >= windowMillis) recent.removeFirst()
        val limit = maxStarts
        if (limit != UNLIMITED && recent.size >= limit) return false
        recent.addLast(time)
        return true
    }

    @Synchronized
    fun forget(appId: String) { starts.remove(appId) }
}

package id.phonelink.android.core

/**
 * Adds up how long each app was in front from Android's usage events. Pure, so it can be tested without Android.
 * An app counts from its first activity coming to the front until its last one leaves, or the screen turns off,
 * so two activities of one app are never counted twice.
 */
class ScreenTimeMath(private val since: Long) {
    enum class Kind { Resumed, Paused, ScreenOff, Unlocked }

    private val shown = HashMap<String, MutableSet<String>>()
    private val startedAt = HashMap<String, Long>()
    private val totals = HashMap<String, Long>()
    var unlocks = 0
        private set

    fun add(kind: Kind, pkg: String, activity: String, time: Long) {
        when (kind) {
            Kind.Resumed -> {
                val set = shown.getOrPut(pkg) { HashSet() }
                if (set.isEmpty()) startedAt[pkg] = maxOf(time, since)
                set.add(activity)
            }
            Kind.Paused -> {
                val set = shown[pkg]
                if (set == null) {
                    // It was already in front at midnight, before the first event of the day.
                    if (time > since) totals[pkg] = (totals[pkg] ?: 0) + (time - since)
                    shown[pkg] = HashSet()
                    return
                }
                if (set.remove(activity) && set.isEmpty()) stop(pkg, time)
            }
            Kind.ScreenOff -> shown.keys.toList().forEach { name ->
                if (shown[name]?.isNotEmpty() == true) stop(name, time)
                shown[name]?.clear()
            }
            Kind.Unlocked -> unlocks++
        }
    }

    private fun stop(pkg: String, time: Long) {
        val start = startedAt.remove(pkg) ?: return
        if (time > start) totals[pkg] = (totals[pkg] ?: 0) + (time - start)
    }

    /** Totals per app up to [now], longest first, leaving out what [skip] says and anything under half a minute. */
    fun result(now: Long, skip: (String) -> Boolean = { false }): List<Pair<String, Long>> {
        val all = HashMap(totals)
        for ((pkg, start) in startedAt) if (shown[pkg]?.isNotEmpty() == true && now > start) all[pkg] = (all[pkg] ?: 0) + (now - start)
        return all.filter { !skip(it.key) && it.value >= MIN_MS }.toList().sortedByDescending { it.second }
    }

    companion object {
        const val MIN_MS = 30_000L
    }
}

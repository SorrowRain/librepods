package me.kavishdevar.librepods.audio

internal data class RouteDevice(val type: Int, val address: String = "")

internal interface PreferredRoutePort {
    fun read(strategy: Int): List<RouteDevice>
    fun write(strategy: Int, devices: List<RouteDevice>): Boolean
    fun save(originals: Map<Int, List<RouteDevice>>): Boolean
}

/** Keep cleanup ownership until the platform confirms restoration, including across process death. */
internal class PreferredRouteLease(
    private val port: PreferredRoutePort,
    originalRoutes: Map<Int, List<RouteDevice>> = emptyMap(),
    private val speaker: RouteDevice = RouteDevice(2),
) {
    private val originals = originalRoutes.toMutableMap()
    val hasPendingCleanup: Boolean get() = originals.isNotEmpty()

    fun apply(strategies: List<Int>): Boolean {
        if (strategies.isEmpty()) return false
        val applied = runCatching {
            for (strategy in strategies) {
                if (!originals.containsKey(strategy)) {
                    originals[strategy] = port.read(strategy)
                    check(port.save(originals.toMap())) { "Could not persist route recovery" }
                }
                if (port.read(strategy) != listOf(speaker)) {
                    check(port.write(strategy, listOf(speaker))) { "Speaker route rejected" }
                }
                check(port.read(strategy) == listOf(speaker)) { "Speaker route was not applied" }
            }
        }.isSuccess
        if (!applied) release()
        return applied
    }

    fun isApplied(strategies: List<Int>): Boolean = strategies.isNotEmpty() &&
        strategies.all { strategy ->
            originals.containsKey(strategy) && runCatching { port.read(strategy) == listOf(speaker) }.getOrDefault(false)
        }

    fun release(): Boolean {
        for ((strategy, original) in originals.toMap()) {
            val restored = runCatching {
                val current = port.read(strategy)
                // A newer system/user selection owns this strategy now; leave it intact.
                if (current == listOf(speaker) && current != original) {
                    check(port.write(strategy, original)) { "Route restoration rejected" }
                    check(port.read(strategy) == original) { "Route restoration not confirmed" }
                }
            }.isSuccess
            if (restored) originals.remove(strategy)
        }
        return port.save(originals.toMap()) && originals.isEmpty()
    }
}

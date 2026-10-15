package me.kavishdevar.librepods.audio

/** Android 17 moved this system API from AudioProductStrategy to AudioManager. */
internal object AudioProductStrategyLookup {
    fun query(audioManagerClass: Class<*>, strategyClass: Class<*>): List<*> {
        val method = try {
            audioManagerClass.getMethod("getAudioProductStrategies")
        } catch (_: NoSuchMethodException) {
            strategyClass.getMethod("getAudioProductStrategies")
        }
        // Invocation failures are real platform failures, not a reason to try another API owner.
        return method.invoke(null) as List<*>
    }
}

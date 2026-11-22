package lab.hyperaudio

/** A session owns only the temporary mute it applies to the media stream. */
internal class CaptureMediaVolume(
    private val readVolume: () -> Int,
    private val isMuted: () -> Boolean,
    private val restoreVolume: (Int) -> Unit
) {
    private var previousVolume = readVolume().takeIf { it > 0 && !isMuted() }

    fun restore(reason: CaptureEndReason) {
        val volume = previousVolume ?: return
        previousVolume = null
        if (reason != CaptureEndReason.SLEEP_EXPIRED && (readVolume() == 0 || isMuted())) {
            restoreVolume(volume)
        }
    }
}

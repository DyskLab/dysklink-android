package id.phonelink.android.core

/**
 * Decides which 40 ms sound chunks are worth sending. A chunk is digitally silent when no 16-bit little-endian sample
 * is louder than [threshold] (about -72 dBFS by default). The first [holdChunks] silent chunks in a row still go out,
 * so a fade ends cleanly and short pauses inside music aren't cut; after that (200 ms) silent chunks are held back
 * until sound comes again. The receiver needs nothing in between: its player plays silence when its queue runs dry,
 * and new receivers put a little silence in front of the first chunk after a gap (see PROTOCOL.md, Sound sharing).
 *
 * Only for peers with binary frames (`bin1`): older receivers keep getting every chunk, as before.
 */
class SilenceGate(private val holdChunks: Int = HOLD_CHUNKS, private val threshold: Int = THRESHOLD) {
    private var silentRun = 0

    /** Chunks held back since the gate was made. */
    var held = 0L
        private set

    /** True when [pcm] (the first [length] bytes) should be sent. */
    fun shouldSend(pcm: ByteArray, length: Int = pcm.size): Boolean {
        if (!isSilent(pcm, length, threshold)) {
            silentRun = 0
            return true
        }
        silentRun++
        if (silentRun <= holdChunks) return true
        held++
        return false
    }

    companion object {
        /** 5 × 40 ms = 200 ms of silence still sent. */
        const val HOLD_CHUNKS = 5
        const val THRESHOLD = 8

        fun isSilent(pcm: ByteArray, length: Int = pcm.size, threshold: Int = THRESHOLD): Boolean {
            var i = 0
            val end = length - 1
            while (i < end) {
                val sample = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
                if (sample > threshold || sample < -threshold) return false
                i += 2
            }
            return true
        }
    }
}

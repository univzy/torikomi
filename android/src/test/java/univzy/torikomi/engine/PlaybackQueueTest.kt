package univzy.torikomi.engine

import org.junit.Assert.*
import org.junit.Test
import univzy.torikomi.ui.screens.*

class PlaybackQueueTest {
    @Test fun advancesAndHonorsRepeatModes() {
        assertEquals(1, nextPlaybackIndex(0, 3, 1, RepeatMode.OFF))
        assertEquals(0, nextPlaybackIndex(1, 3, -1, RepeatMode.OFF))
        assertNull(nextPlaybackIndex(2, 3, 1, RepeatMode.OFF, true))
        assertEquals(0, nextPlaybackIndex(2, 3, 1, RepeatMode.ALL, true))
        assertEquals(2, nextPlaybackIndex(2, 3, 1, RepeatMode.ONE, true))
        assertEquals(1, nextPlaybackIndex(2, 3, -1, RepeatMode.ONE))
        assertNull(nextPlaybackIndex(0, 0, 1, RepeatMode.ALL))
    }
    @Test fun volumeHasPerceptualCurveAndExactMute() {
        assertEquals(0f, playbackVolume(100, true))
        assertEquals(0f, playbackVolume(0, false))
        assertEquals(1f, playbackVolume(100, false))
        assertTrue(playbackVolume(50, false) in 0.01f..0.1f)
        assertTrue((1..100).all { playbackVolume(it, false) > playbackVolume(it - 1, false) })
    }
}

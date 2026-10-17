package univzy.torikomi.ui.screens

import kotlin.math.pow

enum class RepeatMode { OFF, ALL, ONE }

fun playbackVolume(percent: Int, muted: Boolean): Float =
    if (muted || percent <= 0) 0f else 10.0.pow((percent.coerceAtMost(100) - 100) / 30.0).toFloat()

fun nextPlaybackIndex(current: Int, size: Int, direction: Int, repeat: RepeatMode, completed: Boolean = false): Int? {
    if (size <= 0 || current !in 0 until size) return null
    if (completed && repeat == RepeatMode.ONE) return current
    val next = current + direction
    return if (next in 0 until size) next else if (repeat == RepeatMode.ALL) Math.floorMod(next, size) else null
}

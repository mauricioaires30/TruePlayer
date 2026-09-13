package dev.truetagger.android

import dev.truetagger.model.AudioTrack

enum class RepeatMode { OFF, ONE }

data class PlayerSnapshot(
    val current: AudioTrack? = null,
    val queue: List<AudioTrack> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
)

object PlayerState {
    @Volatile var snapshot: PlayerSnapshot = PlayerSnapshot()
}

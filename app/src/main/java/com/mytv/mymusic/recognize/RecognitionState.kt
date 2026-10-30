package com.mytv.mymusic.recognize

import com.mytv.mymusic.data.Song

enum class RecognitionPhase { Idle, Listening, Matching, Done, Error }

data class RecognitionUiState(
    val phase: RecognitionPhase = RecognitionPhase.Idle,
    /** 录音进度 0..1。 */
    val progress: Float = 0f,
    /** 实时音量 0..1，只用于录音动画。 */
    val level: Float = 0f,
    val secondsLeft: Int = 0,
    val results: List<Song> = emptyList(),
    val message: String = "",
)

package com.mytv.mymusic.lyricon

import com.mytv.mymusic.data.LyricSubline
import com.mytv.mymusic.data.LyricTextPrefs
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.LyricPipeline
import com.mytv.mymusic.ui.ParsedLyrics
import io.github.proify.lyricon.lyric.model.LyricWord as RemoteLyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine as RemoteLyricLine
import io.github.proify.lyricon.lyric.model.Song as RemoteSong

/** 播放层的一帧输入；只有这些字段变化时才重新解析歌词并推送。 */
internal data class LyriconTrackInput(
    val song: Song?,
    val durationMs: Long,
    val lyricsRaw: String,
    val lyricsReady: Boolean,
    val lyricText: LyricTextPrefs,
    val subline: LyricSubline,
    val playing: Boolean,
    val providerEnabled: Boolean,
    val subscriberEnabled: Boolean,
)

internal data class LyriconProviderUpdate(
    val song: RemoteSong?,
    val playing: Boolean,
    val displayTranslation: Boolean,
    val displayRoma: Boolean,
    val providerEnabled: Boolean,
    val subscriberEnabled: Boolean,
)

internal data class LyriconReceivedLine(
    val beginMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String = "",
    val romanization: String = "",
)

internal data class LyriconReceivedSong(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val durationMs: Long = 0,
    val lyrics: List<LyriconReceivedLine> = emptyList(),
)

internal object LyriconMapper {
    fun prepare(input: LyriconTrackInput): LyriconProviderUpdate {
        val song = input.song
        val parsed = if (song != null && input.lyricsReady) {
            LyricPipeline.prepare(input.lyricsRaw, input.lyricText)
        } else {
            null
        }
        val hasRomanization = parsed?.lines?.any { it.romanization.isNotBlank() } == true
        return LyriconProviderUpdate(
            song = song?.let { toRemoteSong(it, input.durationMs, parsed) },
            playing = input.playing,
            displayTranslation = input.subline != LyricSubline.Romanization || !hasRomanization,
            displayRoma = input.subline != LyricSubline.Translation && hasRomanization,
            providerEnabled = input.providerEnabled,
            subscriberEnabled = input.subscriberEnabled,
        )
    }

    fun toRemoteSong(song: Song, durationMs: Long, lyrics: ParsedLyrics?): RemoteSong {
        val effectiveDuration = durationMs.takeIf { it > 0 } ?: song.duration * 1000L
        val lines = lyrics?.lines?.mapIndexed { index, line ->
            val begin = line.timeMs.coerceAtLeast(0)
            val nextBegin = lyrics.lines.getOrNull(index + 1)?.timeMs?.coerceAtLeast(0)
            val lastWordEnd = line.words.maxOfOrNull { it.endMs }?.coerceAtLeast(0)
            // 行模型本身没有结束时间：普通行唱到下一句，最后一句可落到歌曲末尾；逐字结束标签始终保留。
            val lineEnd = nextBegin?.takeIf { it > begin } ?: effectiveDuration.takeIf { it > begin }
            val end = maxOf(lineEnd ?: 0, lastWordEnd ?: 0).takeIf { it > begin } ?: (begin + 1)
            RemoteLyricLine(
                begin = begin,
                end = end,
                text = line.text,
                words = line.words.map { word ->
                    val wordBegin = word.startMs.coerceAtLeast(0)
                    RemoteLyricWord(
                        begin = wordBegin,
                        end = word.endMs.coerceAtLeast(wordBegin + 1),
                        text = word.text,
                    )
                },
                translation = line.translation.ifBlank { null },
                roma = line.romanization.ifBlank { null },
            )
        }
        return RemoteSong(
            id = song.key,
            name = song.title,
            artist = song.artist,
            duration = effectiveDuration.coerceAtLeast(0),
            lyrics = lines,
        )
    }

    fun fromRemoteSong(song: RemoteSong): LyriconReceivedSong = LyriconReceivedSong(
        id = song.id.orEmpty(),
        name = song.name.orEmpty(),
        artist = song.artist.orEmpty(),
        durationMs = song.duration.coerceAtLeast(0),
        lyrics = song.lyrics.orEmpty().map { line ->
            LyriconReceivedLine(
                beginMs = line.begin.coerceAtLeast(0),
                endMs = line.end.coerceAtLeast(line.begin + 1),
                text = line.text.orEmpty(),
                translation = line.translation.orEmpty(),
                romanization = line.roma.orEmpty(),
            )
        }.sortedBy { it.beginMs },
    )
}

package com.mytv.mymusic

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytv.mymusic.data.CrossfadeMode
import com.mytv.mymusic.data.DOWNLOAD_DONE
import com.mytv.mymusic.data.DownloadRecord
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.local.LOCAL_SOURCE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QueuePlayNextDeviceTest {
    @Test
    fun aBatchStartsWithoutOpeningPlayerAndFollowsItsOrderInShuffle() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MyMusicApp>()
        val file = File.createTempFile("queue-next-", ".wav", app.cacheDir)
        val fixtureId = UUID.randomUUID()
        val songs = ('A'..'D').map { name ->
            Song(id = "queue-next-$name-$fixtureId", name = name.toString(), source = LOCAL_SOURCE, duration = 6)
        }
        val (first, second, third, fourth) = songs
        val dao = app.database.dao()
        val settings = app.settings.state.first()
        try {
            app.settings.crossfadeEnabled(false)
            writeSilence(file)
            songs.forEach { song ->
                dao.saveDownload(DownloadRecord(song.key, Json.encodeToString(song), file.path, DOWNLOAD_DONE, 0L))
            }
            withContext(Dispatchers.Main) {
                val model = app.musicViewModel
                val player = checkNotNull(model.awaitController())
                try {
                    withTimeout(5_000) { model.downloads.first { records -> songs.all { song -> records.any { it.key == song.key } } } }
                    model.clearQueue()
                    player.shuffleModeEnabled = true
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    model.playSongsNext(songs.take(3))
                    await { player.isPlaying && player.mediaItemCount == 3 }
                    val state = withTimeout(5_000) { model.state.first { it.queue.size == 3 } }
                    assertFalse(state.playerOpen)
                    assertEquals(first.key, player.currentMediaItem?.mediaId)

                    player.seekTo(5_650L)
                    await { player.currentMediaItem?.mediaId == second.key && player.isPlaying }
                    player.pause()
                    player.repeatMode = Player.REPEAT_MODE_ONE
                    val pausedAt = player.currentPosition
                    model.playSongsNext(listOf(fourth, third, second))
                    val edited = withTimeout(5_000) { model.state.first { it.queue.size == 4 } }
                    assertEquals(second.key, edited.current?.key)
                    await { player.currentMediaItem?.mediaId == second.key && player.nextMediaItemIndex == 2 }
                    assertEquals(songs.map { it.key }.toSet(), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet())
                    assertFalse(player.playWhenReady)
                    assertTrue(kotlin.math.abs(player.currentPosition - pausedAt) < 250L)

                    model.playSongNext(first)
                    model.playSongNext(fourth)
                    val reordered = withTimeout(5_000) { model.state.first { it.queue.map(Song::key) == listOf(second.key, fourth.key, first.key, third.key) } }
                    assertEquals(second.key, reordered.current?.key)
                    await { player.currentMediaItem?.mediaId == second.key && player.nextMediaItemIndex == 1 }

                    app.settings.crossfadeMode(CrossfadeMode.Fade)
                    app.settings.crossfadeEnabled(true)
                    withTimeout(5_000) { model.state.first { it.crossfadeEnabled && it.crossfadeMode == CrossfadeMode.Fade } }
                    val looped = CompletableDeferred<String?>()
                    val listener = object : Player.Listener {
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) looped.complete(mediaItem?.mediaId)
                        }
                    }
                    player.addListener(listener)
                    try {
                        player.seekTo(2_500L)
                        player.play()
                        assertEquals(second.key, withTimeout(5_000) { looped.await() })
                    } finally {
                        player.removeListener(listener)
                    }
                    model.transport("next")
                    await { player.currentMediaItem?.mediaId == fourth.key && player.isPlaying }
                    model.transport("next")
                    await { player.currentMediaItem?.mediaId == first.key && player.isPlaying }
                } finally {
                    model.clearQueue()
                }
            }
        } finally {
            app.settings.crossfadeEnabled(settings.crossfadeEnabled)
            app.settings.crossfadeMode(CrossfadeMode.entries.firstOrNull { it.name == settings.crossfadeMode } ?: CrossfadeMode.Automix)
            songs.forEach { dao.deleteDownload(it.key) }
            file.delete()
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(8_000) {
        while (!condition()) delay(20)
    }

    private fun writeSilence(file: File) {
        val sampleRate = 8_000
        val byteCount = sampleRate * 6 * 2
        val wav = ByteBuffer.allocate(44 + byteCount).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + byteCount).put("WAVEfmt ".toByteArray()).putInt(16)
        wav.putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        wav.put("data".toByteArray()).putInt(byteCount)
        file.writeBytes(wav.array())
    }
}

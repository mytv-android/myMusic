package com.mytv.mymusic.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.aggregate.TrackNormalizer
import com.mytv.mymusic.data.Song

/** 歌单内检索：多个关键词按 AND 匹配歌名、歌手和专辑，结果保持原顺序。 */
fun songMatchesCollectionQuery(song: Song, query: String): Boolean = filterCollectionSongs(listOf(song), query).isNotEmpty()

fun filterCollectionSongs(songs: List<Song>, query: String): List<Song> {
    val terms = query.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }.map(TrackNormalizer::keyOf).filter { it.isNotBlank() }
    if (terms.isEmpty()) return songs
    return songs.filter { song ->
        val text = TrackNormalizer.keyOf(listOf(song.name, song.artist, song.album).joinToString(" "))
        terms.all(text::contains)
    }
}

@Composable
fun CollectionSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().tvFocusRing(RoundedCornerShape(4.dp)),
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text("搜索本页歌名、歌手或专辑", maxLines = 1) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = if (query.isEmpty()) null else ({
            IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, "清空检索") }
        }),
        singleLine = true,
    )
}

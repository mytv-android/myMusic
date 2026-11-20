package com.mytv.mymusic.ui.player

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.mytv.mymusic.data.LyricFontPrefs
import java.io.File

internal const val LYRIC_FONT_PRIMARY = 0
internal const val LYRIC_FONT_FALLBACK = 1

internal class LyricFontRuntime(val family: FontFamily, val typeface: Typeface)
internal val LocalLyricFont = staticCompositionLocalOf<LyricFontRuntime?> { null }

internal object LyricFontRules {
    const val MAX_BYTES = 30 * 1024 * 1024
    private val extensions = setOf("ttf", "otf", "ttc", "otc")
    fun supportedName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in extensions
}

/** 导入文件复制到应用私有目录；字体回退链只在歌词组合局部生效。 */
internal object LyricFonts {
    fun import(context: Context, slot: Int, uri: Uri): String {
        require(slot == LYRIC_FONT_PRIMARY || slot == LYRIC_FONT_FALLBACK) { "unknown font slot" }
        val resolver = context.contentResolver
        val name = displayName(context, uri).ifBlank { "歌词字体" }
        require(LyricFontRules.supportedName(name)) { "请选择 TTF、OTF、TTC 或 OTC 字体" }
        val dir = File(context.filesDir, "lyric-fonts").apply { mkdirs() }
        val target = file(context, slot)
        val temp = File(dir, "import-${slot}-${System.nanoTime()}.tmp")
        try {
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > LyricFontRules.MAX_BYTES) throw IllegalArgumentException("字体文件不能超过 30 MB")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalArgumentException("打不开所选字体")
            if (temp.length() == 0L) throw IllegalArgumentException("字体文件是空的")
            // createFromFile 会实际解析字体表；无效文件在覆盖旧字体之前就被拒绝。
            Typeface.createFromFile(temp)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return name
        } finally {
            temp.delete()
        }
    }

    fun clear(context: Context, slot: Int) {
        file(context, slot).delete()
    }

    fun resolve(context: Context, prefs: LyricFontPrefs): LyricFontRuntime? {
        val primary = file(context, LYRIC_FONT_PRIMARY).takeIf { prefs.primaryName.isNotBlank() && it.isFile }
            ?: return null
        val fallback = file(context, LYRIC_FONT_FALLBACK).takeIf { prefs.fallbackName.isNotBlank() && it.isFile }
        val candidates = listOfNotNull(primary, fallback)
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val families = candidates.map { file ->
                    android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(file).build()).build()
                }
                val builder = Typeface.CustomFallbackBuilder(families.first())
                families.drop(1).forEach(builder::addCustomFallback)
                val typeface = builder.setSystemFallback("sans-serif").build()
                LyricFontRuntime(FontFamily(typeface), typeface)
            } else {
                val family = FontFamily(candidates.map { Font(it) })
                LyricFontRuntime(family, Typeface.createFromFile(candidates.first()))
            }
        }.getOrNull()
    }

    private fun file(context: Context, slot: Int) =
        File(File(context.filesDir, "lyric-fonts"), if (slot == LYRIC_FONT_PRIMARY) "primary.font" else "fallback.font")

    private fun displayName(context: Context, uri: Uri): String {
        val fromCursor = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else ""
            }
        }.getOrNull()
        return fromCursor?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }
}

internal fun TextStyle.withLyricFont(family: FontFamily?): TextStyle =
    if (family == null) this else copy(fontFamily = family)

internal fun styledLyricTypeface(font: LyricFontRuntime?, weight: Int): Typeface {
    val base = font?.typeface ?: Typeface.DEFAULT
    return when {
        Build.VERSION.SDK_INT >= 28 -> Typeface.create(base, weight, false)
        weight >= 600 -> Typeface.create(base, Typeface.BOLD)
        else -> base
    }
}

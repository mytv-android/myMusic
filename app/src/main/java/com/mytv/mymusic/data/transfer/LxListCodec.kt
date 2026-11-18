package com.mytv.mymusic.data.transfer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 洛雪（lx-music）导出的歌单文件，只读不写。格式逐条对过 lx-music-desktop @ 323e4ba、lx-music-mobile @ 0669d1b：
 * - 新版：playListPart_v2（单个歌单）、playList_v2（全部歌单）、allData_v2（完整备份，只取 playList）；
 * - 旧版：playListPart、playList、allData、defautlList（洛雪自己的拼写），歌曲字段照洛雪 toNewMusicInfo 转成新版。
 * 固定歌单按 id 认（default 试听列表、love 我的收藏、temp 临时列表），桌面版的名字可能是 i18n 键。
 */
enum class LxKind(val wire: String, val legacy: Boolean) {
    PartV2("playListPart_v2", false),
    ListV2("playList_v2", false),
    AllV2("allData_v2", false),
    PartV1("playListPart", true),
    ListV1("playList", true),
    AllV1("allData", true),
    DefaultV1("defautlList", true),
    ;

    companion object {
        fun of(type: String?): LxKind? = entries.firstOrNull { it.wire == type }
    }
}

enum class LxFixed(val id: String, val label: String) {
    Default("default", "试听列表"),
    Love("love", "我的收藏"),
    Temp("temp", "临时列表"),
}

data class LxListFile(val kind: LxKind, val lists: List<LxList>)

data class LxList(
    val id: String,
    val name: String,
    val fixed: LxFixed?,
    val source: String?,
    val sourceListId: String?,
    val songs: List<LxSong>,
    /** 照洛雪 filterMusicList 丢掉的（id / 歌名为空、同 id 重复），加上结构坏了解不出来的。 */
    val dropped: Int,
)

data class LxSong(
    val id: String,
    val name: String,
    val singer: String,
    val source: String,
    /** "04:12" → 252；没有或解析失败是 0。 */
    val intervalSec: Int,
    /** meta 里的标量字段拍平成字符串：songId、albumName、picUrl、albumId、hash、albumAudioId、strMediaMid、id、albumMid、copyrightId、filePath、ext… */
    val meta: Map<String, String>,
)

object LxListCodec {
    /** 结构不对（data 不是数组之类）抛 [IllegalArgumentException]；单首坏了只丢那一首。 */
    fun parse(root: JsonObject): LxListFile {
        val kind = LxKind.of(root.str("type")) ?: throw IllegalArgumentException("不是洛雪的歌单文件")
        val lists = listsOf(kind, root).map { obj ->
            val id = obj.str("id").orEmpty()
            val fixed = fixedOf(id)
            val rawName = obj.str("name").orEmpty().trim()
            val name = when {
                rawName.isBlank() || rawName.startsWith("list__name_") -> fixed?.label ?: "未命名歌单"
                else -> rawName
            }.take(MAX_LIST_NAME)
            val rawSongs = obj["list"] as? JsonArray ?: JsonArray(emptyList())
            val parsed = rawSongs.map { element ->
                val song = element as? JsonObject ?: return@map null
                runCatching { if (kind.legacy) songV1(song) else songV2(song) }.getOrNull()
            }
            val (songs, dropped) = filter(parsed)
            LxList(
                id = id,
                name = name,
                fixed = fixed,
                source = obj.str("source")?.takeIf { it.isNotBlank() },
                sourceListId = obj.str("sourceListId")?.takeIf { it.isNotBlank() },
                songs = songs,
                dropped = dropped,
            )
        }
        return LxListFile(kind, lists)
    }

    internal fun listsOf(kind: LxKind, root: JsonObject): List<JsonObject> = when (kind) {
        LxKind.PartV2, LxKind.PartV1 -> listOf(root["data"] as? JsonObject ?: structure())
        LxKind.ListV2, LxKind.ListV1 -> objects(root["data"])
        LxKind.AllV2 -> objects(root["playList"])
        LxKind.AllV1 -> {
            val defaultList = root["defaultList"] as? JsonObject
            if (defaultList != null) {
                listOf(fixedList(LxFixed.Default, defaultList["list"]))
            } else {
                objects(root["playList"])
            }
        }
        LxKind.DefaultV1 -> listOf(fixedList(LxFixed.Default, (root["data"] as? JsonObject ?: structure())["list"]))
    }

    private fun fixedList(fixed: LxFixed, list: JsonElement?): JsonObject = buildJsonObject {
        put("id", fixed.id)
        put("name", fixed.label)
        put("list", list as? JsonArray ?: JsonArray(emptyList()))
    }

    private fun objects(element: JsonElement?): List<JsonObject> {
        val array = element as? JsonArray ?: structure()
        return array.mapNotNull { it as? JsonObject }
    }

    private fun structure(): Nothing = throw IllegalArgumentException("洛雪文件结构不对")

    /** 新版歌曲：meta 不是对象、source 空 → null。 */
    internal fun songV2(obj: JsonObject): LxSong? {
        val meta = obj["meta"] as? JsonObject ?: return null
        val source = obj.str("source").orEmpty()
        if (source.isBlank()) return null
        return LxSong(
            id = obj.str("id").orEmpty(),
            name = obj.str("name").orEmpty(),
            singer = obj.str("singer").orEmpty(),
            source = source,
            intervalSec = parseInterval(obj.str("interval")),
            meta = flatten(meta),
        )
    }

    /** 旧版歌曲照洛雪 toNewMusicInfo 转；洛雪自己在 _types 缺失时会抛，这里不读它。 */
    internal fun songV1(obj: JsonObject): LxSong? {
        val source = obj.str("source").orEmpty()
        if (source.isBlank()) return null
        val songmid = obj.str("songmid").orEmpty()
        val meta = LinkedHashMap<String, String>()
        fun copy(from: String, to: String = from) {
            obj.str(from)?.takeIf { it.isNotEmpty() }?.let { meta[to] = it }
        }
        if (songmid.isNotEmpty()) meta["songId"] = songmid
        copy("albumName")
        copy("img", "picUrl")
        var id = "${source}_$songmid"
        if (source == "local") {
            val filePath = obj.str("filePath")?.takeIf { it.isNotEmpty() } ?: songmid
            meta["filePath"] = filePath
            meta["ext"] = obj.str("ext")?.takeIf { it.isNotEmpty() } ?: EXT.find(filePath)?.groupValues?.get(1).orEmpty()
        } else {
            copy("albumId")
            when (source) {
                "kg" -> {
                    copy("hash")
                    copy("albumAudioId")
                    id = "${songmid}_${obj.str("hash").orEmpty()}"
                }
                "tx" -> {
                    copy("strMediaMid")
                    copy("songId", "id")
                    copy("albumMid")
                }
                "mg" -> {
                    copy("copyrightId")
                    copy("lrcUrl")
                    copy("mrcUrl")
                    copy("trcUrl")
                }
            }
        }
        return LxSong(
            id = if (songmid.isEmpty()) "" else id,
            name = obj.str("name").orEmpty(),
            singer = obj.str("singer").orEmpty(),
            source = source,
            intervalSec = parseInterval(obj.str("interval")),
            meta = meta,
        )
    }

    /** 照洛雪 filterMusicList：id 空、歌名空、同 id 重复的丢掉（null 是解不出来的，一起计数）。 */
    internal fun filter(songs: List<LxSong?>): Pair<List<LxSong>, Int> {
        val ids = HashSet<String>()
        val kept = ArrayList<LxSong>(songs.size)
        var dropped = 0
        for (song in songs) {
            if (song == null || song.id.isBlank() || song.name.isBlank() || !ids.add(song.id)) {
                dropped++
                continue
            }
            kept += song
        }
        return kept to dropped
    }

    /** "mm:ss" / "h:mm:ss" → 秒；null 或认不出是 0。 */
    internal fun parseInterval(raw: String?): Int {
        val parts = raw?.trim()?.split(':') ?: return 0
        if (parts.size !in 2..3) return 0
        val numbers = parts.map { it.trim().toIntOrNull() ?: return 0 }
        if (numbers.any { it < 0 }) return 0
        return numbers.fold(0) { acc, n -> acc * 60 + n }
    }

    internal fun fixedOf(id: String): LxFixed? = LxFixed.entries.firstOrNull { it.id == id }

    /** meta 里的标量拍平：数字原样转字符串（"123" 而不是 "123.0"），null、对象、数组不要。 */
    private fun flatten(meta: JsonObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, value) in meta) {
            val primitive = value as? JsonPrimitive ?: continue
            if (primitive is JsonNull) continue
            val text = scalar(primitive) ?: continue
            if (text.isNotEmpty()) out[key] = text
        }
        return out
    }

    private fun scalar(primitive: JsonPrimitive): String? {
        if (primitive is JsonNull) return null
        val content = primitive.content
        return if (!primitive.isString && WHOLE_DECIMAL.matches(content)) content.substringBefore('.') else content
    }

    private fun JsonObject.str(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return scalar(primitive)
    }

    private val EXT = Regex("""\.(\w+)$""")
    private val WHOLE_DECIMAL = Regex("""-?\d+\.0+""")

    /** 和洛雪 clipNameLength 同上限。 */
    private const val MAX_LIST_NAME = 80
}

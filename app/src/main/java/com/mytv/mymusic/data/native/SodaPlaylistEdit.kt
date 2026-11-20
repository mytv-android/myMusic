package com.mytv.mymusic.data.native

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
/**
 * 汽水歌单写操作的请求体与回包解析，逐条对齐 libresoda `playlist_edit.rs`
 * （AGPL-3.0，见 THIRD_PARTY_NOTICES.md）。HTTP 由 [SodaProvider] 发——这些接口
 * 都要求逐请求的应用级签名（`x-helios` / `x-medusa`），所以这里只放纯函数。
 *
 * | 能力 | 接口 | 请求体 |
 * | --- | --- | --- |
 * | 创建 | `POST /luna/pc/me/playlist` | `{name, is_private, media?}` |
 * | 改信息 | `POST /luna/pc/me/playlist/update` | `{playlist_id, name?, description?, is_private?, cover_uri?}` |
 * | 删除 | `POST /luna/pc/me/playlist/delete` | `{playlist_ids}` |
 * | 加歌 | `POST /luna/pc/me/playlist/media/append` | `{playlist_id, media[{id,type}]}` |
 * | 删歌 | `POST /luna/pc/me/playlist/media/delete` | `{playlist_id, media[{id,type}]}` |
 * | 排序 | `POST /luna/me/playlist/media/sort` | `{playlist_id, media[{id,type}]}` |
 * | 敏感词 | `GET /luna/me/playlist/wordcheck` | 查询参数 `content` + `type` |
 * | 导入 | `POST /luna/me/playlist/import` | `{url}` |
 * | 导入进度 | `POST /luna/me/playlist/import_task_info` | `{task_ids}` |
 */
internal object SodaPlaylistEdit {
    const val CREATE_PATH = "/luna/pc/me/playlist"
    const val UPDATE_PATH = "/luna/pc/me/playlist/update"
    const val DELETE_PATH = "/luna/pc/me/playlist/delete"
    const val APPEND_MEDIA_PATH = "/luna/pc/me/playlist/media/append"
    const val DELETE_MEDIA_PATH = "/luna/pc/me/playlist/media/delete"

    /** 排序只有非 PC 路径：PC 路径会 404，客户端 IDL `SortPlaylistMedia` 里也只登记了这一条。 */
    const val SORT_MEDIA_PATH = "/luna/me/playlist/media/sort"
    const val WORD_CHECK_PATH = "/luna/me/playlist/wordcheck"
    const val IMPORT_PATH = "/luna/me/playlist/import"
    const val IMPORT_TASKS_PATH = "/luna/me/playlist/import_task_info"

    /** 官方 `SensitiveWordCheckType`。 */
    const val WORD_CHECK_NAME = "name"
    const val WORD_CHECK_DESC = "desc"

    /**
     * 歌单名的字符数上限：官方 PC 客户端两个输入框都是 `max-length=30`，服务端硬线也在 30
     * （30 字符通过、31 字符回 `ERR_INVALID_PARAM` = 1000004）。超限时本地就报错，
     * 不把裸错误码丢给上层。
     */
    const val NAME_MAX_CHARS = 30

    /** 校验歌单名；返回 null 表示通过，否则是给用户看的错误文案。 */
    fun checkName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "歌单名不能为空"
        val chars = trimmed.length
        if (chars > NAME_MAX_CHARS) return "歌单名最多 $NAME_MAX_CHARS 个字符（当前 $chars）"
        return null
    }

    /** `media` 数组：`[{id, type}]`，type 单曲固定 `track`；空项直接丢掉。 */
    fun mediaArray(ids: List<String>, type: String = "track"): JsonArray = buildJsonArray {
        ids.forEach { raw ->
            val id = raw.trim()
            if (id.isEmpty()) return@forEach
            add(buildJsonObject {
                put("id", id)
                put("type", type)
            })
        }
    }

    fun mediaArrayCount(ids: List<String>): Int = ids.count { it.trim().isNotEmpty() }

    /** 建歌单：歌曲用 `media` 传（官方 `CreatePlaylistDialog.vue` 就是这么发的），不用 `track_ids`。 */
    fun createBody(name: String, isPrivate: Boolean, trackIds: List<String>): String =
        buildJsonObject {
            put("name", name.trim())
            put("is_private", isPrivate)
            val media = mediaArray(trackIds)
            if (media.isNotEmpty()) put("media", media)
        }.toString()

    /** 改名 / 改描述 / 改可见性；只传要改的字段。 */
    fun updateBody(
        playlistId: String,
        name: String? = null,
        description: String? = null,
        isPrivate: Boolean? = null,
    ): String = buildJsonObject {
        put("playlist_id", playlistId.trim())
        name?.let { put("name", it.trim()) }
        description?.let { put("description", it.trim()) }
        isPrivate?.let { put("is_private", it) }
    }.toString()

    fun deleteBody(playlistIds: List<String>): String = buildJsonObject {
        put("playlist_ids", buildJsonArray {
            playlistIds.forEach { raw ->
                val id = raw.trim()
                if (id.isNotEmpty()) add(JsonPrimitive(id))
            }
        })
    }.toString()

    /** 加歌 / 删歌 / 排序共用：`{playlist_id, media:[{id,type}]}`，排序时 media 按目标顺序。 */
    fun mediaBody(playlistId: String, trackIds: List<String>): String = buildJsonObject {
        put("playlist_id", playlistId.trim())
        put("media", mediaArray(trackIds))
    }.toString()

    fun importBody(url: String): String = buildJsonObject {
        put("url", url.trim())
    }.toString()

    fun importTasksBody(taskIds: List<String>): String = buildJsonObject {
        put("task_ids", buildJsonArray {
            taskIds.forEach { raw ->
                val id = raw.trim()
                if (id.isNotEmpty()) add(JsonPrimitive(id))
            }
        })
    }.toString()

    /**
     * 写操作回包是否成功。**成功回包不一定带 `status_code`**：`CreatePlaylist` /
     * `UpdatePlaylistInfo` 成功时只回 `playlist` + `status_info`，出错才带非 0 码
     * （`ERR_INVALID_PARAM` 1000004、`HIT_SENSITIVE_WORD` 1000014）。
     * 所以「缺字段」必须当成功——写成 `status_code == 0` 会把所有成功都判成失败。
     * 但**整份回包都解析不出来**（null）时不能算成功：那说明连对答案的资格都没有。
     */
    fun responseIsOk(root: JsonObject?): Boolean {
        if (root == null) return false
        val code = jsonInt(root, "status_code") ?: return true
        return code == 0
    }

    /** 回包里的错误文案（`status_info.status_msg`），成功时为空。 */
    fun responseErrorMessage(root: JsonObject?): String =
        jsonText(root, "status_info", "status_msg").trim()

    /** 从创建/导入的回包里取新歌单 id；服务端换过几版字段名，都兜住。 */
    fun extractPlaylistId(root: JsonObject?): String {
        if (root == null) return ""
        val data = jsonObj(root, "data")
        val candidates = listOf(
            jsonText(data, "playlist_id"),
            jsonText(data, "playlist", "id"),
            jsonText(data, "id"),
            jsonText(root, "playlist", "id"),
            jsonText(root, "playlist_id"),
        )
        return candidates.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    /**
     * 敏感词检查：`true` = 命中。回包形如 `{status_code, is_passed}`，所以命中 = `!is_passed`。
     * 非 0 的 `status_code` 是接口错误，不当成「命中」——调用方要能区分这两件事。
     */
    fun sensitiveWordHit(root: JsonObject?): Boolean? {
        if (root == null) return null
        val code = jsonInt(root, "status_code")
        if (code != null && code != 0) return null
        val passed = jsonBool(root, "is_passed") ?: jsonBool(root, "data", "is_passed")
        return passed?.let { !it }
    }
}

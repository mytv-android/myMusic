package com.mytv.mymusic.data.transfer

import com.mytv.mymusic.data.PlaylistEntity
import com.mytv.mymusic.data.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPlanTest {
    private fun resource(name: String) =
        LxListCodec.parse(Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/lx/$name")).use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject)

    private fun playlist(id: String, name: String, bound: Boolean = false, deleted: Boolean = false) = PlaylistEntity(
        id = id, name = name, createdAt = 1, updatedAt = 1,
        deletedAt = if (deleted) 5 else null,
        linkProvider = if (bound) "netease" else "", linkId = if (bound) "88" else "",
    )

    private fun lxPlan(name: String, existing: List<PlaylistEntity> = emptyList(), local: LocalMatchIndex? = null, migu: Map<String, Song> = emptyMap()) =
        buildImportPlan(TransferContent.Lx(resource(name)), name, existing, local, migu, knownProvider = { true })

    @Test
    fun defaultChoicesFollowTheTable() {
        val plan = lxPlan("list_v2.json", existing = listOf(playlist("p-run", "跑步")))
        // temp 不在里面
        assertEquals(listOf("default", "love", "userlist_1"), plan.lists.map { it.sourceId })
        assertEquals(1, plan.skippedTemp)
        val merge = defaultChoices(plan, mergeSameName = true)
        assertEquals(ImportChoice(true, ImportTarget.New), merge["default"])
        assertEquals(ImportChoice(true, ImportTarget.Favorites), merge["love"])
        assertEquals(ImportChoice(true, ImportTarget.Merge), merge["userlist_1"])
        val separate = defaultChoices(plan, mergeSameName = false)
        assertEquals(ImportTarget.New, separate["userlist_1"]?.target)
        assertEquals(ImportTarget.Favorites, separate["love"]?.target)
        assertEquals(listOf(ImportTarget.Favorites, ImportTarget.New), targetsFor(plan, plan.lists[1]))
        assertEquals(listOf(ImportTarget.Merge, ImportTarget.New), targetsFor(plan, plan.lists[2]))
        assertEquals(listOf(ImportTarget.New), targetsFor(plan, plan.lists[0]))
    }

    @Test
    fun emptyListIsNotChecked() {
        val file = LxListCodec.parse(
            Json.parseToJsonElement(
                """{"type":"playListPart_v2","data":{"id":"x","name":"自定义源","list":[{"id":"xyz_1","name":"a","singer":"b","source":"xyz","meta":{"songId":"1"}}]}}""",
            ).jsonObject,
        )
        val plan = buildImportPlan(TransferContent.Lx(file), "a.lxmc", emptyList(), null, emptyMap(), { true })
        assertEquals(ImportChoice(false, ImportTarget.New), defaultChoices(plan, true)["x"])
        assertEquals(0 to 0, selectedCount(plan, defaultChoices(plan, true)))
        assertEquals(mapOf("other" to 1), plan.lists.single().skippedPlatforms)
    }

    @Test
    fun toggleOnlyTouchesSameNameRowsNotOverridden() {
        val existing = listOf(playlist("p1", "跑步"), playlist("p2", "试听列表"))
        val plan = lxPlan("list_v2.json", existing)
        val start = defaultChoices(plan, mergeSameName = true)
        assertEquals(ImportTarget.Merge, start["default"]?.target)
        // 用户单独把「试听列表」改成了新建
        val edited = start + ("default" to ImportChoice(true, ImportTarget.New))
        val toggled = applySameNameToggle(plan, edited, overridden = setOf("default"), mergeSameName = false)
        assertEquals(ImportTarget.New, toggled["userlist_1"]?.target)
        assertEquals(ImportTarget.New, toggled["default"]?.target)
        assertEquals(ImportTarget.Favorites, toggled["love"]?.target)
        val back = applySameNameToggle(plan, toggled, overridden = setOf("default"), mergeSameName = true)
        assertEquals(ImportTarget.Merge, back["userlist_1"]?.target)
        assertEquals(ImportTarget.New, back["default"]?.target)
    }

    @Test
    fun boundSameNameCanOnlyCreateWithSuffix() {
        val plan = lxPlan("list_v2.json", listOf(playlist("p1", " 跑步 ", bound = true)))
        val run = plan.lists.single { it.sourceId == "userlist_1" }
        assertEquals(SameName("p1", " 跑步 ", bound = true), plan.sameName["userlist_1"])
        assertEquals(ImportChoice(true, ImportTarget.New), defaultChoices(plan, true)["userlist_1"])
        assertEquals(listOf(ImportTarget.New), targetsFor(plan, run))
        assertEquals("跑步（导入）", newNameFor(plan, run))
        assertEquals("新建歌单「跑步（导入）」", targetLabel(plan, run, ImportTarget.New))
        // 同名的既有绑定的也有没绑定的：合并进没绑定的那个
        val mixed = lxPlan("list_v2.json", listOf(playlist("p1", "跑步", bound = true), playlist("p2", "跑步")))
        assertEquals(SameName("p2", "跑步", bound = false), mixed.sameName["userlist_1"])
        // 删掉的歌单不算同名
        assertNull(lxPlan("list_v2.json", listOf(playlist("p3", "跑步", deleted = true))).sameName["userlist_1"])
    }

    @Test
    fun newNamesAvoidConflicts() {
        assertEquals("跑步", newListName("跑步", setOf("通勤")))
        assertEquals("跑步（2）", newListName("跑步", setOf("跑步")))
        assertEquals("跑步（3）", newListName("跑步", setOf("跑步", "跑步（2）")))
        assertEquals("Run（2）", newListName("Run", setOf(" run ")))
        assertEquals("未命名歌单", newListName("  ", emptySet()))
    }

    @Test
    fun kugouKeysDedupeCaseInsensitively() {
        val file = LxListCodec.parse(
            Json.parseToJsonElement(
                """{"type":"playListPart_v2","data":{"id":"x","name":"x","list":[
                  {"id":"1_ABC","name":"a","singer":"b","source":"kg","meta":{"songId":"1","hash":"ABC"}},
                  {"id":"2_abc","name":"a","singer":"b","source":"kg","meta":{"songId":"2","hash":"abc"}},
                  {"id":"wy_1","name":"c","singer":"d","source":"wy","meta":{"songId":"1"}}
                ]}}""",
            ).jsonObject,
        )
        val list = buildImportPlan(TransferContent.Lx(file), "x", emptyList(), null, emptyMap(), { true }).lists.single()
        assertEquals(2, list.importable)
        assertEquals(1, list.skipped[SkipReason.Duplicate])
        assertEquals(2, list.shown)
        assertEquals(importKey(Song(id = "ABC", source = "kugou")), importKey(Song(id = "abc", source = "kugou")))
        assertFalse(importKey(Song(id = "ABC", source = "qq")) == importKey(Song(id = "abc", source = "qq")))
    }

    @Test
    fun countsAddUp() {
        val resolved = mapOf("60084600529" to Song(id = "600907|2|HQ", name = "小幸运", source = "migu", extra = mapOf("content_id" to "600907")))
        val local = LocalMatchIndex(listOf(LocalCandidate(Song(id = "sha:1", name = "夜曲", artist = "周杰伦", duration = 226, source = "local"), "夜曲.mp3")))
        val plan = lxPlan("part_v2.json", local = local, migu = resolved)
        val list = plan.lists.single()
        // 8 首过了洛雪的过滤：wy、tx×2、kg、kw、mg、local、xyz；另 2 首格式不对 / 重复
        assertEquals(8, list.tracks.size)
        assertEquals(7, list.importable)
        assertEquals(mapOf("netease" to 1, "qq" to 2, "kugou" to 1, "kuwo" to 1, "migu" to 1, "local" to 1), list.platformCounts)
        assertEquals(mapOf("other" to 1), list.skippedPlatforms)
        assertEquals(mapOf(SkipReason.UnknownSource to 1, SkipReason.BadFormat to 2), list.skipped)
        assertEquals(list.tracks.size, list.platformCounts.values.sum() + list.skippedPlatforms.values.sum())
        assertEquals(1, plan.migu)
        assertEquals(1, plan.miguResolved)
        assertEquals("600907|2|HQ", list.songs.single { it.source == "migu" }.id)
        assertEquals("sha:1", list.songs.single { it.source == "local" }.id)
        assertTrue(plan.lx)
        assertFalse(plan.legacy)
        val text = importSkippedText(plan)
        assertTrue(text, text.contains("咪咕 1 首"))
        assertTrue(text, text.contains("1 首本应用放不了"))
    }

    @Test
    fun unresolvedMiguFallsBackToCopyright() {
        val plan = lxPlan("part_v2.json")
        val migu = plan.lists.single().songs.single { it.source == "migu" }
        assertEquals("60084600529|2|PQ", migu.id)
        assertEquals(1, plan.migu)
        assertEquals(0, plan.miguResolved)
        // 本地没找到
        assertEquals(1, plan.lists.single().skipped[SkipReason.LocalNotFound])
        assertTrue(importSkippedText(plan).contains("1 首没换到"))
    }

    @Test
    fun legacyFlag() {
        assertTrue(lxPlan("part_v1.json").legacy)
    }

    @Test
    fun myMusicFileWithFavoritesAndLocal() {
        val decoded = DecodedPlaylists(
            MyMusicPlaylistFile(
                playlists = listOf(
                    PortablePlaylist("我的收藏", KIND_FAVORITES, tracks = listOf(PortableTrack("qq", "m1", "晴天"))),
                    PortablePlaylist(
                        "跑步",
                        tracks = listOf(
                            PortableTrack("netease", "1", "a"),
                            PortableTrack("gone", "2", "b"),
                            PortableTrack("local", "sha:x", "夜曲", "周杰伦", durationSec = 226),
                            PortableTrack("local", "sha:y", "七里香", "周杰伦"),
                        ),
                    ),
                ),
            ),
            newerVersion = true,
            dropped = listOf(0, 3),
        )
        val local = LocalMatchIndex(
            listOf(
                LocalCandidate(Song(id = "sha:other", name = "夜曲", artist = "周杰伦", duration = 226, source = "local"), "夜曲.flac"),
                LocalCandidate(Song(id = "sha:y", name = "七里香（Live）", artist = "周杰伦", source = "local"), "七里香.mp3"),
            ),
        )
        val plan = buildImportPlan(
            TransferContent.Playlists(decoded), "a.mymusic.json", listOf(playlist("p", "跑步")), local, emptyMap(),
            knownProvider = { it != "gone" },
        )
        assertEquals(ImportPlan.MYMUSIC_LABEL, plan.sourceLabel)
        assertTrue(plan.newerVersion)
        assertEquals(LxFixed.Love, plan.lists[0].fixed)
        assertEquals(ImportTarget.Favorites, defaultChoices(plan, true)["mymusic:0"]?.target)
        assertNull(plan.sameName["mymusic:0"])
        val run = plan.lists[1]
        assertEquals(ImportTarget.Merge, defaultChoices(plan, true)["mymusic:1"]?.target)
        // 本地歌：同一个文件按身份认，别的按歌名歌手找
        assertEquals(listOf("netease:1", "local:sha:other", "local:sha:y"), run.songs.map { it.key })
        assertEquals(1, run.skipped[SkipReason.UnknownSource])
        assertEquals(3, run.skipped[SkipReason.BadFormat])
    }

    @Test
    fun pickLocalRules() {
        val a = LocalCandidate(Song(id = "a", name = "晴天", artist = "周杰伦", duration = 269, source = "local"), "晴天.mp3")
        val b = LocalCandidate(Song(id = "b", name = "晴天", artist = "周杰伦", duration = 300, source = "local"), "晴天 (live).flac")
        val c = LocalCandidate(Song(id = "c", name = "晴天", artist = "别人", duration = 269, source = "local"), "x.mp3")
        val all = listOf(a, b, c)
        assertEquals("a", pickLocal(listOf(a), "晴天", "周杰伦", 0, "")?.id)
        // 歌手空也算命中
        assertEquals("c", pickLocal(listOf(c), "晴天", "", 0, "")?.id)
        // 多首：先挑时长差 ≤ 3 秒
        assertEquals("b", pickLocal(listOf(a, b), "晴天", "周杰伦", 301, "")?.id)
        // 时长都对不上时按文件名
        assertEquals("b", pickLocal(listOf(a, b), "晴天", "周杰伦", 0, "晴天 (live).flac")?.id)
        // 都不中取第一首
        assertEquals("a", pickLocal(listOf(a, b), "晴天", "周杰伦", 0, "")?.id)
        assertNull(pickLocal(all, "雨天", "周杰伦", 0, ""))
        assertNull(pickLocal(listOf(c), "晴天", "周杰伦", 0, ""))
    }
}

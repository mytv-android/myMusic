package com.mytv.mymusic.data.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongCommentsTest {
    @Test
    fun onlyProvidersWithACommentImplementationAdvertiseTheCapability() {
        assertTrue(NativeProviders.get("netease")?.info?.comments == true)
        assertTrue(NativeProviders.get("qq")?.info?.comments == true)
        assertFalse(NativeProviders.get("kugou")?.info?.comments == true)
        assertFalse(NativeProviders.get("kuwo")?.info?.comments == true)
    }

    @Test
    fun neteaseParsesHotLatestAndPagination() {
        val page = parseNeteaseComments(
            """
            {
              "code": 200, "total": 51, "more": true,
              "hotComments": [{
                "commentId": 10, "content": "热门", "likedCount": 226143,
                "time": 1413809782401, "timeStr": "2014-10-20",
                "user": {"nickname": "甲", "avatarUrl": "http://p1.music.126.net/a.jpg"},
                "ipLocation": {"location": "浙江"}
              }],
              "comments": [{
                "commentId": 11, "content": "最新", "likedCount": 5, "liked": true,
                "user": {"nickname": "乙", "avatarUrl": "https://p1.music.126.net/b.jpg"}
              }]
            }
            """.trimIndent(),
            page = 1,
            limit = 20,
        )
        assertEquals(1, page.page)
        assertEquals(51, page.total)
        assertTrue(page.hasMore)
        assertEquals("热门", page.hotComments.single().content)
        assertEquals("https://p1.music.126.net/a.jpg", page.hotComments.single().avatar)
        assertEquals("浙江", page.hotComments.single().location)
        assertEquals("最新", page.comments.single().content)
        assertTrue(page.comments.single().liked)
    }

    @Test
    fun neteaseRejectsApiFailure() {
        val failure = runCatching { parseNeteaseComments("""{"code":-460}""", 1, 20) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("-460") == true)
    }

    @Test
    fun qqParsesSecondsLikesAndHotComments() {
        val page = parseQqComments(
            """
            {
              "code": 0, "subcode": 0, "morecomment": 1,
              "hot_comment": {"commenttotal": 1, "commentlist": [{
                "commentid": "hot-1", "nick": "Cheer", "rootcommentcontent": "虽然叫晴天",
                "praisenum": 266693, "time": 1475808377, "ispraise": 0,
                "avatarurl": "http://thirdqq.qlogo.cn/a"
              }]},
              "comment": {"commenttotal": 80, "commentlist": [{
                "commentid": "new-1", "nick": "夏目", "rootcommentcontent": "最新评论",
                "praisenum": 8, "time": 1627227358, "ispraise": 1
              }]}
            }
            """.trimIndent(),
            page = 1,
            limit = 20,
        )
        assertEquals(80, page.total)
        assertTrue(page.hasMore)
        assertEquals(1475808377000, page.hotComments.single().time)
        assertEquals("https://thirdqq.qlogo.cn/a", page.hotComments.single().avatar)
        assertEquals("最新评论", page.comments.single().content)
        assertTrue(page.comments.single().liked)
        assertFalse(page.comments.single().avatar.isNotEmpty())
    }

    @Test
    fun emptyCommentListIsNotAnError() {
        val page = parseQqComments(
            """{"code":0,"subcode":0,"comment":{"commentlist":null,"commenttotal":0},"hot_comment":{"commentlist":null}}""",
            1,
            20,
        )
        assertEquals(0, page.total)
        assertTrue(page.comments.isEmpty())
        assertFalse(page.hasMore)
    }
}

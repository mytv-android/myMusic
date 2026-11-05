package com.mytv.mymusic

import com.mytv.mymusic.data.native.convertTtmlToLrc
import com.mytv.mymusic.ui.LyricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple 逐词 TTML 转 LRC 时的词间空格。样例取自 2026-11-05 实抓的
 * Shape of You（zarz 代理，`ttmlContent`）。
 *
 * 两个坑都在这里：
 *  - Apple 把空格放在 span **之间**当文本节点，只取 span 内容英文会连成一片；
 *  - 但有些词被拆成多个 span（conver+sation、mail+box、mm-+hmm-），那种本来就没空格，
 *    无条件补空格反而错。所以按原始有没有空白决定。
 */
class AppleLyricsSpacingTest {

    private val spaced =
        """<p begin="9.731" end="12.105" itunes:key="L1" ttm:agent="v1">""" +
            """<span begin="9.731" end="9.927">The</span> """ +
            """<span begin="9.927" end="10.284">club</span> """ +
            """<span begin="10.284" end="10.570">isn't</span> """ +
            """<span begin="10.570" end="10.721">the</span> """ +
            """<span begin="10.721" end="10.996">best</span> """ +
            """<span begin="10.996" end="11.355">place</span> """ +
            """<span begin="11.355" end="11.473">to</span> """ +
            """<span begin="11.473" end="11.726">find</span> """ +
            """<span begin="11.726" end="11.826">a</span> """ +
            """<span begin="11.826" end="12.105">lover</span></p>"""

    /** 同一行里既有正常空格、又有词中被拆开的情况——最容易修错的一种。 */
    private val mixed =
        """<p begin="19.533" end="22.315" itunes:key="L5" ttm:agent="v1">""" +
            """<span begin="19.533" end="19.633">And</span> """ +
            """<span begin="19.633" end="19.763">you</span> """ +
            """<span begin="19.763" end="19.974">come</span> """ +
            """<span begin="19.974" end="20.278">over</span> """ +
            """<span begin="20.278" end="20.401">and</span> """ +
            """<span begin="20.401" end="20.626">start</span> """ +
            """<span begin="20.626" end="20.764">up</span> """ +
            """<span begin="20.764" end="20.878">a</span> """ +
            """<span begin="20.878" end="21.149">conver</span>""" +
            """<span begin="21.149" end="21.536">sation</span> """ +
            """<span begin="21.536" end="21.644">with</span></p>"""

    private fun ttml(block: String) = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>$block</div></body></tt>"""

    /** 用户最终看到的文本：TTML → LRC → 解析器。 */
    private fun displayOf(block: String): String {
        val parsed = LyricsParser.parse(convertTtmlToLrc(ttml(block)))
        return parsed.lines.joinToString("\n") { it.text }
    }

    @Test
    fun `spaces between spans survive into the displayed line`() {
        assertEquals("The club isn't the best place to find a lover", displayOf(spaced))
    }

    @Test
    fun `words split across spans are not broken by an inserted space`() {
        // conver+sation 之间本来没空格，补了就变成 "conver sation"
        val display = displayOf(mixed)
        assertTrue("被插进了多余空格: $display", display.contains("conversation"))
        assertTrue("词被拆开了: $display", !display.contains("conver sation"))
        assertEquals("And you come over and start up a conversation with", display)
    }

    @Test
    fun `both cases in one line keep their own spacing`() {
        // 上面两条各自的断言合起来还不够：这里直接钉死整行
        assertEquals(
            "And you come over and start up a conversation with",
            displayOf(mixed),
        )
    }

    @Test
    fun `lines without spans still work`() {
        val plain = """<p begin="1.0" end="3.0">Hello there</p>"""
        assertEquals("Hello there", displayOf(plain))
    }
}

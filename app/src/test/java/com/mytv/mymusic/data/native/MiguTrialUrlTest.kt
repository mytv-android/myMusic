package com.mytv.mymusic.data.native

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 咪咕 CDN 地址里的 `_60s` 段是 60 秒试听片段，解析器按它换源。 */
class MiguTrialUrlTest {

    @Test
    fun `60s path segments are trial urls`() {
        assertTrue(miguTrialUrl("https://freetyst.nf.migu.cn/public/product01/wav_3d_60s/T001_abc.mp3"))
        assertTrue(miguTrialUrl("https://freetyst.nf.migu.cn/public/product01/ZQ32_60s/T001_abc.flac"))
    }

    @Test
    fun `full quality urls are not trials`() {
        assertFalse(miguTrialUrl("https://freetyst.nf.migu.cn/public/product01/ZQ32/T001_abc.flac"))
        assertFalse(miguTrialUrl("https://freetyst.nf.migu.cn/public/product01/wav_3d/T001_abc.wav"))
        assertFalse(miguTrialUrl("https://freetyst.nf.migu.cn/public/product08/PQ/128/t001_abc.mp3"))
        assertFalse(miguTrialUrl(""))
    }
}

package com.mytv.mymusic.data.lx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力表版本：解析层新增平台（汽水 qs）后，老记录要重探；探过的记录和桌面插件不用。
 */
class LxCapabilityVersionTest {
    @Test
    fun legacyRecordsNeedRefresh() {
        // 升级上来的老记录没有版本号（默认 0），这次必须补探一次
        assertTrue(LxSourceRecord(id = "a", name = "a").needsCapabilityRefresh())
        assertTrue(LxSourceRecord(id = "a", name = "a", capabilityVersion = 1).needsCapabilityRefresh())
    }

    @Test
    fun currentAndDesktopRecordsAreSkipped() {
        assertFalse(
            LxSourceRecord(id = "a", name = "a", capabilityVersion = LX_CAPABILITY_SCHEMA_VERSION)
                .needsCapabilityRefresh(),
        )
        // 桌面插件没有平台能力表，重探也拿不到平台
        assertFalse(LxSourceRecord(id = "d", name = "d", desktop = true).needsCapabilityRefresh())
    }
}

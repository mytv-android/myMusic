package com.mytv.mymusic

import com.mytv.mymusic.data.sync.LocalNetworkAccess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkAccessTest {

    @Test
    fun requiredOnlyWhenApi37AndTargetSdk37() {
        assertFalse(LocalNetworkAccess.isRequired(36, 36))
        // 系统是 37 但 targetSdk 没升：INTERNET 隐式放行本地网络，申请只会被系统直接拒绝
        assertFalse(LocalNetworkAccess.isRequired(37, 36))
        assertFalse(LocalNetworkAccess.isRequired(36, 37))
        assertTrue(LocalNetworkAccess.isRequired(37, 37))
        assertTrue(LocalNetworkAccess.isRequired(38, 37))
    }
}

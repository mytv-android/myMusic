package com.mytv.mymusic

import android.media.MediaDrm
import android.media.MediaDrmException
import android.media.UnsupportedSchemeException
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 只回答一个问题：这台设备（模拟器/真机）到底有没有可用的 Widevine CDM。
 * 没有它，整曲这条路无从验证；有它，才能在设备上把授权和 kid 匹配跑通。
 */
@RunWith(AndroidJUnit4::class)
class WidevineProbeTest {

    private val tag = "WidevineProbe"

    @Test
    fun probe() {
        val uuid = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
        var drm: MediaDrm? = null
        try {
            drm = MediaDrm(uuid)
            Log.i(tag, "RESULT supported=true")
            Log.i(tag, "RESULT vendor=" + drm.getPropertyString(MediaDrm.PROPERTY_VENDOR))
            Log.i(tag, "RESULT version=" + drm.getPropertyString(MediaDrm.PROPERTY_VERSION))
            Log.i(tag, "RESULT description=" + drm.getPropertyString(MediaDrm.PROPERTY_DESCRIPTION))
            Log.i(tag, "RESULT algorithms=" + drm.getPropertyString(MediaDrm.PROPERTY_ALGORITHMS))
            runCatching {
                Log.i(tag, "RESULT securityLevel=" + drm.getPropertyString("securityLevel"))
            }.onFailure { Log.i(tag, "RESULT securityLevel=n/a ${it.message}") }
            runCatching {
                Log.i(tag, "RESULT deviceUniqueId=" + drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID).size)
            }.onFailure { Log.i(tag, "RESULT deviceUniqueId=n/a ${it.message}") }
            // 能不能真开一个 session（授权的第一步）
            runCatching {
                val session = drm.openSession()
                Log.i(tag, "RESULT openSession=OK")
                drm.closeSession(session)
            }.onFailure { Log.i(tag, "RESULT openSession=FAIL ${it.javaClass.simpleName}: ${it.message}") }
        } catch (e: UnsupportedSchemeException) {
            Log.e(tag, "RESULT supported=false UnsupportedScheme: ${e.message}")
        } catch (e: MediaDrmException) {
            Log.e(tag, "RESULT supported=false MediaDrmException: ${e.message}")
        } catch (e: Throwable) {
            Log.e(tag, "RESULT supported=false ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { drm?.release() }
        }
    }
}

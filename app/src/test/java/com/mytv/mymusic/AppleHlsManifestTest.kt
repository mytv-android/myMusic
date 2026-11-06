package com.mytv.mymusic

import com.mytv.mymusic.data.native.AppleHlsManifest
import com.mytv.mymusic.playback.AppleWidevineDrmCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * 整曲 HLS 清单解析 + 授权载体地址。样例取自 2026-11-05 实抓的 Shape of You
 * （cn 区，adam 1193701392），不是编的——清单格式一改这些就得红。
 */
class AppleHlsManifestTest {

    @Test
    fun `master manifest variants are parsed with group and uri`() {
        val master = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio-stereo-256",AUTOSELECT=YES,CHANNELS="2",NAME="songEnhanced"
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=256662,BANDWIDTH=269636,CODECS="mp4a.40.2",AUDIO="audio-stereo-256"
            P1251000174_A1193701392_audio_en_gr256_mp4a-40-2.m3u8
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=770180,BANDWIDTH=771967,CODECS="ec-3",AUDIO="audio-atmos-2768"
            P1251000174_A1193701392_audio_en_gr2768_mp4a-A6.m3u8
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=128916,BANDWIDTH=134133,CODECS="mp4a.40.2",AUDIO="audio-stereo-128"
            P1251000174_A1193701392_audio_en_gr128_mp4a-40-2.m3u8
        """.trimIndent()

        val variants = AppleHlsManifest.variants(master)
        // Atmos 不在 AUDIO_GROUPS 里，被过滤掉
        assertEquals(2, variants.size)
        assertEquals("audio-stereo-256", variants[0].group)
        assertEquals(256, variants[0].bitrateKbps)
        assertEquals("P1251000174_A1193701392_audio_en_gr256_mp4a-40-2.m3u8", variants[0].uri)
        assertEquals("audio-stereo-128", variants[1].group)
        assertEquals(128, variants[1].bitrateKbps)
    }

    @Test
    fun `play assets master without AUDIO attributes is parsed by bandwidth`() {
        // /v1/play/assets 回的是简版主清单：没有 AUDIO="…"、没有 EXT-X-MEDIA，只有三档。
        // 实抓自 Housewife（adam 6822497774，2026-11-06）。
        val master = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=269599,BANDWIDTH=289565,CODECS="mp4a.40.2",STABLE-VARIANT-ID="2q5f5td31"
            P1525602171_A6822497774_MS_audio_gr256_f2_AAC.m3u8
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=138670,BANDWIDTH=150251,CODECS="mp4a.40.2",STABLE-VARIANT-ID="08ws0xwdp"
            P1525602171_A6822497774_MS_audio_gr128_f1_AAC.m3u8
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=69833,BANDWIDTH=79675,CODECS="mp4a.40.5",STABLE-VARIANT-ID="f0em5p4su"
            P1525602171_A6822497774_MS_audio_gr64_f22_AAC.m3u8
        """.trimIndent()

        val variants = AppleHlsManifest.variants(master)
        assertEquals(3, variants.size)
        assertEquals("audio-stereo-256", variants[0].group)
        // BANDWIDTH 正则会先命中 AVERAGE-BANDWIDTH（269599），老清单也是这个行为
        assertEquals(269, variants[0].bitrateKbps)
        assertEquals("audio-stereo-128", variants[1].group)
        assertEquals("audio-HE-stereo-64", variants[2].group)
        assertEquals("P1525602171_A6822497774_MS_audio_gr256_f2_AAC.m3u8", AppleHlsManifest.preferred(master)?.uri)
    }

    @Test
    fun `play assets media playlist has a single widevine key`() {
        // 简版媒体清单：三套 DRM 挤在一个 key 块里，只有一条 Widevine、一个 tenc。
        // init 段 kid 改写必须按这把 key 来，不能套 enhancedHls 的「默认+轮换」两条。
        val media = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://itunes.apple.com/P1525602171/e1",KEYFORMAT="com.apple.streamingkeydelivery",KEYFORMATVERSIONS="1"
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="data:text/plain;charset=UTF-16;base64,vgEAAAEAAQC0ATwA",KEYFORMAT="com.microsoft.playready",KEYFORMATVERSIONS="1"
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="data:text/plain;base64,AAAAOHBzc2gAAAAA7e+LqXnWSs6jyCfc1R0h7QAAABgSEAAAAABa7td7ZTEgICAgICBI88aJmwY=",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",KEYFORMATVERSIONS="1"
            #EXT-X-MAP:URI="P1525602171_A6822497774_MS_audio_gr256_f2_AAC-.mp4",BYTERANGE="1557@0"
            #EXTINF:9.98458,
            #EXT-X-BYTERANGE:326021@1557
            P1525602171_A6822497774_MS_audio_gr256_f2_AAC-.mp4
            #EXT-X-ENDLIST
        """.trimIndent()

        val uris = AppleHlsManifest.widevineKeyUris(media)
        assertEquals(1, uris.size)
        assertEquals(uris.single(), AppleHlsManifest.widevinePssh(media))
        val kid = AppleHlsManifest.widevineKeyId(uris.single())
        assertEquals("000000005aeed77b6531202020202020", kid?.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
    }

    @Test
    fun `preferred group wins over later better bitrate`() {
        val master = """
            #EXT-X-STREAM-INF:BANDWIDTH=900000,AUDIO="audio-HE-stereo-64"
            he64.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=134133,AUDIO="audio-stereo-128"
            aac128.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=269636,AUDIO="audio-stereo-256"
            aac256.m3u8
        """.trimIndent()

        val chosen = AppleHlsManifest.preferred(master)
        assertEquals("audio-stereo-256", chosen?.group)
        assertEquals("aac256.m3u8", chosen?.uri)
        // BANDWIDTH=269636 是真实带宽，除以 1000 就是 269；组名里的 256 是标称值，别混
        assertEquals(269, chosen?.bitrateKbps)
    }

    @Test
    fun `preferred falls back down the group list`() {
        val onlyLowTiers = """
            #EXT-X-STREAM-INF:BANDWIDTH=73309,AUDIO="audio-HE-stereo-64"
            he64.m3u8
        """.trimIndent()
        assertEquals("audio-HE-stereo-64", AppleHlsManifest.preferred(onlyLowTiers)?.group)
    }

    @Test
    fun `crlf manifests parse the same as lf`() {
        val lf = "#EXT-X-STREAM-INF:BANDWIDTH=269636,AUDIO=\"audio-stereo-256\"\naac256.m3u8"
        assertEquals(AppleHlsManifest.variants(lf), AppleHlsManifest.variants(lf.replace("\n", "\r\n")))
    }

    @Test
    fun `empty or foreign manifest yields nothing`() {
        assertTrue(AppleHlsManifest.variants("").isEmpty())
        assertNull(AppleHlsManifest.preferred(""))
        assertTrue(AppleHlsManifest.variants("#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"audio-alac\"\nx.m3u8").isEmpty())
    }

    @Test
    fun `widevine pssh is picked out of triple drm keys`() {
        // 真实样例：FairPlay + PlayReady + Widevine 三条并存，且 Widevine 有两条
        // （第一条是覆盖 init segment 的坏 key，得跳过）
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:15
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://itunes.apple.com/P000000000/s1/e1",KEYFORMAT="com.apple.streamingkeydelivery",KEYFORMATVERSIONS="1"
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="data:text/plain;charset=UTF-16;base64,vgEAAAEAAQC0ATwA",KEYFORMAT="com.microsoft.playready",KEYFORMATVERSIONS="1"
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="${AppleHlsManifest.defaultWidevineKeyUri()}",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",KEYFORMATVERSIONS="1"
            #EXT-X-MAP:URI="P1251000174_A1193701392_audio_en_gr128_mp4a-40-2_m.mp4",BYTERANGE="1124@0"
            #EXTINF:14.97687,
            #EXT-X-BYTERANGE:243673@1124
            P1251000174_A1193701392_audio_en_gr128_mp4a-40-2_m.mp4
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://itunes.apple.com/p1251000174/c22",KEYFORMAT="com.apple.streamingkeydelivery",KEYFORMATVERSIONS="1"
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="data:text/plain;charset=UTF-16;base64,vgEAAAEAAQC0ATwA",KEYFORMAT="com.microsoft.playready",KEYFORMATVERSIONS="1"
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="data:text/plain;base64,AAAAOHBzc2gAAAAA7e+LqXnWSs6jyCfc1R0h7QAAABgSEAAAAABKkL9uYzIyICAgICBI88aJmwY=",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",KEYFORMATVERSIONS="1"
            #EXTINF:15.00009,
            #EXT-X-BYTERANGE:235111@244797
            P1251000174_A1193701392_audio_en_gr128_mp4a-40-2_m.mp4
        """.trimIndent()

        val pssh = AppleHlsManifest.widevinePssh(media)
        assertEquals(
            "data:text/plain;base64,AAAAOHBzc2gAAAAA7e+LqXnWSs6jyCfc1R0h7QAAABgSEAAAAABKkL9uYzIyICAgICBI88aJmwY=",
            pssh,
        )
        // 解出来盒头声明的长度和实际一致，是条能用的 PSSH
        val box = base64Lenient(pssh.orEmpty().substringAfter("base64,"))
        assertEquals(56, box.size)
        assertEquals(56, java.nio.ByteBuffer.wrap(box, 0, 4).int)
        assertEquals("pssh", String(box, 4, 4))
        assertEquals(UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"), uuidAt(box, 12))
    }

    @Test
    fun `default init segment key is skipped`() {
        // 只有默认那一条时也得给出个值，不能返回 null 让播放起不来
        val onlyDefault = """
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="${AppleHlsManifest.defaultWidevineKeyUri()}",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",KEYFORMATVERSIONS="1"
        """.trimIndent()
        val onlyDefaultKey = AppleHlsManifest.widevinePssh(onlyDefault).orEmpty()
        assertTrue(onlyDefaultKey.endsWith("czEvZTEgICBI88aJmwY="))
    }

    @Test
    fun `default key constant has not drifted`() {
        // 抄漏一个 A 长度就从 99 变 98，跳过逻辑静默失效，而 fixture 和常量抄同一个
        // 错值时测试照样绿——所以拿长度和 base64 合法性盯着它
        val uri = AppleHlsManifest.defaultWidevineKeyUri()
        assertEquals(99, uri.length)
        val b64 = uri.removePrefix("data:text/plain;base64,")
        assertEquals(76, b64.length)
        assertEquals(0, b64.length % 4)
        val box = java.util.Base64.getDecoder().decode(b64)
        assertEquals(56, box.size)
        // 盒头声明的长度得和实际字节数一致
        assertEquals(56, java.nio.ByteBuffer.wrap(box, 0, 4).int)
        assertEquals("pssh", String(box, 4, 4))
    }

    @Test
    fun `no widevine key means no pssh`() {
        val onlyFairPlay = """
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://itunes.apple.com/p1251000174/c22",KEYFORMAT="com.apple.streamingkeydelivery",KEYFORMATVERSIONS="1"
        """.trimIndent()
        assertNull(AppleHlsManifest.widevinePssh(onlyFairPlay))
        assertNull(AppleHlsManifest.widevinePssh(""))
    }

    @Test
    fun `license uri round trips through parseQuery`() {
        val pssh = "AAAAOHBzc2g+AAAA/123=="
        val uri = AppleWidevineDrmCallback.licenseUri("1193701392", pssh, isLibrary = false)
        assertTrue(uri.startsWith(AppleWidevineDrmCallback.LICENSE_ENDPOINT))
        val params = AppleWidevineDrmCallback.parseQuery(uri)
        assertEquals("1193701392", params["adamId"])
        assertEquals(pssh, params["pssh"])
        assertEquals("0", params["library"])

        val lib = AppleWidevineDrmCallback.parseQuery(
            AppleWidevineDrmCallback.licenseUri("42", "abc", isLibrary = true),
        )
        assertEquals("1", lib["library"])
        assertEquals("abc", lib["pssh"])
    }

    @Test
    fun `parseQuery tolerates junk`() {
        assertTrue(AppleWidevineDrmCallback.parseQuery("").isEmpty())
        assertTrue(AppleWidevineDrmCallback.parseQuery(AppleWidevineDrmCallback.LICENSE_ENDPOINT).isEmpty())
        assertTrue(AppleWidevineDrmCallback.parseQuery("http://x/y?novalue").isEmpty())
        assertEquals("v", AppleWidevineDrmCallback.parseQuery("http://x/y?a=v&b")["a"])
    }

    @Test
    fun `mediaUserToken is read out of a cookie or accepted bare`() {
        fun cb(cookie: String) = AppleWidevineDrmCallback(
            cookieProvider = { cookie },
            adamId = "1193701392",
            pssh = "data:text/plain;base64,AAAA",
        )
        assertEquals("TOKEN123", cb("foo=bar; media-user-token=TOKEN123; storefront=cn").mediaUserToken())
        assertEquals("TOKEN123", cb("TOKEN123").mediaUserToken())
        assertEquals("", cb("foo=bar; storefront=cn").mediaUserToken())
        assertEquals("", cb("").mediaUserToken())
    }

    @Test
    fun `variant url resolves against the master itself, not its parent segment`() {
        // 真实结构：.../52/<uuid>/P1511502822_lossless.m3u8，变体是相对引用。
        // 必须拿主清单自己的 URL 去 resolve；先 substringBeforeLast('/') 再 resolve 是错的——
        // 那串 UUID 只是路径段不是目录，最后一段会被换掉，拼出来少一层，CDN 404。
        // （踩过：手工字符串拼接 "$base/$uri" 是对的，代码用 URI.resolve 是错的，
        //  两边指向不同 URL，而 runCatching 把 404 吞了，症状是整曲永远退化成试听。）
        val master =
            "https://aod.itunes.apple.com/itunes-assets/HLSMusic221/v4/59/7d/52/597d52ca-1d85-ec27-553d-811384ab67bd/P1511502822_lossless.m3u8"
        val uri = "P1511502822_A6818209989_audio_en_gr256_mp4a-40-2.m3u8"
        val uuidSegment = "/597d52ca-1d85-ec27-553d-811384ab67bd/"

        val correct = java.net.URI(master).resolve(uri).toString()
        assertTrue("父目录段被吃掉了: $correct", correct.contains(uuidSegment))
        assertTrue(correct.endsWith(uri))

        val wrong = java.net.URI(master.substringBeforeLast('/')).resolve(uri).toString()
        assertTrue("这个断言该成立才能证明两种写法确实不同", !wrong.contains(uuidSegment))
    }

    private fun uuidAt(bytes: ByteArray, offset: Int): UUID =
        UUID(java.nio.ByteBuffer.wrap(bytes, offset, 8).long, java.nio.ByteBuffer.wrap(bytes, offset + 8, 8).long)

    /**
     * Apple 的 PSSH base64 经常缺 padding（init segment 那条就是 75 字符），
     * 和 Media3 用的 `android.util.Base64` 一样得宽容着解。
     */
    private fun base64Lenient(value: String): ByteArray {
        val padded = value.padEnd((value.length + 3) / 4 * 4, '=')
        return java.util.Base64.getDecoder().decode(padded)
    }
}

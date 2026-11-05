# Third-party notices

## music-lib

- Upstream: https://github.com/guohuiyuan/music-lib
- Revision included: 7cc4467b4e300547edd5d3d5bf0eff1c21ffc2ec
- License: GNU Affero General Public License v3.0
- Location: third_party/music-lib

The vendored Go source is retained as the reference for the Kotlin native port in `app/src/main/java/com/mytv/mymusic/data/native`. This includes the word-timing lyric conversions ported from `lyrics/lyrics.go` (`ParseLRC` / `ParseYRC` / `ParseQRC` / `ParseKRC` / `ConvertVerbatimLRC` → the verbatim-LRC output in `QQProvider.kt`, `KugouProvider.kt`, `NetEaseProvider.kt`). The Kotlin port is a derivative work distributed under AGPL-3.0. Modifications must retain this notice and the AGPL-3.0 terms.

## SodaM / libresoda

- Upstream: https://github.com/sodahub-org/sodam and https://github.com/sodahub-org/libresoda
- License: GNU Affero General Public License v3.0
- Usage: reference for the Soda Music signer service protocol (`POST /sign`, `GET /config`, `x-helios` / `x-medusa`, `X-SS-STUB`), the signed `POST /luna/pc/track_v2` stream flow, and the local spade / CENC AES-CTR decryption. The Kotlin implementations in `data/native/SodaProvider.kt` and `data/native/SodaDecrypt.kt` are derivative works distributed under AGPL-3.0.
- Default signer service: myMusic ships with SodaM's public signer instance as its default (`SodaProvider.DEFAULT_SIGNER_URL` / `DEFAULT_SIGNER_TOKEN`, the same instance the SodaM desktop client uses by default), used with the author's permission. The setting can be pointed at a self-hosted instance or switched off in `设置 › 平台账号 › 汽水播放签名器`.
- Brand asset: the SodaM logo in `app/src/main/res/drawable-*/ic_sodam_logo.png` and `docs/images/sodam-logo.png` is the upstream `crates/sodam/assets/brand/sodam-logo.svg`, used for the "Powered by SodaM" attribution in the app and README.
- QR login: the in-app Soda Music QR login bundles the security host page from libresoda's `tools/qishui-signer/security/` verbatim in `app/src/main/assets/soda/security/` (`security_host.html`, `sdk-glue.js`, `bdms.js`, `react.js`, `react-dom.js`, unmodified), plus `soda_verify.html`, an adaptation of libresoda's security host for the second-verification window (AGPL-3.0 derivative). `security_host.html` is libresoda's AGPL-3.0 code; `sdk-glue.js` and `bdms.js` are Soda Music's official web security SDK (obfuscated, redistributed as-is by libresoda); `react.js` / `react-dom.js` are React under MIT. They run inside WebViews given the `https://api.qishui.com/` origin (`data/native/SodaQrSigner.kt`); the passport request state machine in `data/native/SodaQrLogin.kt` is a Kotlin port of libresoda's `src/soda/qr_login.rs` (AGPL-3.0, derivative work).
- Play reporting: `data/native/SodaPlayReport.kt` and `data/playreport/PlayReportHub.kt` are Kotlin ports of the playback-report state machine and message construction in libresoda's `src/soda/report.rs` + sodam's `crates/sodam-core/src/play_report.rs` (AGPL-3.0, derivative works). Covered upstream behaviour: the applog channel (`POST https://mcs.zijieapi.com/v1/json`, events `audio_play` / `player_metrics(progressed)` / `audio_over` / `audio_finish` / `player_metrics(finish)`, `X-SS-STUB` = uppercase MD5 of the body, `params` and `header.custom` serialized as JSON strings), the play-count channel (`POST /luna/pc/action`, `action_type=play`), the recently-played write (`POST /luna/pc/me/recently-played-media`), and the `played_media` array sent with `/luna/pc/feed/song-tab` (the channel the official client uses to report listening time). Reporting is on by default and can be switched off in `设置 › 播放上报`.

## Other platforms' play reporting (protocol only)

The playback-report state machine in `data/playreport/BasePlayReporter.kt` is shared by all platforms; each platform's message construction was written against the protocols below. No upstream source code is bundled for these — only the HTTP contracts, reverse-engineered from the official clients' public web bundles:

- **NetEase Cloud Music** (`data/playreport/NetEasePlayReport.kt`): `POST https://clientlog.music.163.com/eapi/feedback/weblog`, eapi envelope (AES-128-ECB, key `e82ckenh8dichen8`), the `startplay` / `play` log actions, and the `os=osx` client identity. The eapi encryption reuses `NativeCrypto.encryptEApi`, already present for NetEase stream URLs. Reference: NeteaseCloudMusicApiEnhanced issue #167 and `GuitaristRin/Ncrust` (`player/PlayReporter.kt`, `network/crypto/EapiCrypto.kt`).
- **QQ Music** (`data/playreport/QQPlayReport.kt`): the play-duration image beacon `GET https://stat6.y.qq.com/pc/fcgi-bin/cgi_music_webreport.fcg` and the play-event report `POST https://stat6.y.qq.com/sdk/fcgi-bin/sdk.fcg`, reverse-engineered from `y.qq.com`'s player bundle.
- **Kuwo Music** (`data/playreport/KuwoPlayReport.kt`): the internal telemetry endpoint `POST https://log.kuwo.cn/music.yl`, reverse-engineered from `h5s.kuwo.cn`'s player bundle.
- **Qianqian / Taihe Music** (`data/playreport/QianqianPlayReport.kt`): the tracking pixel `GET https://click-qianqian.91q.com/v.gif`, reverse-engineered from `static1-qianqian.91q.com`'s player SDK bundle.
- **Migu Music and JOOX**: no publicly implementable play-report endpoint was found (Migu's upload goes through its UEM/Amber front-end monitoring platform with a non-public AES key; JOOX's goes through the Tencent Cloud Aegis SDK). Nothing is implemented for them.

All of these channels are best-effort: they carry no user-identifying data beyond what the platform already receives for the same account, they fail silently, and they can be switched off entirely in `设置 › 播放上报`.

## libmssdk (protocol only)

- Upstream: https://github.com/sodahub-org/libmssdk
- License: MIT License (the repository's own source); the official `bdms.node` / `metasecml.dll` binaries are proprietary and are not included or redistributed by myMusic.
- Usage: reference for the signer service HTTP contract and device identity (`/sign`, `/config`). No libmssdk code or binary is bundled.

## ncm-afp / NeteaseCloudMusicApi (NetEase song recognition)

- Upstream demo: https://github.com/c55403225/ncm-afp (the original mos9527/ncm-afp repository is no longer available)
- Bundled copies: `app/src/main/assets/songrec/afp.js` and `afp.wasm.js`, taken verbatim from NeteaseCloudMusicApi's `public/audio_match_demo/` (NeteaseCloudMusicApi: MIT License, https://github.com/Binaryify/NeteaseCloudMusicApi).
- License: the fingerprint JavaScript / WASM assets carry no explicit license declaration upstream. They are included unmodified for the NetEase song-recognition feature.
- Usage: computes the NetEase audio-match fingerprint (`algorithmCode=shazam_v2`) from exactly 3 seconds of 8 kHz mono PCM inside an off-screen WebView (`recognize/NeteaseFingerprinter.kt`). The matcher endpoint itself is an unofficial API reverse-engineered from NetEase's official browser extension.

## KuGouMusicApi / MoeKoeMusic (Kugou song recognition protocol)

- Upstream: https://github.com/MakcRe/KuGouMusicApi (MIT License), https://github.com/MoeKoeMusic/MoeKoeMusic
- Usage: reference for the `fingerprint.service/v1/music_trackid_mulit` request format (8 kHz 16-bit mono PCM body plus the Android signature). The Kotlin implementation in `data/native/KugouProvider.kt` (`matchAudio`) is written against the protocol; no upstream code or binary is bundled.

## Apple Music anonymous lyrics proxies

Apple Music no longer returns song lyrics to unauthenticated clients: `amp-api.music.apple.com`'s `include=lyrics` / `with=lyrics` / `extend=lyrics` no longer yields a `relationships.lyrics` resource, and the only remaining anonymous endpoint (`/v1/lyrics/{storefront}/snippet/songs/{id}`) requires a `tk` token that Apple's native apps mint server-side when sharing a lyrics snippet. Full lyrics require a `media-user-token`, i.e. a signed-in Apple Music account, which this app does not have.

`data/native/AppleProvider.kt` therefore has two paths. With a signed-in `media-user-token` it uses Apple's own lyrics sub-resource, `/v1/catalog/{storefront}/songs/{id}/syllable-lyrics` (then `/lyrics`), requested with `extend=ttmlLocalizations` and the `media-user-token` value sent **both** as a request header and as a `Cookie` — sending only the Cookie does not work. The storefront must match the catalogue the song's adam ID belongs to, so it is recorded on each `Song.extra["storefront"]` at search time, falling back to a `storefront=` field in the pasted Cookie and then to `us`. Without a usable token it falls back to community-operated services that relay lyrics using their own signed-in Apple Music accounts. Both paths return Apple's native word-by-word TTML (`itunes:timing="Word"`), which the existing `convertTtmlToLrc` in the same file turns into enhanced LRC — no third-party lyric text is stored or re-serialised by this app.

| Source | Endpoint | Notes |
| --- | --- | --- |
| zarz | `https://api.zarz.moe/apple-music/lyrics?id=<appleMusicId>&v=1` | JSON; TTML in `ttmlContent`. Primary: measured the most reliable of the three. |
| paxsenix | `https://lyrics.paxsenix.org/apple-music/lyrics?id=<appleMusicId>&ttml=true` | Raw TTML text with `ttml=true`. Frequently returns `503`/`502`; falls through when it does. |
| AMLL TTML DB | `https://api.amll.dev/v1/lyrics/get?appleMusicId=<appleMusicId>` | JSON; TTML in `data.lyrics`. Smallest catalogue (~3.4k entries) but the only one with published API docs and an OpenAPI schema. |

- Usage: tried in the order above, first non-empty result wins; they are only reached when Apple's own endpoint yields nothing (no token, expired token, or that track has no lyrics in the user's catalogue). The user can switch all three off in `设置 › 播放页 › Apple Music 歌词 › 使用第三方歌词中转` (`AppleLyricsConfig` / the `third_party_lyrics` preference); with it off, only Apple's own endpoint is tried and unauthenticated users get no lyrics.
- What leaves the device: the Apple Music song ID of the track currently being played, sent to whichever of the three services is tried. No username, cookie, media-user-token, playback URL or file content is sent.
- Apple's own `/v1/me/account?meta=subscription` is used to populate the account card (`AccountInfo.detail` = subscription state). Apple exposes neither an Apple ID display name nor an avatar to web clients, so the avatar stays empty and the card shows the provider icon. The same response's `meta.subscription.storefront` is cached and used as the default catalogue, so a non-US account is no longer forced onto the US storefront.
- Attribution: this relay approach, and the endpoint contracts above, follow the same pattern used by [Mornye](https://github.com/mornyeapp/Mornye) (`rust_backend/crates/providers/src/lyrics/builtin/apple.rs`) and [musicdl](https://github.com/CharlesPikachu/musicdl). No upstream source code is bundled in this repository.
- Stability: these are volunteer-run public services with no SLA and may rate-limit or disappear; the app treats any failure as "no lyrics for this track" rather than surfacing an error.

### Apple Music full-track playback (Widevine)

A song resource requested with `extend=extendedAssetUrls` carries an `extendedAssetUrls.enhancedHls` URL — a full-length HLS master manifest (measured: the complete track duration, with ALAC, Dolby Atmos, AAC 256k, AAC-HE 64k and binaural/downmix variants), available with only the anonymous developer token. The `.m4p` direct links in the same object are FairPlay-protected (`moov` contains `sinf` + `frma`), and every variant of the master manifest is triple-DRM `SAMPLE-AES`: `com.apple.streamingkeydelivery` (FairPlay, `skd://itunes.apple.com/…`), `com.microsoft.playready` and Widevine. There is no unencrypted path — `previews` is the only clear content, and it is 30–90 seconds.

Android can only satisfy Widevine of the three (FairPlay's Streaming SDK is licensed by Apple to approved partners only and has no public implementation; PlayReady's MediaDrm plugin is essentially absent from phones). The app therefore hands DRM to the device's own Widevine CDM through Media3: `DefaultDrmSessionManager` + a `MediaDrmCallback` (`playback/AppleWidevineDrm.kt`) that wraps the CDM's challenge in Apple's JSON envelope and POSTs it to `https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense`, decoding the base64 `license` field from the response. The `DrmConfiguration.licenseUri` carries `adamId` and the PSSH as query parameters because the callback needs them and `ExoMediaDrm.KeyRequest` does not expose them. The default developer token and the pasted Cookie's `media-user-token` are sent exactly as they are for `amp-api` requests — a missing or expired token makes Apple answer `failureType: 2002` ("Your session has ended"), which surfaces as a playback error and falls back to the preview. No Widevine device private key (`.wvd`) or equivalent is used, bundled or required; decryption happens inside the platform CDM.

Two details worth keeping if this is ever revisited:

- The media playlist carries **two** Widevine `#EXT-X-KEY` entries. The first, before `#EXT-X-MAP`, is the init-segment default and must be skipped — `data/native/AppleHlsManifest.kt` hardcodes it. That constant is a two-line string literal in the upstream source and is easy to transcribe one `A` short, which silently breaks the skip while leaving tests green when the fixture copies the same wrong value; `AppleHlsManifestTest` therefore pins its length and base64 validity.
- Only AAC tiers are selected (`audio-stereo-256` first, then 128k and HE 64k). ALAC would need the FairPlay wrapper, and Atmos (`ec-3`) decoders are not universal.

Attribution: the license-exchange envelope, the `data:text/plain;base64,<PSSH>` URI form passed as `uri`, and the default-key skip follow [gamdl](https://github.com/glomatico/gamdl) (`gamdl/api/apple_music.py`, `gamdl/interface/song.py`, `gamdl/interface/constants.py`). No upstream source code is bundled in this repository; only the endpoint contracts and the ordering of the flow were reused.

- Not verified on device: whether Apple's Widevine licence server accepts a generic Android device's CDM, and whether the resulting stream decrypts, could not be tested here. What *was* verified with a real signed-in account (2026-11-05): `/v1/me/account?meta=subscription` returns `active: true`; `/v1/play/assets?id=<adamId>&kind=song&includeLicenseUrls=true&hlsEncryption=CBC&hlsProfile=enhancedHls` returns `keyServerUrl` (matching the constant above), a `widevineKeyCertificateUrl` and a `url` pointing at a `…_lossy.m3u8` manifest that is structurally identical to the `enhancedHls` one; and the licence endpoint accepts the session — without a token it answers `failureType: 2002` ("Your session has ended"), with a valid token and a deliberately malformed challenge it answers `errorCode: -42585` / `status: -1021` instead, i.e. it gets past authentication and fails only on the challenge. The final step cannot be exercised here because the x86 emulator cannot instantiate a Widevine CDM at all (`MediaDrm` throws `UnsupportedSchemeException: NO_INIT`), so a physical device is required. The failure mode remains a playback error that falls back to the preview, i.e. no worse than before this was added.

## YesPlayMusic (lyrics background rhythm)

- Upstream: https://github.com/ijoji42/YesPlayMusic
- Reference revision: 347cf629a07340bae33a09ed0ae9ee502be27c1a
- License: MIT License
- Usage: the per-band automatic gain control, spectral-flux onset detection, refractory window and beat envelope in `playback/RhythmAnalysis.kt`, and the reactive lyrics backdrop design in `ui/player/LyricsBackdrop.kt`, are adapted from `src/utils/audioAnalyser.js` and `src/views/lyrics.vue`. The Android implementation taps ExoPlayer PCM instead of Web Audio. No upstream binaries, images or other assets are bundled.

```
MIT License

Copyright (c) 2020-2023 qier222

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## folia-major (poster wall)

- Upstream: https://github.com/chthollyphile/folia-major
- Revision checked: f96c65b27f7ca351e8db781301cedfa0a52252e2 (upstream HEAD on 2026-10-29)
- License: GNU Affero General Public License v3.0
- Location: `app/src/main/java/com/mytv/mymusic/ui/poster` (the `Wall*.kt` layout core and the poster UI built on it)
- Usage: the queue / collection poster wall adapts the layout mechanism of folia-major's Lattice view: 12×8-unit blocks holding 12 poster slots; four hand-drawn block templates × four mirrors, with the template chosen by a linear step over the block coordinates and the mirror by a coordinate hash; infinite tiling of a repeat cell of ceil(N/12) blocks; growing a tapped poster in place to 6×6 and reflowing only its own block with the minimum-movement exact cover; following the current track with the camera to the nearest copy; and D-pad navigation scored as gap + 2·miss + pitch. myMusic redrew the four templates, precomputes the 192 reflows offline (`WallReflowTable.kt`) and replaces the visual layer with Material 3 Expressive components. The code is a Kotlin rewrite, not a copy of the upstream TypeScript, and no upstream assets are bundled. It is a derivative work distributed under AGPL-3.0; modifications must retain this notice and the AGPL-3.0 terms.

## MaterialKolor

- Upstream: https://github.com/jordond/MaterialKolor
- Version: 6.0.0
- License: MIT License
- Usage: dynamic color schemes generated from the album cover (`ui/theme`).

## Haze

- Upstream: https://github.com/chrisbanes/haze
- Version: 2.0.1
- License: Apache License 2.0
- Usage: blur behind the mini player, navigation bar and bottom player bar.

## Music platform brand marks

The platform icons in `app/src/main/res/drawable/ic_provider_*.xml` are **monochrome vector adaptations of the platforms' real brand marks**. Their outlines are traced from the official application artwork previously sourced through Apple's public App Store Search API, with Jamendo's mark taken from the official Jamendo website icon (all retrieved 2026-11-05). The geometry is normalized to a shared 24 × 24 canvas, but the characteristic shapes, negative spaces and proportions are preserved rather than redesigned. QQ Music uses only its actual green-note silhouette (not the yellow app-icon backdrop), and Bilibili uses only the `bilibili` wordmark (not the temporary event caption).

`ui/components/ProviderIcon.kt` supplies a shared rounded container and tints each mark with Material theme roles, so the icons follow light/dark themes and dynamic color instead of carrying unrelated brand backgrounds.

The set includes NetEase Cloud Music, QQ Music, Kugou Music, Kuwo Music, Migu Music, Apple Music, Soda Music / 汽水音乐, Bilibili, JOOX, 5sing, Qianqian Music and Jamendo. The local-files mark is adapted from Google's Material Icons `library_music` (Apache License 2.0).

The platform names and referenced brand identities remain trademarks of their respective owners (网易云音乐 / QQ音乐 / 酷狗音乐 / 酷我音乐 / 咪咕音乐 / Apple Music / 汽水音乐 / 哔哩哔哩 / JOOX / 5sing / 千千音乐 / Jamendo) and are used solely to identify the corresponding services. myMusic is not affiliated with, endorsed by, or sponsored by any of these companies. SodaM is a separate project: its logo (`drawable-*/ic_sodam_logo.png`) is covered above.

Where a platform cannot be identified — for example a provider id or name added upstream after this file was written — `ProviderIcon` falls back to a matching tonal badge with the platform's short name, so no platform ever renders as a blank space.

## NanoHTTPD

- Upstream: https://github.com/NanoHttpd/nanohttpd
- Version: 2.3.1
- License: BSD 3-Clause License
- Usage: temporary loopback / LAN HTTP server for the TV Gist / Gitee setup panel (`data/sync/SyncSetupServer.kt`). The server runs only while that page is open in the foreground. All HTML, CSS and JavaScript are bundled locally; no NanoHTTPD example application or external web assets are included.

```
Copyright (c) 2012 - 2016, nanohttpd

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the nanohttpd nor the names of its contributors
   may be used to endorse or promote products derived from this software without
   specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Nayuki QR Code generator

- Upstream: https://github.com/nayuki/QR-Code-generator
- License: MIT License
- Location: `app/src/main/java/com/mytv/mymusic/ui/settings/QrCode.kt` (`QrEncoder`, `QrCodeImage`)
- Usage: reference implementation for the local QR encoder (byte mode, error-correction level M, versions 1–40, Reed–Solomon ECC, mask selection). The Kotlin port is a derivative work distributed under AGPL-3.0. It renders the login QR codes, the project / Telegram QR codes on the About screen, and the temporary TV sync-setup address, so no QR image has to be fetched from a third-party service.

## Roboto Flex

- Upstream: https://github.com/googlefonts/roboto-flex (via https://github.com/google/fonts/tree/main/ofl/robotoflex)
- License: SIL Open Font License, Version 1.1
- Location: `app/src/main/res/font/roboto_flex.ttf`
- Modifications: subset to Latin, Latin-1, general punctuation and currency symbols; all variation axes except `wght` are pinned to their defaults (fontTools `subset` + `varLib.instancer`). The Android TV launcher banner (`res/drawable/tv_banner.xml`) embeds `myMusic` glyph outlines converted to vector paths from the same subset.

```
Copyright 2017 The Roboto Flex Project Authors (https://github.com/TypeNetwork/Roboto-Flex)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
http://scripts.sil.org/OFL


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

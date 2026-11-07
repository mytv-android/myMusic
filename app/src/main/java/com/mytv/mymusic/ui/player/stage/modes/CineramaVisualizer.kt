package com.mytv.mymusic.ui.player.stage.modes

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.ui.player.stage.LyricSetting
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import com.mytv.mymusic.ui.player.stage.LyricVisualizer
import com.mytv.mymusic.ui.player.stage.ModOrigin
import com.mytv.mymusic.ui.player.stage.SettingValues
import com.mytv.mymusic.ui.player.stage.formatPercent
import kotlin.math.max
import kotlin.math.min

/**
 * 巨幕（Folia 模组 cinerama 1.0.0，作者 tumuyan，AGPL-3.0）。整页像一块 LED 大屏：屏上一层细扫描线、四周一圈暗角，
 * 每句歌词按歌曲种子抽一种版式放映，相邻两句不重复同一种——
 * - 大字报：按词把一句切成至多 5 块，一块一块在屏心闪出（淡入 0.1 秒、自己的词唱完前淡出），块按屏宽能放多大就放多大，
 *   带 0.95 / 1 / 1.05 的字号起伏、三成多是斜体；多数块边闪边推近、拉远、横移、竖移或摊开字距，
 *   字多的块有一成多的机会“先描边、再按块内进度从左到右或从上到下填色”；
 * - 小字报：整句按切分方式（整句、词、分句、逐字、对半）拆成单元，单元依次浮起点亮，落在居中、上三分、下三分、
 *   左右侧带或宽银幕底部之一；
 * - 斜切丝带：6 条强调色的斜带横穿整屏（左右轮流倾斜，平角不超过三分之一），带身有斜向光泽、外发光和投影，
 *   主题底色的粗体字沿带子往左走，整条再上下慢慢漂。
 * 大字报和小字报的句子还会配跑马灯带：上下两条（或沿屏四边一圈）滚动原文、翻译或歌名，带子朝屏心那条边是一道强调色的灯管辉光。
 * 句与句之间是溶解转场（0.14–0.28 秒）：上一句的带子先淡出、这一句的带子后淡入；不紧邻的句子在行窗口末尾自己溶解退场。
 *
 * 移植取舍：
 * - 版式、排版、切分、跑马灯形态和取材、大字报分块与闪出时序、丝带几何都照原版的抽签和数值（见 CineramaProgram.kt），
 *   时间用歌词时间，seek、暂停、重建都得到同一帧。原版排版轴把字符串种子喂给 hash01，每句都掷到同一个点（只在左右两条侧带之间来回），
 *   这里先哈希成数再掷，落点按句变化。三个版式权重都为 0 时按等权（原版退成全小字报）。
 * - “屏”是安全区：原版按 vh / vw 定的尺寸都按安全区的宽高折算，CSS px 按屏短边 720 折算；带厚取屏短边 12%，
 *   但不超过正文基准字号的 1.6 倍（竖屏手机、桌面姿态的矮舞台上带子不会粗得占掉一大截）；小字报字号再按文字盒宽限到一行至少 7 个字。
 *   宿主副行（翻译 / 罗马音）让到跑马灯带上面（[subtitleInset]），正文和丝带都不压到副行那一条。
 *   丝带切在屏边上，四边各渐隐一小段（原版切在窗口边，看不出硬边）；频谱卡片开着时宿主把屏底让出卡片那一截。
 * - 只做原版的暗色主题那一套（播放页是深色）：带边辉光、丝带外发光；亮色主题的“暗槽”画法不做。屏面填充让位给宿主背景，只留扫描线和暗角。
 * - 原版每层文字是 CSS 多重 text-shadow（14px 暗投影 + 30px 同色辉光），这里只保留同色辉光（Shadow，浓度量化缓存）；
 *   带上字的那层很淡的光照留，暗投影不画。盒阴影（带边灯管、丝带外发光与投影、暗角）按高斯尾巴（erfc）取样成渐变，所有版本都能画。
 * - 扫描线是一张 1 × 3dp 的平铺小图（ImageShader），一次 drawRect；四边环的字用 drawTextOnPath，路径连走两圈，任意起点都排得满一整圈、没有接缝。
 * - 大字报“摊开”的那档要逐帧改字距，按字分别量好、逐字摆；描边用 Stroke 画字形，填色用裁切。
 * - 原版行窗口用宿主的 renderEndTime（句末加一段视觉尾巴），这里按句末 + 0.6 秒，夹到下一句开口。
 * - 原版两句都是丝带时有“丝带接力”（部分丝带留下、其余撕开飞走、新丝带飞入）；因为相邻不重复，只有把另两种权重都拖到 0 才会出现，
 *   这里按普通溶解转场处理。
 * - 原模组 30 项设置，面板放 6 项；其余（带厚 12%、带内字高 0.55、辉光 0.3、歌名概率 0.25、出字节奏 0.6、斜体 0.35、
 *   丝带 6 条 / 角度 1 / 漂移 1、大字报运动 1 等）按原默认值写死。字号设置的范围放宽到 0.6–1.6（原版 0.8–1.3），和其他模式一致。
 * - 减少动态效果：当前句按抽到的版式整句定格——大字报所有块排成一张填完色的海报，小字报全部点亮，丝带不漂不走，带子静止；
 *   扫描线和暗角照画。原版没有待机画面，没有歌词时交给宿主。
 */
object CineramaVisualizer : LyricVisualizer {
    override val id = "cinerama"
    override val label = "巨幕"
    override val origin = ModOrigin("cinerama", "1.0.0", "tumuyan", "AGPL-3.0")
    override val order = 60
    override val hostBackground = true
    override val hostSubtitles = true
    override val wantsAudio = false
    override val drawsWithoutLyrics = false
    override val hiddenSettings = 24

    override val settings: List<LyricSetting> = listOf(
        LyricSetting.Choice(
            "marquee",
            "跑马灯带",
            listOf("off" to "关", "auto" to "自动", "bands" to "双带", "frame" to "四边"),
            "auto",
        ),
        LyricSetting.Number("heroWeight", "大字报", 0f, 1f, 0.05f, 0.4f, ::formatPercent, summary = "三种版式的出现比例"),
        LyricSetting.Number("smallWeight", "小字报", 0f, 1f, 0.05f, 0.2f, ::formatPercent),
        LyricSetting.Number("ribbonWeight", "斜切丝带", 0f, 1f, 0.05f, 0.4f, ::formatPercent),
        LyricSetting.Number("fontScale", "字号", 0.6f, 1.6f, 0.05f, 1f),
        LyricSetting.Number("marqueeSpeed", "带速", 0.2f, 3f, 0.1f, 1f),
    )

    /**
     * 跑马灯带占着安全区底部：宿主副行（本来在安全区底部往上 14dp）让到带子上方带厚 × 1.25 处。
     * 频谱卡片开着时宿主已经把安全区底部让出卡片那一截，带子和副行都跟着上移，这里不用另算。
     */
    override fun subtitleInset(ctx: LyricStageContext, stage: Size, density: Density): Float =
        if (ctx.settings.choice("marquee") == "off") 0f else with(density) {
            val band = cineramaBand(ctx.display.safeRect(stage), ctx.display.textSp.sp.toPx())
            max(0f, band * CINERAMA_CLEARANCE - SUBTITLE_GAP_DP.dp.toPx())
        }

    @Composable
    override fun Stage(ctx: LyricStageContext, modifier: Modifier) {
        val measurer = rememberTextMeasurer(cacheSize = 64)
        val config = remember(ctx.settings) { cineramaConfig(ctx.settings) }
        val program = remember(ctx.lines, ctx.seed, ctx.song.name, config) {
            cineramaCompile(ctx.lines, ctx.seed.toString(), ctx.song.name, config.weights, config.marquee)
        }
        val state = remember(program) { CineramaState() }
        Spacer(
            modifier.drawWithCache {
                val display = ctx.display
                val safe = display.safeRect(size)
                val band = cineramaBand(safe, display.textSp.sp.toPx())
                // 副行实际抬多少：让带子（和 subtitleInset 同一个算法）
                val inset = subtitleInset(ctx, size, this)
                val subtitleBand = SUBTITLE_GAP_DP.dp.toPx() + inset + (display.textSp * SUBTITLE_LINES).sp.toPx()
                val geometry = CineramaGeometry(
                    page = size,
                    screen = safe,
                    band = band,
                    unit = max(0.25f, min(safe.width, safe.height) / CSS_REFERENCE),
                    pageUnit = max(0.25f, min(size.width, size.height) / CSS_REFERENCE),
                    subtitleBand = subtitleBand,
                )
                val still = display.reduceMotion
                val scene = CineramaScene(program, measurer, ctx.colors, config, geometry, still, display.countScale, ctx.subline, this)
                onDrawBehind {
                    scene.drawChrome(this)
                    if (program.plans.isEmpty()) return@onDrawBehind
                    val t = ctx.clock.ms / 1000.0
                    val frame = state.frame
                    cineramaEvaluate(program.starts, program.ends, t, still, still && ctx.lineIndex.value < 0, frame)
                    if (frame.outgoing >= 0) scene.drawPlan(this, frame.outgoing, t, frame.out, state)
                    if (frame.current >= 0) scene.drawPlan(this, frame.current, t, frame.now, state)
                }
            },
        )
    }
}

private fun cineramaConfig(s: SettingValues) = CineramaConfig(
    marquee = s.choice("marquee"),
    weights = CineramaWeights(s.number("heroWeight"), s.number("smallWeight"), s.number("ribbonWeight")),
    fontScale = s.number("fontScale"),
    speed = s.number("marqueeSpeed"),
)

/** 跑马灯带厚（px）：安全区短边的 12%，但不超过正文基准字号的 1.6 倍。 */
private fun cineramaBand(safe: Rect, textPx: Float): Float =
    max(1f, min(CINERAMA_BAND_RATIO * min(safe.width, safe.height), textPx * CINERAMA_BAND_TEXT))

/** 宿主副行离安全区底部的距离（dp），和宿主一致。 */
private const val SUBTITLE_GAP_DP = 14f

/** 副行（字号 0.55 倍、行高 1.35 倍）按两行留：0.55 × 1.35 × 2。 */
private const val SUBTITLE_LINES = 1.5f

/** 原模组按 1280 × 720 的屏写的 px：屏短边 720 折一个 CSS px。 */
private const val CSS_REFERENCE = 720f

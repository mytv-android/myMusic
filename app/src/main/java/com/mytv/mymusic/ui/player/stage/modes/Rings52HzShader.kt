package com.mytv.mymusic.ui.player.stage.modes

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush

/**
 * 52Hz 的年轮着色器（Android 13+，AGSL），一次 drawRect 铺满舞台。照 rippleFilter.mjs 的着色逻辑，
 * 只把“距离场纹理 + 历史纹理”换成解析计算：
 *
 * - 距离场：每个槽位（一句歌词或一个髓心）是一串元素，歌词是轴对齐圆角矩形（每个字或每个拉丁词一个），
 *   髓心是闭合多边形的边。像素到槽位的距离 = 到这些元素并集的距离（多边形用射线奇偶判断内外），
 *   歌词再减去 0.55 倍字号的留白，留白里不出年轮。
 * - 年轮：原版按“距离 ÷ 速度 = 年龄”去查历史，这里每圈年轮直接给半径、半线宽、强度、底色，
 *   效果一样：线宽 0.5–2.4dp 随响度，强度 0.25 + 0.75 × 响度，两圈之间铺响度² × 0.05 的底色。
 * - 木纹：两层值噪声让年轮离字越远越歪（最多 ±11%，600dp 封顶），在空间里固定，所以每圈歪得一样。
 * - 按距离淡出（(1 − d/半对角线)^1.8），贴近留白边缘的 24dp 渐显；颜色由近处的强调色过渡到远处的文字色。
 *
 * uniform 布局（数组长度固定）：
 * - uElem[64]：元素，矩形是 (cx, cy, 半宽, 半高)，多边形边是 (x0, y0, x1, y1)；按槽位连续存。
 * - uSlotEnd / uSlotKind / uSlotCorner / uSlotGap / uSlotMax：4 个槽位各占一个分量，分别是元素的累计终止下标、
 *   种类（0 矩形、1 多边形、−1 不用）、圆角、留白、最外圈半径（含线宽，用来跳过够不着的像素）；uSlotBox[4] 是外接框。
 * - uRing[32]：(半径, 半线宽, 线强度, 底色)，按槽位分组、组内从外到内，uRingEnd 是各槽位的累计终止下标。
 * - uLook：(淡出距离, 浓度, 每 dp 像素数, 元素总数)；uNear、uFar 是近、远处的颜色。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class RingsShader private constructor(private val shader: RuntimeShader) {
    val brush = ShaderBrush(shader)

    fun update(u: RingUniforms, fadeDistance: Float, opacity: Float, dp: Float, near: Color, far: Color) {
        shader.setFloatUniform("uElem", u.elem)
        shader.setFloatUniform("uSlotEnd", u.slotEnd)
        shader.setFloatUniform("uSlotKind", u.slotKind)
        shader.setFloatUniform("uSlotCorner", u.slotCorner)
        shader.setFloatUniform("uSlotGap", u.slotGap)
        shader.setFloatUniform("uSlotMax", u.slotMax)
        shader.setFloatUniform("uSlotBox", u.slotBox)
        shader.setFloatUniform("uRingEnd", u.ringEnd)
        shader.setFloatUniform("uRing", u.ring)
        shader.setFloatUniform("uLook", fadeDistance, opacity, dp, u.elemCount.toFloat())
        shader.setFloatUniform("uNear", near.red, near.green, near.blue)
        shader.setFloatUniform("uFar", far.red, far.green, far.blue)
    }

    companion object {
        /** 编译失败（个别驱动）时返回 null，调用方退回兜底画法。 */
        fun createOrNull(): RingsShader? = try {
            RingsShader(RuntimeShader(RINGS_AGSL))
        } catch (_: RuntimeException) {
            null
        }
    }
}

private const val RINGS_AGSL = """
uniform float4 uElem[64];
uniform float4 uSlotEnd;
uniform float4 uSlotKind;
uniform float4 uSlotCorner;
uniform float4 uSlotGap;
uniform float4 uSlotMax;
uniform float4 uSlotBox[4];
uniform float4 uRingEnd;
uniform float4 uRing[32];
uniform float4 uLook;
uniform float3 uNear;
uniform float3 uFar;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

float valueNoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}

float boxDist(float2 p, float4 b, float r) {
    float rr = min(r, min(b.z, b.w));
    float2 k = abs(p - b.xy) - b.zw + rr;
    return length(max(k, 0.0)) + min(max(k.x, k.y), 0.0) - rr;
}

half4 main(float2 p) {
    float fadeD = uLook.x;
    float dp = uLook.z;
    // 外接框减留白是到槽位距离的下界：木纹最多往里歪 11%，还比最外圈远就整个槽位跳过
    float4 low = float4(boxDist(p, uSlotBox[0], 0.0), boxDist(p, uSlotBox[1], 0.0),
                        boxDist(p, uSlotBox[2], 0.0), boxDist(p, uSlotBox[3], 0.0)) - uSlotGap;
    float4 live = step(float4(-0.5), uSlotKind) * step(low * 0.89, uSlotMax);
    if (dot(live, float4(1.0)) < 0.5) return half4(0.0);

    float4 starts = float4(0.0, uSlotEnd.xyz);
    // “够不着”的哨兵值取 2 万像素（4K 屏对角线也才 4400）：不能用 1e9，32 位浮点在 1e9 附近的精度是 64，
    // 驱动把 d + (1 − hot) × 哨兵 重排成 (d + 哨兵) − hot × 哨兵 后距离场会变成 64 像素一级的台阶，细线全丢
    float4 dmin = float4(20000.0);
    float4 crossings = float4(0.0);
    for (int i = 0; i < 64; i++) {
        float fi = float(i);
        if (fi >= uLook.w) break;
        float4 hot = step(starts, float4(fi)) * (1.0 - step(uSlotEnd, float4(fi)));
        if (dot(hot, live) < 0.5) continue;
        float4 e = uElem[i];
        float d = 0.0;
        if (dot(hot, uSlotKind) < 0.5) {
            d = boxDist(p, e, dot(hot, uSlotCorner));
        } else {
            float2 a = e.xy;
            float2 b = e.zw;
            float2 pa = p - a;
            float2 ba = b - a;
            float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-4), 0.0, 1.0);
            d = length(pa - ba * h);
            if ((a.y > p.y) != (b.y > p.y)) {
                if (p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) crossings += hot;
            }
        }
        dmin = min(dmin, float4(d) + (1.0 - hot) * 20000.0);
    }

    float4 inside = step(0.5, mod(crossings, 2.0)) * step(0.5, uSlotKind);
    float4 raw = mix(dmin - uSlotGap, float4(-1.0), inside);
    float2 g = p / (260.0 * dp);
    float grain = valueNoise(g) * 0.7 + valueNoise(g * 2.3 + 7.1) * 0.3 - 0.5;
    float4 dist = raw + grain * min(max(raw, 0.0), float4(600.0 * dp)) * 0.22;
    float4 fade = pow(clamp(1.0 - dist / fadeD, 0.0, 1.0), float4(1.8))
        * smoothstep(0.0, 24.0 * dp, raw) * live * step(0.0, raw);

    float4 rstarts = float4(0.0, uRingEnd.xyz);
    float total = 0.0;
    float prevR = 0.0;
    for (int i = 0; i < 32; i++) {
        float fi = float(i);
        if (fi >= uRingEnd.w) break;
        float4 hot = step(rstarts, float4(fi)) * (1.0 - step(uRingEnd, float4(fi)));
        float4 r = uRing[i];
        if (dot(hot, rstarts) == fi) prevR = r.x;
        float f = dot(hot, fade);
        if (f > 0.0) {
            float d = dot(hot, dist);
            float line = 1.0 - smoothstep(r.y, r.y + 1.2 * dp, abs(d - r.x));
            float band = (d >= r.x && d < prevR) ? r.w : 0.0;
            total += (line * r.z + band) * f;
        }
        prevR = r.x;
    }
    if (total <= 0.0) return half4(0.0);

    float4 near = mix(float4(20000.0), dist, step(1e-6, fade));
    float nearest = min(min(near.x, near.y), min(near.z, near.w));
    float alpha = (1.0 - exp(-total * 1.6)) * uLook.y;
    float3 color = mix(uNear, uFar, clamp(nearest / fadeD * 1.6, 0.0, 1.0));
    return half4(half3(color * alpha), half(alpha));
}
"""

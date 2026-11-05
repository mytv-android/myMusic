package com.mytv.mymusic.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.R
import com.mytv.mymusic.ui.providerMark

/**
 * 平台图标：24dp 画板上的单色轮廓，统一放在圆角色块里。
 * 普通状态跟随 surfaceContainerHighest / onSurfaceVariant，[active] 跟随 primaryContainer；
 * 不使用各平台 App 图标的渐变和材质，避免与动态配色打架。
 */

@DrawableRes
fun providerIcon(id: String): Int? = when (id) {
    "netease" -> R.drawable.ic_provider_netease
    "qq" -> R.drawable.ic_provider_qq
    "kugou" -> R.drawable.ic_provider_kugou
    "kuwo" -> R.drawable.ic_provider_kuwo
    "migu" -> R.drawable.ic_provider_migu
    "apple" -> R.drawable.ic_provider_apple
    "soda" -> R.drawable.ic_provider_soda
    "bilibili" -> R.drawable.ic_provider_bilibili
    "joox" -> R.drawable.ic_provider_joox
    "fivesing" -> R.drawable.ic_provider_fivesing
    "qianqian" -> R.drawable.ic_provider_qianqian
    "jamendo" -> R.drawable.ic_provider_jamendo
    "yunting" -> R.drawable.ic_provider_yunting
    "local" -> R.drawable.ic_provider_local
    else -> null
}

/** 只有平台名时（歌的来源字段、没拿到 ProviderInfo 的调用点）按关键词认图标。 */
@DrawableRes
fun providerIconByName(name: String): Int? = when {
    name.contains("网易") -> R.drawable.ic_provider_netease
    name.contains("QQ", ignoreCase = true) -> R.drawable.ic_provider_qq
    name.contains("酷狗") -> R.drawable.ic_provider_kugou
    name.contains("酷我") -> R.drawable.ic_provider_kuwo
    name.contains("咪咕") -> R.drawable.ic_provider_migu
    name.contains("Apple", ignoreCase = true) -> R.drawable.ic_provider_apple
    name.contains("汽水") -> R.drawable.ic_provider_soda
    name.startsWith("Bili", ignoreCase = true) -> R.drawable.ic_provider_bilibili
    name.contains("JOOX", ignoreCase = true) -> R.drawable.ic_provider_joox
    name.contains("5sing", ignoreCase = true) -> R.drawable.ic_provider_fivesing
    name.contains("千千") -> R.drawable.ic_provider_qianqian
    name.contains("Jamendo", ignoreCase = true) -> R.drawable.ic_provider_jamendo
    name.contains("云听") -> R.drawable.ic_provider_yunting
    else -> null
}

/** 先按 id 认，id 不在表里再按名字认。 */
@DrawableRes
fun providerIconFor(id: String?, name: String): Int? =
    id?.takeIf { it.isNotBlank() }?.let(::providerIcon) ?: providerIconByName(name)

/**
 * 平台头像。有图标就显示图标（圆角裁剪，[active] 时加一圈 primary 描边）；
 * 认不出平台时退回原来的圆形底 + 简称，新平台上线也不会出现空位。
 */
@Composable
fun ProviderIcon(
    id: String? = null,
    name: String = "",
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    corner: Dp = size * 0.28f,
) {
    val shape = RoundedCornerShape(corner)
    val container = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val foreground = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val icon = providerIconFor(id, name)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(container)
            .then(if (active) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            // 图标只是头像，行里另有平台全称，读屏不念
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(size * 0.70f),
                colorFilter = ColorFilter.tint(foreground),
            )
        } else {
            val mark = providerMark(name.ifBlank { id.orEmpty() })
            Text(
                mark,
                style = if (mark.length > 2) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = foreground,
                maxLines = 1,
            )
        }
    }
}

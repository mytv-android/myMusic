package com.mytv.mymusic.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.R
import com.mytv.mymusic.ui.providerMark

/**
 * 平台图标：优先用平台自己的标志，认不出来时才退回圆形底 + 简称。
 * 图标自带品牌色和圆角，所以不再套底色；[active] 只用一圈 primary 描边表示选中 / 已登录。
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
    val icon = providerIconFor(id, name)
    if (icon != null) {
        Image(
            painter = painterResource(icon),
            // 图标只是头像，行里另有平台全称，读屏不念
            contentDescription = null,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(corner))
                .then(
                    if (active) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(corner)) else Modifier,
                )
                .clearAndSetSemantics {},
        )
        return
    }
    val mark = providerMark(name.ifBlank { id.orEmpty() })
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            mark,
            Modifier.clearAndSetSemantics {},
            style = if (mark.length > 2) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

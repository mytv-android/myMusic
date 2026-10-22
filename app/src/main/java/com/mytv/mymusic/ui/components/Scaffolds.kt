package com.mytv.mymusic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 页面骨架。手机和平板用 Large flexible app bar（带副标题，滚动后收成小标题）；
 * 电视没有滚动收起，用一行大标题，右侧放操作。
 * content 收到的 PaddingValues 包含顶栏、系统栏和底部浮层（迷你播放器、导航栏），列表直接用作 contentPadding。
 * 左右留白不在里面：横向滚动的卡片行要贴边，其余内容自己加 LocalAppWindow.current.gutter。
 */
@Composable
fun PageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    large: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val window = LocalAppWindow.current
    val overlay = LocalOverlayPadding.current
    if (window.isTv) {
        Column(modifier.fillMaxSize().semantics { paneTitle = title }) {
            TvPageHeader(title, subtitle, onBack, actions)
            Box(Modifier.weight(1f)) {
                content(PaddingValues(bottom = 48.dp))
            }
        }
        return
    }
    val scroll = if (large) {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    } else {
        TopAppBarDefaults.pinnedScrollBehavior()
    }
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surface,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    val navigation: @Composable () -> Unit = {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.tvFocusRing(CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
        }
    }
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection).semantics { paneTitle = title },
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = floatingActionButton,
        topBar = {
            if (large) {
                LargeFlexibleTopAppBar(
                    // M3 顶栏不给标题加 heading，读屏按标题跳转时找不到页面标题
                    title = { Text(title, Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    subtitle = if (subtitle.isNotBlank()) ({ Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
                    navigationIcon = navigation,
                    actions = actions,
                    colors = colors,
                    scrollBehavior = scroll,
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (subtitle.isNotBlank()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    navigationIcon = navigation,
                    actions = actions,
                    colors = colors,
                    scrollBehavior = scroll,
                )
            }
        },
    ) { inner ->
        val direction = LocalLayoutDirection.current
        content(
            PaddingValues(
                start = inner.calculateStartPadding(direction),
                end = inner.calculateEndPadding(direction),
                top = inner.calculateTopPadding(),
                bottom = inner.calculateBottomPadding() + overlay.calculateBottomPadding() + 24.dp,
            ),
        )
    }
}

@Composable
private fun TvPageHeader(title: String, subtitle: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit) {
    val window = LocalAppWindow.current
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = window.gutter - if (onBack != null) 12.dp else 0.dp, end = window.gutter, top = 28.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.tvFocusRing(CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

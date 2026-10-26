package com.mytv.mymusic.ui.charts

/**
 * 电视 Hero 按焦点 key 从最新数据里查找对象，避免焦点持有旧对象导致回调/文字过期。
 * 焦点项不在任何一组里时，回退到 [fallbackGroups] 的第一个非空项（默认与查找顺序相同）。
 */
internal fun <T> findFocusedHero(
    focusedKey: String?,
    groups: List<List<T>>,
    fallbackGroups: List<List<T>> = groups,
    keyOf: (T) -> String,
): T? {
    if (focusedKey != null) {
        groups.forEach { items ->
            items.firstOrNull { keyOf(it) == focusedKey }?.let { return it }
        }
    }
    fallbackGroups.forEach { items ->
        items.firstOrNull()?.let { return it }
    }
    return null
}

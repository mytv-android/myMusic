package com.mytv.mymusic.ui.poster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PosterWallStateTest {
    private var now = 0L
    private val phoneView = WallViewport(360f, 800f, insetTop = 84f, insetBottom = 92f)
    private val tvView = WallViewport(960f, 540f, insetTop = 80f)

    private fun phone(current: Int, n: Int = 30, follow: Boolean = true, reduceMotion: Boolean = false) = WallConfig(
        metrics = WallMetrics.Phone,
        field = fieldFor(n),
        current = current,
        follow = follow,
        isTv = false,
        drift = false,
        reduceMotion = reduceMotion,
        appending = false,
        insetTop = phoneView.insetTop,
        insetBottom = phoneView.insetBottom,
    )

    private fun tv(current: Int, drift: Boolean = true, n: Int = 30) = WallConfig(
        metrics = WallMetrics.Tv,
        field = fieldFor(n),
        current = current,
        follow = true,
        isTv = true,
        drift = drift,
        reduceMotion = false,
        appending = false,
        insetTop = tvView.insetTop,
    )

    private fun wall(c: WallConfig, view: WallViewport): PosterWallState {
        val s = PosterWallState(Random(3)) { now }
        s.configure(c)
        s.setViewportSize(view.width, view.height)
        return s
    }

    /** 把镜头弹簧走完。 */
    private fun PosterWallState.settle() {
        repeat(120) { tick(1f / 60) }
    }

    // ------------------------------------------------------------------ 纯函数

    @Test
    fun metricsDependOnTheWindowShortSide() {
        assertEquals(WallMetrics.Phone, wallMetricsFor(false, 360, 800))
        assertEquals(WallMetrics.Tablet, wallMetricsFor(false, 800, 1280))
        assertEquals(WallMetrics.Tablet, wallMetricsFor(false, 1366, 1024))
        // 横屏平板 800 高：606 的大卡加上下浮层和播放条放不下，用 451 的
        assertEquals(WallMetrics.ShortTablet, wallMetricsFor(false, 1280, 800))
        // 600dp 宽算平板，但放不下 606 的大卡
        assertEquals(WallMetrics.ShortTablet, wallMetricsFor(false, 600, 960))
        assertEquals(WallMetrics.Phone, wallMetricsFor(false, 800, 400))
        assertEquals(WallMetrics.Phone, wallMetricsFor(false, 915, 412))
        assertEquals(WallMetrics.Tv, wallMetricsFor(true, 960, 540))
    }

    @Test
    fun initialCameraPutsTheFirstSlotTopLeft() {
        assertEquals(WallPoint(-12f, -88f), initialCamera(WallMetrics.Phone, phoneView))
        // 平板一块 1224 × 816 比 800 高：水平居中、顶到浮层下沿
        assertEquals(WallPoint(-28f, -0f), initialCamera(WallMetrics.Tablet, WallViewport(1280f, 800f)))
        assertEquals(WallPoint(-24f, -80f), initialCamera(WallMetrics.Tv, tvView))
        // 电视详情页让出导航栏后比一块（912）窄：不居中，第 1 位左边留 12dp
        assertEquals(-12f, initialCamera(WallMetrics.Tv, WallViewport(872f, 540f, insetTop = 80f)).x)
        // 块比可用区矮：竖直居中
        assertEquals(WallPoint(-28f, -(40f + 112f)), initialCamera(WallMetrics.Tablet, WallViewport(1280f, 1080f, insetTop = 40f, insetBottom = 0f)))
    }

    @Test
    fun cullCellFloorsNegativeCoordinates() {
        assertEquals(0, cullCell(0f, 56f))
        assertEquals(0, cullCell(55.9f, 56f))
        assertEquals(1, cullCell(56f, 56f))
        assertEquals(-1, cullCell(-0.1f, 56f))
        assertEquals(-2, cullCell(-56.1f, 56f))
    }

    @Test
    fun culledTilesCoverEveryCameraInTheCell() {
        val random = Random(11)
        for (m in WallMetrics.entries) {
            val scene = WallScene(m, fieldFor(37), grown = SlotPos(0, 0, 5))
            val view = WallViewport(960f, 540f)
            repeat(200) {
                val cam = WallPoint(random.nextFloat() * 4000f - 2000f, random.nextFloat() * 4000f - 2000f)
                val culled = scene.cullTiles(cullCell(cam.x, m.pitch), cullCell(cam.y, m.pitch), view).map { it.pos }.toSet()
                for (t in scene.visibleTiles(cam, view)) assertTrue("$m $cam ${t.pos}", t.pos in culled)
            }
        }
    }

    @Test
    fun fieldChangeTellsAppendFromReset() {
        val band = feedFor(WallMetrics.Phone, 15)
        assertEquals(FieldChange.Same, fieldChange(band, feedFor(WallMetrics.Phone, 15)))
        assertEquals(FieldChange.Append, fieldChange(band, feedFor(WallMetrics.Phone, 30)))
        assertEquals(FieldChange.Reset, fieldChange(band, feedFor(WallMetrics.Phone, 10)))
        assertEquals(FieldChange.Reset, fieldChange(band, feedFor(WallMetrics.Tv, 30)))
        assertEquals(FieldChange.Reset, fieldChange(band, fieldFor(15)))
        assertEquals(FieldChange.Reset, fieldChange(fieldFor(15), fieldFor(16)))
        assertEquals(FieldChange.Same, fieldChange(fieldFor(15), fieldFor(15)))
    }

    @Test
    fun idleWaitIsTheNextThreshold() {
        assertEquals(6.0, idleWaitSeconds(isTv = true, chromeIdle = false, idleSeconds = 0.0, driftEnabled = true, drifting = false)!!, 1e-9)
        assertEquals(26.0, idleWaitSeconds(isTv = true, chromeIdle = true, idleSeconds = 4.0, driftEnabled = true, drifting = false)!!, 1e-9)
        assertEquals(2.0, idleWaitSeconds(isTv = true, chromeIdle = false, idleSeconds = 4.0, driftEnabled = false, drifting = false)!!, 1e-9)
        assertNull(idleWaitSeconds(isTv = true, chromeIdle = true, idleSeconds = 40.0, driftEnabled = true, drifting = true))
        assertNull(idleWaitSeconds(isTv = false, chromeIdle = false, idleSeconds = 0.0, driftEnabled = false, drifting = false))
        assertEquals(0.0, idleWaitSeconds(isTv = true, chromeIdle = false, idleSeconds = 9.0, driftEnabled = false, drifting = false)!!, 1e-9)
    }

    // ------------------------------------------------------------------ 状态机

    @Test
    fun opensGrownAndCentredOnTheCurrentSong() {
        val s = wall(phone(current = 4), phoneView)
        assertEquals(4, s.grownIndex)
        assertTrue(s.currentGrown)
        val scene = s.scene!!
        assertEquals(scene.centerTarget(scene.grown!!, s.viewport!!), s.camera.position)
        assertFalse(s.camera.isAnimating)
        assertTrue(s.wave is WallWave.Open)
        assertEquals(WaveStyle.Full, s.wave!!.style)
        assertTrue(s.chromeVisible)
        assertTrue(s.composedTiles.any { it.grown && it.song == 4 })
    }

    @Test
    fun opensOnTheFirstBlockWhenNothingPlays() {
        val s = wall(phone(current = -1), phoneView)
        assertEquals(-1, s.grownIndex)
        assertFalse(s.hasCurrent)
        assertFalse(s.currentGrown)
        assertEquals(WallPoint(-12f, -88f), s.camera.position)
        assertTrue(s.composedTiles.any { it.song == 0 && it.pos == SlotPos(0, 0, 0) })
        // 没在放：定位什么也不做
        s.locate()
        assertEquals(-1, s.grownIndex)
    }

    @Test
    fun followsTheNextSongWithASpring() {
        val s = wall(phone(current = 4), phoneView)
        s.configure(phone(current = 5))
        assertEquals(5, s.grownIndex)
        val target = s.scene!!.centerTarget(s.scene!!.grown!!, s.viewport!!)
        assertEquals(target, s.camera.target)
        s.settle()
        assertEquals(target.x, s.camX, 0.5f)
        assertEquals(target.y, s.camY, 0.5f)
    }

    @Test
    fun reduceMotionSnapsTheCamera() {
        val s = wall(phone(current = 4, reduceMotion = true), phoneView)
        assertEquals(WaveStyle.Plain, s.wave!!.style)
        s.configure(phone(current = 9, reduceMotion = true))
        assertFalse(s.camera.isAnimating)
        assertEquals(s.scene!!.centerTarget(s.scene!!.grown!!, s.viewport!!), s.camera.position)
    }

    @Test
    fun followOffLeavesTheCardAndLocateBringsItBack() {
        val s = wall(phone(current = 4, follow = false), phoneView)
        s.configure(phone(current = 5, follow = false))
        assertEquals(4, s.grownIndex)
        assertFalse(s.currentGrown)
        s.locate()
        assertEquals(5, s.grownIndex)
        assertTrue(s.currentGrown)
    }

    @Test
    fun tapGrowsTheTileUnderTheFinger() {
        val s = wall(phone(current = 4), phoneView)
        s.settle()
        val other = s.composedTiles.first { !it.grown && it.rect.x - s.camX in 0f..250f && it.rect.y - s.camY in 100f..700f }
        s.tapAt(other.rect.centerX - s.camX, other.rect.centerY - s.camY)
        assertEquals(other.song, s.grownIndex)
        assertFalse(s.currentGrown)
        // 点大卡本身不动
        val grown = s.scene!!.grown
        val r = s.scene!!.rectAt(grown!!)
        s.tapAt(r.centerX - s.camera.x, r.centerY - s.camera.y)
        assertEquals(grown, s.scene!!.grown)
    }

    @Test
    fun longPressReportsTheSong() {
        val s = wall(phone(current = 4), phoneView)
        var pressed = -1
        s.onLongPress = { pressed = it }
        val r = s.scene!!.rectAt(s.scene!!.grown!!)
        assertTrue(s.longPressAt(r.centerX - s.camera.x, r.centerY - s.camera.y))
        assertEquals(4, pressed)
    }

    @Test
    fun dragMovesTheCameraAgainstTheFinger() {
        val s = wall(phone(current = 4), phoneView)
        val start = s.camera.position
        s.beginDrag()
        s.dragBy(40f, -30f)
        assertEquals(start.x - 40f, s.camX, 1e-3f)
        assertEquals(start.y + 30f, s.camY, 1e-3f)
        s.endDrag(-800f, 0f)
        assertTrue(s.camera.isFlinging)
        s.settle()
        assertTrue(s.camX > start.x - 40f)
    }

    @Test
    fun backOnTvCollapsesThenClearsFocusThenLeaves() {
        val s = wall(tv(current = 4, drift = false), tvView)
        s.onWallFocus(isFocused = true, hasFocus = true)
        assertTrue(s.onKey(WallKey.Right, down = true, repeatCount = 0, longPress = false))
        val first = s.focus!!
        // 第一下只选中离可用区中心最近的格：正在播放的大卡
        assertEquals(s.scene!!.grown, first)
        assertTrue(s.grownFocused)
        s.onKey(WallKey.Right, down = true, repeatCount = 0, longPress = false)
        val second = s.focus!!
        assertNotEquals(first, second)
        // OK：按下不动作，松开放大焦点格
        s.onKey(WallKey.Confirm, down = true, repeatCount = 0, longPress = false)
        assertEquals(4, s.grownIndex)
        s.onKey(WallKey.Confirm, down = false, repeatCount = 0, longPress = false)
        assertEquals(s.focusedIndex, s.grownIndex)
        assertNotEquals(4, s.grownIndex)

        assertTrue(s.handleBack())
        assertNull(s.scene!!.grown)
        assertNotNull(s.focus)
        assertTrue(s.handleBack())
        assertNull(s.focus)
        assertFalse(s.handleBack())
    }

    @Test
    fun backOnPhoneLeavesWithoutCollapsing() {
        val s = wall(phone(current = 4), phoneView)
        val other = s.composedTiles.first { !it.grown }
        s.growAt(other.pos, center = true)
        assertFalse(s.handleBack())
        assertEquals(other.pos, s.scene!!.grown)
    }

    @Test
    fun okOnTheGrownCardGoesToTheScreen() {
        val s = wall(tv(current = 4, drift = false), tvView)
        var confirmed: Pair<Int, Boolean>? = null
        s.onGrownConfirm = { i, cur -> confirmed = i to cur }
        s.onWallFocus(isFocused = true, hasFocus = true)
        // 没有焦点时 OK 先选一格
        s.onKey(WallKey.Confirm, down = true, repeatCount = 0, longPress = false)
        s.onKey(WallKey.Confirm, down = false, repeatCount = 0, longPress = false)
        assertEquals(s.scene!!.grown, s.focus)
        assertNull(confirmed)
        s.onKey(WallKey.Confirm, down = true, repeatCount = 0, longPress = false)
        s.onKey(WallKey.Confirm, down = false, repeatCount = 0, longPress = false)
        assertEquals(4 to true, confirmed)
    }

    @Test
    fun holdingOkIsALongPressNotAGrow() {
        val s = wall(tv(current = 4, drift = false), tvView)
        var pressed = -1
        s.onLongPress = { pressed = it }
        s.onWallFocus(isFocused = true, hasFocus = true)
        s.onKey(WallKey.Right, down = true, repeatCount = 0, longPress = false)
        s.onKey(WallKey.Right, down = true, repeatCount = 0, longPress = false)
        val target = s.focusedIndex
        s.onKey(WallKey.Confirm, down = true, repeatCount = 0, longPress = false)
        s.onKey(WallKey.Confirm, down = true, repeatCount = 1, longPress = true)
        s.onKey(WallKey.Confirm, down = true, repeatCount = 2, longPress = false)
        s.onKey(WallKey.Confirm, down = false, repeatCount = 0, longPress = false)
        assertEquals(target, pressed)
        assertEquals(4, s.grownIndex)
    }

    @Test
    fun keysTheWallDoesNotHandleFallThrough() {
        val s = wall(tv(current = 4, drift = false), tvView)
        assertFalse(s.onKey(WallKey.Other, down = true, repeatCount = 0, longPress = false))
        assertFalse(s.onKey(WallKey.Passthrough, down = true, repeatCount = 0, longPress = false))
        s.onWallFocus(isFocused = false, hasFocus = true)
        assertNull(s.litFocus)
    }

    @Test
    fun chromeFadesAfterSixIdleSecondsOnTv() {
        val s = wall(tv(current = 4, drift = false), tvView)
        s.tick(5f)
        assertTrue(s.chromeVisible)
        s.tick(1.5f)
        assertFalse(s.chromeVisible)
        assertFalse(s.wake())
        assertTrue(s.chromeVisible)
        // 手机不淡出
        val p = wall(phone(current = 4), phoneView)
        p.tick(10f)
        assertTrue(p.chromeVisible)
    }

    @Test
    fun theFirstKeyWhileDriftingOnlyWakes() {
        val s = wall(tv(current = 4), tvView)
        s.onWallFocus(isFocused = true, hasFocus = true)
        s.onKey(WallKey.Right, down = true, repeatCount = 0, longPress = false)
        s.onKey(WallKey.Right, down = true, repeatCount = 0, longPress = false)
        val focus = s.focus
        s.tick(31f)
        assertTrue(s.isDrifting)
        assertFalse(s.chromeVisible)
        repeat(300) { s.tick(1f / 30) }
        // 按下、自动重复、松开都吞掉，镜头弹回正在播放
        assertTrue(s.previewKey(WallKey.Right, down = true))
        assertFalse(s.isDrifting)
        assertTrue(s.chromeVisible)
        assertEquals(4, s.grownIndex)
        assertEquals(s.scene!!.centerTarget(s.scene!!.grown!!, s.viewport!!), s.camera.target)
        assertTrue(s.previewKey(WallKey.Right, down = true))
        assertTrue(s.previewKey(WallKey.Right, down = false))
        // 焦点留在原来的格子
        assertEquals(focus, s.focus)
        // 下一次按键照常
        assertFalse(s.previewKey(WallKey.Right, down = true))
        assertFalse(s.previewKey(WallKey.Right, down = false))
        // 音量、返回不经过唤醒
        s.tick(40f)
        assertTrue(s.isDrifting)
        assertFalse(s.previewKey(WallKey.Passthrough, down = true))
        assertTrue(s.isDrifting)
    }

    @Test
    fun backWhileDriftingOnlyWakes() {
        val s = wall(tv(current = 4), tvView)
        s.tick(31f)
        assertTrue(s.isDrifting)
        assertTrue(s.handleBack())
        assertFalse(s.isDrifting)
        assertTrue(s.chromeVisible)
        assertFalse(s.handleBack())
    }

    @Test
    fun touchWhileDriftingOnlyWakes() {
        val s = wall(tv(current = 4), tvView)
        s.tick(31f)
        assertTrue(s.onPointerDown())
        assertFalse(s.onPointerDown())
    }

    @Test
    fun aNewBatchKeepsEveryExistingTile() {
        val band = WallConfig(
            metrics = WallMetrics.Phone,
            field = feedFor(WallMetrics.Phone, 15),
            current = 3,
            follow = true,
            isTv = false,
            drift = false,
            reduceMotion = false,
            appending = true,
            insetTop = phoneView.insetTop,
            insetBottom = phoneView.insetBottom,
        )
        val s = wall(band, phoneView)
        // 续取中：微光 0.3 秒淡入
        s.tick(0.15f)
        assertEquals(0.5f, s.shimmerAlpha, 1e-4f)
        s.settle()
        assertEquals(1f, s.shimmerAlpha, 0f)
        val before = (0 until 15).map { s.scene!!.rectAt((s.scene!!.field as WallField.FeedBand).posOf(it)) }
        val cam = s.camera.position
        val generation = s.generation
        assertTrue(s.isGhost(20))

        s.configure(band.copy(field = feedFor(WallMetrics.Phone, 30), appending = false))
        val f = s.scene!!.field as WallField.FeedBand
        assertEquals(30, f.songs)
        assertEquals(before, (0 until 15).map { s.scene!!.rectAt(f.posOf(it)) })
        assertEquals(cam, s.camera.position)
        assertEquals(generation, s.generation)
        assertEquals(3, s.grownIndex)
        assertEquals(WallWave.Batch(15, 30, WaveStyle.Full), s.wave)
        assertFalse(s.isGhost(20))
        assertTrue(s.isGhost(30))
        repeat(30) { s.tick(1f / 30) }
        assertEquals(0f, s.shimmerAlpha, 0f)
    }

    @Test
    fun aDifferentQueueRebuildsTheWall() {
        val s = wall(phone(current = 4, n = 30), phoneView)
        val generation = s.generation
        s.configure(phone(current = 2, n = 12))
        assertEquals(generation + 1, s.generation)
        assertEquals(2, s.grownIndex)
        assertTrue(s.wave is WallWave.Open)
        assertFalse(s.camera.isAnimating)
    }

    @Test
    fun theWaveEndsAndStopsFrames() {
        val s = wall(phone(current = 4), phoneView)
        assertTrue(s.needsFrames)
        repeat(90) { s.tick(1f / 60) }
        assertNull(s.wave)
        assertFalse(s.needsFrames)
    }

    @Test
    fun lateInsetsRecentreTheCardUnlessTheWallWasMoved() {
        val s = wall(phone(current = 4).copy(insetTop = 0f, insetBottom = 0f), phoneView)
        s.configure(phone(current = 4))
        assertEquals(s.scene!!.centerTarget(s.scene!!.grown!!, s.viewport!!), s.camera.position)
        assertEquals(84f, s.viewport!!.insetTop, 0f)

        s.beginDrag()
        s.dragBy(30f, 30f)
        s.endDrag(0f, 0f)
        val moved = s.camera.position
        s.configure(phone(current = 4).copy(insetTop = 120f))
        assertEquals(moved, s.camera.position)
    }

    @Test
    fun resizeKeepsTheCardCentred() {
        val s = wall(phone(current = 4), phoneView)
        s.setViewportSize(800f, 360f)
        assertEquals(s.scene!!.centerTarget(s.scene!!.grown!!, s.viewport!!), s.camera.position)
    }

    @Test
    fun aNewQueueWithTheSameCountStillReplaysTheEntrance() {
        val s = wall(phone(current = 4).copy(resetKey = 1), phoneView)
        repeat(90) { s.tick(1f / 60) }
        assertNull(s.wave)
        val generation = s.generation
        s.configure(phone(current = 0).copy(resetKey = 2))
        assertEquals(generation + 1, s.generation)
        assertTrue(s.wave is WallWave.Open)
        assertEquals(0, s.grownIndex)
    }

    @Test
    fun aReorderedQueueDoesNotFollow() {
        val s = wall(phone(current = 4).copy(currentKey = "a"), phoneView)
        val grown = s.scene!!.grown
        // “下一首播放”把前面的歌挪到当前这首后面：当前这首退到第 3 位，key 没变
        s.configure(phone(current = 3).copy(currentKey = "a"))
        assertEquals(grown, s.scene!!.grown)
        assertFalse(s.camera.isAnimating)
        // 真的切歌了照常跟随
        s.configure(phone(current = 4).copy(currentKey = "b"))
        assertEquals(4, s.grownIndex)
        assertTrue(s.camera.isAnimating)
    }

    @Test
    fun growSongCentresTheMovedSong() {
        val s = wall(phone(current = 4), phoneView)
        s.growSong(5)
        assertEquals(5, s.grownIndex)
        assertEquals(s.scene!!.centerTarget(s.scene!!.grown!!, s.viewport!!), s.camera.target)
    }

    @Test
    fun upWithoutAFocusedTileLeavesTheWallForTheTopRow() {
        val s = wall(tv(current = 4, drift = false).copy(escapeUp = true), tvView)
        s.onWallFocus(isFocused = true, hasFocus = true)
        assertFalse(s.onKey(WallKey.Up, down = true, repeatCount = 0, longPress = false))
        assertNull(s.focus)
        // 别的方向照常选中一格；选中之后上键在墙里走
        assertTrue(s.onKey(WallKey.Down, down = true, repeatCount = 0, longPress = false))
        assertNotNull(s.focus)
        assertTrue(s.onKey(WallKey.Up, down = true, repeatCount = 0, longPress = false))
        // 没开这个出口时上键也只在墙里走
        val plain = wall(tv(current = 4, drift = false), tvView)
        plain.onWallFocus(isFocused = true, hasFocus = true)
        assertTrue(plain.onKey(WallKey.Up, down = true, repeatCount = 0, longPress = false))
    }

    @Test
    fun comingDownFromTheTopRowSelectsTheCard() {
        val s = wall(tv(current = 4, drift = false).copy(escapeUp = true), tvView)
        s.focusFromAbove()
        assertEquals(s.scene!!.grown, s.focus)
    }

    @Test
    fun aCoveredWallSnapsWhenFollowing() {
        val s = wall(phone(current = 4), phoneView)
        s.configure(phone(current = 9).copy(active = false))
        assertEquals(9, s.grownIndex)
        assertFalse(s.camera.isAnimating)
        assertEquals(s.scene!!.centerTarget(s.scene!!.grown!!, s.viewport!!), s.camera.position)
    }

    @Test
    fun anOpenPanelHoldsTheChromeAwake() {
        val s = wall(tv(current = 4, drift = true).copy(holdAwake = true), tvView)
        repeat(40) { s.tick(1f) }
        assertTrue(s.chromeVisible)
        assertFalse(s.isDrifting)
        s.configure(tv(current = 4, drift = true))
        repeat(7) { s.tick(1f) }
        assertFalse(s.chromeVisible)
    }
}

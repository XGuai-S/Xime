package com.kingzcheung.xime.ui.menubar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按拖拽换位纯计算的测试（候选排序面板 / 工具栏排序态共用）。
 *
 * 行高统一取 44dp 换算的 100px 便于心算：半行 = 50px，一行 = 100px。
 * 方向语义：手指向下拉（accumY > 0）项跟随下移（目标位置增大）；
 * 手指向上拉（accumY < 0）项跟随上移（目标位置减小）。
 */
class DragReorderLogicTest {

    private fun result(order: List<String>, from: Int, accumY: Float, row: Float = 100f) =
        computeDragReorder(order, from, accumY, row)

    @Test
    fun `位移未过半行不换位`() {
        val r = result(listOf("a", "b", "c"), from = 1, accumY = 49f)
        assertFalse(r.swapped)
        assertEquals(listOf("a", "b", "c"), r.order)
        assertEquals(49f, r.remainingAccumY, 0.001f)
    }

    @Test
    fun `向下拖动过半行 与下方一项换位`() {
        // b 从中间被拉向下：跨过半行与 c 换位，落到第 2 位
        val r = result(listOf("a", "b", "c"), from = 1, accumY = 60f)
        assertTrue(r.swapped)
        assertEquals(listOf("a", "c", "b"), r.order)
        assertEquals(2, r.from)
        // 剩余位移 = 60 - 100 = -40（正向已消耗，负号只是方向符号，调用方原样保存）
        assertEquals(-40f, r.remainingAccumY, 0.001f)
    }

    @Test
    fun `向上拖动过半行 与上方一项换位`() {
        // b 被拉向上：跨过半行与 a 换位，落到第 0 位
        val r = result(listOf("a", "b", "c"), from = 1, accumY = -60f)
        assertTrue(r.swapped)
        assertEquals(listOf("b", "a", "c"), r.order)
        assertEquals(0, r.from)
        assertEquals(40f, r.remainingAccumY, 0.001f)
    }

    @Test
    fun `一次拖动跨多行`() {
        // c 向下拉 220px：跨两行到末尾（剩 120px 后到边界停止）
        val r = result(listOf("a", "b", "c", "d"), from = 2, accumY = 220f)
        assertTrue(r.swapped)
        assertEquals(listOf("a", "b", "d", "c"), r.order)
        assertEquals(3, r.from)
        assertEquals(120f, r.remainingAccumY, 0.001f)
    }

    @Test
    fun `末项继续下拉 不再换位`() {
        // d 已是最后一位，向下拖不再越过任何人
        val r = result(listOf("a", "b", "c", "d"), from = 3, accumY = 300f)
        assertFalse(r.swapped)
        assertEquals(listOf("a", "b", "c", "d"), r.order)
        assertEquals(300f, r.remainingAccumY, 0.001f)
    }

    @Test
    fun `首项继续上拉 不再换位`() {
        // a 已是第一位，向上拖不再越过任何人
        val r = result(listOf("a", "b", "c"), from = 0, accumY = -300f)
        assertFalse(r.swapped)
        assertEquals(listOf("a", "b", "c"), r.order)
        assertEquals(-300f, r.remainingAccumY, 0.001f)
    }

    @Test
    fun `非法位置不换位`() {
        val r = result(listOf("a", "b", "c"), from = 5, accumY = 80f)
        assertFalse(r.swapped)
        assertEquals(listOf("a", "b", "c"), r.order)
    }

    @Test
    fun `行高非正不换位`() {
        val r = result(listOf("a", "b", "c"), from = 1, accumY = 80f, row = 0f)
        assertFalse(r.swapped)
        assertEquals(listOf("a", "b", "c"), r.order)
    }

    @Test
    fun `空列表不换位`() {
        val r = result(emptyList(), from = 0, accumY = 80f)
        assertFalse(r.swapped)
        assertEquals(emptyList<String>(), r.order)
    }

    // ── 边缘自动滚动建议量（拖到可视区上下边缘时列表自动滚动）──

    // 约定：容器窗口 y 范围 [100, 500)，边缘带 40px、单帧步长 24px

    @Test
    fun `手指压到上边缘 建议向上滚`() {
        assertEquals(-24f, computeEdgeAutoScroll(120f, 100f, 500f, 40f, 24f), 0.001f)
    }

    @Test
    fun `手指压到下边缘 建议向下滚`() {
        assertEquals(24f, computeEdgeAutoScroll(480f, 100f, 500f, 40f, 24f), 0.001f)
    }

    @Test
    fun `手指在中部 不建议滚动`() {
        assertEquals(0f, computeEdgeAutoScroll(300f, 100f, 500f, 40f, 24f), 0.001f)
    }

    @Test
    fun `恰在边缘带边界 也触发滚动`() {
        // 边缘带是闭合区间：容器顶 + 40 依然在带内（手指位置连续，边界抖动无碍）
        assertEquals(-24f, computeEdgeAutoScroll(140f, 100f, 500f, 40f, 24f), 0.001f)
        assertEquals(24f, computeEdgeAutoScroll(460f, 100f, 500f, 40f, 24f), 0.001f)
        // 刚出带（141 / 459）即不滚动
        assertEquals(0f, computeEdgeAutoScroll(141f, 100f, 500f, 40f, 24f), 0.001f)
        assertEquals(0f, computeEdgeAutoScroll(459f, 100f, 500f, 40f, 24f), 0.001f)
    }

    @Test
    fun `容器尺寸非法 不滚动`() {
        assertEquals(0f, computeEdgeAutoScroll(10f, 100f, 100f, 40f, 24f), 0.001f) // 高度为 0
        assertEquals(0f, computeEdgeAutoScroll(10f, 200f, 100f, 40f, 24f), 0.001f) // 顶>底
    }

    @Test
    fun `边缘带或步长非正 不滚动`() {
        assertEquals(0f, computeEdgeAutoScroll(10f, 100f, 500f, 0f, 24f), 0.001f)
        assertEquals(0f, computeEdgeAutoScroll(480f, 100f, 500f, 40f, 0f), 0.001f)
    }
}
package dev.shimpyo.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp

/**
 * 주행 화면은 스크롤하지 않으므로 넘치는 내용이 잘리기 전에 덜 중요한 것부터 뺀다.
 * [rows]를 위에서부터 쌓는다. 높이가 모자라면 [priority](중요한 순서의 행 번호, 없으면 위에서부터)의 뒤쪽부터 하나씩 빼되,
 * 중요한 앞 [required]개는 늘 둔다. 남은 행은 원래 순서로 놓고, 내용 묶음은 세로 가운데에 둔다([center]).
 *
 * 예: 급해 결과 카드는 compact 세로 윈도(628×855, 800×810, 648×800)에서 칸 높이가 236~263dp인데 내용이 약 278dp라
 * 아래 줄이 잘렸다(UI 감사 #10). 이름·시간은 두고 소요 구성 → 배지 줄 순으로 뺀다.
 */
@Composable
fun FitColumn(
    rows: List<@Composable () -> Unit>,
    gap: Dp,
    modifier: Modifier = Modifier,
    required: Int = 1,
    priority: List<Int>? = null,
    center: Boolean = true,
) {
    Layout(contents = rows, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gapPx = gap.roundToPx()
        val bounded = constraints.hasBoundedHeight
        val measured = measurables.map { group -> group.map { it.measure(loose) } }
        val heights = measured.map { ps -> ps.maxOfOrNull { it.height } ?: 0 }
        val keep = fitRows(heights, gapPx, if (bounded) constraints.maxHeight else null, required, priority)
        val total = stackHeight(heights, keep, gapPx)
        // 정해진 높이(weight, fillMax)면 그 높이를 채우고 가운데에 둔다. 감싸기면 내용 높이
        val height = if (bounded) total.coerceIn(constraints.minHeight, constraints.maxHeight) else total.coerceAtLeast(constraints.minHeight)
        val shown = heights.indices.filter { keep[it] }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else shown.maxOfOrNull { i -> measured[i].maxOf { it.width } } ?: 0
        layout(width, height) {
            var y = if (center && bounded) ((height - total) / 2).coerceAtLeast(0) else 0
            shown.forEachIndexed { n, i ->
                if (n > 0) y += gapPx
                measured[i].forEach { it.placeRelative(0, y) }
                y += heights[i]
            }
        }
    }
}

/**
 * [FitColumn]이 남길 행. 높이 0인 행은 뺀다. [maxHeight]가 있으면 [priority](없으면 위에서부터)의 덜 중요한 쪽부터
 * 하나씩 빼서 들어가게 하되 중요한 앞 [required]개는 남긴다(그것만으로 넘치면 넘친 채로 둔다).
 */
fun fitRows(heights: List<Int>, gap: Int, maxHeight: Int?, required: Int, priority: List<Int>? = null): List<Boolean> {
    val keep = heights.map { it > 0 }.toMutableList()
    if (maxHeight != null) {
        val order = priority ?: heights.indices.toList()
        for (i in order.drop(required).asReversed()) {
            if (stackHeight(heights, keep, gap) <= maxHeight) break
            keep[i] = false
        }
    }
    return keep
}

/** 남긴 행을 [gap] 간격으로 쌓은 높이 */
fun stackHeight(heights: List<Int>, keep: List<Boolean>, gap: Int): Int {
    val shown = heights.indices.filter { keep[it] }
    return shown.sumOf { heights[it] } + gap * (shown.size - 1).coerceAtLeast(0)
}

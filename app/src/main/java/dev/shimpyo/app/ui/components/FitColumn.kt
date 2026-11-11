package dev.shimpyo.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Dp

/**
 * 주행 화면은 스크롤하지 않으므로 넘치는 내용이 잘리기 전에 덜 중요한 것부터 뺀다.
 * [rows]를 위에서부터 쌓되 앞의 [required]개는 늘 두고, 그 뒤는 순서대로 들어가는 만큼만 둔다(하나가 안 들어가면 그 뒤도 뺀다).
 * 내용 묶음은 세로 가운데에 놓는다 ([center]).
 *
 * 예: 급해 결과 카드는 compact 세로 윈도(628×855, 800×810, 648×800)에서 칸 높이가 236~263dp인데 내용이 약 278dp라
 * 아래 줄이 잘렸다(UI 감사 #10). 이름·시간은 두고 소요 구성·배지 줄을 뺀다.
 */
@Composable
fun FitColumn(
    rows: List<@Composable () -> Unit>,
    gap: Dp,
    modifier: Modifier = Modifier,
    required: Int = 1,
    center: Boolean = true,
) {
    Layout(contents = rows, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gapPx = gap.roundToPx()
        val bounded = constraints.hasBoundedHeight
        val placed = ArrayList<List<Placeable>>(measurables.size)
        var used = 0
        for ((i, group) in measurables.withIndex()) {
            val ps = group.map { it.measure(loose) }
            val h = ps.maxOfOrNull { it.height } ?: 0
            if (h == 0) continue
            val add = h + if (placed.isEmpty()) 0 else gapPx
            if (i >= required && bounded && used + add > constraints.maxHeight) break
            placed += ps
            used += add
        }
        // 정해진 높이(weight, fillMax)면 그 높이를 채우고 가운데에 둔다. 감싸기면 내용 높이
        val height = if (bounded) used.coerceIn(constraints.minHeight, constraints.maxHeight) else used.coerceAtLeast(constraints.minHeight)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placed.maxOfOrNull { ps -> ps.maxOf { it.width } } ?: 0
        layout(width, height) {
            var y = if (center && bounded) ((height - used) / 2).coerceAtLeast(0) else 0
            placed.forEachIndexed { i, ps ->
                if (i > 0) y += gapPx
                ps.forEach { it.placeRelative(0, y) }
                y += ps.maxOf { it.height }
            }
        }
    }
}

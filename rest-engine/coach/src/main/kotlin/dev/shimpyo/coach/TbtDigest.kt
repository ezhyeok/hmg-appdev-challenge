package dev.shimpyo.coach

/**
 * TBT 응답 하나를 코치가 쓰는 값으로 정리한 것. NaviTracker가 받을 때마다 만든다.
 *
 * TBT 거리는 '직전 항목부터의 거리'라서 누적해야 경로 거리가 된다(RouteOpportunities). 그래서 거리 하나가 깨지면
 * (NaN·무한·음수) 그 뒤 모든 누적 거리가 틀린다. 예전에는 NaN이 coerceAtLeast를 그대로 통과해 뒤의 휴게소 거리가
 * 전부 NaN이 됐다(데이터 감사 #7). 깨진 항목에서 읽기를 멈추고, 거기까지만 안다고 [coverageM]으로 알린다.
 * 0으로 바꿔 계속 읽으면 뒤의 휴게소가 실제보다 가깝게 보여서, 모른다고 하는 쪽이 안전하다.
 */
data class TbtDigest(
    /** 경로 위 휴게소·졸음쉼터 (현재 위치부터 누적 경로 거리) */
    val opportunities: List<RouteOpportunity>,
    /** 다음 회전까지 거리 (첫 항목). 목록이 비었거나 첫 거리가 깨졌으면 null */
    val nextManeuverM: Double?,
    /**
     * 목록이 닿는 경로 거리 (읽은 마지막 항목까지의 누적). 빈 목록이면 0: 아무것도 모른다.
     * 보통 마지막 항목이 목적지라 남은 거리와 같고, 짧으면 그 뒤는 모른다 (서비스 감사 P5)
     */
    val coverageM: Double,
    /** 깨진 거리 때문에 뒤를 버렸는지 (로그용) */
    val truncated: Boolean,
) {
    companion object {
        fun of(tbt: List<TbtItem>): TbtDigest {
            val readable = readable(tbt)
            return TbtDigest(
                opportunities = RouteOpportunities.extract(readable),
                nextManeuverM = RouteOpportunities.nextManeuverDistance(readable),
                coverageM = readable.sumOf { it.distanceM },
                truncated = readable.size < tbt.size,
            )
        }

        /** 거리가 깨진 첫 항목 앞까지. 0은 정상이다 (같은 지점의 두 안내) */
        fun readable(tbt: List<TbtItem>): List<TbtItem> {
            val bad = tbt.indexOfFirst { !it.distanceM.isFinite() || it.distanceM < 0.0 }
            return if (bad < 0) tbt else tbt.subList(0, bad)
        }
    }
}

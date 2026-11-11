package dev.shimpyo.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** 엔진 안에서만 쓰는 수학 함수. 표준 라이브러리에 없는 감마 함수와 베타 표본 추출. */
internal object EngineMath {
    /** 시각·시간 값이 없음을 나타내는 표식 */
    const val NONE: Long = Long.MIN_VALUE

    // Lanczos 근사 (g = 7, n = 9). 양수 구간에서 상대오차 1e-13 수준
    private val LANCZOS = doubleArrayOf(
        0.99999999999980993, 676.5203681218851, -1259.1392167224028,
        771.32342877765313, -176.61502916214059, 12.507343278686905,
        -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7,
    )

    fun gamma(x: Double): Double {
        if (x < 0.5) return PI / (sin(PI * x) * gamma(1.0 - x))
        val xx = x - 1.0
        var a = LANCZOS[0]
        val t = xx + 7.5
        for (i in 1 until LANCZOS.size) a += LANCZOS[i] / (xx + i)
        return sqrt(2.0 * PI) * t.pow(xx + 0.5) * exp(-t) * a
    }

    /** values[0 until n]의 중앙값. 원본은 건드리지 않는다 */
    fun median(values: DoubleArray, n: Int): Double {
        require(n > 0)
        val copy = values.copyOf(n)
        copy.sort()
        return if (n % 2 == 1) copy[n / 2] else (copy[n / 2 - 1] + copy[n / 2]) / 2.0
    }

    /** 중앙값 절대 편차 (MAD). 정규분포면 1.4826 × MAD ≈ 표준편차 */
    fun mad(values: DoubleArray, n: Int, median: Double): Double {
        val dev = DoubleArray(n) { abs(values[it] - median) }
        return median(dev, n)
    }
}

/** Box–Muller 표준정규 표본 */
internal fun Random.nextGaussian(): Double {
    var u1 = nextDouble()
    if (u1 < 1e-300) u1 = 1e-300
    val u2 = nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
}

/** Marsaglia–Tsang 감마(shape, 1) 표본 */
internal fun Random.nextGamma(shape: Double): Double {
    require(shape > 0.0)
    if (shape < 1.0) return nextGamma(shape + 1.0) * nextDouble().pow(1.0 / shape)
    val d = shape - 1.0 / 3.0
    val c = 1.0 / sqrt(9.0 * d)
    while (true) {
        var x: Double
        var v: Double
        do {
            x = nextGaussian()
            v = 1.0 + c * x
        } while (v <= 0.0)
        v = v * v * v
        val u = nextDouble()
        if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
        if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
    }
}

/** 베타(a, b) 표본: 두 감마 표본의 비 */
internal fun Random.nextBeta(a: Double, b: Double): Double {
    val x = nextGamma(a)
    val y = nextGamma(b)
    return x / (x + y)
}

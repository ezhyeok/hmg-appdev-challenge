package dev.shimpyo.engine.sim

import java.io.File

/**
 * 기획서용 엔진 수치 생성기.
 *   ./gradlew -p rest-engine runSim                 (여정 5,000개)
 *   ./gradlew -p rest-engine runSim -PsimTrips=500  (빠른 확인)
 * 인자: <analysis.json> <출력 폴더> [여정 수]
 * 출력: engine_curves.json (A), effect_sim.json (B). 시드가 고정이라 같은 인자면 같은 파일이 나온다.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: <analysis.json> <outDir> [trips]" }
    val analysis = File(args[0])
    if (args.getOrNull(2) == "trace") {
        // 진단: 조건 하나의 여정 몇 개를 정책별 사건 기록으로 출력 (파일은 쓰지 않는다)
        val (stops, length) = RouteData.load(analysis)
        val sim = EffectSim(stops, length)
        val c = EffectSim.Condition(0, sim.occupantSets[0], holiday = true, sigma = 0.3)
        for (trip in (args.getOrNull(3)?.toInt() ?: 0) until (args.getOrNull(4)?.toInt() ?: 3)) {
            val setup = sim.setup(c, trip)
            for (policy in EffectSim.Policy.entries) {
                val log = ArrayList<String>()
                val r = sim.simulate(c, setup, policy, log)
                println("=== trip $trip ${policy.key}: maxWait %.0f분 stops ${r.stops} trip %.0f분".format(r.maxWaitS / 60, r.tripS / 60))
                log.forEach { println("  $it") }
            }
        }
        return
    }
    val outDir = File(args[1]).apply { mkdirs() }
    val trips = args.getOrNull(2)?.toInt() ?: 5_000

    val t0 = System.nanoTime()
    val curves = Curves.build()
    File(outDir, "engine_curves.json").writeText(Json.write(curves), Charsets.UTF_8)
    println("engine_curves.json written (%.1fs)".format((System.nanoTime() - t0) / 1e9))

    val (stops, length) = RouteData.load(analysis)
    val t1 = System.nanoTime()
    val effect = EffectSim(stops, length).run(trips)
    File(outDir, "effect_sim.json").writeText(Json.write(effect), Charsets.UTF_8)
    println("effect_sim.json written: $trips trips per condition × policy (%.1fs)".format((System.nanoTime() - t1) / 1e9))
}

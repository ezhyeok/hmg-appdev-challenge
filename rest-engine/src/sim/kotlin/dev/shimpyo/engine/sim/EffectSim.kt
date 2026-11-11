package dev.shimpyo.engine.sim

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.CoachOutput
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.RestroomNeedModel
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.TripProfile
import dev.shimpyo.engine.VoiceIntent
import java.time.ZonedDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * (B) 효과 몬테카를로: 경부고속도로 서울→부산 실제 휴게소·졸음쉼터 위치에서 세 가지 행동을 비교한다.
 * 이산 사건 시뮬레이션: 필요 발생·정차 도착·출발·목적지 도착 시각을 교통 모델로 정확히 계산하고,
 * 쉼표 정책은 실제 CoachEngine을 30초마다(그리고 사건마다) 호출한다. 정차는 앱과 같은 경로(기어 P + 문 열림)로 엔진에 들어간다.
 */
internal class EffectSim(private val stops: List<StopDef>, private val routeLen: Double) {
    private val config = CoachConfig()
    private val k = config.restroomWeibullShape
    private val departure = ZonedDateTime.of(2026, 9, 24, 9, 0, 0, 0, config.zoneId).toInstant().toEpochMilli()

    enum class Policy(val key: String, val label: String, val pAccept: Double) {
        NOW_LIKE("nowLike", "지금처럼: 누군가 급해지면 다음 휴게소", 0.0),
        EVERY_2H("every2h", "2시간마다 휴게소 + 급해지면 다음 휴게소", 0.0),
        SHIMPYO_P07("shimpyoP07", "쉼표 엔진 (질문 수락 확률 0.7) + 급해지면 다음 휴게소", 0.7),
        SHIMPYO_P10("shimpyoP10", "쉼표 엔진 (질문 수락 확률 1.0) + 급해지면 다음 휴게소", 1.0),
        ;

        val engine: Boolean get() = pAccept > 0.0
    }

    class PersonSpec(val id: String, val type: OccupantType, val age: Int?) {
        fun occupant() = Occupant(id, type, ageYears = age)
    }

    class OccSpec(val key: String, val label: String, val people: List<PersonSpec>)

    class Condition(val occIdx: Int, val occ: OccSpec, val holiday: Boolean, val sigma: Double) {
        val key: String get() = "${occ.key}_${if (holiday) "holiday" else "free"}_sigma${sigma}"
    }

    class TripSetup(
        val traffic: Traffic,
        val offsetsMin: DoubleArray,
        val trueMedians: DoubleArray,
        val personSeeds: LongArray,
        val acceptSeed: Long,
        val engineSeed: Long,
    )

    class TripResult(
        val maxWaitS: Double,
        val waitsS: DoubleArray,
        val inJamOnset: Boolean,
        val stops: Int,
        val restStops: Int,
        val reactiveStops: Int,
        val tripS: Double,
        val maxStretchS: Double,
        val lastChancePrompts: Int,
        val restPrompts: Int,
        val acceptedStops: Int,
        val promptsWhileParked: Int,
        val lcCongestion: Int,
        val lcGap: Int,
        val lcNow: Int,
        /** 탑승자의 '화장실 가고 싶어'에 엔진이 바로 답한 횟수 (엔진이 먼저 건 질문이 아니다) */
        val needAnswers: Int,
    )

    val occupantSets = listOf(
        OccSpec("child7", "운전자 + 아이 7세", listOf(PersonSpec("driver", OccupantType.ADULT, null), PersonSpec("kid", OccupantType.CHILD, 7))),
        OccSpec("child4", "운전자 + 아이 4세", listOf(PersonSpec("driver", OccupantType.ADULT, null), PersonSpec("kid", OccupantType.CHILD, 4))),
        OccSpec("elderly", "운전자 + 어르신", listOf(PersonSpec("driver", OccupantType.ADULT, null), PersonSpec("grandma", OccupantType.ELDERLY, null))),
        OccSpec("oabDriver", "과민성 방광 운전자 혼자", listOf(PersonSpec("driver", OccupantType.OAB, null))),
        OccSpec("adults", "어른 2명", listOf(PersonSpec("driver", OccupantType.ADULT, null), PersonSpec("p2", OccupantType.ADULT, null))),
    )

    private val priorModel = RestroomNeedModel(config)

    fun priorMedian(p: PersonSpec): Double = priorModel.priorMedianMin(p.occupant())

    // ------------------------------------------------------------ 여정 뽑기

    /** 같은 (탑승자 조합, 여정 번호)면 정책·sigma·교통과 상관없이 같은 사람(마지막 화장실, z, 필요 난수열)을 쓴다 (공통 난수) */
    fun setup(c: Condition, trip: Int): TripSetup {
        val rng = Random(1_000_003L * (c.occIdx + 1) + 7_919L * trip + 12_345L)
        val n = c.occ.people.size
        val offsets = DoubleArray(n)
        val medians = DoubleArray(n)
        val seeds = LongArray(n)
        for (i in 0 until n) {
            offsets[i] = 30.0 * rng.nextDouble()
            val z = nextGaussian(rng)
            medians[i] = priorMedian(c.occ.people[i]) * exp(c.sigma * z)
            seeds[i] = rng.nextLong()
        }
        val acceptSeed = rng.nextLong()
        val engineSeed = rng.nextLong()
        val jamRng = Random(rng.nextLong())
        val jams = if (c.holiday) drawJams(jamRng) else emptyList()
        return TripSetup(Traffic(jams, FREE_KPH, routeLen), offsets, medians, seeds, acceptSeed, engineSeed)
    }

    private fun drawJams(r: Random): List<Jam> {
        val count = 1 + r.nextInt(3)
        val out = ArrayList<Jam>()
        var attempts = 0
        while (out.size < count && attempts++ < 1_000) {
            val start = 20.0 + 360.0 * r.nextDouble()
            val len = 5.0 + 25.0 * r.nextDouble()
            val kph = 10.0 + 20.0 * r.nextDouble()
            val end = start + len
            if (out.none { start < it.endKm && end > it.startKm }) out += Jam(start, end, kph)
        }
        return out.sortedBy { it.startKm }
    }

    // ------------------------------------------------------------ 한 여정

    private inner class PersonState(val spec: PersonSpec, trueMedian: Double, seed: Long) {
        private val rng = Random(seed)
        private val lambda = trueMedian / ln(2.0).pow(1.0 / k)
        var nextNeed = 0.0
        var active = false
        var onset = 0.0

        fun reset(atS: Double) {
            nextNeed = atS + 60.0 * lambda * (-ln(1.0 - rng.nextDouble())).pow(1.0 / k)
            active = false
        }
    }

    fun simulate(c: Condition, s: TripSetup, policy: Policy, trace: MutableList<String>? = null): TripResult {
        fun log(t: Double, x: Double, msg: String) { trace?.add(String.format(java.util.Locale.ROOT, "%6.1fmin %6.1fkm  %s", t / 60.0, x, msg)) }
        val traffic = s.traffic
        val persons = c.occ.people.mapIndexed { i, p -> PersonState(p, s.trueMedians[i], s.personSeeds[i]) }
        persons.forEachIndexed { i, p -> p.reset(-s.offsetsMin[i] * 60.0) }

        var t = 0.0
        var x = 0.0
        var parkedUntil = -1.0
        var currentDwellMin = 0.0
        var drivingSinceRest = 0.0
        var maxStretch = 0.0
        var restDue = false
        var reactive = -1
        var engineT = -1
        var engineKind: PromptKind? = null
        var scheduled = -1
        val waits = ArrayList<Double>(8)
        var inJamOnset = false
        var nStops = 0
        var nRest = 0
        var nReactive = 0
        var lcPrompts = 0
        var restPrompts = 0
        var accepted = 0
        var parkedPrompts = 0
        var lcCongestion = 0
        var lcGap = 0
        var lcNow = 0
        var needAnswers = 0

        val acceptRng = Random(s.acceptSeed)
        val engine = if (policy.engine) {
            val occupants = c.occ.people.map { it.occupant() }
            val last = c.occ.people.mapIndexed { i, p -> p.id to (departure - (s.offsetsMin[i] * 60_000.0).toLong()) }.toMap()
            CoachEngine(config, TripProfile(occupants, tripStart = departure, lastRestroomAt = last), random = Random(s.engineSeed))
        } else null
        var lastOut: CoachOutput? = null
        val seen = HashSet<String>()
        val answers = ArrayDeque<Pair<Double, VoiceIntent>>()
        var nextTick = 0.0

        fun nextStopAhead(minKm: Double): Int {
            for (st in stops) if (st.km >= x + minKm) return st.idx
            return -1
        }

        fun engineUpdate(intent: VoiceIntent?, door: Boolean) {
            val e = engine ?: return
            val parked = parkedUntil >= 0.0
            val cands = ArrayList<StopCandidate>(stops.size)
            for (st in stops) {
                if (st.km <= x + 1e-6) continue
                cands += StopCandidate(
                    id = st.idx.toString(), name = st.name, kind = st.kind, lat = st.lat, lon = st.lon,
                    facilities = if (st.kind == StopKind.HIGHWAY_REST_AREA) Facilities(food = true, fuel = true) else Facilities(),
                    parkable = true, openNow = true,
                    routeDistanceM = (st.km - x) * 1000.0, etaS = traffic.hours(x, st.km) * 3600.0, directionOk = true,
                )
            }
            val jamsAhead = traffic.jams.filter { it.endKm > x }.map {
                CongestionSegment(
                    startDistanceM = (it.startKm - x) * 1000.0,
                    lengthM = (it.endKm - it.startKm) * 1000.0,
                    durationS = (it.endKm - it.startKm) / it.kph * 3600.0,
                )
            }
            val route = RouteState(
                guiding = true, remainDistanceM = (routeLen - x) * 1000.0, remainTimeS = traffic.hours(x, routeLen) * 3600.0,
                congestion = jamsAhead,
            )
            val out = e.update(
                CoachInput(
                    now = departure + (t * 1000.0).toLong(),
                    gear = if (parked) Gear.PARK else Gear.DRIVE,
                    speedKph = if (parked) 0f else traffic.speedAt(x).toFloat(),
                    doorOpened = door,
                    route = route,
                    candidates = cands,
                    intent = intent,
                ),
            )
            lastOut = out
            // 이 시뮬레이션의 사람은 식사 효과가 없고 자지 않으며(groundTruth), 정차는 7·15분이라 식사 정차(20분)가 되지 않는다.
            // 엔진이 문맥 보정(need-covariates.md 8장)을 걸었다면 그 가정이 깨진 것이다: 발표한 수치를 조용히 바꾸지 않고 멈춘다.
            // 정차 시간을 늘리는 등으로 여기에 걸리면, 실제 사람에게도 식사 효과를 넣을지 엔진의 배수를 끌지(CoachConfig) 정하고 가정에 적는다
            check(out.restroom.reasons.none { it.code in CONTEXT_REASONS }) {
                "효과 시뮬레이션에 없는 문맥 보정이 걸렸다: ${out.restroom.reasons.map { it.code }} (t=${t / 60.0}분, x=${x}km)"
            }
            val p = out.prompt ?: return
            if (!seen.add(p.id)) return
            log(t, x, "PROMPT ${p.kind} → ${p.stop?.name} : ${p.speech}")
            when (p.kind) {
                PromptKind.LAST_CHANCE, PromptKind.REST_SUGGESTION -> {
                    if (p.kind == PromptKind.LAST_CHANCE && intent is VoiceIntent.OccupantNeed) {
                        needAnswers++ // 필요를 말한 그 update의 답: 엔진이 먼저 묻는 질문 수에 넣지 않는다
                    } else if (p.kind == PromptKind.LAST_CHANCE) {
                        lcPrompts++
                        // 어떤 규칙의 질문인지 문구 틀로 나눈다 (진단용)
                        when {
                            p.speech.startsWith("앞에 ") -> lcCongestion++
                            p.speech.startsWith("화장실 여유가 거의 없어요") -> lcNow++
                            else -> lcGap++
                        }
                    } else {
                        restPrompts++
                    }
                    if (parked) { parkedPrompts++; return } // 이미 서 있다: 대답하지 않는다
                    val ok = policy.pAccept >= 1.0 || acceptRng.nextDouble() < policy.pAccept
                    answers.addLast(t + ANSWER_DELAY_S to (if (ok) VoiceIntent.Accept(p.id) else VoiceIntent.Decline(p.id)))
                    val id = p.stop?.id?.toIntOrNull()
                    if (ok && id != null && stops[id].km > x) {
                        accepted++
                        if (engineT < 0 || stops[id].km < stops[engineT].km) { engineT = id; engineKind = p.kind }
                    }
                }
                PromptKind.CHECK_IN -> {
                    if (parked) { parkedPrompts++; return }
                    answers.addLast(t + ANSWER_DELAY_S to VoiceIntent.Decline(p.id)) // 이 시뮬레이션의 운전자는 졸리지 않다고 답한다
                }
                else -> {}
            }
        }

        fun nearestTarget(): Int {
            var best = -1
            for (cand in intArrayOf(reactive, engineT, scheduled)) {
                if (cand < 0) continue
                if (best < 0 || stops[cand].km < stops[best].km) best = cand
            }
            return best
        }

        fun dwellFor(target: Int): Double = when (policy) {
            Policy.NOW_LIKE -> config.restroomDwellMin.toDouble()
            Policy.EVERY_2H ->
                if (target == scheduled || drivingSinceRest >= REST_MERGE_S) config.restDwellMin.toDouble()
                else config.restroomDwellMin.toDouble()
            else -> {
                val restAsked = target == engineT && engineKind == PromptKind.REST_SUGGESTION
                val restDueSoon = (lastOut?.fatigue?.level ?: Level.OK) >= Level.SOON
                if (restAsked || restDueSoon) config.restDwellMin.toDouble() else config.restroomDwellMin.toDouble()
            }
        }

        fun fire(p: PersonState) {
            val parked = parkedUntil >= 0.0
            if (parked) {
                // 휴게소에 서 있는 동안 필요해졌다: 바로 해결
                waits += 0.0
                p.reset(t)
                return
            }
            p.active = true
            p.onset = t
            if (traffic.inJam(x)) inJamOnset = true
            log(t, x, "NEED ${p.spec.id}${if (traffic.inJam(x)) " (정체 안)" else ""}")
            val st = nextStopAhead(EXIT_MIN_KM)
            if (st >= 0 && (reactive < 0 || stops[st].km < stops[reactive].km)) reactive = st
            engineUpdate(VoiceIntent.OccupantNeed(p.spec.id, Purpose.RESTROOM), false)
        }

        fun arrive(target: Int) {
            x = stops[target].km
            val dwell = dwellFor(target)
            log(t, x, "STOP ${stops[target].name} ${dwell.toInt()}분" + persons.filter { it.active }.joinToString("") { " wait(${it.spec.id})=%.0f분".format((t - it.onset) / 60.0) })
            var anyActive = false
            for (p in persons) {
                if (p.active) { waits += t - p.onset; anyActive = true }
                p.reset(t) // 정차 때 모두 화장실에 간다
            }
            nStops++
            if (anyActive) nReactive++
            currentDwellMin = dwell
            parkedUntil = t + dwell * 60.0
            reactive = -1
            engineT = -1
            engineKind = null
            scheduled = -1
            engineUpdate(null, true)
        }

        fun depart() {
            if (currentDwellMin >= config.restMinParkedMin) {
                drivingSinceRest = 0.0
                nRest++
                restDue = false
            }
            parkedUntil = -1.0
            engineUpdate(null, false)
        }

        // 출발 전에 이미 필요해진 사람
        log(0.0, 0.0, "START jams=" + traffic.jams.joinToString { "%.0f-%.0fkm@%.0f".format(it.startKm, it.endKm, it.kph) } +
            " medians=" + s.trueMedians.joinToString { "%.0f".format(it) } + " offsets=" + s.offsetsMin.joinToString { "%.0f".format(it) })
        engineUpdate(null, false)
        nextTick = TICK_S
        for (p in persons) if (p.nextNeed <= 0.0) fire(p)

        var guard = 0
        while (guard++ < 2_000_000) {
            val parked = parkedUntil >= 0.0
            val target = if (!parked) nearestTarget() else -1
            val tStop = if (target >= 0) t + traffic.hours(x, stops[target].km) * 3600.0 else INF
            val tDest = if (!parked) t + traffic.hours(x, routeLen) * 3600.0 else INF
            val tPark = if (parked) parkedUntil else INF
            var tNeed = INF
            for (p in persons) if (!p.active && p.nextNeed < tNeed) tNeed = p.nextNeed
            val t2h = if (policy == Policy.EVERY_2H && !parked && !restDue) t + maxOf(0.0, REST_EVERY_S - drivingSinceRest) else INF
            val tTick = if (policy.engine) nextTick else INF
            val tAns = answers.firstOrNull()?.first ?: INF
            val tNext = maxOf(t, minOf(minOf(tStop, tDest, tPark), minOf(tNeed, t2h), minOf(tTick, tAns)))
            if (!parked && tNext > t) {
                val dt = tNext - t
                x = traffic.advance(x, dt / 3600.0)
                drivingSinceRest += dt
                if (drivingSinceRest > maxStretch) maxStretch = drivingSinceRest
            }
            t = tNext
            if (!parked && t >= tDest - 1e-6 && (target < 0 || tDest <= tStop)) {
                for (p in persons) if (p.active) waits += t - p.onset
                break
            }
            if (target >= 0 && t >= tStop - 1e-6) arrive(target)
            else if (parked && t >= tPark - 1e-6) depart()
            for (p in persons) if (!p.active && p.nextNeed <= t + 1e-6) fire(p)
            if (t >= t2h - 1e-6) {
                restDue = true
                scheduled = nextStopAhead(EXIT_MIN_KM)
            }
            while (answers.isNotEmpty() && answers.first().first <= t + 1e-6) {
                val a = answers.removeFirst().second
                log(t, x, "ANSWER $a")
                engineUpdate(a, false)
            }
            if (policy.engine && t >= nextTick - 1e-6) {
                engineUpdate(null, false)
                nextTick += TICK_S
            }
        }
        check(guard < 2_000_000) { "simulation did not finish" }
        return TripResult(
            maxWaitS = waits.maxOrNull() ?: 0.0,
            waitsS = waits.toDoubleArray(),
            inJamOnset = inJamOnset,
            stops = nStops,
            restStops = nRest,
            reactiveStops = nReactive,
            tripS = t,
            maxStretchS = maxStretch,
            lastChancePrompts = lcPrompts,
            restPrompts = restPrompts,
            acceptedStops = accepted,
            promptsWhileParked = parkedPrompts,
            lcCongestion = lcCongestion,
            lcGap = lcGap,
            lcNow = lcNow,
            needAnswers = needAnswers,
        )
    }

    // ------------------------------------------------------------ 전체 실행과 집계

    fun run(trips: Int, threads: Int = Runtime.getRuntime().availableProcessors()): Map<String, Any?> {
        val conditions = ArrayList<Condition>()
        occupantSets.forEachIndexed { i, o ->
            for (holiday in listOf(true, false)) for (sigma in listOf(0.3, 0.0)) conditions += Condition(i, o, holiday, sigma)
        }
        val pool = Executors.newFixedThreadPool(threads)
        val futures = LinkedHashMap<Pair<Int, Policy>, java.util.concurrent.Future<Array<TripResult>>>()
        val setups = conditions.map { c -> Array(trips) { setup(c, it) } }
        // 무거운 일(엔진 정책)부터 넣어 스레드를 고르게 쓴다
        for (policy in Policy.entries.sortedByDescending { it.engine }) {
            conditions.forEachIndexed { ci, c ->
                futures[ci to policy] = pool.submit(Callable { Array(trips) { simulate(c, setups[ci][it], policy) } })
            }
        }
        // 여정 하나라도 실패하면(위의 문맥 보정 점검 등) 남은 일을 버리고 끝낸다. 예전에는 스레드 풀이 남아 프로세스가 끝나지 않았다
        val results = try {
            futures.mapValues { it.value.get() }
        } finally {
            pool.shutdownNow()
        }

        val out = ArrayList<Map<String, Any?>>()
        conditions.forEachIndexed { ci, c ->
            val base = results.getValue(ci to Policy.NOW_LIKE)
            val pol = LinkedHashMap<String, Any?>()
            val vs = LinkedHashMap<String, Any?>()
            for (p in Policy.entries) {
                val r = results.getValue(ci to p)
                pol[p.key] = metrics(r)
                if (p != Policy.NOW_LIKE) vs[p.key] = paired(r, base)
            }
            val s0 = setups[ci]
            out += obj(
                "key" to c.key,
                "occupants" to c.occ.label,
                "occupantKey" to c.occ.key,
                "traffic" to if (c.holiday) "holiday" else "free",
                "sigma" to c.sigma,
                "trips" to trips,
                "people" to c.occ.people.map { obj("id" to it.id, "type" to it.type.name, "age" to it.age, "enginePriorMedianMin" to priorMedian(it)) },
                "meanJamsPerTrip" to s0.map { it.traffic.jams.size.toDouble() }.average(),
                "meanJamDelayMin" to s0.map { (it.traffic.hours(0.0, routeLen) - routeLen / FREE_KPH) * 60.0 }.average(),
                "policies" to pol,
                "vsNowLike" to vs,
            )
        }
        return obj(
            "generatedBy" to "rest-engine/src/sim (./gradlew -p rest-engine runSim), 엔진 코드 그대로 (커밋 0ee4de0 설정)",
            "route" to obj(
                "name" to "경부고속도로 서울→부산 (부산방향)",
                "lengthKm" to routeLen,
                "stops" to stops.size,
                "restAreas" to stops.count { it.kind == StopKind.HIGHWAY_REST_AREA },
                "shelters" to stops.count { it.kind == StopKind.DROWSY_SHELTER },
                "source" to "docs/plan/data/analysis.json → gyeongbu_south.stops[] (fromOriginKm)",
            ),
            "policies" to Policy.entries.associate { it.key to it.label },
            "assumptions" to assumptions(trips),
            "limitations" to limitations(),
            "metricDefinitions" to metricDefinitions(),
            "headline" to headline(out),
            "conditions" to out,
        )
    }

    private fun metrics(r: Array<TripResult>): Map<String, Any?> {
        val n = r.size
        val maxWaitMin = DoubleArray(n) { r[it].maxWaitS / 60.0 }
        val allWaits = r.flatMap { tr -> tr.waitsS.map { it / 60.0 } }.toDoubleArray()
        return obj(
            "crisisOver30MinShare" to prop(r.count { it.maxWaitS > 1800.0 }, n),
            "over15MinShare" to prop(r.count { it.maxWaitS > 900.0 }, n),
            "maxWaitPerTripMin" to obj("mean" to meanCi(maxWaitMin), "p90" to quantile(maxWaitMin, 0.9)),
            "waitPerNeedMin" to obj(
                "needEventsPerTrip" to meanCi(DoubleArray(n) { r[it].waitsS.size.toDouble() }),
                "mean" to if (allWaits.isEmpty()) null else allWaits.average(),
                "p90" to if (allWaits.isEmpty()) null else quantile(allWaits, 0.9),
            ),
            "needInsideCongestionShare" to prop(r.count { it.inJamOnset }, n),
            "stopsPerTrip" to meanCi(DoubleArray(n) { r[it].stops.toDouble() }),
            "restStopsPerTrip" to meanCi(DoubleArray(n) { r[it].restStops.toDouble() }),
            "reactiveStopsPerTrip" to meanCi(DoubleArray(n) { r[it].reactiveStops.toDouble() }),
            "tripMin" to meanCi(DoubleArray(n) { r[it].tripS / 60.0 }),
            "stretchOver2hShare" to prop(r.count { it.maxStretchS > 7200.0 }, n),
            "stretchOver2h30Share" to prop(r.count { it.maxStretchS > 9000.0 }, n),
            "longestStretchMin" to meanCi(DoubleArray(n) { r[it].maxStretchS / 60.0 }),
            "enginePromptsPerTrip" to obj(
                "lastChance" to DoubleArray(n) { r[it].lastChancePrompts.toDouble() }.average(),
                "lastChanceByRule" to obj(
                    "congestion" to DoubleArray(n) { r[it].lcCongestion.toDouble() }.average(),
                    "gapOrCoverage" to DoubleArray(n) { r[it].lcGap.toDouble() }.average(),
                    "gaugeNow" to DoubleArray(n) { r[it].lcNow.toDouble() }.average(),
                ),
                "restSuggestion" to DoubleArray(n) { r[it].restPrompts.toDouble() }.average(),
                "needAnswers" to DoubleArray(n) { r[it].needAnswers.toDouble() }.average(),
                "acceptedStops" to DoubleArray(n) { r[it].acceptedStops.toDouble() }.average(),
                "promptsWhileParked" to DoubleArray(n) { r[it].promptsWhileParked.toDouble() }.average(),
            ),
        )
    }

    /** 같은 여정(공통 난수)끼리 짝지은 차이: 정책 − 지금처럼 */
    private fun paired(r: Array<TripResult>, base: Array<TripResult>): Map<String, Any?> {
        val n = r.size
        return obj(
            "crisisOver30MinShareDiff" to meanCi(DoubleArray(n) { ind(r[it].maxWaitS > 1800.0) - ind(base[it].maxWaitS > 1800.0) }),
            "extraStops" to meanCi(DoubleArray(n) { (r[it].stops - base[it].stops).toDouble() }),
            "extraTripMin" to meanCi(DoubleArray(n) { (r[it].tripS - base[it].tripS) / 60.0 }),
        )
    }

    private fun headline(conds: List<Map<String, Any?>>): Map<String, Any?> {
        fun pick(key: String) = conds.first { it["key"] == key }
        val h = pick("child7_holiday_sigma0.3")
        @Suppress("UNCHECKED_CAST")
        val pol = h["policies"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val vs = h["vsNowLike"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        fun crisis(p: String) = (pol.getValue(p)["crisisOver30MinShare"] as Map<String, Any?>)["value"]
        @Suppress("UNCHECKED_CAST")
        fun v(p: String, m: String) = ((vs.getValue(p)[m]) as Map<String, Any?>)["mean"]
        return obj(
            "condition" to "운전자 + 아이 7세, 명절 정체, 사람마다 실제 간격이 사전값과 다름(sigma 0.3)",
            "crisisOver30MinShare" to Policy.entries.associate { it.key to crisis(it.key) },
            "shimpyoP07vsNowLike" to obj("extraStops" to v("shimpyoP07", "extraStops"), "extraTripMin" to v("shimpyoP07", "extraTripMin")),
            "shimpyoP10vsNowLike" to obj("extraStops" to v("shimpyoP10", "extraStops"), "extraTripMin" to v("shimpyoP10", "extraTripMin")),
            "every2hVsNowLike" to obj("extraStops" to v("every2h", "extraStops"), "extraTripMin" to v("every2h", "extraTripMin")),
        )
    }

    private fun assumptions(trips: Int): Map<String, Any?> = obj(
        "tripsPerConditionAndPolicy" to trips,
        "commonRandomNumbers" to "같은 조건의 정책들은 같은 여정(정체, 마지막 화장실, 사람별 실제 중앙값, 필요 난수열)을 공유한다. 차이는 짝지은 여정끼리 계산",
        "departure" to "2026-09-24 09:00 (KST), 기상 시각 미입력 → 엔진 기본 07:00, 수면 미입력",
        "route" to "서울(0km) → 부산(${routeLen}km), 정차 후보 ${stops.size}곳 모두 화장실 있음, 경로 위(우회 0분)",
        "traffic" to obj(
            "freeFlowKph" to FREE_KPH,
            "holiday" to "정체 1~3개(균등), 시작 20~380km 균등, 길이 5~30km 균등, 속도 10~30km/h 균등, 겹치지 않게",
            "free" to "정체 없음",
            "engineKnowledge" to "엔진은 내비가 주는 것처럼 앞쪽 정체 구간(시작·길이·통과 시간)과 후보별 정확한 ETA를 안다",
        ),
        "occupants" to occupantSets.map { o -> obj("key" to o.key, "label" to o.label, "people" to o.people.map { "${it.type}${it.age?.let { a -> "($a)" } ?: ""}" }) },
        "lastRestroomBeforeDeparture" to "사람마다 출발 0~30분 전 균등. 엔진에도 그 시각을 알려 준다(앱이 출발 때 묻는 상황)",
        "groundTruth" to obj(
            "model" to "사람마다 마지막 화장실 이후 필요해지는 시간 ~ 와이블(k = $k)",
            "trueMedian" to "엔진의 유형별 사전 중앙값 × exp(sigma·Z), Z ~ N(0,1). sigma 0.3(사람마다 다름)과 0(사전값이 맞음) 두 경우",
            "reset" to "어느 정차든 도착 시각에 모든 사람의 시계가 0으로 돌아가고 다음 필요 시각을 새로 뽑는다",
            "needDuringStop" to "정차 중에 필요해지면 기다림 0",
            "needBeforeDeparture" to "필요 시각이 출발 전이면 출발 시각부터 기다린다",
        ),
        "policies" to obj(
            "nowLike" to "누군가 필요해지면 0.5km 이상 앞의 다음 정차 후보에 선다(화장실 정차 ${config.restroomDwellMin}분)",
            "every2h" to "위와 같고, 마지막 15분 이상 휴식 뒤 2시간을 달리면 0.5km 이상 앞의 다음 후보에서 ${config.restDwellMin}분 쉰다. " +
                "급해서 선 정차라도 휴식 뒤 100분 이상이면 ${config.restDwellMin}분으로 늘린다",
            "shimpyo" to "실제 CoachEngine에 30초마다(그리고 필요 발생·정차·출발·대답 때) 후보·정체·차속·기어를 넣는다. 마지막 기회·휴식 제안 질문에 " +
                "확률 p로 '응'(5초 뒤 대답), 응이면 그곳에 선다. 상태 확인(CHECK_IN)에는 '아니'. 정차 중 나온 질문에는 대답하지 않는다. " +
                "필요가 생기면 '화장실 급해'(OccupantNeed)를 알리고 기준선처럼 다음 후보에 선다. 정차 시간: 휴식 제안이었거나 엔진 피로가 SOON 이상이면 " +
                "${config.restDwellMin}분, 아니면 ${config.restroomDwellMin}분. 정차는 기어 P + 문 열림으로 엔진에 들어간다",
            "acceptProbability" to listOf(0.7, 1.0),
        ),
        "engineConfig" to obj(
            "weibullShape" to config.restroomWeibullShape,
            "priorStrengthA0" to config.restroomPriorStrength,
            "decisionProbability" to config.restroomDecisionProbability,
            "gaugeProbability" to config.restroomNeedProbability,
            "restMinParkedMin" to config.restMinParkedMin,
            "restroomStopMinParkedMin" to config.restroomStopMinParkedMin,
            "fatigueNowMin" to config.fatigueNowMin,
            "askWindowKm" to listOf(config.askWindowNearM / 1000.0, config.askWindowFarM / 1000.0),
            "lastChanceMinGapMin" to config.lastChanceMinGapMin,
            "congestionAlertMin" to listOf(config.congestionAlertMin, config.congestionAlertSensitiveMin),
        ),
        "tickSeconds" to TICK_S,
        "answerDelaySeconds" to ANSWER_DELAY_S,
        "exitMinDistanceKm" to EXIT_MIN_KM,
        "memory" to "여정마다 빈 개인 기록(사전값만)으로 시작. 개인화 효과는 넣지 않았다",
        "seeds" to "조합·여정 번호로 정해지는 고정 시드. 같은 명령이면 같은 결과",
    )

    private fun limitations(): List<String> = listOf(
        "실제 필요 시각을 엔진 사전분포와 같은 와이블 계열로 만들었다. 실제 사람의 간격 분포가 다르면 결과가 달라진다",
        "엔진은 첫 여정 기준(개인 기록 없음)으로 돌렸다. 기록이 쌓여 사람별 중앙값을 배우는 효과는 빠졌다",
        "휴게소 혼잡·주차 대기·화장실 줄은 넣지 않았다. 정차 시간은 엔진 상수(7분, 15분)로 고정",
        "정체는 단순 모형(1~3개 균등)이고 엔진은 정체 위치·통과 시간을 정확히 안다고 가정했다. 실제 내비 정체 정보는 늦거나 틀릴 수 있다",
        "필요가 생기면 바로 말하고, 그 사람이 참을 수 있는 한계나 급한 정도는 모형에 없다. 기다림 30분을 '위기'로 정의했다",
        "운전자는 항상 시간 맞춰 반응하고, 0.5km 안의 진입로는 놓친다고만 가정했다. 갓길 정차·국도 우회 같은 다른 선택지는 없다",
        "피로는 엔진의 시간 기준 모형으로만 보고, 실제 졸음이나 사고는 다루지 않는다. 운전 패턴 신호(조향)는 넣지 않았다",
        "결과는 경부고속도로 한 노선, 오전 9시 출발에 대한 것이다",
    )

    private fun metricDefinitions(): Map<String, Any?> = obj(
        "crisisOver30MinShare" to "한 여정에서 누군가 필요해진 뒤 화장실에 닿기까지 30분을 넘게 기다린 적이 있는 여정의 비율",
        "over15MinShare" to "같은 기준 15분",
        "maxWaitPerTripMin" to "여정마다 가장 긴 기다림(필요가 한 번도 없으면 0)의 평균과 90분위",
        "waitPerNeedMin" to "필요가 생긴 모든 경우의 기다림 (정책이 미리 세워 필요가 생기지 않으면 그 경우는 세지 않는다)",
        "needInsideCongestionShare" to "누군가 정체 구간 안에서 필요해진 여정의 비율",
        "stopsPerTrip" to "모든 정차 수 (rest = 15분 이상, reactive = 도착 때 이미 누군가 필요했던 정차)",
        "tripMin" to "출발부터 부산 도착까지 (정차 포함)",
        "stretchOver2hShare" to "15분 이상 쉬지 않고 이어 달린 시간이 2시간을 넘은 적이 있는 여정의 비율. 2시간을 넘긴 뒤 '다음' 휴게소에 서므로 " +
            "규칙대로 쉬어도 조금은 넘는다 → stretchOver2h30Share(2시간 30분)와 longestStretchMin도 함께 본다",
        "enginePromptsPerTrip" to "엔진이 먼저 건 질문 수(마지막 기회는 규칙별로 나눈다). needAnswers는 탑승자가 필요를 말한 순간 엔진이 바로 답한 " +
            "다음 화장실 안내라 따로 센다(답에도 시뮬레이션 운전자는 같은 확률로 '응'한다)",
        "ci95" to "비율은 윌슨 95% 구간, 평균은 ±1.96·표준오차",
    )

    // ------------------------------------------------------------ 통계

    private fun ind(b: Boolean) = if (b) 1.0 else 0.0

    private fun prop(count: Int, n: Int): Map<String, Any?> {
        val p = count.toDouble() / n
        val z = 1.959964
        val denom = 1 + z * z / n
        val center = (p + z * z / (2 * n)) / denom
        val half = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom
        return obj("value" to p, "ci95" to listOf(maxOf(0.0, center - half), minOf(1.0, center + half)), "count" to count)
    }

    private fun meanCi(xs: DoubleArray): Map<String, Any?> {
        val n = xs.size
        val m = xs.average()
        var ss = 0.0
        for (v in xs) ss += (v - m) * (v - m)
        val se = if (n > 1) sqrt(ss / (n - 1) / n) else 0.0
        return obj("mean" to m, "se" to se, "ci95" to listOf(m - 1.959964 * se, m + 1.959964 * se))
    }

    private fun quantile(xs: DoubleArray, q: Double): Double {
        val s = xs.sortedArray()
        if (s.isEmpty()) return Double.NaN
        val pos = q * (s.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    companion object {
        const val FREE_KPH = 100.0
        const val TICK_S = 30.0
        const val ANSWER_DELAY_S = 5.0
        const val EXIT_MIN_KM = 0.5
        const val REST_EVERY_S = 7_200.0
        const val REST_MERGE_S = 6_000.0
        const val INF = Double.POSITIVE_INFINITY
        /** 화장실 게이지에 문맥 보정이 걸렸을 때 붙는 이유 코드 (RestroomNeedModel.gauge) */
        val CONTEXT_REASONS = setOf("RESTROOM_AFTER_MEAL", "RESTROOM_ASLEEP")
    }
}

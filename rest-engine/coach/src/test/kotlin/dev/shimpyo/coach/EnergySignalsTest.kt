package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.CoreRig
import dev.shimpyo.coach.fakes.WorldClock
import dev.shimpyo.engine.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Test

class EnergySignalsTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun successfulUnchangedPollsKeepStaticEnergyFreshButFailuresExpireIt() = runTest {
        val clock = ElapsedClock { testScheduler.currentTime }
        val hub = SignalHub(clock)
        var readable = true
        val source = SignalSource("range", read = { if (!readable) error("unavailable") else 58.0 },
            changes = { flow { emit(58.0); awaitCancellation() } }, reportReads = true)
        val link = object : SignalLink {
            override val name = "energy"
            override suspend fun connect(again: Boolean) {}
            override val groups = listOf(SignalGroup.of(source) { hub.rangeKm = it })
        }
        backgroundScope.launch { SignalCollector(clock, CoachLog.NONE).run(link) }
        runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(58.0, hub.energySnapshot().rangeKm!!, 0.0)
        readable = false
        advanceTimeBy(120_000); runCurrent()
        assertNull(hub.energySnapshot().rangeKm)
    }

    @Test fun unknownInvalidAndStaleSignalsAreNullAndARefreshRestoresThem() {
        val clock = WorldClock(0)
        val hub = SignalHub(clock)
        assertEquals(VehicleEnergy(), hub.energySnapshot())
        hub.rangeKm = 58.5
        hub.energyKind = EnergyKind.ELECTRIC
        assertEquals(VehicleEnergy(58.5, EnergyKind.ELECTRIC), hub.energySnapshot())
        clock.advance(90_001)
        assertEquals(VehicleEnergy(), hub.energySnapshot())
        hub.rangeKm = 55.0
        assertEquals(VehicleEnergy(55.0, null), hub.energySnapshot())
        for (range in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            hub.rangeKm = range
            assertNull(hub.energySnapshot().rangeKm)
        }
        hub.rangeKm = 0.0
        assertEquals(0.0, hub.energySnapshot().rangeKm!!, 0.0)
        hub.rangeKm = null
        assertNull(hub.energySnapshot().rangeKm)
    }

    @Test fun realSignalsReachUiAndOtherDemosDoNotInheritThem() = runTest {
        val rig = CoreRig(this)
        rig.hub.rangeKm = 80.0
        rig.hub.energyKind = EnergyKind.ELECTRIC
        rig.drive(100f)
        rig.start()
        rig.run(1_000)
        assertEquals(80.0, rig.state.rangeKm!!, 0.0)
        assertEquals(EnergyKind.ELECTRIC, rig.state.energy)
        rig.command(UiCommand.Demo("holiday"))
        rig.run(1_000)
        assertNull(rig.state.rangeKm)
        assertNull(rig.state.energy)
        rig.command(UiCommand.Demo(null))
        rig.run(1_000)
        assertEquals(80.0, rig.state.rangeKm!!, 0.0)
    }
}

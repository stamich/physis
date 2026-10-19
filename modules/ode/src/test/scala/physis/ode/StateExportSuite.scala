package physis.ode

import physis.core.live.{Snapshot, StateExport}
import physis.core.random.SplittableRng
import physis.verify.Allocation

class StateExportSuite extends munit.FunSuite:
  private val exporter = summon[StateExport[OdeState]]
  private val p = OscillatorParams(omega = 1.0)

  test("the ODE state vector is exported as is") {
    val state = Oscillator.model.init(p, SplittableRng(0L))
    val snapshot = Snapshot(exporter.size(state), probeCount = 0)
    exporter.copyInto(state, snapshot.state)
    assertEquals(snapshot.state.toVector, Vector(1.0, 0.0))
  }

  test("exporting into a snapshot does not allocate") {
    assume(Allocation.supported, "per-thread allocation not supported")
    val state = Oscillator.model.init(p, SplittableRng(0L))
    val snapshot = Snapshot(exporter.size(state), probeCount = 0)
    val bytes = Allocation.perStep(10000) {
      exporter.copyInto(state, snapshot.state)
    }
    assert(bytes < 1024L, s"allocated $bytes bytes in 10^4 exports")
  }

  test("snapshot parameters are looked up by name") {
    val snapshot = Snapshot(stateSize = 2, probeCount = 0)
    snapshot.paramNames = Vector("omega", "damping")
    snapshot.paramValues = Vector(2.0, 0.1)
    assertEquals(snapshot.param("damping"), 0.1)
    assert(snapshot.param("missing").isNaN)
  }

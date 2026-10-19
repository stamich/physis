package physis.models.wave

import physis.view.{View, ValueRange}
import WaveSupport.*

class WaveViewsSuite extends munit.FunSuite:
  test("the field view reads slices the wave state exports, and walls are its overlay") {
    val r = run(WaveParams.schema.defaults.copy(nx = 64, ny = 64))
    val names = WaveState.stateExport.layout(r.state).map(_.name)
    WaveViews.field match
      case h: View.Heatmap =>
        assert(names.contains(h.slice))
        assertEquals(h.overlay, Some("wall"))
        assert(names.contains("wall"))
        assertEquals(h.range, ValueRange.SymmetricAuto)
      case other => fail(s"expected a heatmap, got $other")
  }

package physis.models.fluid

import physis.view.{Colormap, ValueRange, View}
import FluidSupport.*

class FluidViewsSuite extends munit.FunSuite:
  private val r = run(params(FluidEdge.Box, 48, 32))
  private val names = FluidState.stateExport.layout(r.state).map(_.name)

  test("the views read slices that the fluid state exports, and walls are their overlay") {
    for h <- Seq(FluidViews.dye, FluidViews.vorticity, FluidViews.speed) do
      assert(names.contains(h.slice), h.slice)
      assertEquals(h.overlay, Some("wall"))
    assert(names.contains("wall"))
  }

  test("the vorticity has a fixed scale centred at zero on a diverging map") {
    assertEquals(FluidViews.vorticity.range, ValueRange.Symmetric(0.5))
    assertEquals(FluidViews.vorticity.colormap, Colormap.Diverging)
    assertEquals(FluidViews.dye.range, ValueRange.Fixed(0.0, 1.0))
  }

  test("the plots read the probes they name") {
    assertEquals(Fluid.probes(0).name, "kinetic_energy")
    assertEquals(Fluid.probes(2).name, "enstrophy")
    FluidViews.default match
      case View.Layout(Seq(_: View.Heatmap, _: View.Layout), _) => ()
      case other => fail(s"unexpected default view $other")
  }

  test("the exported slices hold the dye, the vorticity, the speed and the walls") {
    val s = r.state
    for j <- 10 until 14; i <- 10 until 14 do s.dye(i, j) = 0.75
    s.wall(30, 20) = true
    s.wallsChanged()
    val _ =
      Fluid.intervene(s, r.params, physis.core.run.Intervention.Pulse("u", 24.0, 16.0, 3.0, 1.0))
    val slices = FluidState.stateExport
    val out = new Array[Double](slices.size(s))
    slices.copyInto(s, out)
    val n = 48 * 32
    assertEquals(out(12 * 48 + 12), 0.75)
    assertEquals(out(3 * n + 20 * 48 + 30), 1.0)
    assertEquals(out(3 * n + 20 * 48 + 29), 0.0)
    var speed = 0.0
    var swirl = 0.0
    for k <- 0 until n do
      speed = math.max(speed, out(2 * n + k))
      swirl = math.max(swirl, math.abs(out(n + k)))
    assert(speed > 0.5 && swirl > 0.01, s"speed $speed vorticity $swirl")
    assertEquals(slices.size(s), 4 * n)
  }

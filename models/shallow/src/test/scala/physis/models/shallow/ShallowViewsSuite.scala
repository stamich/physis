package physis.models.shallow

import physis.view.{Channel, Colormap, ValueRange, View}
import ShallowSupport.*

class ShallowViewsSuite extends munit.FunSuite:
  private val r = run(params(nx = 32, ny = 16, f0 = 1e-4))
  private val names = ShallowState.stateExport.layout(r.state).map(_.name)

  test("the views read slices that the state exports, and land is their overlay") {
    for h <- Seq(
        ShallowViews.height,
        ShallowViews.speed,
        ShallowViews.vorticity,
        ShallowViews.depth
      )
    do
      assert(names.contains(h.slice), h.slice)
      assertEquals(h.overlay, Some("land"))
    assert(names.contains("land"))
  }

  test("the height and the vorticity are centred at zero on a diverging map") {
    assertEquals(ShallowViews.height.range, ValueRange.SymmetricAuto)
    assertEquals(ShallowViews.height.colormap, Colormap.Diverging)
    assertEquals(ShallowViews.vorticity.range, ValueRange.SymmetricAuto)
    assertEquals(ShallowViews.vorticity.colormap, Colormap.Diverging)
  }

  test("the plots read the energy and the highest surface by their probe index") {
    assertEquals(ShallowViews.energy, Channel.ProbeAt(1, "energy"))
    assertEquals(ShallowViews.highest, Channel.ProbeAt(2, "highest surface"))
    assertEquals(Shallow.probes(1).name, "energy")
    assertEquals(Shallow.probes(2).name, "max_height")
  }

  test("the Lab view has the surface above the two plots") {
    ShallowViews.lab match
      case View.Layout(Seq(top: View.Heatmap, _: View.Layout), _) =>
        assertEquals(top.slice, "height")
      case other => fail(s"unexpected layout $other")
  }

  test("the exported slices have the size of the grid and carry height, speed, depth and land") {
    val s = r.state
    hump(s, 16.0, 8.0, 2.0, 1.0)
    for j <- 4 until 6; i <- 20 until 24 do s.land(i, j) = true
    s.landChanged()
    r.runSteps(5)
    val g = s.mac.cells
    val out = new Array[Double](ShallowState.stateExport.size(s))
    ShallowState.stateExport.copyInto(s, out)
    assertEquals(out.length, 5 * g.cells)
    assertEquals(
      ShallowState.stateExport.layout(s).map(_.name),
      Vector("height", "speed", "vorticity", "depth", "land")
    )
    assertEqualsDouble(out(8 * g.nx + 16), s.eta(16, 8), 1e-12)
    val depth = out.slice(3 * g.cells, 4 * g.cells)
    assert(depth.forall(_ == 4000.0))
    val land = out.slice(4 * g.cells, 5 * g.cells)
    assertEquals(land.sum, 8.0)
    assert(out.slice(g.cells, 2 * g.cells).max > 0.0, "the water moves")
  }

  test("the vorticity of a hump's swirl is clockwise, that is negative, seen from above") {
    val q = run(params(nx = 41, ny = 41, f0 = 1e-3))
    hump(q.state, 20.5, 20.5, 3.0, 1.0)
    q.runSteps(120)
    val g = q.state.mac.cells
    val out = new Array[Double](ShallowState.stateExport.size(q.state))
    ShallowState.stateExport.copyInto(q.state, out)
    val vorticity = out.slice(2 * g.cells, 3 * g.cells)
    // the swirl round the middle: sum of the vorticity near the hump
    var near = 0.0
    for j <- 14 until 27; i <- 14 until 27 do near += vorticity(j * g.nx + i)
    assert(near < 0.0, s"vorticity near the hump $near")
  }

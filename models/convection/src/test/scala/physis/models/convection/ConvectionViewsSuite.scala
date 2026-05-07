package physis.models.convection

import physis.view.{Channel, Colormap, ValueRange, View}
import ConvectionSupport.*

class ConvectionViewsSuite extends munit.FunSuite:
  private val r = run(params(nx = 32, ny = 16))
  private val names = ConvectionState.stateExport.layout(r.state).map(_.name)

  test("the views read slices that the state exports, and walls are their overlay") {
    for h <- Seq(
        ConvectionViews.temperature,
        ConvectionViews.deviation,
        ConvectionViews.vorticity,
        ConvectionViews.speed
      )
    do
      assert(names.contains(h.slice), h.slice)
      assertEquals(h.overlay, Some("wall"))
    assert(names.contains("wall"))
  }

  test("the temperature runs from 0 to 1 and the deviation and vorticity are centred at zero") {
    assertEquals(ConvectionViews.temperature.range, ValueRange.Fixed(0.0, 1.0))
    assertEquals(ConvectionViews.deviation.range, ValueRange.Symmetric(0.3))
    assertEquals(ConvectionViews.deviation.colormap, Colormap.Diverging)
    assertEquals(ConvectionViews.vorticity.colormap, Colormap.Diverging)
  }

  test("the plots read the kinetic energy and the Nusselt number by their probe index") {
    assertEquals(ConvectionViews.energy, Channel.ProbeAt(0, "kinetic energy"))
    assertEquals(ConvectionViews.nusselt, Channel.ProbeAt(2, "Nusselt number"))
    assertEquals(Convection.probes(0).name, "kinetic_energy")
    assertEquals(Convection.probes(2).name, "nusselt")
  }

  test("the Lab view has the temperature above the two plots") {
    ConvectionViews.lab match
      case View.Layout(Seq(top: View.Heatmap, _: View.Layout), _) =>
        assertEquals(top.slice, "temperature")
      case other => fail(s"unexpected layout $other")
  }

  test("the exported slices have the size of the grid and the temperature is hot at the floor") {
    val g = r.state.mac.cells
    val out = new Array[Double](ConvectionState.stateExport.size(r.state))
    ConvectionState.stateExport.copyInto(r.state, out)
    assertEquals(out.length, 5 * g.cells)
    val floor = out(0)
    val lid = out(g.cells - 1)
    assert(floor > 0.9 && lid < 0.1, s"floor $floor lid $lid")
  }

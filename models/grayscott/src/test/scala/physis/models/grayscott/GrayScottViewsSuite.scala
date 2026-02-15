package physis.models.grayscott

import physis.core.run.Physis
import physis.view.{Channel, Direction, ValueRange, View}

class GrayScottViewsSuite extends munit.FunSuite:

  test("the default view is two fields over a plot of the pattern statistics") {
    GrayScottViews.default match
      case View.Layout(
            Seq(View.Layout(Seq(u, v), Direction.Horizontal), plot),
            Direction.Vertical
          ) =>
        assertEquals(u, GrayScottViews.u)
        assertEquals(v, GrayScottViews.v)
        plot match
          case View.Plot(channels, window, _) =>
            assertEquals(channels.size, 3)
            assertEquals(window, 5000.0)
          case other => fail(s"expected a plot, got $other")
      case other => fail(s"unexpected view $other")
  }

  test("the heatmaps show the slices the model exports, with a fixed range from 0 to 1") {
    val state = Physis
      .model(GrayScott)
      .stepping(GrayScott.stepping)
      .overrides(Map("nx" -> 32.0, "ny" -> 32.0))
      .build()
      .state
    val slices = GrayScottState.stateExport.layout(state).map(_.name)
    for view <- Seq(GrayScottViews.u, GrayScottViews.v) do
      view match
        case View.Heatmap(slice, range, _, _) =>
          assert(slices.contains(slice), s"no slice $slice")
          assertEquals(range, ValueRange.Fixed(0.0, 1.0))
        case other => fail(s"expected a heatmap, got $other")
  }

  test("the plot channels point at the probes they name") {
    val names = GrayScott.probes.map(_.name)
    def probeName(c: Channel): String = c match
      case Channel.ProbeAt(i, _) => names(i)
      case other => fail(s"not a probe channel: $other")
    assertEquals(probeName(GrayScottViews.vMean), "v_mean")
    assertEquals(probeName(GrayScottViews.vMax), "v_max")
    assertEquals(probeName(GrayScottViews.coverage), "coverage")
  }

package physis.models.boids

import physis.core.run.Physis
import physis.view.{Channel, Direction, View}

class BoidsViewsSuite extends munit.FunSuite:

  test("the default view is the flock over a plot of its polarization") {
    BoidsViews.default match
      case View.Layout(
            Seq(flock: View.Particles, View.Plot(Seq(channel), _, _)),
            Direction.Vertical
          ) =>
        assertEquals(flock, BoidsViews.flock)
        assertEquals(channel, BoidsViews.polarization)
      case other => fail(s"unexpected view $other")
  }

  test("the particle view names the slices and parameters the model has") {
    val run = Physis
      .model(Boids)
      .stepping(Boids.stepping)
      .overrides(Map("n" -> 10.0))
      .build()
    val slices = BoidsState.stateExport.layout(run.state).map(_.name)
    val names = Boids.params.names
    BoidsViews.flock match
      case p: View.Particles =>
        for s <- Seq(p.x, p.y, p.vx, p.vy) do assert(slices.contains(s), s"no slice $s")
        assert(names.contains(p.widthParam) && names.contains(p.heightParam))
      case other => fail(s"expected particles, got $other")
  }

  test("the polarization channel is probe 0, which is the polarization probe") {
    assertEquals(BoidsViews.polarization, Channel.ProbeAt(0, "polarization"))
    assertEquals(Boids.probes.head.name, "polarization")
  }

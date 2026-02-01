package physis.models.pendulum

import physis.view.{SceneInput, Shape, View}

class PendulumViewsSuite extends munit.FunSuite:
  private val params = Map("l1" -> 1.0, "l2" -> 0.5, "m1" -> 2.0, "m2" -> 1.0)

  private def bobs(theta1: Double, theta2: Double): Seq[(Double, Double)] =
    PendulumViews.draw(SceneInput.of(
      0.0,
      Vector(theta1, theta2, 0.0, 0.0),
      params = params
    )).collect {
      case Shape.Circle(x, y, _, _) => (x, y)
    }

  test("at theta = 0 the bobs hang straight down") {
    assertEquals(bobs(0.0, 0.0), Seq((0.0, 0.0), (0.0, -1.0), (0.0, -1.5)))
  }

  test("at theta = 90 degrees the rods point right") {
    val b = bobs(math.Pi / 2, math.Pi / 2)
    val (x1, y1) = b(1)
    val (x2, y2) = b(2)
    assertEqualsDouble(x1, 1.0, 1e-12)
    assertEqualsDouble(y1, 0.0, 1e-12)
    assertEqualsDouble(x2, 1.5, 1e-12)
    assertEqualsDouble(y2, 0.0, 1e-12)
  }

  test("the trail follows the outer bob; the scene frames both rods") {
    val in = SceneInput.of(0.0, Vector(0.3, -1.1, 0.0, 0.0), params = params)
    val (x2, y2) = PendulumViews.outer(1.0, 0.5, 0.3, -1.1)
    assertEquals(PendulumViews.outerTrail.x(in), x2)
    assertEquals(PendulumViews.outerTrail.y(in), y2)
    PendulumViews.mechanism(PendulumParams.chaotic) match
      case View.Scene2D(bounds, _, Some(_)) =>
        val reach = PendulumParams.chaotic.l1 + PendulumParams.chaotic.l2
        assert(bounds.contains(reach, -reach) && bounds.contains(-reach, reach))
      case other => fail(s"unexpected view $other")
  }

  test("the energy plot reads the energy-drift probe") {
    val probe = Pendulum.probes(PendulumParams.chaotic)(1)
    PendulumViews.energyDrift match
      case View.Plot(Seq(physis.view.Channel.ProbeAt(1, _)), _, _) =>
        assertEquals(probe.name, "energyDrift")
      case other => fail(s"unexpected view $other")
  }

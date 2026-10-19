package physis.view

import physis.core.live.Snapshot

class ViewModelSuite extends munit.FunSuite:
  test("channels read state components and probes, NaN when out of range") {
    val s = new Snapshot(stateSize = 3, probeCount = 1)
    s.state(1) = 4.0
    s.probes(0) = 7.0
    assertEquals(Channel.StateAt(1, "y").read(s), 4.0)
    assertEquals(Channel.ProbeAt(0, "e").read(s), 7.0)
    assert(Channel.StateAt(3, "w").read(s).isNaN)
    assertEquals(Channel.ProbeAt(0, "e").label, "e")
  }

  test("scene input looks parameters up by name") {
    val s = new Snapshot(stateSize = 1, probeCount = 0)
    s.paramNames = Vector("rho", "sigma")
    s.paramValues = Vector(28.0, 10.0)
    val in = SceneInput.of(s)
    assertEquals(in.param("sigma"), 10.0)
    assert(in.param("beta").isNaN)
    assertEquals(SceneInput.of(1.0, Vector(2.0), params = Map("a" -> 3.0)).param("a"), 3.0)
  }

  test("bounds and colours") {
    val b = Bounds.square(2.0)
    assertEquals((b.width, b.centreX), (4.0, 0.0))
    assert(b.contains(2.0, -2.0) && !b.contains(2.1, 0.0))
    assertEquals(b.padded(0.25), Bounds(-3, 3, -3, 3))
    intercept[IllegalArgumentException](Bounds(1, 1, 0, 1))
    assertEquals(Rgb.bytes(255, 0, 128).hex, "#ff0080")
    intercept[IllegalArgumentException](Rgb(1.5, 0, 0))
  }

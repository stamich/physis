package physis.core.units

class UnitsSuite extends munit.FunSuite:

  test("literal constructors keep the value") {
    assertEquals(0.01.s.toDouble, 0.01)
    assertEquals(2.0.m.toDouble, 2.0)
    assertEquals(1.5.kg.toDouble, 1.5)
    assertEquals(9.81.mps2.toDouble, 9.81)
    assertEquals(0.5.rad.toDouble, 0.5)
  }

  test("degrees convert to radians") {
    assertEqualsDouble(180.0.deg.toDouble, math.Pi, 1e-15)
    assertEqualsDouble(90.0.deg.toDouble, math.Pi / 2, 1e-15)
    assertEqualsDouble((-10.0).deg.toDouble, -math.Pi / 18, 1e-15)
  }

  test("radians round-trip through degrees") {
    val angles = Seq(-720.0, -120.0, -0.5, 0.0, 1e-9, 37.25, 120.0, 359.999)
    angles.foreach { a =>
      assertEqualsDouble(a.deg.toDegrees, a, 1e-12 * math.max(1.0, math.abs(a)))
    }
  }

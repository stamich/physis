package physis.view

class CameraSuite extends munit.FunSuite:
  private val box = Bounds3(-1, 1, -1, 1, -1, 1)

  private def camera(yaw: Double, pitch: Double): Camera =
    val c = new Camera
    c.viewport(200, 100)
    c.fit(box, margin = 0.0)
    c.orient(yaw, pitch)
    c

  test("the centre projects to the middle of the viewport") {
    val c = camera(0.3, 0.4)
    assertEqualsDouble(c.viewX(0, 0, 0), 100.0, 1e-12)
    assertEqualsDouble(c.viewY(0, 0, 0), 50.0, 1e-12)
  }

  test("front view: x to the right, z up, y away from the viewer") {
    val c = camera(0.0, 0.0)
    val s = 50.0 / math.sqrt(3.0) // half the short side over the box radius
    assertEqualsDouble(c.viewX(1, 0, 0), 100 + s, 1e-9)
    assertEqualsDouble(c.viewY(0, 0, 1), 50 - s, 1e-9)
    assertEqualsDouble(c.viewX(0, 1, 0), 100.0, 1e-9)
    assertEqualsDouble(c.viewY(0, 1, 0), 50.0, 1e-9)
    assert(c.depth(0, 1, 0) > 0 && c.depth(0, -1, 0) < 0)
  }

  test("yaw by 90 degrees turns +y to the left; pitch from above raises far points") {
    val side = camera(math.Pi / 2, 0.0)
    assert(side.viewX(0, 1, 0) < 100.0)
    assertEqualsDouble(side.viewX(1, 0, 0), 100.0, 1e-9)
    val top = camera(0.0, math.toRadians(60))
    assert(top.viewY(0, 1, 0) < 50.0, "far point should be higher on screen")
    assert(top.depth(0, 0, 1) < 0, "top of the box is nearer when looking down")
  }

  test("fitted points stay inside the viewport in every orientation") {
    val corners = for x <- Seq(-1.0, 1.0); y <- Seq(-1.0, 1.0); z <- Seq(-1.0, 1.0) yield (x, y, z)
    for
      yaw <- -180 to 180 by 15
      pitch <- -85 to 85 by 17
    do
      val c = camera(math.toRadians(yaw), math.toRadians(pitch))
      for (x, y, z) <- corners do
        val vx = c.viewX(x, y, z)
        val vy = c.viewY(x, y, z)
        assert(vx >= -1e-9 && vx <= 200 + 1e-9 && vy >= -1e-9 && vy <= 100 + 1e-9, s"$yaw $pitch")
  }

  test("pitch is clamped, yaw wraps, zoom scales around the centre") {
    val c = camera(0.0, 0.0)
    c.rotate(3 * math.Pi, math.Pi)
    assertEqualsDouble(c.pitch, math.toRadians(89), 1e-12)
    assert(math.abs(c.yaw) <= math.Pi)
    val before = c.viewX(1, 0, 0) - 100
    c.zoomBy(2.0)
    assertEqualsDouble(c.viewX(1, 0, 0) - 100, 2 * before, 1e-9)
    c.fit(box, margin = 0.0)
    assertEquals(c.zoom, 2.0, "fit keeps the zoom")
    c.zoomBy(1e6)
    assertEquals(c.zoom, 50.0)
    c.resetZoom()
    assertEquals(c.zoom, 1.0)
  }

  test("resizing the viewport keeps the fit") {
    val c = camera(0.2, 0.2)
    val r = c.viewX(1, 1, 1) - 100
    c.viewport(400, 200)
    assertEqualsDouble(c.viewX(1, 1, 1) - 200, 2 * r, 1e-9)
  }

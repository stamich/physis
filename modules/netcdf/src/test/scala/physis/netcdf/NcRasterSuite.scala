package physis.netcdf

import java.io.ByteArrayOutputStream

class NcRasterSuite extends munit.FunSuite:
  private def file(
      lat: Option[Array[Double]],
      elevation: Array[Double],
      name: String = "elevation"
  ) =
    val ny = 2
    val nx = 3
    val schema = Schema(
      Vector(Dim.fixed("y", ny), Dim.fixed("x", nx)),
      Vector(Cf.conventions),
      lat.toVector.map(_ => Var("lat", Vector("y"), NcType.Float64)) :+
        Var(name, Vector("y", "x"), NcType.Float32)
    )
    val out = new ByteArrayOutputStream()
    val fixed = lat.map("lat" -> _).toMap + (name -> elevation)
    val w = new NcWriter(out, schema, 0, fixed)
    w.finish()
    out.toByteArray

  private val values = Array(-10.0, -20.0, -30.0, 1.0, 2.0, 3.0)

  test("rows are kept as they are when there is no latitude") {
    val r = NcRaster.read(file(None, values)).fold(e => fail(e), identity)
    assertEquals((r.nx, r.ny), (3, 2))
    assertEquals(r.values.toSeq, values.toSeq)
  }

  test("a file that runs from south to north is turned over, one that runs south is not") {
    val up = NcRaster.read(file(Some(Array(10.0, 11.0)), values)).fold(e => fail(e), identity)
    assertEquals(up.values.toSeq, Seq(1.0, 2.0, 3.0, -10.0, -20.0, -30.0))
    val down = NcRaster.read(file(Some(Array(11.0, 10.0)), values)).fold(e => fail(e), identity)
    assertEquals(down.values.toSeq, values.toSeq)
  }

  test("a file without elevation, or with something else, is refused with a reason") {
    assert(NcRaster.read(file(None, values, "height")).left.exists(_.contains("elevation")))
    assert(NcRaster.read(Array[Byte](1, 2, 3)).isLeft)
  }

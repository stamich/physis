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

  private def geoFile(
      lat: Array[Double],
      lon: Array[Double],
      names: (String, String) = ("lat", "lon")
  ) =
    val schema = Schema(
      Vector(Dim.fixed("y", 2), Dim.fixed("x", 3)),
      Vector(Cf.conventions),
      Vector(
        Var(names._1, Vector("y"), NcType.Float64),
        Var(names._2, Vector("x"), NcType.Float64),
        Var("elevation", Vector("y", "x"), NcType.Float32)
      )
    )
    val out = new ByteArrayOutputStream()
    val fixed = Map(names._1 -> lat, names._2 -> lon, "elevation" -> values)
    new NcWriter(out, schema, 0, fixed).finish()
    out.toByteArray

  test("lat and lon give the raster its place: edges half a spacing beyond the centres") {
    val r = NcRaster.read(geoFile(Array(-10.0, 10.0), Array(0.0, 120.0, 240.0)))
    val e = r.fold(m => fail(m), identity).extent.get
    assertEquals((e.west, e.east, e.south, e.north), (-60.0, 300.0, -20.0, 20.0))
    assert(e.global)
    val names = ("latitude", "longitude")
    val named = NcRaster.read(geoFile(Array(1.0, 0.0), Array(10.0, 11.0, 12.0), names))
    assertEquals(named.fold(m => fail(m), identity).extent.map(_.north), Some(1.5))
  }

  test("a file without coordinates has no place, and irregular or impossible ones are refused") {
    assertEquals(NcRaster.read(file(None, values)).fold(m => fail(m), identity).extent, None)
    val uneven = NcRaster.read(geoFile(Array(0.0, 1.0), Array(0.0, 1.0, 5.0)))
    assert(uneven.left.exists(_.contains("regularly")), uneven.toString)
    val polar = NcRaster.read(geoFile(Array(80.0, 95.0), Array(0.0, 1.0, 2.0)))
    assert(polar.left.exists(_.contains("poles")), polar.toString)
  }

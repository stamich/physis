package physis.core.run

class RasterSuite extends munit.FunSuite:
  test("sampling is bilinear between cell centres and flat beyond the outer centres") {
    // two rows, three columns; row 0 is the north
    val r = new Raster(3, 2, Array(0.0, 10.0, 20.0, 100.0, 110.0, 120.0))
    assertEqualsDouble(r.sample(0.5 / 3, 0.25), 0.0, 1e-12) // centre of the north-west cell
    assertEqualsDouble(r.sample(2.5 / 3, 0.75), 120.0, 1e-12) // centre of the south-east cell
    assertEqualsDouble(r.sample(0.5, 0.25), 10.0, 1e-12)
    assertEqualsDouble(r.sample(0.5, 0.5), 60.0, 1e-12) // half-way between the rows
    assertEqualsDouble(r.sample(1.5 / 3 + 0.5 / 3, 0.25), 15.0, 1e-12) // half-way between columns
    assertEqualsDouble(r.sample(0.0, 0.0), 0.0, 1e-12)
    assertEqualsDouble(r.sample(1.0, 1.0), 120.0, 1e-12)
  }

  test("a raster needs as many values as it has cells") {
    intercept[IllegalArgumentException](new Raster(2, 2, Array(1.0, 2.0, 3.0)))
  }

  test("the digest depends on the bytes, not on a name, and rasters are found by it") {
    val a = "sea floor".getBytes("UTF-8")
    val b = "sea flooR".getBytes("UTF-8")
    assertEquals(Rasters.digestOf(a), Rasters.digestOf(a.clone()))
    assertNotEquals(Rasters.digestOf(a), Rasters.digestOf(b))
    val r = new Raster(1, 1, Array(3.0))
    assertEquals(Rasters.get(0x1234L), None)
    Rasters.register(0x1234L, r)
    assert(Rasters.get(0x1234L).exists(_ eq r))
  }

  test("a load round-trips through JSON and is refused without a file") {
    val iv = Intervention.Load("seafloor", "bay.nc", -1234567890123L)
    val json = Json.render(Intervention.toJson(iv))
    assertEquals(Json.parse(json).flatMap(Intervention.fromJson), Right(iv), json)
    assert(Intervention.Load("seafloor", "", 1L).validate.isDefined)
    assert(Intervention.Load("", "bay.nc", 1L).validate.isDefined)
    assertEquals(iv.validate, None)
  }

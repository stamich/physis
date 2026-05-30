package physis.core.live

class ExportSpecSuite extends munit.FunSuite:
  test("variable names are letters, digits and underscores, not starting with a digit") {
    for good <- Seq("height", "_x", "max_speed", "u2") do assert(FieldMeta.valid(good), good)
    for bad <- Seq("", "2u", "a b", "a-b", "a.b", "ä") do assert(!FieldMeta.valid(bad), bad)
    intercept[IllegalArgumentException](FieldMeta("s", "bad name", "x", "1"))
  }

  test("a spec refuses a cell of no size and two fields with one name") {
    val a = FieldMeta("a", "a", "x", "1")
    intercept[IllegalArgumentException](ExportSpec("t", Vector(a), dx = 0.0))
    intercept[IllegalArgumentException](ExportSpec("t", Vector(a, a.copy(slice = "b"))))
    assertEquals(ExportSpec("t", Vector(a)).dx, 1.0)
  }

  test("a spec with no fields writes the probes only") {
    val s = ExportSpec.probesOnly("Lorenz")
    assert(s.fields.isEmpty)
    assertEquals(s.timeUnits, "1")
    assertEquals(ExportSpec.probesOnly("Sea", "s").timeUnits, "s")
  }

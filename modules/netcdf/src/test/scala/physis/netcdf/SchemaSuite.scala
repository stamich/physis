package physis.netcdf

class SchemaSuite extends munit.FunSuite:
  private val ok = Schema(
    Vector(Dim.record("time"), Dim.fixed("y", 3), Dim.fixed("x", 4)),
    Vector(Cf.conventions),
    Vector(
      Cf.time("time", "s"),
      Cf.axis("x", "X", "m", "x"),
      Cf.data("h", Vector("time", "y", "x"), NcType.Float64, "height", "m")
    )
  )

  test("a well-formed structure validates, and knows its record variables") {
    assertEquals(ok.validate, Right(ok))
    assertEquals(ok.recordVars.map(_.name), Vector("time", "h"))
    assertEquals(ok.fixedVars.map(_.name), Vector("x"))
    assertEquals(ok.valuesPerRecord(ok.variable("h").get), 12)
    assertEquals(ok.valuesPerRecord(ok.variable("time").get), 1)
    assertEquals(ok.valuesPerRecord(ok.variable("x").get), 4)
  }

  test("names follow the NetCDF rules") {
    for good <- Seq("h", "_x", "max_height", "a.b", "v-2", "T1") do
      assert(Schema.validName(good), good)
    for bad <- Seq("", "1a", "a b", "a/b", "ä", "x" * 256) do assert(!Schema.validName(bad), bad)
  }

  test("the record dimension comes first, and there is one at most") {
    val late = ok.copy(vars = Vector(Var("h", Vector("y", "time"), NcType.Float64)))
    assert(late.validate.left.exists(_.contains("record dimension must come first")))
    val two = ok.copy(dims = ok.dims :+ Dim.record("t2"))
    assert(two.validate.left.exists(_.contains("more than one record")))
  }

  test("unknown dimensions, repeated names, empty dimensions and text variables are refused") {
    val unknown = ok.copy(vars = Vector(Var("h", Vector("z"), NcType.Float64)))
    assert(unknown.validate.left.exists(_.contains("no dimension 'z'")))
    val twice = ok.copy(vars = ok.vars ++ ok.vars.take(1))
    assert(twice.validate.left.exists(_.contains("twice")))
    val empty = ok.copy(dims = Vector(Dim.fixed("x", 0)), vars = Vector.empty)
    assert(empty.validate.isLeft)
    val text = ok.copy(vars = Vector(Var("t", Vector("x"), NcType.Text)))
    assert(text.validate.left.exists(_.contains("not text")))
  }

  test("attributes need good names and values") {
    val badName = ok.copy(globals = Vector(Attr.text("bad name", "x")))
    assert(badName.validate.isLeft)
    val empty = ok.copy(globals = Vector(Attr("a", AttrValue.Ints(Vector.empty))))
    assert(empty.validate.isLeft)
    val twice = ok.copy(globals = Vector(Attr.text("a", "x"), Attr.int("a", 1)))
    assert(twice.validate.isLeft)
  }

  test("the axis helper marks a downward axis, and the time helper the record") {
    assert(Cf.axis(
      "y",
      "Y",
      "m",
      "y",
      down = true
    ).attr("positive").contains(AttrValue.Text("down")))
    assert(Cf.axis("y", "Y", "m", "y").attr("positive").isEmpty)
    assertEquals(Cf.time("time", "s").attr("axis"), Some(AttrValue.Text("T")))
  }

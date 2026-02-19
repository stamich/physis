package physis.core.run

import physis.core.run.Intervention.{Brush, Disturb, Pulse}

class InterventionModelSuite extends munit.FunSuite:
  private val all = Vector[Intervention](
    Brush("v", Disc(10.5, 3.25, 6.0), Op.Assign, 0.5),
    Brush("u", Disc(0.0, 0.0, 1.0), Op.Add, -0.125),
    Brush("wall", Disc(1e-3, 255.75, 64.0), Op.Erase, 0.0),
    Pulse("u", 128.0, 64.5, 3.0, 1.5),
    Disturb(Disc(40.0, 41.0, 12.0), 0.75)
  )

  test("every intervention round-trips through JSON exactly") {
    all.foreach { iv =>
      val json = Json.render(Intervention.toJson(iv))
      assertEquals(Json.parse(json).flatMap(Intervention.fromJson), Right(iv), json)
    }
  }

  test("valid interventions have no problem") {
    all.foreach(iv => assertEquals(iv.validate, None, iv.toString))
  }

  test("non-finite and negative values are rejected with a message") {
    val bad = Vector[(Intervention, String)](
      Brush("v", Disc(0, 0, 0.0), Op.Assign, 1.0) -> "radius",
      Brush("v", Disc(0, 0, -2.0), Op.Assign, 1.0) -> "radius",
      Brush("v", Disc(0, 0, 1e9), Op.Assign, 1.0) -> "radius",
      Brush("v", Disc(Double.NaN, 0, 2.0), Op.Assign, 1.0) -> "x must be finite",
      Brush("v", Disc(0, Double.PositiveInfinity, 2.0), Op.Add, 1.0) -> "y must be finite",
      Brush("v", Disc(0, 0, 2.0), Op.Add, Double.NaN) -> "value must be finite",
      Brush("", Disc(0, 0, 2.0), Op.Add, 1.0) -> "target",
      Pulse("u", 0, 0, 0.0, 1.0) -> "sigma",
      Pulse("u", 0, 0, -1.0, 1.0) -> "sigma",
      Pulse("u", 0, 0, 1.0, Double.NaN) -> "amplitude",
      Disturb(Disc(0, 0, 2.0), Double.NaN) -> "strength",
      Disturb(Disc(0, 0, 0.0), 1.0) -> "radius"
    )
    bad.foreach { (iv, word) =>
      val problem = iv.validate
      assert(problem.exists(_.contains(word)), s"$iv: $problem")
    }
  }

  test("parsing refuses unknown kinds, operations and invalid values") {
    def parse(text: String) = Json.parse(text).flatMap(Intervention.fromJson)
    assert(parse("""{"kind": "melt"}""").left.exists(_.contains("unknown intervention kind")))
    assert(
      parse(
        """{"kind":"brush","target":"v","x":1,"y":1,"r":2,"op":"smear","value":1}"""
      ).left.exists(_.contains("unknown brush operation"))
    )
    assert(
      parse(
        """{"kind":"brush","target":"v","x":1,"y":1,"r":-2,"op":"set","value":1}"""
      ).left.exists(_.contains("radius"))
    )
    assert(parse("""{"kind":"disturb","x":1}""").isLeft)
  }

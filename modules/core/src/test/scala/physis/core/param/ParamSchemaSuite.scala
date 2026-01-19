package physis.core.param

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

final case class DemoParams(rate: Double, count: Int, enabled: Boolean)

object DemoParams:
  given schema: ParamSchema[DemoParams] = ParamSchema.derived[DemoParams](
    ParamSpec("rate", default = 0.5, min = 0.0, max = 10.0, unit = "1/s", doc = "Decay rate"),
    ParamSpec("count", default = 3.0, min = 1.0, max = 100.0, doc = "Number of items"),
    ParamSpec("enabled", default = 1.0, min = 0.0, max = 1.0, doc = "Feature switch")
  )

final case class TwoFields(a: Double, b: Double)

class ParamSchemaSuite extends munit.ScalaCheckSuite:
  private val schema = DemoParams.schema

  test("specs follow field order") {
    assertEquals(schema.names, Vector("rate", "count", "enabled"))
    assertEquals(schema.specs.head.unit, "1/s")
    assertEquals(schema.specs.head.doc, "Decay rate")
  }

  test("defaults are built from the specs") {
    assertEquals(schema.defaults, DemoParams(0.5, 3, true))
  }

  test("toMap names every field") {
    assertEquals(
      schema.toMap(DemoParams(2.0, 7, false)).toList,
      List("rate" -> 2.0, "count" -> 7.0, "enabled" -> 0.0)
    )
  }

  test("missing values take their default") {
    assertEquals(schema.fromValues(Map("rate" -> 4.0)), Right(DemoParams(4.0, 3, true)))
  }

  test("unknown names are rejected") {
    val result = schema.fromValues(Map("rte" -> 1.0))
    assert(result.left.exists(_.contains("unknown parameter(s): rte")), result.toString)
  }

  test("out-of-range values are rejected") {
    assert(schema.fromValues(Map("rate" -> 11.0)).isLeft)
    assert(schema.fromValues(Map("count" -> 0.0)).isLeft)
    assert(schema.fromValues(Map("rate" -> Double.NaN)).isLeft)
  }

  test("values the field type cannot hold are rejected") {
    assert(schema.fromValues(Map("count" -> 2.5)).left.exists(_.contains("not an integer")))
    assert(schema.fromValues(Map("enabled" -> 0.5)).left.exists(_.contains("not a boolean")))
  }

  test("withOverrides replaces only the given values") {
    assertEquals(
      schema.withOverrides(DemoParams(1.0, 10, false), Map("count" -> 20.0)),
      Right(DemoParams(1.0, 20, false))
    )
  }

  test("a field without a spec fails at schema creation") {
    val error = intercept[IllegalArgumentException] {
      ParamSchema.derived[TwoFields](ParamSpec("a", default = 1.0))
    }
    assert(error.getMessage.contains("no ParamSpec for field(s): b"), error.getMessage)
  }

  test("a spec without a field fails at schema creation") {
    val error = intercept[IllegalArgumentException] {
      ParamSchema.derived[TwoFields](
        ParamSpec("a", default = 1.0),
        ParamSpec("b", default = 1.0),
        ParamSpec("c", default = 1.0)
      )
    }
    assert(error.getMessage.contains("unknown field(s): c"), error.getMessage)
  }

  test("a default outside its range is rejected") {
    intercept[IllegalArgumentException](ParamSpec("x", default = 5.0, min = 0.0, max = 1.0))
  }

  test("the unit schema accepts nothing but an empty map") {
    assertEquals(ParamSchema.unit.fromValues(Map.empty), Right(()))
    assert(ParamSchema.unit.fromValues(Map("x" -> 1.0)).isLeft)
  }

  private val validParams: Gen[DemoParams] =
    for
      rate <- Gen.choose(0.0, 10.0)
      count <- Gen.choose(1, 100)
      enabled <- Gen.oneOf(true, false)
    yield DemoParams(rate, count, enabled)

  property("toMap and fromValues round-trip") {
    forAll(validParams) { p =>
      schema.fromValues(schema.toMap(p)) == Right(p)
    }
  }

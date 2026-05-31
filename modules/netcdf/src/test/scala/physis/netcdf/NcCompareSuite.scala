package physis.netcdf

import java.io.ByteArrayOutputStream

class NcCompareSuite extends munit.FunSuite:
  private def schema(tpe: NcType = NcType.Float64, extra: Boolean = false) = Schema(
    Vector(Dim.record("time"), Dim.fixed("x", 3)),
    Vector(Attr.text("source", "Physis 1")),
    Vector(
      Cf.time("time", "s"),
      Var("a", Vector("time", "x"), tpe)
    ) ++ (if extra then Vector(Var("b", Vector("time"), NcType.Float64)) else Vector.empty)
  )

  private def file(s: Schema, a: Seq[Array[Double]], source: String = "Physis 1"): NcFile =
    val out = new ByteArrayOutputStream()
    val withSource = s.copy(globals = Vector(Attr.text("source", source)))
    val w = new NcWriter(out, withSource, a.size, Map.empty)
    a.zipWithIndex.foreach { (values, i) =>
      val rest = withSource.recordVars.drop(2).map(_ => Array(1.0))
      w.add((Vector(Array(i.toDouble), values) ++ rest).toIndexedSeq)
    }
    w.finish()
    NcFile.read(out.toByteArray).fold(e => fail(e), identity)

  private val base = Seq(Array(1.0, 2.0, 3.0), Array(4.0, 5.0, 6.0))

  test("the same data is the same, whatever wrote it") {
    assertEquals(NcCompare.diff(file(schema(), base), file(schema(), base, "Physis 2")), None)
  }

  test("a value that differs is named with its variable, record and place") {
    val other = Seq(base.head, Array(4.0, 5.5, 6.0))
    val d = NcCompare.diff(file(schema(), base), file(schema(), other)).get
    assert(d.contains("'a' differs at record 1, value 1"), d)
    assert(d.contains("5.0 in the file, 5.5 in the replay"), d)
  }

  test("one bit is enough, including a signed zero") {
    val a = file(schema(), Seq(Array(0.0, 1.0, 2.0)))
    val b = file(schema(), Seq(Array(-0.0, 1.0, 2.0)))
    assert(NcCompare.diff(a, b).isDefined)
  }

  test("different numbers of records, variables and types are different") {
    assert(NcCompare.diff(
      file(schema(), base),
      file(schema(), base.take(1))
    ).get.contains("records"))
    assert(
      NcCompare.diff(
        file(schema(), base),
        file(schema(extra = true), base)
      ).get.contains("variables")
    )
    assert(
      NcCompare.diff(
        file(schema(), base),
        file(schema(NcType.Float32), base)
      ).get.contains("variables")
    )
  }

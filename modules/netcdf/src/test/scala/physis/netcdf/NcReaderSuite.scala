package physis.netcdf

import java.io.ByteArrayOutputStream
import java.nio.file.Files

class NcReaderSuite extends munit.FunSuite:
  private val schema = Schema(
    Vector(Dim.record("time"), Dim.fixed("y", 2), Dim.fixed("x", 3)),
    Vector(
      Cf.conventions,
      Attr.text("title", "a run"),
      Attr.int("steps", 7),
      Attr.double("dt", 0.25)
    ),
    Vector(
      Cf.time("time", "s"),
      Cf.axis("x", "X", "m", "x"),
      Var("n", Vector("time"), NcType.Int32),
      Cf.data("f", Vector("time", "y", "x"), NcType.Float32, "a field", "m"),
      Cf.data("g", Vector("time", "y", "x"), NcType.Float64, "another field", "1")
    )
  )

  private val x = Array(0.5, 1.5, 2.5)

  private def build(records: Int): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val w = new NcWriter(out, schema, records, Map("x" -> x))
    for r <- 0 until records do
      w.add(
        Vector(
          Array(r * 10.0),
          Array(r.toDouble),
          Array.tabulate(6)(i => r + i * 0.5),
          Array.tabulate(6)(i => r * 100.0 + i / 3.0)
        )
      )
    w.finish()
    out.toByteArray

  private def file(records: Int): NcFile = NcFile.read(build(records)).fold(e => fail(e), identity)

  test("what the writer wrote reads back: structure, attributes and every value") {
    val f = file(3)
    assertEquals(f.schema, schema)
    assertEquals(f.records, 3)
    assertEquals(f.text("title"), Some("a run"))
    assertEquals(f.global("steps"), Some(AttrValue.Ints(Vector(7))))
    assertEquals(f.global("dt"), Some(AttrValue.Doubles(Vector(0.25))))
    assertEquals(f.values("time").toOption.map(_.toSeq), Some(Seq(0.0, 10.0, 20.0)))
    assertEquals(f.values("x").toOption.map(_.toSeq), Some(x.toSeq))
    assertEquals(f.values("n").toOption.map(_.toSeq), Some(Seq(0.0, 1.0, 2.0)))
    val g = f.values("g").fold(e => fail(e), identity)
    assertEquals(g.length, 18)
    assertEquals(g(6 + 2), 100.0 + 2.0 / 3.0)
    val fv = f.values("f").fold(e => fail(e), identity)
    assertEquals(fv(12 + 3), 2.0 + 1.5)
  }

  test("a record can be read alone") {
    val f = file(3)
    assertEquals(
      f.record("g", 1).toOption.map(_.toSeq),
      Some(Seq.tabulate(6)(i => 100.0 + i / 3.0))
    )
    assert(f.record("g", 3).isLeft)
    assert(f.record("x", 0).isLeft)
    assert(f.record("nope", 0).isLeft)
    assert(f.values("nope").isLeft)
  }

  test("a file with no records has the axes and no data") {
    val f = file(0)
    assertEquals(f.records, 0)
    assertEquals(f.values("x").toOption.map(_.toSeq), Some(x.toSeq))
    assertEquals(f.values("g").toOption.map(_.length), Some(0))
  }

  test("a file on disk is read the same") {
    val path = Files.createTempFile("physis-nc", ".nc")
    try
      Files.write(path, build(2))
      val f = NcFile.read(path).fold(e => fail(e), identity)
      assertEquals(f.records, 2)
      assertEquals(f.values("n").toOption.map(_.toSeq), Some(Seq(0.0, 1.0)))
    finally
      val _ = Files.deleteIfExists(path)
  }

  test("what is not a NetCDF file, or is cut short, is refused with a reason") {
    assert(NcFile.read(Array.emptyByteArray).left.exists(_.contains("not a NetCDF")))
    assert(
      NcFile.read("hello, this is text".getBytes("UTF-8")).left.exists(_.contains("not a NetCDF"))
    )
    val hdf = Array[Byte](67, 68, 70, 5, 0, 0, 0, 0)
    assert(NcFile.read(hdf).left.exists(_.contains("version 5")))
    val cut = build(2).take(40)
    assert(NcFile.read(cut).isLeft)
    assert(NcFile.read(Files.createTempDirectory("physis-nc-missing").resolve("none.nc")).isLeft)
  }

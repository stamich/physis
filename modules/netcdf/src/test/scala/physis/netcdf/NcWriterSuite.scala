package physis.netcdf

import java.io.ByteArrayOutputStream

class NcWriterSuite extends munit.FunSuite:
  /** Two records of a float variable beside a fixed axis; built by `tools/netcdf_reference.py mini`. */
  private val miniHex =
    "43444602000000020000000a000000020000000474696d6500000000000000017800000000000002" +
      "0000000c00000001000000057469746c650000000000000200000002686900000000000b00000002" +
      "000000017800000000000001000000010000000c0000000100000005756e69747300000000000002" +
      "000000016d000000000000060000001000000000000000d800000001760000000000000200000000" +
      "000000010000000c00000001000000057363616c6500000000000006000000013fe0000000000000" +
      "000000050000000800000000000000e83ff800000000000040040000000000003e8000003f400000" +
      "3fa000003fe00000"

  private val mini = Schema(
    Vector(Dim.record("time"), Dim.fixed("x", 2)),
    Vector(Attr.text("title", "hi")),
    Vector(
      Var("x", Vector("x"), NcType.Float64, Vector(Attr.text("units", "m"))),
      Var("v", Vector("time", "x"), NcType.Float32, Vector(Attr.double("scale", 0.5)))
    )
  )

  private def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString

  private def write(
      schema: Schema,
      records: Seq[Seq[Array[Double]]],
      fixed: Map[String, Array[Double]]
  ) =
    val out = new ByteArrayOutputStream()
    val w = new NcWriter(out, schema, records.size, fixed)
    records.foreach(r => w.add(r.toIndexedSeq))
    w.finish()
    out.toByteArray

  test("a small file is the bytes the format says, as built by an independent script") {
    val bytes = write(
      mini,
      Seq(Seq(Array(0.25, 0.75)), Seq(Array(1.25, 1.75))),
      Map("x" -> Array(1.5, 2.5))
    )
    assertEquals(hex(bytes), miniHex)
  }

  test("the layout puts fixed variables after the header and the records after them") {
    val l = NcFormat.layout(mini, 2)
    assertEquals(l.begins(0), l.headerSize.toLong)
    assertEquals(l.begins(1), l.headerSize + 16L)
    assertEquals(l.recordSize, 8L)
    assertEquals(l.size, l.headerSize + 16L + 16L)
    assertEquals(NcWriter.size(mini, 2), l.size)
    assertEquals(
      write(
        mini,
        Seq(Seq(Array(0.0, 0.0)), Seq(Array(0.0, 0.0))),
        Map("x" -> Array(0.0, 0.0))
      ).length.toLong,
      l.size
    )
  }

  test("records of several variables are interleaved") {
    val schema = Schema(
      Vector(Dim.record("t"), Dim.fixed("n", 2)),
      Vector.empty,
      Vector(
        Var("a", Vector("t"), NcType.Float64),
        Var("b", Vector("t", "n"), NcType.Int32)
      )
    )
    val bytes = write(
      schema,
      Seq(Seq(Array(1.0), Array(10.0, 11.0)), Seq(Array(2.0), Array(20.0, 21.0))),
      Map.empty
    )
    val l = NcFormat.layout(schema, 2)
    assertEquals(l.recordSize, 16L)
    val data = java.nio.ByteBuffer.wrap(bytes, l.headerSize, bytes.length - l.headerSize)
    assertEquals(data.getDouble, 1.0)
    assertEquals((data.getInt, data.getInt), (10, 11))
    assertEquals(data.getDouble, 2.0)
    assertEquals((data.getInt, data.getInt), (20, 21))
  }

  test("float32 rounds, int32 must be whole, and the same input gives the same bytes") {
    val schema =
      Schema(Vector(Dim.record("t")), Vector.empty, Vector(Var("a", Vector("t"), NcType.Float32)))
    val bytes = write(schema, Seq(Seq(Array(0.1))), Map.empty)
    assertEquals(java.nio.ByteBuffer.wrap(bytes).getFloat(bytes.length - 4), 0.1f)
    assertEquals(bytes.toSeq, write(schema, Seq(Seq(Array(0.1))), Map.empty).toSeq)
    val ints =
      Schema(Vector(Dim.record("t")), Vector.empty, Vector(Var("n", Vector("t"), NcType.Int32)))
    intercept[IllegalArgumentException](write(ints, Seq(Seq(Array(0.5))), Map.empty))
  }

  test("the writer refuses what does not fit") {
    val out = new ByteArrayOutputStream()
    intercept[IllegalArgumentException](new NcWriter(out, mini, 1, Map.empty))
    intercept[IllegalArgumentException](new NcWriter(out, mini, 1, Map("x" -> Array(1.0))))
    val w = new NcWriter(out, mini, 1, Map("x" -> Array(1.0, 2.0)))
    intercept[IllegalArgumentException](w.add(Vector(Array(1.0))))
    intercept[IllegalArgumentException](w.finish())
    w.add(Vector(Array(1.0, 2.0)))
    intercept[IllegalArgumentException](w.add(Vector(Array(1.0, 2.0))))
    w.finish()
    val bad = mini.copy(vars = mini.vars :+ Var("w", Vector("nope"), NcType.Float64))
    intercept[IllegalArgumentException](new NcWriter(out, bad, 0, Map.empty))
  }

  test("a file with no variables or dimensions is a header only") {
    val empty = Schema(Vector.empty, Vector.empty, Vector.empty)
    val bytes = write(empty, Seq.empty, Map.empty)
    assertEquals(
      hex(bytes),
      "43444602" + "00000000" + "00000000" * 2 + "00000000" * 2 + "00000000" * 2
    )
  }

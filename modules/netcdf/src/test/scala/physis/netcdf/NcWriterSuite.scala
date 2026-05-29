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

  /** The file of `NcReaderSuite` with three records; built by `tools/netcdf_reference.py full`. */
  private val fullHex =
    "43444602000000030000000a000000030000000474696d6500000000000000017900000000000002" +
      "0000000178000000000000030000000c000000040000000b436f6e76656e74696f6e730000000002" +
      "0000000643462d312e380000000000057469746c650000000000000200000005612072756e000000" +
      "00000005737465707300000000000004000000010000000700000002647400000000000600000001" +
      "3fd00000000000000000000b000000050000000474696d6500000001000000000000000c00000003" +
      "0000000461786973000000020000000154000000000000096c6f6e675f6e616d6500000000000002" +
      "0000000474696d6500000005756e6974730000000000000200000001730000000000000600000008" +
      "00000000000002a8000000017800000000000001000000020000000c000000030000000461786973" +
      "000000020000000158000000000000096c6f6e675f6e616d65000000000000020000000178000000" +
      "00000005756e69747300000000000002000000016d00000000000006000000180000000000000290" +
      "000000016e00000000000001000000000000000000000000000000040000000400000000000002b0" +
      "0000000166000000000000030000000000000001000000020000000c00000002000000096c6f6e67" +
      "5f6e616d65000000000000020000000761206669656c640000000005756e69747300000000000002" +
      "000000016d000000000000050000001800000000000002b400000001670000000000000300000000" +
      "00000001000000020000000c00000002000000096c6f6e675f6e616d65000000000000020000000d" +
      "616e6f74686572206669656c6400000000000005756e697473000000000000020000000131000000" +
      "000000060000003000000000000002cc3fe00000000000003ff80000000000004004000000000000" +
      "000000000000000000000000000000003f0000003f8000003fc00000400000004020000000000000" +
      "000000003fd55555555555553fe55555555555553ff00000000000003ff55555555555553ffaaaaa" +
      "aaaaaaab4024000000000000000000013f8000003fc0000040000000402000004040000040600000" +
      "4059000000000000405915555555555540592aaaaaaaaaab40594000000000004059555555555555" +
      "40596aaaaaaaaaab4034000000000000000000024000000040200000404000004060000040800000" +
      "40900000406900000000000040690aaaaaaaaaab4069155555555555406920000000000040692aaa" +
      "aaaaaaab4069355555555555"

  test("a file with every kind of variable and attribute is the bytes of the independent script") {
    val schema = Schema(
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
    val records = (0 until 3).map { r =>
      Seq(
        Array(r * 10.0),
        Array(r.toDouble),
        Array.tabulate(6)(i => r + i * 0.5),
        Array.tabulate(6)(i => r * 100.0 + i / 3.0)
      )
    }
    assertEquals(hex(write(schema, records, Map("x" -> Array(0.5, 1.5, 2.5)))), fullHex)
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

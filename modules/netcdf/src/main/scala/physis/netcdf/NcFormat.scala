package physis.netcdf

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * The classic NetCDF format, version 2 (64-bit offsets; magic `CDF\002`). A file is a header,
 * then the variables without a record dimension one after the other, then the records: for each
 * record, the slab of every record variable in turn. All numbers are big endian and every
 * item is a multiple of four bytes long. Physis writes only variables of 4 and 8 byte numbers,
 * so no variable needs padding.
 */
object NcFormat:
  val Magic: Array[Byte] = Array('C'.toByte, 'D'.toByte, 'F'.toByte, 2.toByte)

  private[netcdf] val TagDimension = 10
  private[netcdf] val TagVariable = 11
  private[netcdf] val TagAttribute = 12

  /** Where everything is in a file of `records` records. */
  final case class Layout(
      headerSize: Int,
      begins: Vector[Long],
      vsizes: Vector[Long],
      recordSize: Long,
      size: Long
  )

  /** Padding to a multiple of four. */
  private def padded(n: Int): Int = (n + 3) & ~3

  private def nameSize(s: String): Int = 4 + padded(s.getBytes(StandardCharsets.UTF_8).length)

  private def attrSize(a: Attr): Int =
    nameSize(a.name) + 8 + (a.value match
      case AttrValue.Text(s) => padded(s.getBytes(StandardCharsets.UTF_8).length)
      case AttrValue.Ints(v) => 4 * v.size
      case AttrValue.Doubles(v) => 8 * v.size)

  private def attrListSize(attrs: Vector[Attr]): Int =
    8 + attrs.map(attrSize).sum

  /** The size of the header of `schema`; the offsets do not change it (they have 8 bytes). */
  private def headerSize(schema: Schema): Int =
    val dims = 8 + schema.dims.map(d => nameSize(d.name) + 4).sum
    val vars = 8 + schema.vars.map { v =>
      nameSize(v.name) + 4 + 4 * v.dims.size + attrListSize(v.attrs) + 4 + 4 + 8
    }.sum
    4 + 4 + dims + attrListSize(schema.globals) + vars

  /** The size in bytes of one record of `v`, or of all of `v` if it is not a record variable. */
  private def vsize(schema: Schema, v: Var): Long =
    schema.valuesPerRecord(v).toLong * v.tpe.size

  /** Where the data of a file of `records` records starts and ends. */
  def layout(schema: Schema, records: Int): Layout =
    val header = headerSize(schema)
    val sizes = schema.vars.map(v => vsize(schema, v))
    val fixedBytes = schema.vars.zip(sizes).filterNot((v, _) => schema.isRecord(v)).map(_._2).sum
    val recordStart = header.toLong + fixedBytes
    var atFixed = header.toLong
    var inRecord = 0L
    val begins = schema.vars.zip(sizes).map { (v, s) =>
      if schema.isRecord(v) then
        val begin = recordStart + inRecord
        inRecord += s
        begin
      else
        val begin = atFixed
        atFixed += s
        begin
    }
    Layout(header, begins, sizes, inRecord, recordStart + inRecord * records)

  private def i32(b: ByteBuffer, x: Int): Unit =
    val _ = b.putInt(x)

  private def i64(b: ByteBuffer, x: Long): Unit =
    val _ = b.putLong(x)

  private def f64(b: ByteBuffer, x: Double): Unit =
    val _ = b.putDouble(x)

  private def raw(b: ByteBuffer, bytes: Array[Byte]): Unit =
    val _ = b.put(bytes)

  /** Zero bytes up to a multiple of four after `n` bytes. */
  private def pad(b: ByteBuffer, n: Int): Unit =
    var k = n
    while (k & 3) != 0 do
      val _ = b.put(0.toByte)
      k += 1

  private def putName(b: ByteBuffer, s: String): Unit =
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    i32(b, bytes.length)
    raw(b, bytes)
    pad(b, bytes.length)

  private def putAttrs(b: ByteBuffer, attrs: Vector[Attr]): Unit =
    if attrs.isEmpty then
      i32(b, 0)
      i32(b, 0)
    else
      i32(b, TagAttribute)
      i32(b, attrs.size)
      attrs.foreach { a =>
        putName(b, a.name)
        a.value match
          case AttrValue.Text(s) =>
            val bytes = s.getBytes(StandardCharsets.UTF_8)
            i32(b, NcType.Text.code)
            i32(b, bytes.length)
            raw(b, bytes)
            pad(b, bytes.length)
          case AttrValue.Ints(v) =>
            i32(b, NcType.Int32.code)
            i32(b, v.size)
            v.foreach(x => i32(b, x))
          case AttrValue.Doubles(v) =>
            i32(b, NcType.Float64.code)
            i32(b, v.size)
            v.foreach(x => f64(b, x))
      }

  /** The header of a file of `records` records laid out as `layout`. */
  def header(schema: Schema, records: Int, layout: Layout): Array[Byte] =
    val b = ByteBuffer.allocate(layout.headerSize)
    raw(b, Magic)
    i32(b, records)
    if schema.dims.isEmpty then
      i32(b, 0)
      i32(b, 0)
    else
      i32(b, TagDimension)
      i32(b, schema.dims.size)
      schema.dims.foreach { d =>
        putName(b, d.name)
        i32(b, d.size.getOrElse(0))
      }
    putAttrs(b, schema.globals)
    if schema.vars.isEmpty then
      i32(b, 0)
      i32(b, 0)
    else
      i32(b, TagVariable)
      i32(b, schema.vars.size)
      schema.vars.zipWithIndex.foreach { (v, i) =>
        putName(b, v.name)
        i32(b, v.dims.size)
        v.dims.foreach(d => i32(b, schema.dims.indexWhere(_.name == d)))
        putAttrs(b, v.attrs)
        i32(b, v.tpe.code)
        i32(b, math.min(layout.vsizes(i), 0xffffffffL).toInt)
        i64(b, layout.begins(i))
      }
    require(b.position() == layout.headerSize, "NetCDF header size miscounted")
    b.array()

package physis.netcdf

import java.io.IOException
import java.nio.{ByteBuffer, MappedByteBuffer}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, StandardOpenOption}

/**
 * A classic NetCDF file read back: its structure, and the values of its variables on request.
 * Reads versions 1 and 2 of the format (32-bit and 64-bit offsets), variables of int32, float32
 * and float64, and attributes of text, numbers and floats, which is what Physis and the common
 * tools write for such data. Meant for checking what the writer wrote and for `verify`, not as a
 * general NetCDF library.
 *
 * @param schema  dimensions, attributes and variables
 * @param records the number of records
 */
final class NcFile private[netcdf] (
    val schema: Schema,
    val records: Int,
    data: ByteBuffer,
    begins: Vector[Long],
    recordSize: Long
):
  /** The global attribute called `name`. */
  def global(name: String): Option[AttrValue] = schema.globals.find(_.name == name).map(_.value)

  /** The text of the global attribute called `name`. */
  def text(name: String): Option[String] = global(name).collect { case AttrValue.Text(s) => s }

  /** All values of a variable: for a record variable, the records one after the other. */
  def values(name: String): Either[String, Array[Double]] =
    schema.variable(name) match
      case None => Left(s"no variable '$name'")
      case Some(v) =>
        val index = schema.vars.indexOf(v)
        val per = schema.valuesPerRecord(v)
        val count = if schema.isRecord(v) then records else 1
        val out = new Array[Double](per * count)
        var r = 0
        while r < count do
          val at = begins(index) + (if schema.isRecord(v) then r * recordSize else 0L)
          NcFile.get(data, at.toInt, v.tpe, out, r * per, per)
          r += 1
        Right(out)

  /** The values of record `r` of a record variable. */
  def record(name: String, r: Int): Either[String, Array[Double]] =
    schema.variable(name) match
      case Some(v) if schema.isRecord(v) && r >= 0 && r < records =>
        val per = schema.valuesPerRecord(v)
        val out = new Array[Double](per)
        NcFile.get(
          data,
          (begins(schema.vars.indexOf(v)) + r * recordSize).toInt,
          v.tpe,
          out,
          0,
          per
        )
        Right(out)
      case Some(_) => Left(s"'$name' is not a record variable with a record $r")
      case None => Left(s"no variable '$name'")

object NcFile:
  private def get(
      data: ByteBuffer,
      at: Int,
      tpe: NcType,
      out: Array[Double],
      from: Int,
      n: Int
  ): Unit =
    var i = 0
    while i < n do
      out(from + i) = tpe match
        case NcType.Float64 => data.getDouble(at + 8 * i)
        case NcType.Float32 => data.getFloat(at + 4 * i).toDouble
        case NcType.Int32 => data.getInt(at + 4 * i).toDouble
        case NcType.Text => 0.0
      i += 1

  /** Reads the file at `path`. */
  def read(path: Path): Either[String, NcFile] =
    try
      val channel = FileChannel.open(path, StandardOpenOption.READ)
      try
        val size = channel.size()
        if size > Int.MaxValue then Left(s"$path is larger than 2 GB")
        else
          val mapped: MappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
          parse(mapped)
      finally channel.close()
    catch case e: IOException => Left(s"cannot read $path: ${e.getMessage}")

  /** Reads a file held in memory. */
  def read(bytes: Array[Byte]): Either[String, NcFile] = parse(ByteBuffer.wrap(bytes))

  private final class Cursor(val b: ByteBuffer):
    var at: Int = 0
    def int(): Int =
      val x = b.getInt(at)
      at += 4
      x
    def long(): Long =
      val x = b.getLong(at)
      at += 8
      x
    def name(): String =
      val n = int()
      val bytes = new Array[Byte](n)
      var i = 0
      while i < n do
        bytes(i) = b.get(at + i)
        i += 1
      at += (n + 3) & ~3
      new String(bytes, StandardCharsets.UTF_8)

  private def attrs(c: Cursor): Vector[Attr] =
    val tag = c.int()
    val n = c.int()
    if tag == 0 then Vector.empty
    else
      require(tag == NcFormat.TagAttribute, s"bad attribute list $tag")
      Vector.fill(n) {
        val name = c.name()
        val code = c.int()
        val count = c.int()
        val value = code match
          case 2 =>
            val bytes = new Array[Byte](count)
            var i = 0
            while i < count do
              bytes(i) = c.b.get(c.at + i)
              i += 1
            c.at += (count + 3) & ~3
            AttrValue.Text(new String(bytes, StandardCharsets.UTF_8))
          case 4 => AttrValue.Ints(Vector.fill(count)(c.int()))
          case 5 =>
            AttrValue.Doubles(Vector.fill(count)(java.lang.Float.intBitsToFloat(c.int()).toDouble))
          case 6 =>
            AttrValue.Doubles(Vector.fill(count)(java.lang.Double.longBitsToDouble(c.long())))
          case other => throw new IllegalArgumentException(s"attribute type $other not supported")
        Attr(name, value)
      }

  private def parse(b: ByteBuffer): Either[String, NcFile] =
    try
      if b.capacity() < 8 || b.get(0) != 'C'.toByte || b.get(1) != 'D'.toByte || b.get(
          2
        ) != 'F'.toByte
      then Left("not a NetCDF file")
      else
        val version = b.get(3).toInt
        if version != 1 && version != 2 then Left(s"NetCDF version $version is not supported")
        else
          val c = new Cursor(b)
          c.at = 4
          val declared = c.int()
          val dimTag = c.int()
          val dimCount = c.int()
          val dims =
            if dimTag == 0 then Vector.empty[Dim]
            else
              require(dimTag == NcFormat.TagDimension, s"bad dimension list $dimTag")
              Vector.fill(dimCount) {
                val name = c.name()
                val size = c.int()
                Dim(name, if size == 0 then None else Some(size))
              }
          val globals = attrs(c)
          val varTag = c.int()
          val varCount = c.int()
          val raw =
            if varTag == 0 then Vector.empty
            else
              require(varTag == NcFormat.TagVariable, s"bad variable list $varTag")
              Vector.fill(varCount) {
                val name = c.name()
                val ids = Vector.fill(c.int())(c.int())
                val vattrs = attrs(c)
                val code = c.int()
                val vsize = c.int() & 0xffffffffL
                val begin = if version == 2 then c.long() else c.int().toLong & 0xffffffffL
                val tpe = code match
                  case 4 => NcType.Int32
                  case 5 => NcType.Float32
                  case 6 => NcType.Float64
                  case other =>
                    throw new IllegalArgumentException(
                      s"variable '$name': type $other not supported"
                    )
                (Var(name, ids.map(i => dims(i).name), tpe, vattrs), vsize, begin)
              }
          val schema = Schema(dims, globals, raw.map(_._1))
          schema.validate.left.foreach(why => throw new IllegalArgumentException(why))
          val recordVars = schema.recordVars
          val recordSize = recordVars.map(v => schema.valuesPerRecord(v).toLong * v.tpe.size).sum
          val begins = raw.map(_._3)
          val records =
            if declared != -1 then declared
            else if recordSize == 0 then 0
            else
              val first =
                recordVars.map(v => begins(schema.vars.indexOf(v))).minOption.getOrElse(0L)
              ((b.capacity() - first) / recordSize).toInt
          Right(new NcFile(schema, records, b, begins, recordSize))
    catch
      case e: IllegalArgumentException => Left(s"bad NetCDF file: ${e.getMessage}")
      case _: IndexOutOfBoundsException => Left("bad NetCDF file: it ends too soon")
      case _: NegativeArraySizeException => Left("bad NetCDF file: a length is negative")

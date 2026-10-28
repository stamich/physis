package physis.netcdf

import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Writes a classic NetCDF file to a stream one record at a time, so a long run needs memory for
 * one record, not for the run. The header comes first and holds the number of records, so the
 * writer is told how many it will get; [[finish]] checks that they all came.
 *
 * Variables without a record dimension (the axes) are given up front and written at once. The
 * values of every record variable of a record are given to [[add]] in the order of
 * `schema.recordVars`; they are converted to the type of the variable (a float32 loses
 * precision, an int32 must be a whole number). The bytes depend only on the schema and the
 * values.
 *
 * @param out     where the file goes; the writer does not close it
 * @param schema  the structure of the file
 * @param records how many records will be added
 * @param fixed   the values of every variable without a record dimension, by name
 */
final class NcWriter(
    out: OutputStream,
    val schema: Schema,
    val records: Int,
    fixed: Map[String, Array[Double]]
):
  require(records >= 0, s"netcdf: $records records")
  schema.validate.left.foreach(why => throw new IllegalArgumentException(s"netcdf: $why"))

  /** Where everything is in the file. */
  val layout: NcFormat.Layout = NcFormat.layout(schema, records)

  private val recordVars: Vector[Var] = schema.recordVars
  private val buffer = ByteBuffer.allocate(layout.recordSize.toInt)
  private var count = 0
  private var finished = false

  locally {
    require(layout.recordSize <= Int.MaxValue, "netcdf: a record is larger than 2 GB")
    out.write(NcFormat.header(schema, records, layout))
    schema.fixedVars.foreach { v =>
      val values = fixed.getOrElse(
        v.name,
        throw new IllegalArgumentException(s"netcdf: no values for '${v.name}'")
      )
      require(
        values.length == schema.valuesPerRecord(v),
        s"netcdf: '${v.name}' has ${values.length} values, needs ${schema.valuesPerRecord(v)}"
      )
      val b = ByteBuffer.allocate(values.length * v.tpe.size)
      NcWriter.put(b, v, values)
      out.write(b.array())
    }
  }

  /** The number of records written so far. */
  def written: Int = count

  /** Adds a record: one array for each record variable, in `schema.recordVars` order. */
  def add(values: IndexedSeq[Array[Double]]): Unit =
    require(!finished, "netcdf: the file is finished")
    require(count < records, s"netcdf: more than the $records records announced")
    require(
      values.size == recordVars.size,
      s"netcdf: ${values.size} arrays for ${recordVars.size} record variables"
    )
    buffer.clear()
    var k = 0
    while k < recordVars.size do
      val v = recordVars(k)
      require(
        values(k).length == schema.valuesPerRecord(v),
        s"netcdf: '${v.name}' has ${values(k).length} values, needs ${schema.valuesPerRecord(v)}"
      )
      NcWriter.put(buffer, v, values(k))
      k += 1
    out.write(buffer.array(), 0, buffer.position())
    count += 1

  /** Checks that every announced record came, and flushes. */
  def finish(): Unit =
    require(count == records, s"netcdf: $count of the $records records were written")
    finished = true
    out.flush()

object NcWriter:
  /** The size in bytes of a file of `records` records of `schema`. */
  def size(schema: Schema, records: Int): Long = NcFormat.layout(schema, records).size

  private def put(b: ByteBuffer, v: Var, values: Array[Double]): Unit =
    def f64(x: Double): Unit =
      val _ = b.putDouble(x)
    def f32(x: Float): Unit =
      val _ = b.putFloat(x)
    def i32(x: Int): Unit =
      val _ = b.putInt(x)
    var i = 0
    v.tpe match
      case NcType.Float64 =>
        while i < values.length do
          f64(values(i))
          i += 1
      case NcType.Float32 =>
        while i < values.length do
          f32(values(i).toFloat)
          i += 1
      case NcType.Int32 =>
        while i < values.length do
          val x = values(i)
          require(
            x == math.rint(x) && math.abs(x) <= Int.MaxValue,
            s"netcdf: ${v.name} = $x is not an int"
          )
          i32(x.toInt)
          i += 1
      case NcType.Text => throw new IllegalArgumentException(s"netcdf: '${v.name}' is text")

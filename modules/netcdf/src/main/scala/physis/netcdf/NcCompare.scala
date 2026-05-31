package physis.netcdf

/**
 * Compares the data of two files: the same records, the same variables with the same dimensions
 * and types, and every value bit for bit. Attributes are not compared, because they carry the
 * version of the program that wrote the file; a replay on another build of Physis can still be
 * checked against the file it should reproduce.
 */
object NcCompare:

  /** `None` if the data is the same, otherwise the first difference found. */
  def diff(a: NcFile, b: NcFile): Option[String] =
    if a.records != b.records then
      Some(s"the file has ${a.records} records, the replay ${b.records}")
    else
      val shape = (f: NcFile) => f.schema.vars.map(v => (v.name, v.dims, v.tpe))
      val mine = shape(a)
      val theirs = shape(b)
      if mine != theirs then
        val missing = mine.diff(theirs).headOption.orElse(theirs.diff(mine).headOption)
        Some(s"the variables differ${missing.fold("")(m => s" (${m._1})")}")
      else
        a.schema.vars.iterator.flatMap(v => differs(a, b, v)).nextOption()

  private def differs(a: NcFile, b: NcFile, v: Var): Option[String] =
    (a.values(v.name), b.values(v.name)) match
      case (Right(x), Right(y)) =>
        val at = x.indices.find(i =>
          java.lang.Double.doubleToLongBits(x(i)) != java.lang.Double.doubleToLongBits(y(i))
        )
        at.map { i =>
          val per = math.max(1, a.schema.valuesPerRecord(v))
          val where =
            if a.schema.isRecord(v) then s"record ${i / per}, value ${i % per}" else s"value $i"
          s"'${v.name}' differs at $where: ${x(i)} in the file, ${y(i)} in the replay"
        }
      case (Left(why), _) => Some(why)
      case (_, Left(why)) => Some(why)

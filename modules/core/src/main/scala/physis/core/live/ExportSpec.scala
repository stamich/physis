package physis.core.live

/**
 * What a field of a model's exported state is, for writing it to a data file: the slice that
 * holds it, the name of the variable, a description and the unit.
 *
 * @param slice        name of the [[Slice]] of the exported state
 * @param name         variable name: a letter or underscore, then letters, digits and underscores
 * @param longName     a description for the reader of the file
 * @param units        unit, in the form UDUNITS reads (`m`, `m s-1`, `s-1`; "1" for no unit)
 * @param standardName a CF standard name, when one fits exactly
 */
final case class FieldMeta(
    slice: String,
    name: String,
    longName: String,
    units: String,
    standardName: Option[String] = None
):
  require(FieldMeta.valid(name), s"export: bad variable name '$name'")

object FieldMeta:
  /** Whether `name` is a name that a data file can use for a variable. */
  def valid(name: String): Boolean =
    name.nonEmpty && (name.head.isLetter && name.head < 128 || name.head == '_') &&
      name.forall(c => c < 128 && (c.isLetterOrDigit || c == '_'))

/**
 * How a model's fields are written to a data file: which slices, in which units, on which grid.
 * Rows are written as the state holds them, row 0 first; `yDown` says that `y` grows with the
 * row number, which a viewer needs to put row 0 on top.
 *
 * @param title       what to call the dataset
 * @param fields      the slices to write, in order; the state may hold more
 * @param dx          the size of a cell, in `lengthUnits`
 * @param lengthUnits unit of length: `m` for a model in metres, "1" for cells of size 1
 * @param timeUnits   unit of the model's time: `s`, or "1" for a model in steps or scaled units
 * @param yDown       whether `y` runs down the picture (row 0 is the north edge)
 */
final case class ExportSpec(
    title: String,
    fields: Vector[FieldMeta],
    dx: Double = 1.0,
    lengthUnits: String = "1",
    timeUnits: String = "1",
    yDown: Boolean = false
):
  require(dx > 0.0 && !dx.isInfinite, s"export: cell size $dx")
  require(
    fields.map(_.name).distinct.size == fields.size,
    "export: two fields with one variable name"
  )

object ExportSpec:
  /** A dataset with no fields: only the probes are written (the ODE models, the flock). */
  def probesOnly(title: String, timeUnits: String = "1"): ExportSpec =
    ExportSpec(title, Vector.empty, timeUnits = timeUnits)

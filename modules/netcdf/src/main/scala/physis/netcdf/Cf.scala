package physis.netcdf

/**
 * The attributes the CF conventions ask for, so that `xarray`, Panoply and ParaView know what
 * the axes and the fields are.
 */
object Cf:
  /** The version of the conventions the files follow. */
  val Conventions: String = "CF-1.8"

  /** The `Conventions` attribute for the global attributes of a file. */
  val conventions: Attr = Attr.text("Conventions", Conventions)

  /** A coordinate variable for a space axis: `axis` is "X" or "Y". */
  def axis(
      name: String,
      axis: String,
      units: String,
      longName: String,
      down: Boolean = false
  ): Var =
    Var(
      name,
      Vector(name),
      NcType.Float64,
      Vector(
        Attr.text("axis", axis),
        Attr.text("long_name", longName),
        Attr.text("units", units)
      ) ++ (if down then Vector(Attr.text("positive", "down")) else Vector.empty)
    )

  /** The time coordinate, on the record dimension `name`. */
  def time(name: String, units: String): Var =
    Var(
      name,
      Vector(name),
      NcType.Float64,
      Vector(
        Attr.text("axis", "T"),
        Attr.text("long_name", "time"),
        Attr.text("units", units)
      )
    )

  /** A data variable with a name, a unit and a description. */
  def data(
      name: String,
      dims: Vector[String],
      tpe: NcType,
      longName: String,
      units: String
  ): Var =
    Var(name, dims, tpe, Vector(Attr.text("long_name", longName), Attr.text("units", units)))

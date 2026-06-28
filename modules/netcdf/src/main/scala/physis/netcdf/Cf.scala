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

  /** The longitude coordinate `lon`, in degrees east. */
  val longitude: Var =
    Var(
      "lon",
      Vector("lon"),
      NcType.Float64,
      Vector(
        Attr.text("axis", "X"),
        Attr.text("standard_name", "longitude"),
        Attr.text("long_name", "longitude of the centre of the cell"),
        Attr.text("units", "degrees_east")
      )
    )

  /** The latitude coordinate `lat`, in degrees north (it decreases: row 0 is the north). */
  val latitude: Var =
    Var(
      "lat",
      Vector("lat"),
      NcType.Float64,
      Vector(
        Attr.text("axis", "Y"),
        Attr.text("standard_name", "latitude"),
        Attr.text("long_name", "latitude of the centre of the cell"),
        Attr.text("units", "degrees_north")
      )
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

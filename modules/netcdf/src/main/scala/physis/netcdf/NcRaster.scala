package physis.netcdf

import physis.core.run.Raster

/**
 * The ground of a region read from a NetCDF file: the variable `elevation` (m, positive up,
 * negative under the sea) on two dimensions, rows then columns. Row 0 of the result is the
 * north: a file whose `lat` variable runs from south to north, as most files do, is turned over.
 * If the file also has regular `lat` and `lon` variables (degrees; `latitude` and `longitude`
 * are read as well, longitudes may run from 0 to 360 or from -180 to 180) the raster knows where
 * it lies on the globe, which a model on a sphere needs. Other variable names are not guessed at.
 */
object NcRaster:
  val Variable: String = "elevation"

  def read(bytes: Array[Byte]): Either[String, Raster] =
    NcFile.read(bytes).flatMap { file =>
      file.schema.variable(Variable) match
        case None => Left(s"the file has no variable '$Variable'")
        case Some(v) if v.dims.size != 2 =>
          Left(s"'$Variable' must have two dimensions (rows, columns), it has ${v.dims.size}")
        case Some(v) =>
          val sizes = v.dims.map(d => file.schema.dim(d).flatMap(_.size))
          (sizes(0), sizes(1)) match
            case (Some(ny), Some(nx)) if ny > 0 && nx > 0 =>
              for
                values <- file.values(Variable)
                finite <-
                  if values.forall(x => !x.isNaN && !x.isInfinite) then Right(values)
                  else Left(s"'$Variable' has missing or infinite values")
                south <- southFirst(file)
                extent <- extentOf(file, nx, ny)
              yield
                if south then new Raster(nx, ny, flip(finite, nx, ny), extent)
                else new Raster(nx, ny, finite, extent)
            case _ => Left(s"'$Variable' must have fixed dimensions")
    }

  private def axis(file: NcFile, names: Seq[String]): Option[String] =
    names.find(n => file.schema.variable(n).isDefined)

  private val LatNames = Seq("lat", "latitude")
  private val LonNames = Seq("lon", "longitude")

  /** Whether the file's rows run from south to north (a `lat` variable that increases). */
  private def southFirst(file: NcFile): Either[String, Boolean] =
    axis(file, LatNames) match
      case None => Right(false)
      case Some(name) => file.values(name).map(lat => lat.length > 1 && lat.head < lat.last)

  /**
   * Where the raster lies, from the centres in `lat` and `lon`, which must be regularly spaced:
   * the edges are half a spacing beyond the outermost centres. `None` when the file has no
   * coordinates; an error when it has them and they are not usable.
   */
  private def extentOf(file: NcFile, nx: Int, ny: Int): Either[String, Option[Raster.Extent]] =
    (axis(file, LonNames), axis(file, LatNames)) match
      case (Some(lonName), Some(latName)) =>
        for
          lon <- file.values(lonName)
          lat <- file.values(latName)
          lonStep <- spacing(lonName, lon, nx)
          latStep <- spacing(latName, lat, ny)
          extent <- scala.util
            .Try(
              Raster.Extent(
                lon.min - 0.5 * lonStep,
                lon.max + 0.5 * lonStep,
                lat.min - 0.5 * latStep,
                lat.max + 0.5 * latStep
              )
            )
            .toEither
            .left
            .map(e => s"the coordinates are not usable: ${e.getMessage}")
          _ <-
            if extent.south >= -90.0 - 1e-6 && extent.north <= 90.0 + 1e-6 then Right(())
            else Left(s"'$latName' reaches beyond the poles")
        yield Some(extent)
      case _ => Right(None)

  /** The spacing of the centres `x` (absolute), if there are `n` of them and they are regular. */
  private def spacing(name: String, x: Array[Double], n: Int): Either[String, Double] =
    if x.length != n then Left(s"'$name' has ${x.length} values, the raster has $n")
    else if n < 2 then Left(s"'$name' needs at least two values to give a spacing")
    else
      val step = math.abs(x(1) - x(0))
      val regular = step > 0.0 && (1 until n).forall { k =>
        math.abs(math.abs(x(k) - x(k - 1)) - step) <= 1e-3 * step
      }
      if regular then Right(step) else Left(s"'$name' is not regularly spaced")

  private def flip(values: Array[Double], nx: Int, ny: Int): Array[Double] =
    val out = new Array[Double](values.length)
    var j = 0
    while j < ny do
      System.arraycopy(values, j * nx, out, (ny - 1 - j) * nx, nx)
      j += 1
    out

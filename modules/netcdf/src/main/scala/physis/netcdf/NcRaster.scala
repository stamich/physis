package physis.netcdf

import physis.core.run.Raster

/**
 * The ground of a region read from a NetCDF file: the variable `elevation` (m, positive up,
 * negative under the sea) on two dimensions, rows then columns. Row 0 of the result is the
 * north: a file whose `lat` variable runs from south to north, as most files do, is turned over.
 * Other variable names are not guessed at.
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
              yield
                if south then new Raster(nx, ny, flip(finite, nx, ny))
                else new Raster(nx, ny, finite)
            case _ => Left(s"'$Variable' must have fixed dimensions")
    }

  /** Whether the file's rows run from south to north (a `lat` variable that increases). */
  private def southFirst(file: NcFile): Either[String, Boolean] =
    if file.schema.variable("lat").isEmpty then Right(false)
    else file.values("lat").map(lat => lat.length > 1 && lat.head < lat.last)

  private def flip(values: Array[Double], nx: Int, ny: Int): Array[Double] =
    val out = new Array[Double](values.length)
    var j = 0
    while j < ny do
      System.arraycopy(values, j * nx, out, (ny - 1 - j) * nx, nx)
      j += 1
    out

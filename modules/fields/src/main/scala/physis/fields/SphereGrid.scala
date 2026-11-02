package physis.fields

/**
 * The geometry of a band of a sphere on a longitude–latitude grid, for the staggered layout of a
 * [[MacGrid]]: `nx` columns that go once round the sphere and `ny` rows between the latitudes
 * `-latMax` and `+latMax`. Row 0 is the northern edge and columns count eastward from 180° west:
 * column `i` spans the longitudes `-180° + i · 360° / nx` to `-180° + (i + 1) · 360° / nx`.
 *
 * A cell is `dλ` wide in longitude and `dφ` high in latitude. Its height on the ground is the same
 * everywhere, `dy = R dφ`; its width is not: `R cos φ dλ` at the latitude `φ` of its row, which is
 * why the cells narrow toward the poles. The cell at `(i, j)` has its centre at the latitude
 * [[latC]]`(j)`; the horizontal face between rows `j - 1` and `j` lies at [[latF]]`(j)`, for
 * `j = 0 .. ny`. The areas are exact (`R² dλ (sin φ_north - sin φ_south)`), so the cells add up
 * to the band's area `4 π R² sin(latMax)` whatever the resolution. Nothing here allocates after
 * construction.
 *
 * @param radius the radius of the sphere, in m
 * @param latMaxDeg the northern edge of the band, in degrees (the southern one is its negative);
 *                  the poles themselves are not on the grid
 */
final class SphereGrid(val nx: Int, val ny: Int, val radius: Double, val latMaxDeg: Double):
  require(nx >= 3 && ny >= 2, s"a sphere grid of $nx x $ny cells")
  require(radius > 0.0 && !radius.isInfinite, s"radius $radius")
  require(latMaxDeg > 0.0 && latMaxDeg < 90.0, s"latMax $latMaxDeg is not between 0 and 90 degrees")

  /** The latitude of the northern edge, in radians. */
  val latMax: Double = math.toRadians(latMaxDeg)

  /** The width of a cell in longitude, in radians. */
  val dlam: Double = 2.0 * math.Pi / nx

  /** The height of a cell in latitude, in radians. */
  val dphi: Double = 2.0 * latMax / ny

  /** The height of a cell on the ground, in m. */
  val dy: Double = radius * dphi

  /** The latitude of the centre of row `j`, in radians, `j = 0 until ny`. */
  val latC: Array[Double] = Array.tabulate(ny)(j => latMax - (j + 0.5) * dphi)

  /** The latitude of the horizontal face above row `j`, in radians, `j = 0 to ny`. */
  val latF: Array[Double] = Array.tabulate(ny + 1)(j => latMax - j * dphi)

  val cosC: Array[Double] = latC.map(math.cos)
  val sinC: Array[Double] = latC.map(math.sin)
  val cosF: Array[Double] = latF.map(math.cos)
  val sinF: Array[Double] = latF.map(math.sin)

  /** `tan φ / R` at the centre of row `j` and at the face above it: the metric term of the flow. */
  val tanC: Array[Double] = Array.tabulate(ny)(j => sinC(j) / cosC(j) / radius)
  val tanF: Array[Double] = Array.tabulate(ny + 1)(j => sinF(j) / cosF(j) / radius)

  /** The width of the cells of row `j` at their centre, in m. */
  val dxC: Array[Double] = Array.tabulate(ny)(j => radius * cosC(j) * dlam)

  /** The length of the horizontal face above row `j`, in m: the edge of a cell. */
  val lenF: Array[Double] = Array.tabulate(ny + 1)(j => radius * cosF(j) * dlam)

  /** The area of a cell of row `j`, in m². */
  val area: Array[Double] =
    Array.tabulate(ny)(j => radius * radius * dlam * (sinF(j) - sinF(j + 1)))

  /** The area of the band, `4 π R² sin(latMax)`, in m². */
  val bandArea: Double = 4.0 * math.Pi * radius * radius * math.sin(latMax)

  /** The smallest cell width, at the rows next to the poles, in m. */
  val dxMin: Double = math.min(dxC(0), dxC(ny - 1))

  // 1 / (the width of a cell) at y = 0, 0.5, 1, ... ny rows from the north, for advection.
  private val invDxTable: Array[Double] =
    Array.tabulate(2 * ny + 1)(k => 1.0 / (radius * math.cos(latMax - 0.5 * k * dphi) * dlam))

  /**
   * One over the width on the ground of a cell, `1 / (R cos φ dλ)`, at the position `y` counted in
   * rows from the north (a cell centre is at `j + 0.5`, a horizontal face at `j`): the factor that
   * turns a speed in m/s into cells per second along a row. Linear between the half rows.
   */
  def invDx(y: Double): Double =
    val t = math.max(0.0, math.min(ny.toDouble, y)) * 2.0
    val k = math.min(t.toInt, 2 * ny - 1)
    val f = t - k
    invDxTable(k) * (1.0 - f) + invDxTable(k + 1) * f

  /** The longitude of the centre of column `i`, in degrees east, between -180 and 180. */
  def lonDegC(i: Int): Double = -180.0 + (i + 0.5) * 360.0 / nx

  /** The latitude of the centre of row `j`, in degrees north. */
  def latDegC(j: Int): Double = math.toDegrees(latC(j))

  /** The column that holds the longitude `lon` (degrees east; any value is wrapped). */
  def columnOf(lon: Double): Int =
    val w = ((lon + 180.0) % 360.0 + 360.0) % 360.0
    math.min(nx - 1, (w / 360.0 * nx).toInt)

  /** The row that holds the latitude `lat` (degrees north), or the nearest one. */
  def rowOf(lat: Double): Int =
    val y = (latMaxDeg - lat) / (2.0 * latMaxDeg) * ny
    math.max(0, math.min(ny - 1, y.toInt))

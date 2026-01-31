package physis.view

/**
 * Fixed-capacity ring buffer of the most recent points of a trajectory, stored as separate
 * coordinate arrays (struct of arrays) so appending allocates nothing.
 *
 * Index 0 is the oldest point kept, `size - 1` the newest. Each point also carries a `value`
 * (for colouring by a channel); pass 0 when it is not needed.
 *
 * Owned by the rendering thread; not thread-safe.
 */
final class Trail(val capacity: Int):
  require(capacity > 1, "a trail needs a capacity of at least two points")

  private val xs = new Array[Double](capacity)
  private val ys = new Array[Double](capacity)
  private val zs = new Array[Double](capacity)
  private val vs = new Array[Double](capacity)
  private var head = 0 // next write position
  private var count = 0

  /** Number of points currently kept. */
  def size: Int = count

  def isEmpty: Boolean = count == 0

  /** Appends a point, dropping the oldest one when full. */
  def append(x: Double, y: Double, z: Double = 0.0, value: Double = 0.0): Unit =
    xs(head) = x
    ys(head) = y
    zs(head) = z
    vs(head) = value
    head = if head + 1 == capacity then 0 else head + 1
    if count < capacity then count += 1

  /** Forgets all points. */
  def clear(): Unit =
    head = 0
    count = 0

  def x(i: Int): Double = xs(slot(i))
  def y(i: Int): Double = ys(slot(i))
  def z(i: Int): Double = zs(slot(i))
  def value(i: Int): Double = vs(slot(i))

  /** Age of point `i` in [0, 1]: 1 for the oldest point kept, 0 for the newest. */
  def age(i: Int): Double =
    if count <= 1 then 0.0 else (count - 1 - i).toDouble / (count - 1)

  /** The newest point's coordinates; the trail must not be empty. */
  def lastX: Double = x(count - 1)
  def lastY: Double = y(count - 1)
  def lastZ: Double = z(count - 1)

  private def slot(i: Int): Int =
    if i < 0 || i >= count then throw new IndexOutOfBoundsException(s"$i of $count")
    val s = head - count + i
    if s < 0 then s + capacity else s

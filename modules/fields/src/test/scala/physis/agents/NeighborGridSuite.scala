package physis.agents

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import physis.verify.{Allocation, Neighbours}

class NeighborGridSuite extends munit.ScalaCheckSuite:

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(10000).withWorkers(1)

  /** Neighbour sets found by the grid; also checks nobody is visited twice and dx, dy, d2. */
  private def gridSets(
      grid: NeighborGrid,
      x: Array[Double],
      y: Array[Double],
      n: Int
  ): Vector[Set[Int]] =
    grid.rebuild(x, y, n)
    Vector.tabulate(n) { i =>
      val seen = scala.collection.mutable.HashSet.empty[Int]
      grid.foreachNear(i) { (j, dx, dy, d2) =>
        if !seen.add(j) then fail(s"particle $i visited neighbour $j twice")
        val wrongX = dx != Neighbours.displacement(x(i), x(j), grid.width)
        val wrongY = dy != Neighbours.displacement(y(i), y(j), grid.height)
        if wrongX || wrongY || d2 != dx * dx + dy * dy then fail(s"bad geometry for $i, $j")
      }
      seen.toSet
    }

  /** A configuration with particles on cell edges, world edges and exactly one radius apart. */
  private def configuration(
      seed: Long,
      n: Int,
      width: Double,
      height: Double,
      radius: Double
  ): (Array[Double], Array[Double]) =
    val rng = new java.util.Random(seed)
    val grid = new NeighborGrid(n, width, height, radius)
    val x = new Array[Double](n)
    val y = new Array[Double](n)
    for i <- 0 until n do
      val kind = if i == 0 then 0 else rng.nextInt(6)
      kind match
        case 1 => // on a cell edge
          x(i) = math.min(rng.nextInt(grid.cellsX + 1) * grid.cellWidth, width)
          y(i) = math.min(rng.nextInt(grid.cellsY + 1) * grid.cellHeight, height)
        case 2 => // at the world edge, on either side
          x(i) = if rng.nextBoolean() then 0.0 else math.nextDown(width)
          y(i) = if rng.nextBoolean() then 0.0 else math.nextDown(height)
        case 3 => // one radius away from an earlier particle
          val k = rng.nextInt(i)
          x(i) = (x(k) + radius) % width
          y(i) = y(k)
        case 4 => // on the same spot as an earlier particle
          val k = rng.nextInt(i)
          x(i) = x(k)
          y(i) = y(k)
        case _ =>
          x(i) = rng.nextDouble() * width
          y(i) = rng.nextDouble() * height
    (x, y)

  private val worlds =
    for
      seed <- Gen.long
      n <- Gen.choose(1, 60)
      width <- Gen.choose(4.0, 60.0)
      height <- Gen.choose(4.0, 60.0)
      radius <- Gen.choose(0.4, 14.0)
    yield (seed, n, width, height, radius)

  property("the grid finds exactly the brute-force neighbours (10 000 configurations)") {
    forAll(worlds) { case (seed, n, w, h, r) =>
      val (x, y) = configuration(seed, n, w, h, r)
      val grid = new NeighborGrid(n, w, h, r)
      val found = gridSets(grid, x, y, n)
      val expected = Neighbours.bruteForce(x, y, n, w, h, r)
      Neighbours.mismatch(expected, found) match
        case None => true
        case Some(message) =>
          val where = s"seed $seed, n $n, world $w x $h, radius $r, fallback ${grid.usesFallback}"
          fail(s"$message ($where)")
    }
  }

  test("a world less than three radii wide falls back to scanning every particle") {
    val narrow = new NeighborGrid(10, 20.0, 100.0, 8.0)
    assert(narrow.usesFallback)
    assertEquals((narrow.cellsX, narrow.cellsY), (1, 1))
    val wide = new NeighborGrid(10, 100.0, 100.0, 8.0)
    assert(!wide.usesFallback)
    assertEquals((wide.cellsX, wide.cellsY), (12, 12))
    // 30 / 10 = 3 cells exactly would not leave room for the margin: 2 cells, so fallback.
    assert(new NeighborGrid(10, 30.0, 30.0, 10.0).usesFallback)
  }

  test("neighbours across the periodic edges are found") {
    val x = Array(0.2, 99.9, 50.0, 50.0)
    val y = Array(50.0, 50.0, 0.1, 99.8)
    val grid = new NeighborGrid(4, 100.0, 100.0, 1.0)
    val sets = gridSets(grid, x, y, 4)
    assertEquals(sets, Vector(Set(1), Set(0), Set(3), Set(2)))
  }

  test("neighbours are visited in a fixed order: cell by cell, ascending index within a cell") {
    val x = Array(5.5, 5.2, 5.8, 5.4, 25.0)
    val y = Array(5.5, 5.6, 5.1, 5.9, 25.0)
    val grid = new NeighborGrid(5, 50.0, 50.0, 2.0)
    grid.rebuild(x, y, 5)
    val order = Vector.newBuilder[Int]
    grid.foreachNear(0)((j, _, _, _) => order += j)
    assertEquals(order.result(), Vector(1, 2, 3))
    val again = Vector.newBuilder[Int]
    grid.rebuild(x, y, 5)
    grid.foreachNear(0)((j, _, _, _) => again += j)
    assertEquals(again.result(), Vector(1, 2, 3))
  }

  test("the radius is inclusive") {
    val x = Array(10.0, 12.0)
    val y = Array(10.0, 10.0)
    val grid = new NeighborGrid(2, 40.0, 40.0, 2.0)
    assertEquals(gridSets(grid, x, y, 2), Vector(Set(1), Set(0)))
    val smaller = new NeighborGrid(2, 40.0, 40.0, 1.999)
    assertEquals(gridSets(smaller, x, y, 2), Vector(Set.empty[Int], Set.empty[Int]))
  }

  test("rebuilding and querying do not allocate") {
    assume(Allocation.supported)
    val n = 500
    val rng = new java.util.Random(5L)
    val x = Array.fill(n)(rng.nextDouble() * 200.0)
    val y = Array.fill(n)(rng.nextDouble() * 100.0)
    val grid = new NeighborGrid(n, 200.0, 100.0, 12.0)
    var total = 0L
    val bytes = Allocation.perStep(3000) {
      grid.rebuild(x, y, n)
      var i = 0
      while i < n do
        grid.foreachNear(i)((_, _, _, _) => total += 1L)
        i += 1
    }
    assert(bytes < 3000L, s"rebuild and queries allocated $bytes bytes in 3000 steps")
    assert(total > 0L)
  }

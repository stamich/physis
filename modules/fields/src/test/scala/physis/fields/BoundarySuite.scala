package physis.fields

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import physis.verify.Allocation

class BoundarySuite extends munit.ScalaCheckSuite:

  /** Random interior values in [-5, 5]; the halo starts as NaN so a missed cell shows up. */
  private def randomField(nx: Int, ny: Int, seed: Long): Field2D =
    val rng = new java.util.Random(seed)
    val f = new Field2D(Grid2D(nx, ny))
    f.fillAll(Double.NaN)
    for j <- 0 until ny; i <- 0 until nx do f(i, j) = rng.nextDouble() * 10.0 - 5.0
    f

  private val shapes =
    for
      nx <- Gen.choose(1, 9)
      ny <- Gen.choose(1, 9)
      seed <- Gen.long
    yield (nx, ny, seed)

  private def halo(f: Field2D): Seq[(Int, Int)] =
    for
      j <- -1 to f.ny
      i <- -1 to f.nx
      if i < 0 || i >= f.nx || j < 0 || j >= f.ny
    yield (i, j)

  private def wrap(i: Int, n: Int): Int = ((i % n) + n) % n
  private def clamp(i: Int, n: Int): Int = math.max(0, math.min(n - 1, i))

  property("periodic: every halo cell, corners included, equals the wrapped interior cell") {
    forAll(shapes) { case (nx, ny, seed) =>
      val f = randomField(nx, ny, seed)
      Boundaries.periodic.fill(f)
      halo(f).forall((i, j) => f(i, j) == f(wrap(i, nx), wrap(j, ny)))
    }
  }

  property("neumann: every halo cell equals the nearest interior cell") {
    forAll(shapes) { case (nx, ny, seed) =>
      val f = randomField(nx, ny, seed)
      Boundaries.neumann.fill(f)
      halo(f).forall((i, j) => f(i, j) == f(clamp(i, nx), clamp(j, ny)))
    }
  }

  property("dirichlet: halo and adjacent interior cell average to the boundary value") {
    forAll(shapes, Gen.choose(-3.0, 3.0)) { case ((nx, ny, seed), v) =>
      val f = randomField(nx, ny, seed)
      Boundaries.dirichlet(v).fill(f)
      def near(a: Double, b: Double): Boolean = math.abs(a - b) < 1e-12
      val sides = (0 until ny).forall { j =>
        near((f(-1, j) + f(0, j)) / 2, v) && near((f(nx, j) + f(nx - 1, j)) / 2, v)
      } && (0 until nx).forall { i =>
        near((f(i, -1) + f(i, 0)) / 2, v) && near((f(i, ny) + f(i, ny - 1)) / 2, v)
      }
      // Corners: x is filled first, then y fills whole rows, so a corner mirrors the diagonal cell.
      val corners = near(f(-1, -1), f(0, 0)) && near(f(nx, ny), f(nx - 1, ny - 1)) &&
        near(f(-1, ny), f(0, ny - 1)) && near(f(nx, -1), f(nx - 1, 0))
      sides && corners
    }
  }

  property("mixed conditions apply per axis") {
    forAll(shapes) { case (nx, ny, seed) =>
      val f = randomField(nx, ny, seed)
      Boundaries(Boundary.Periodic, Boundary.Neumann).fill(f)
      val xs = (0 until ny).forall(j => f(-1, j) == f(nx - 1, j) && f(nx, j) == f(0, j))
      val ys = (-1 to nx).forall(i => f(i, -1) == f(i, 0) && f(i, ny) == f(i, ny - 1))
      xs && ys
    }
  }

  property("filling leaves the interior unchanged and writes every halo cell") {
    forAll(shapes) { case (nx, ny, seed) =>
      List(Boundaries.periodic, Boundaries.neumann, Boundaries.dirichlet(1.5)).forall { b =>
        val f = randomField(nx, ny, seed)
        val before = f.digest
        b.fill(f)
        f.digest == before && f.data.forall(!_.isNaN)
      }
    }
  }

  test("a worked example: 3 x 2 field, periodic in x and Dirichlet(0) in y") {
    val f = new Field2D(Grid2D(3, 2))
    f.fillAll(Double.NaN)
    val rows = Seq(Seq(1.0, 2.0, 3.0), Seq(4.0, 5.0, 6.0))
    for j <- rows.indices; i <- rows(j).indices do f(i, j) = rows(j)(i)
    Boundaries(Boundary.Periodic, Boundary.Dirichlet(0.0)).fill(f)
    assertEquals((-1 to 3).map(i => f(i, -1)).toVector, Vector(-3.0, -1.0, -2.0, -3.0, -1.0))
    assertEquals((-1 to 3).map(i => f(i, 2)).toVector, Vector(-6.0, -4.0, -5.0, -6.0, -4.0))
    assertEquals((-1 to 3).map(i => f(i, 0)).toVector, Vector(3.0, 1.0, 2.0, 3.0, 1.0))
  }

  test("filling the halo does not allocate") {
    assume(Allocation.supported)
    val f = randomField(64, 48, 1L)
    val b = Boundaries(Boundary.Periodic, Boundary.Dirichlet(0.5))
    val bytes = Allocation.perStep(20000)(b.fill(f))
    assert(bytes < 20000L, s"allocated $bytes bytes in 20000 steps")
  }

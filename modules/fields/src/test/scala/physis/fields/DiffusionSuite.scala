package physis.fields

/**
 * Forward Euler diffusion with the 9-point stencil on plain fields: what the boundary
 * conditions do to the total, independently of any model.
 */
class DiffusionSuite extends munit.FunSuite:

  private def randomField(n: Int, seed: Long): Field2D =
    val rng = new java.util.Random(seed)
    val f = new Field2D(Grid2D(n, n))
    for j <- 0 until n; i <- 0 until n do f(i, j) = rng.nextDouble()
    f

  /** Totals after each of `steps` steps of u += dt * D * lap(u). */
  private def totals(f: Field2D, b: Boundaries, steps: Int): Vector[Double] =
    val lap = new Field2D(f.grid)
    val out = Vector.newBuilder[Double]
    out += f.sum
    for _ <- 1 to steps do
      b.fill(f)
      Stencils.laplacian9(f, lap)
      for j <- 0 until f.ny; i <- 0 until f.nx do f(i, j) = f(i, j) + 1.0 * (1.0 * lap(i, j))
      out += f.sum
    out.result()

  test("the total is conserved with periodic boundaries over 1 000 steps") {
    val series = totals(randomField(24, 1L), Boundaries.periodic, 1000)
    assertEqualsDouble(series.last / series.head, 1.0, 1e-12)
  }

  test("the total is conserved with Neumann boundaries over 1 000 steps") {
    val series = totals(randomField(24, 2L), Boundaries.neumann, 1000)
    assertEqualsDouble(series.last / series.head, 1.0, 1e-12)
  }

  test("a field with Dirichlet(0) boundaries loses mass, monotonically") {
    val series = totals(randomField(24, 3L), Boundaries.dirichlet(0.0), 300)
    assert(series.last < 0.5 * series.head)
    assert(physis.verify.Conservation.nonIncreasing(series))
  }

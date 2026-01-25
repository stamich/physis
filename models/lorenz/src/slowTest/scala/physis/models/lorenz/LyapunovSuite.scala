package physis.models.lorenz

import physis.core.StepContext
import physis.core.random.SplittableRng

/**
 * Largest Lyapunov exponent of the Lorenz attractor by Benettin's method: follow a reference
 * trajectory and a neighbour d0 away, and every time unit add log(d / d0) and pull the neighbour
 * back to distance d0 along the current separation. The literature value is about 0.906.
 */
class LyapunovSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  test("largest Lyapunov exponent is about 0.906") {
    val p = Lorenz.model.params.defaults
    val dt = 0.01
    val d0 = 1e-8
    val reference = Lorenz.model.init(p, SplittableRng(0L))
    val ctxA = StepContext()
    val ctxB = StepContext()

    def advance(state: physis.ode.OdeState, ctx: StepContext, steps: Int): Unit =
      var i = 0
      while i < steps do
        Lorenz.model.advance(state, p, dt, ctx)
        ctx.time += dt
        i += 1

    // Let the trajectory settle on the attractor.
    advance(reference, ctxA, 10000)
    val neighbour = Lorenz.model.init(p, SplittableRng(0L))
    System.arraycopy(reference.y, 0, neighbour.y, 0, 3)
    neighbour.y(0) += d0
    ctxB.time = ctxA.time

    val periods = 1000
    var sum = 0.0
    var k = 0
    while k < periods do
      advance(reference, ctxA, 100)
      advance(neighbour, ctxB, 100)
      val dx = neighbour.y(0) - reference.y(0)
      val dy = neighbour.y(1) - reference.y(1)
      val dz = neighbour.y(2) - reference.y(2)
      val d = math.sqrt(dx * dx + dy * dy + dz * dz)
      sum += math.log(d / d0)
      val scale = d0 / d
      neighbour.y(0) = reference.y(0) + dx * scale
      neighbour.y(1) = reference.y(1) + dy * scale
      neighbour.y(2) = reference.y(2) + dz * scale
      k += 1

    val lambda = sum / periods
    assert(lambda > 0.85 && lambda < 0.95, s"lambda = $lambda")
  }

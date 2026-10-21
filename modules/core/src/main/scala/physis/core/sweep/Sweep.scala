package physis.core.sweep

import java.util.concurrent.{Callable, Executors, Future}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.collection.immutable.VectorMap
import scala.util.control.NonFatal

import physis.core.{Model, Probe, Stepping}
import physis.core.run.{InterventionEvent, Physis, Run, RunResult}

/**
 * What every run of a sweep has in common: the model, how it is stepped and probed, how long it
 * runs, and the parameters and seed that the grid does not set.
 *
 * @param sampleEvery probe sampling interval; a sweep only reports the last sample, so a large
 *                    value (the default is `until`) saves memory
 * @param interventions strokes every run gets at the recorded steps, for example what a preset
 *                      paints; they are part of each point's manifest
 */
final case class SweepSetup[P, S](
    model: Model.Aux[P, S],
    probes: P => Vector[Probe[S]],
    stepping: Stepping,
    until: Double,
    sampleEvery: Double,
    params: Map[String, Double] = Map.empty,
    seed: Long = 0L,
    interventions: Vector[InterventionEvent] = Vector.empty
)

/** What one finished run reports: where it ended and the last value of each probe. */
final case class PointOutcome[A](
    finalTime: Double,
    acceptedSteps: Long,
    rejectedSteps: Long,
    digest: Long,
    finalProbes: VectorMap[String, Double],
    extra: A
)

/** A grid point with what became of it: its outcome, or why it failed or did not run. */
final case class PointResult[A](point: GridPoint, outcome: Either[String, PointOutcome[A]])

/**
 * Runs the points of a [[ParamGrid]], each as a separate whole run, on a fixed pool of threads.
 *
 * Runs share nothing, and the results are returned in grid order, so the answer does not depend
 * on the number of threads or on which run finished first. A run that fails (a parameter the
 * model refuses, say) is reported at its point and does not stop the others.
 */
object Sweep:
  /** One thread per processor. */
  def defaultThreads: Int = math.max(1, Runtime.getRuntime.availableProcessors())

  /**
   * Runs every point.
   *
   * @param threads how many runs go at the same time
   * @param stop    when set, points that have not started are reported as not run
   * @param extract reads something more from a finished run, on the thread that ran it (for
   *                example its final field); it sees the run and its result
   */
  def run[P, S, A](
      setup: SweepSetup[P, S],
      grid: ParamGrid,
      threads: Int = 1,
      stop: AtomicBoolean = new AtomicBoolean(false)
  )(extract: (Run[P, S], RunResult) => A): Vector[PointResult[A]] =
    require(threads >= 1, s"threads must be at least 1, got $threads")
    val counter = new AtomicInteger(0)
    val pool = Executors.newFixedThreadPool(
      math.min(threads, math.max(1, grid.size)),
      (task: Runnable) =>
        val thread = new Thread(task, s"physis-sweep-${counter.getAndIncrement()}")
        thread.setDaemon(true)
        thread
    )
    try
      val futures: Vector[Future[PointResult[A]]] = grid.points.map { point =>
        pool.submit(new Callable[PointResult[A]]:
          def call(): PointResult[A] = runPoint(setup, point, stop, extract))
      }
      futures.map(_.get())
    finally pool.shutdown()

  private def runPoint[P, S, A](
      setup: SweepSetup[P, S],
      point: GridPoint,
      stop: AtomicBoolean,
      extract: (Run[P, S], RunResult) => A
  ): PointResult[A] =
    if stop.get() then PointResult(point, Left("not run: the sweep was stopped"))
    else
      val schema = setup.model.params
      val outcome: Either[String, PointOutcome[A]] =
        try
          schema.withOverrides(schema.defaults, setup.params ++ point.values).map { p =>
            val run = Physis
              .model(setup.model)
              .withParams(p)
              .stepping(setup.stepping)
              .seed(point.seed.getOrElse(setup.seed))
              .sampleEvery(setup.sampleEvery)
              .interventions(setup.interventions)
              .probe(setup.probes(p)*)
              .build()
            val result = run.runUntil(setup.until)
            PointOutcome(
              result.finalTime,
              result.acceptedSteps,
              result.rejectedSteps,
              result.finalDigest,
              VectorMap.from(
                result.series.iterator.map((name, values) =>
                  name -> (if values.isEmpty then Double.NaN else values.last)
                )
              ),
              extract(run, result)
            )
          }
        catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString))
      PointResult(point, outcome)

package physis.core.live

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport

import physis.core.run.{Run, RunBuilder}

/** Instructions from the UI to a [[LiveRun]], applied by the simulation thread between steps. */
enum Command:
  case Pause, Resume, StepOnce, Reset

  /** Simulated seconds per wall-clock second. */
  case SetSpeed(simSecondsPerSecond: Double)

  /** Named parameter changes, validated through the model's schema. */
  case SetParams(values: Map[String, Double])

/**
 * Drives a [[Run]] from a dedicated simulation thread against wall-clock time.
 *
 * The simulation thread owns the run. The UI thread talks to it only through [[send]] (commands,
 * non-blocking) and [[latest]] (snapshots through a [[TripleBuffer]]), so neither thread ever
 * waits for the other.
 *
 * Pacing: the loop accumulates `speed × elapsed wall time` as a simulated-time target and takes
 * whole steps ([[Run.step]]) until the run reaches it. Steps are never shortened to meet the
 * target, so the step sequence, and therefore the result, does not depend on timing; parameter
 * changes are recorded as step-indexed events. One iteration spends at most `frameBudgetNanos`
 * stepping before it publishes; if the model cannot keep up, the backlog is dropped (the run
 * goes into slow motion) instead of freezing the UI.
 *
 * @param builder          definition of the run; [[Command.Reset]] builds it again
 * @param exporter         copies the state into snapshots
 * @param initialSpeed     simulated seconds per wall-clock second
 * @param startPaused      whether the run waits for [[Command.Resume]]
 * @param frameBudgetNanos longest stepping burst between two snapshots
 */
final class LiveRun[P, S](
    builder: RunBuilder[P, S],
    exporter: StateExport[S],
    initialSpeed: Double = 1.0,
    startPaused: Boolean = false,
    frameBudgetNanos: Long = 8_000_000L
):
  private val commands = new ConcurrentLinkedQueue[Command]()
  @volatile private var running = false
  @volatile private var thread: Thread = null

  // Owned by the simulation thread once started.
  private var run: Run[P, S] = builder.build()
  private val paramNames = run.model.params.names
  private val buffer =
    new TripleBuffer[Snapshot](() => new Snapshot(exporter.size(run.state), run.probes.size))
  private var paused = startPaused
  private var speed = initialSpeed
  private var simTarget = 0.0
  private var error = ""
  private var paramValues: IndexedSeq[Double] = run.model.params.toValues(run.params)
  private var paramEventsSeen = 0

  /** Starts the simulation thread ("physis-sim"). */
  def start(): Unit = synchronized {
    require(thread == null, "a live run can only be started once")
    running = true
    publish(System.nanoTime(), 0.0)
    val t = new Thread(() => loop(), "physis-sim")
    t.setDaemon(true)
    thread = t
    t.start()
  }

  /** Queues a command; never blocks. */
  def send(command: Command): Unit =
    commands.add(command)
    LockSupport.unpark(thread)

  /** The newest snapshot published since the last call, if any. For the UI thread only. */
  def latest(): Option[Snapshot] = buffer.latest()

  /** Stops the simulation thread and waits for it to finish. */
  def stop(): Unit =
    running = false
    val t = thread
    if t != null then
      LockSupport.unpark(t)
      t.join(1000)

  /** Whether the simulation thread is still running. */
  def isAlive: Boolean = thread != null && thread.isAlive

  private def loop(): Unit =
    var last = System.nanoTime()
    var windowStart = last
    var windowSteps = 0L
    var stepsPerSecond = 0.0
    simTarget = run.time
    while running do
      var dirty = drainCommands()
      val now = System.nanoTime()
      val elapsed = now - last
      last = now
      var caughtUp = true
      if !paused then
        simTarget += speed * elapsed / 1e9
        val deadline = now + frameBudgetNanos
        val before = run.stepCount
        while run.time < simTarget && System.nanoTime() < deadline do run.step()
        windowSteps += run.stepCount - before
        caughtUp = run.time >= simTarget
        // Drop a backlog of more than a tenth of a wall second: slow motion, not a frozen UI.
        val maxLag = speed * 0.1
        if simTarget - run.time > maxLag then simTarget = run.time + maxLag
        dirty = true
      if now - windowStart >= 250_000_000L then
        stepsPerSecond = windowSteps * 1e9 / (now - windowStart)
        windowStart = now
        windowSteps = 0L
      if dirty then publish(now, stepsPerSecond)
      if paused then LockSupport.parkNanos(5_000_000L)
      else if caughtUp then LockSupport.parkNanos(1_000_000L)

  /** Applies all queued commands; returns whether anything changed. */
  private def drainCommands(): Boolean =
    var changed = false
    var command = commands.poll()
    while command != null do
      command match
        case Command.Pause => paused = true
        case Command.Resume =>
          paused = false
          simTarget = run.time
        case Command.StepOnce => if paused then run.step()
        case Command.Reset =>
          run = builder.build()
          simTarget = 0.0
          paramEventsSeen = 0
          paramValues = run.model.params.toValues(run.params)
          error = ""
        case Command.SetSpeed(s) =>
          if s > 0.0 && !s.isInfinite then
            speed = s
            simTarget = run.time
          else error = s"invalid speed $s"
        case Command.SetParams(values) =>
          run.setParams(values) match
            case Right(()) => error = ""
            case Left(message) => error = message
      changed = true
      command = commands.poll()
    changed

  private def publish(now: Long, stepsPerSecond: Double): Unit =
    if run.events.size != paramEventsSeen then
      paramEventsSeen = run.events.size
      paramValues = run.model.params.toValues(run.params)
    val s = buffer.back
    s.time = run.time
    s.step = run.stepCount
    s.rejected = run.rejectedCount
    s.stepsPerSecond = stepsPerSecond
    s.speed = speed
    s.paused = paused
    s.publishedAt = now
    s.error = error
    s.paramNames = paramNames
    s.paramValues = paramValues
    exporter.copyInto(run.state, s.state)
    var i = 0
    while i < s.probeCount do
      s.probes(i) = run.probes(i).measure(run.state, run.time)
      i += 1
    buffer.publish()

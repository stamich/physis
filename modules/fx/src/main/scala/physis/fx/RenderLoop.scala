package physis.fx

import scala.collection.mutable

import javafx.animation.AnimationTimer

import physis.core.live.{Command, LiveRun, Snapshot}

/**
 * The render loop of one window: an AnimationTimer that, on every pulse,
 *
 *  1. records the frame time,
 *  1. takes the newest snapshot from the live run, if one arrived, and hands it to every
 *     renderer (clearing their history first when the run went back in steps, i.e. was reset),
 *  1. redraws renderers that got data or are dirty,
 *  1. refreshes the stats overlay,
 *  1. sends the parameter changes collected during the frame as one [[Command.SetParams]], so a
 *     dragged slider sends at most one change per frame.
 *
 * Runs on the FX application thread; [[setParam]] must be called from it too.
 */
final class RenderLoop(
    live: LiveRun[?, ?],
    renderers: Seq[Renderer],
    overlay: Option[StatsOverlay] = None
) extends AnimationTimer:
  /** Frame statistics of this loop. */
  val stats: FrameStats = new FrameStats()

  private val pending = mutable.LinkedHashMap.empty[String, Double]
  private val listeners = mutable.ArrayBuffer.empty[RenderLoop.Frame => Unit]
  private var current: Snapshot = null
  private var lastStep = -1L
  private var snapshots = 0L

  /** Queues a parameter change; sent at the end of the frame together with the others. */
  def setParam(name: String, value: Double): Unit = pending.update(name, value)

  /** Calls `f` at the end of every frame. */
  def onFrame(f: RenderLoop.Frame => Unit): Unit = listeners += f

  /** The newest snapshot received, valid until the next pulse. */
  def snapshot: Option[Snapshot] = Option(current)

  /** Number of snapshots received so far. */
  def snapshotCount: Long = snapshots

  override def handle(now: Long): Unit =
    stats.record(now)
    val fresh = live.latest()
    fresh.foreach { s =>
      if s.step < lastStep then renderers.foreach(_.reset())
      lastStep = s.step
      current = s
      snapshots += 1
      renderers.foreach(_.accept(s))
    }
    renderers.foreach(r => if fresh.isDefined || r.dirty then r.render())
    if current != null then overlay.foreach(_.update(current, stats))
    if pending.nonEmpty then
      live.send(Command.SetParams(pending.toMap))
      pending.clear()
    if listeners.nonEmpty then
      val frame = RenderLoop.Frame(stats.frames, now, fresh.isDefined)
      listeners.foreach(_(frame))

object RenderLoop:
  /** What a frame listener learns: frame number, pulse time and whether a snapshot arrived. */
  final case class Frame(number: Long, nanos: Long, newSnapshot: Boolean)

package physis.fx

import java.util.Locale

import javafx.scene.control.Label

import physis.core.live.Snapshot

/**
 * One line of live statistics: frame rate, p99 frame time, simulation step rate, simulated time,
 * rejected steps, speed and state. Refreshed every few frames to keep text layout cheap.
 */
final class StatsOverlay(refreshEvery: Int = 10):
  val node: Label = new Label("")
  node.getStyleClass.add("physis-stats")
  node.setStyle("-fx-font-family: monospace; -fx-padding: 4 8 4 8;")

  private var frame = 0

  /** Updates the text from the newest snapshot and frame statistics. */
  def update(snapshot: Snapshot, stats: FrameStats): Unit =
    frame += 1
    if frame >= refreshEvery then
      frame = 0
      node.setText(
        StatsOverlay.format(
          stats.fps,
          stats.p99Millis,
          snapshot.stepsPerSecond,
          snapshot.time,
          snapshot.rejected,
          snapshot.speed,
          snapshot.paused,
          snapshot.error
        )
      )

object StatsOverlay:
  /** The overlay text; NaN statistics show as dashes. Locale-independent. */
  def format(
      fps: Double,
      p99Millis: Double,
      stepsPerSecond: Double,
      time: Double,
      rejected: Long,
      speed: Double,
      paused: Boolean,
      error: String
  ): String =
    def num(v: Double, f: String) =
      if v.isNaN || v.isInfinite then "-" else f.formatLocal(Locale.ROOT, v)
    val parts = Vector(
      s"${num(fps, "%.0f")} fps",
      s"p99 ${num(p99Millis, "%.1f")} ms",
      s"${rate(stepsPerSecond)} steps/s",
      s"t = ${num(time, "%.2f")} s",
      s"rejected $rejected",
      s"speed ${num(speed, "%.3g")}x"
    ) ++ (if paused then Vector("paused") else Vector.empty) ++
      (if error.nonEmpty then Vector(s"error: $error") else Vector.empty)
    parts.mkString("  ·  ")

  /** Step rate with a k/M suffix. */
  def rate(v: Double): String =
    if v.isNaN || v.isInfinite then "-"
    else if v >= 1e6 then "%.2f M".formatLocal(Locale.ROOT, v / 1e6)
    else if v >= 1e3 then "%.1f k".formatLocal(Locale.ROOT, v / 1e3)
    else "%.0f".formatLocal(Locale.ROOT, v)

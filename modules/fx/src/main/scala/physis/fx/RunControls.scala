package physis.fx

import java.util.Locale

import javafx.geometry.{Insets, Pos}
import javafx.scene.Node
import javafx.scene.control.{Button, Label, Slider, Tooltip}
import javafx.scene.layout.{FlowPane, HBox}

import physis.core.live.{Command, Snapshot}

/**
 * Play/pause, single step, reset and a speed slider (logarithmic, 0.01x to 100x unless
 * `speedRange` says otherwise). Every action becomes a [[Command]] passed to `send`.
 *
 * @param initialSpeed the speed the run starts with, inside `speedRange`
 * @param speedRange   smallest and largest speed of the slider, in simulated time per second
 * @param speedText    how a speed is shown next to the slider
 */
final class RunControls(
    send: Command => Unit,
    initialSpeed: Double = 1.0,
    speedRange: (Double, Double) = (0.01, 100.0),
    speedText: Double => String = RunControls.speedText
):
  require(
    speedRange._1 > 0.0 && speedRange._1 < speedRange._2,
    s"empty speed range $speedRange"
  )
  private var paused = false

  val playPause: Button = new Button("Pause")
  val stepOnce: Button = new Button("Step")
  val reset: Button = new Button("Reset")
  val speed: Slider =
    new Slider(math.log10(speedRange._1), math.log10(speedRange._2), math.log10(initialSpeed))
  private val speedLabel = new Label(speedText(initialSpeed))

  playPause.setTooltip(new Tooltip("Pause or resume (Space)"))
  stepOnce.setTooltip(new Tooltip("One step while paused (Right arrow)"))
  reset.setTooltip(new Tooltip("Restart from the initial state (R)"))
  speed.setTooltip(new Tooltip("Simulated seconds per second (+ / -)"))
  stepOnce.setDisable(true)

  playPause.setOnAction(_ => togglePause())
  stepOnce.setOnAction(_ => send(Command.StepOnce))
  reset.setOnAction(_ => send(Command.Reset))
  Fx.onChange(speed.valueProperty)(v => speedChanged(math.pow(10, v.doubleValue)))

  private def speedChanged(s: Double): Unit =
    speedLabel.setText(speedText(s))
    send(Command.SetSpeed(s))

  /** The controls' node. */
  val node: Node =
    val speedBox = new HBox(6, new Label("Speed"), speed, speedLabel)
    speedBox.setAlignment(Pos.CENTER_LEFT)
    val pane = new FlowPane(6, 6, playPause, stepOnce, reset, speedBox)
    pane.setPadding(new Insets(8))
    pane

  /** Pauses or resumes. */
  def togglePause(): Unit =
    paused = !paused
    send(if paused then Command.Pause else Command.Resume)
    showPaused(paused)

  /** Multiplies the speed by `factor` (within the slider's range). */
  def changeSpeed(factor: Double): Unit =
    speed.setValue(speed.getValue + math.log10(factor))

  /** Shows the run's state. */
  def refresh(snapshot: Snapshot): Unit =
    if snapshot.paused != paused then
      paused = snapshot.paused
      showPaused(paused)

  private def showPaused(p: Boolean): Unit =
    playPause.setText(if p then "Play" else "Pause")
    stepOnce.setDisable(!p)

object RunControls:
  def speedText(s: Double): String = "%.3gx".formatLocal(Locale.ROOT, s)

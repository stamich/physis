package physis.fx

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.{ComboBox, Label, Tooltip}
import javafx.scene.layout.{HBox, Priority}

import physis.core.param.Preset

/**
 * A drop-down of named parameter sets. Choosing one calls `apply` once with all its values, so
 * the run records a single parameter change.
 */
final class PresetPicker(presets: Seq[Preset], apply: Preset => Unit):
  val combo: ComboBox[String] = new ComboBox[String]()
  combo.getItems.addAll(presets.map(_.name)*)
  combo.setPromptText("Choose…")
  combo.setMaxWidth(Double.MaxValue)
  HBox.setHgrow(combo, Priority.ALWAYS)

  Fx.onChange(combo.getSelectionModel.selectedItemProperty) { name =>
    if name != null then presets.find(_.name == name).foreach(apply)
  }

  private val tip = new Tooltip(
    presets.map(p => if p.doc.isEmpty then p.name else s"${p.name}: ${p.doc}").mkString("\n")
  )
  combo.setTooltip(tip)

  /** The picker's node. */
  val node: Node =
    val box = new HBox(8, new Label("Preset"), combo)
    box.setAlignment(Pos.CENTER_LEFT)
    box

  /** Chooses `name` as if the user had picked it. */
  def choose(name: String): Unit = combo.getSelectionModel.select(name)

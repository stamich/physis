package physis.fx

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.{Button, Label, TextField, Tooltip}
import javafx.scene.layout.{HBox, Priority}

/**
 * A seed field and a Reseed button: starts the run again with the seed in the field. Invalid
 * text is marked red and nothing is sent.
 */
final class SeedBar(initial: Long, reseed: Long => Unit):
  val field: TextField = new TextField(initial.toString)
  val button: Button = new Button("Reseed")
  field.setPrefColumnCount(8)
  HBox.setHgrow(field, Priority.ALWAYS)
  button.setTooltip(new Tooltip("Restart with this seed; the parameters stay"))

  field.setOnAction(_ =>
    val _ = commit()
  )
  button.setOnAction(_ =>
    val _ = commit()
  )

  /** Sends the seed in the field, if it is a whole number. Returns whether it was sent. */
  def commit(): Boolean =
    field.getText.trim.toLongOption match
      case Some(seed) =>
        field.setStyle("")
        reseed(seed)
        true
      case None =>
        field.setStyle("-fx-border-color: #e15759; -fx-border-width: 1.5;")
        false

  /** The bar's node. */
  val node: Node =
    val box = new HBox(8, new Label("Seed"), field, button)
    box.setAlignment(Pos.CENTER_LEFT)
    box

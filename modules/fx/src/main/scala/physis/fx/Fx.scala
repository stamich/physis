package physis.fx

import javafx.beans.value.{ChangeListener, ObservableValue}

/** Small JavaFX conveniences. */
object Fx:
  /** Calls `f` with the new value whenever `property` changes. */
  def onChange[T](property: ObservableValue[T])(f: T => Unit): Unit =
    property.addListener(new ChangeListener[T]:
      def changed(observable: ObservableValue[? <: T], before: T, after: T): Unit = f(after))

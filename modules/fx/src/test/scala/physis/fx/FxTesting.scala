package physis.fx

import java.util.concurrent.{CompletableFuture, ExecutionException, TimeUnit}

import javafx.application.Platform

/** Helpers for FX smoke tests: start the toolkit once, run code on the FX thread. */
object FxTesting:
  /** Whether FX smoke tests are enabled (`-PfxSmoke=true`, needs a display). */
  def enabled: Boolean = sys.props.get("physis.fx.smoke").contains("true")

  /** Ignores a result on purpose. */
  private def discard(value: Any): Unit = ()

  private lazy val started: Unit =
    val ready = new CompletableFuture[Unit]()
    try Platform.startup(() => discard(ready.complete(())))
    catch case _: IllegalStateException => discard(ready.complete(())) // already running
    Platform.setImplicitExit(false)
    discard(ready.get(30, TimeUnit.SECONDS))

  /** Starts the toolkit if needed. */
  def start(): Unit = started

  /** Runs `body` on the FX thread and waits for its result. */
  def onFx[A](body: => A): A =
    start()
    val result = new CompletableFuture[A]()
    Platform.runLater { () =>
      try discard(result.complete(body))
      catch case e: Throwable => discard(result.completeExceptionally(e))
    }
    try result.get(30, TimeUnit.SECONDS)
    catch case e: ExecutionException => throw e.getCause

/** Base suite for tests that need a display; skipped unless [[FxTesting.enabled]]. */
abstract class FxSuite extends munit.FunSuite:
  override def munitTimeout = scala.concurrent.duration.Duration(60, "s")

  override def beforeAll(): Unit =
    if FxTesting.enabled then FxTesting.start()

  def fxTest(name: String)(body: => Any)(using munit.Location): Unit =
    test(name) {
      assume(FxTesting.enabled, "FX smoke tests need -PfxSmoke=true and a display")
      body
    }

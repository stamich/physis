package physis.lab

import java.util.concurrent.{CompletableFuture, ExecutionException, TimeUnit}

import javafx.application.Platform

/** FX smoke-test helpers for the Lab (same contract as the ones in physis-fx's tests). */
object LabFx:
  def enabled: Boolean = sys.props.get("physis.fx.smoke").contains("true")

  private def discard(value: Any): Unit = ()

  private lazy val started: Unit =
    val ready = new CompletableFuture[Unit]()
    try Platform.startup(() => discard(ready.complete(())))
    catch case _: IllegalStateException => discard(ready.complete(()))
    Platform.setImplicitExit(false)
    discard(ready.get(30, TimeUnit.SECONDS))

  def onFx[A](body: => A): A =
    started
    val result = new CompletableFuture[A]()
    Platform.runLater { () =>
      try discard(result.complete(body))
      catch case e: Throwable => discard(result.completeExceptionally(e))
    }
    try result.get(30, TimeUnit.SECONDS)
    catch case e: ExecutionException => throw e.getCause

  /** Waits until `condition` holds, polling every 20 ms. */
  def await(what: String, timeoutMs: Long = 20_000)(condition: => Boolean): Unit =
    val deadline = System.currentTimeMillis() + timeoutMs
    while !condition do
      if System.currentTimeMillis() > deadline then throw new AssertionError(s"timed out: $what")
      Thread.sleep(20)

  /** Live simulation threads. */
  def simThreads: Int =
    Thread.getAllStackTraces.keySet.toArray(Array.empty[Thread]).count(t =>
      t.getName == "physis-sim" && t.isAlive
    )

abstract class LabSuite extends munit.FunSuite:
  override def munitTimeout = scala.concurrent.duration.Duration(120, "s")

  def fxTest(name: String)(body: => Any)(using munit.Location): Unit =
    test(name) {
      assume(LabFx.enabled, "FX smoke tests need -PfxSmoke=true and a display")
      body
    }

package physis.core.live

import physis.core.Stepping
import physis.core.run.Physis
import physis.core.testing.Chain
import physis.verify.Allocation

class FieldSnapshotSuite extends munit.FunSuite:

  test("Slice indexes row-major and reports its length") {
    val s = Slice("u", offset = 10, width = 4, height = 3)
    assertEquals(s.length, 12)
    assertEquals(s.index(0, 0), 10)
    assertEquals(s.index(3, 0), 13)
    assertEquals(s.index(0, 2), 18)
    assertEquals(s.index(3, 2), 21)
  }

  test("a slice must have a positive size and a non-negative offset") {
    intercept[IllegalArgumentException](Slice("u", 0, 0, 3))
    intercept[IllegalArgumentException](Slice("u", -1, 3, 3))
  }

  // 3 x 2 interior inside a halo of 1: stride 5, 4 rows.
  private val nx = 3
  private val ny = 2
  private val halo = 1
  private val stride = nx + 2 * halo
  private def haloField: Array[Double] =
    val a = Array.fill(stride * (ny + 2 * halo))(-1.0)
    for j <- 0 until ny; i <- 0 until nx do a((j + halo) * stride + halo + i) = 10.0 * j + i
    a

  test("copyInterior copies only the interior, row by row, at the offset") {
    val out = Array.fill(10)(99.0)
    FieldExport.copyInterior(haloField, nx, ny, halo, out, offset = 2)
    assertEquals(
      out.toVector,
      Vector(99.0, 99.0, 0.0, 1.0, 2.0, 10.0, 11.0, 12.0, 99.0, 99.0)
    )
  }

  test("copyInterior does not allocate") {
    assume(Allocation.supported)
    val src = haloField
    val out = new Array[Double](nx * ny)
    // A fixed overhead of a few dozen bytes in the whole measurement is not a per-step allocation.
    val bytes = Allocation.perStep(20000)(FieldExport.copyInterior(src, nx, ny, halo, out, 0))
    assert(bytes < 20000L, s"allocated $bytes bytes in 20000 steps")
  }

  // A state of n cells exported as one slice, to check what a live run publishes.
  private given StateExport[Array[Double]] with
    def size(state: Array[Double]): Int = state.length
    def copyInto(state: Array[Double], out: Array[Double]): Unit =
      System.arraycopy(state, 0, out, 0, state.length)
    override def layout(state: Array[Double]): IndexedSeq[Slice] =
      Vector(Slice("cells", 0, state.length / 2, 2))

  private def awaitSlices(run: LiveRun[?, ?])(p: Snapshot => Boolean): Snapshot =
    val deadline = System.currentTimeMillis() + 2000
    var found: Option[Snapshot] = None
    while found.isEmpty && System.currentTimeMillis() < deadline do
      run.latest().filter(p).foreach(s => found = Some(s))
      if found.isEmpty then Thread.sleep(2)
    found.getOrElse(fail("no matching snapshot in time"))

  test("a live run publishes the layout and follows a restart") {
    val builder = Physis.model(Chain).stepping(Stepping.Fixed(0.01))
    val run = new LiveRun(builder, summon[StateExport[Array[Double]]], 1.0, true)
    run.start()
    try
      val first = awaitSlices(run)(_.stateSize == 4)
      assertEquals(first.slices, Vector(Slice("cells", 0, 2, 2)))
      assertEquals(first.slice("cells"), Some(Slice("cells", 0, 2, 2)))
      assertEquals(first.slice("nope"), None)
      run.send(Command.Restart(Map("n" -> 8.0), None))
      val second = awaitSlices(run)(_.stateSize == 8)
      assertEquals(second.slices, Vector(Slice("cells", 0, 4, 2)))
    finally run.stop()
  }

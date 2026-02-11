package physis.agents

import physis.core.Digest
import physis.core.live.Slice
import physis.verify.Allocation

class ParticleSetSuite extends munit.FunSuite:

  private def filled(): ParticleSet =
    val p = ParticleSet(4, "x", "y", "vx")
    for k <- 0 until 3; i <- 0 until 4 do p.columnAt(k)(i) = 10.0 * k + i
    p

  test("attributes are looked up by name and share storage with columnAt") {
    val p = filled()
    assertEquals(p.names, Vector("x", "y", "vx"))
    assertEquals(p.column("y").toVector, Vector(10.0, 11.0, 12.0, 13.0))
    assert(p.column("vx") eq p.columnAt(2))
    assertEquals(p.indexOf("vx"), 2)
    assertEquals(p.indexOf("nope"), -1)
    intercept[IllegalArgumentException](p.column("nope"))
    assertEquals(p.size, 12)
  }

  test("a set needs particles, attributes and distinct names") {
    intercept[IllegalArgumentException](ParticleSet(0, "x"))
    intercept[IllegalArgumentException](ParticleSet(3))
    intercept[IllegalArgumentException](ParticleSet(3, "x", "x"))
  }

  test("the digest equals the digest of all columns in order, and changes with any value") {
    val p = filled()
    val flat = (0 until 3).flatMap(k => p.columnAt(k).toVector).toArray
    assertEquals(p.digest, Digest.ofDoubles(flat))
    val reference = p.digest
    for k <- 0 until 3; i <- 0 until 4 do
      val q = filled()
      q.columnAt(k)(i) += 1e-12
      assertNotEquals(q.digest, reference, s"column $k, particle $i")
  }

  test("copyInto writes the columns one after another, matching slices") {
    val p = filled()
    val out = Array.fill(16)(-1.0)
    p.copyInto(out, 2)
    assertEquals(
      out.toVector,
      Vector(-1.0, -1.0, 0, 1, 2, 3, 10, 11, 12, 13, 20, 21, 22, 23, -1.0, -1.0)
    )
    assertEquals(
      p.slices(2),
      Vector(Slice("x", 2, 4, 1), Slice("y", 6, 4, 1), Slice("vx", 10, 4, 1))
    )
  }

  test("copyFrom copies every column and checks the shape") {
    val a = filled()
    val b = ParticleSet(4, "x", "y", "vx")
    b.copyFrom(a)
    assertEquals(b.digest, a.digest)
    intercept[IllegalArgumentException](b.copyFrom(ParticleSet(4, "x", "y", "z")))
    intercept[IllegalArgumentException](b.copyFrom(ParticleSet(5, "x", "y", "vx")))
  }

  test("copying and hashing do not allocate") {
    assume(Allocation.supported)
    val p = filled()
    val out = new Array[Double](p.size)
    val copy = Allocation.perStep(20000)(p.copyInto(out, 0))
    assert(copy < 20000L, s"copyInto allocated $copy bytes in 20000 steps")
    val hash = Allocation.perStep(20000) { val _ = p.digest }
    assert(hash < 20000L, s"digest allocated $hash bytes in 20000 steps")
  }

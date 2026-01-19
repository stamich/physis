package physis.core.random

import org.scalacheck.Prop.forAll

class SplittableRngSuite extends munit.ScalaCheckSuite:

  private def draw(rng: SplittableRng, n: Int): Vector[Long] = Vector.fill(n)(rng.nextLong())

  property("the same seed gives the same sequence") {
    forAll { (seed: Long) =>
      draw(SplittableRng(seed), 32) == draw(SplittableRng(seed), 32)
    }
  }

  property("the same seed and key give the same forked stream") {
    forAll { (seed: Long, key: Long) =>
      draw(SplittableRng(seed).fork(key), 32) == draw(SplittableRng(seed).fork(key), 32)
    }
  }

  property("forking does not depend on how much was drawn before") {
    forAll { (seed: Long, key: Long) =>
      val fresh = SplittableRng(seed)
      val used = SplittableRng(seed)
      draw(used, 100).size == 100 &&
      draw(fresh.fork(key), 16) == draw(used.fork(key), 16)
    }
  }

  property("different keys give different streams") {
    forAll { (seed: Long, key: Long) =>
      val root = SplittableRng(seed)
      draw(root.fork(key), 8) != draw(root.fork(key + 1), 8)
    }
  }

  test("nextDouble is uniform (chi-squared, 10 bins, 1e5 draws)") {
    val rng = SplittableRng(42L)
    val n = 100000
    val bins = new Array[Int](10)
    var i = 0
    while i < n do
      bins((rng.nextDouble() * 10).toInt) += 1
      i += 1
    val expected = n / 10.0
    val chi2 = bins.map(c => (c - expected) * (c - expected) / expected).sum
    // 99.9th percentile of chi-squared with 9 degrees of freedom.
    assert(chi2 < 27.88, s"chi2 = $chi2")
  }

  test("forked streams are uncorrelated") {
    val root = SplittableRng(7L)
    val a = root.fork(0L)
    val b = root.fork(1L)
    val n = 100000
    var sab, sa, sb, saa, sbb = 0.0
    var i = 0
    while i < n do
      val x = a.nextDouble()
      val y = b.nextDouble()
      sab += x * y
      sa += x
      sb += y
      saa += x * x
      sbb += y * y
      i += 1
    val cov = sab / n - (sa / n) * (sb / n)
    val corr = cov / math.sqrt((saa / n - (sa / n) * (sa / n)) * (sbb / n - (sb / n) * (sb / n)))
    assert(math.abs(corr) < 0.02, s"correlation = $corr")
  }

  test("nextGaussian has mean 0 and variance 1") {
    val rng = SplittableRng(2026L)
    val n = 100000
    val xs = Array.fill(n)(rng.nextGaussian())
    val mean = xs.sum / n
    val variance = xs.map(x => (x - mean) * (x - mean)).sum / (n - 1)
    assertEqualsDouble(mean, 0.0, 0.02)
    assertEqualsDouble(variance, 1.0, 0.02)
  }

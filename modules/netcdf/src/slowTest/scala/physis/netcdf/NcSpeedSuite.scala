package physis.netcdf

import java.nio.file.{Files, Path}

import physis.core.run.Physis
import physis.models.shallow.{Shallow, ShallowExport, ShallowState}
import physis.verify.SpeedReport

/**
 * The speed and the size of datasets: the default sea (128 x 96 cells, five fields) written at
 * every step, and the same file read back. The bars are far below what a CI runner reaches, so
 * that a slow runner passes but a writer that started to copy, to box numbers or to build the
 * file in memory would not; the numbers go to the test output and to `build/speed`.
 */
class NcSpeedSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val Steps = 200L

  private def sea() =
    Physis.model(Shallow).stepping(Shallow.stepping).seed(1L).build()

  private def withTemp[A](body: Path => A): A =
    val dir = Files.createTempDirectory("physis-nc-speed")
    val file = dir.resolve("sea.nc")
    try body(file)
    finally
      val _ = Files.deleteIfExists(file)
      val _ = Files.deleteIfExists(dir)

  private val spec = ShallowExport.spec(Shallow.params.defaults)

  private def write(file: Path): (Long, Double) =
    val start = System.nanoTime()
    val records = NcExport
      .write(
        sea(),
        Steps,
        ShallowState.stateExport,
        spec,
        Shallow.probes,
        file,
        NcExport.Options(every = 1)
      )
      .fold(e => fail(e), identity)
    assertEquals(records, Steps.toInt + 1)
    (Files.size(file), (System.nanoTime() - start) / 1e9)

  test("a dataset of 200 records of the default sea is written at 10 MB/s or more") {
    withTemp { file =>
      write(file) // warm-up: class loading and JIT
      val (bytes, seconds) = write(file)
      val rate = bytes / 1048576.0 / seconds
      println(SpeedReport.record("netcdf write, shallow 128x96, every step", rate, "MB/s"))
      println(SpeedReport.record(
        "netcdf file, shallow 128x96, 201 records",
        bytes / 1048576.0,
        "MB"
      ))
      assert(rate >= 10.0, f"$rate%.1f MB/s ($bytes bytes in $seconds%.2f s)")
    }
  }

  test("the size announced before the run is the size of the file, to the byte") {
    withTemp { file =>
      val (bytes, _) = write(file)
      val plan = NcExport
        .plan(
          sea().state,
          0L,
          Steps,
          Shallow.id,
          Shallow.version,
          ShallowState.stateExport,
          spec,
          Shallow.probes,
          NcExport.Options(every = 1)
        )
        .fold(e => fail(e), identity)
      assertEquals(plan.bytes, bytes)
    }
  }

  test("a dataset is read back at 20 MB/s or more, and every digest is in the file") {
    withTemp { file =>
      val (bytes, _) = write(file)
      val start = System.nanoTime()
      val nc = NcFile.read(file).fold(e => fail(e), identity)
      val height = nc.values("height").fold(e => fail(e), identity)
      val speed = nc.values("speed").fold(e => fail(e), identity)
      val seconds = (System.nanoTime() - start) / 1e9
      val rate = (height.length + speed.length) * 8.0 / 1048576.0 / seconds
      println(SpeedReport.record("netcdf read, two fields of 201 records", rate, "MB/s"))
      assertEquals(height.length, 201 * 128 * 96)
      assert(rate >= 20.0, f"$rate%.1f MB/s ($bytes bytes file, $seconds%.2f s)")
      // the last record is the state after 200 steps: its digest is the run's
      val twin = sea()
      twin.runSteps(Steps)
      val hi = nc.values("digest_hi").fold(e => fail(e), identity).last.toInt
      val lo = nc.values("digest_lo").fold(e => fail(e), identity).last.toInt
      assertEquals(((hi.toLong << 32) | (lo.toLong & 0xffffffffL)), twin.digest)
    }
  }

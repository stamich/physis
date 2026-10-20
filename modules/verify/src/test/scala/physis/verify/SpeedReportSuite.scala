package physis.verify

import java.nio.file.Files

class SpeedReportSuite extends munit.FunSuite:
  test("a recorded value is written as one line to a file named after it") {
    val dir = Files.createTempDirectory("physis-speed")
    val text = SpeedReport.record("physarum agent steps", 31.4159, "million/s", dir)
    assertEquals(text, "physarum agent steps: 31.42 million/s")
    val file = dir.resolve("physarum-agent-steps.txt")
    assertEquals(Files.readString(file), text + "\n")
    // a second value for the same name replaces the first
    val _ = SpeedReport.record("physarum agent steps", 2.0, "million/s", dir)
    assertEquals(Files.readString(file), "physarum agent steps: 2.00 million/s\n")
    val _ = Files.deleteIfExists(file)
    val _ = Files.deleteIfExists(dir)
  }

  test("a directory that cannot be created does not fail the test that measures") {
    val blocker = Files.createTempFile("physis-speed", ".txt")
    val text = SpeedReport.record("x", 1.0, "unit", blocker.resolve("below"))
    assertEquals(text, "x: 1.00 unit")
    val _ = Files.deleteIfExists(blocker)
  }

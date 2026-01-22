package physis.verify

import java.lang.management.ManagementFactory

/** Measures heap allocation of the current thread, to guard allocation-free inner loops. */
object Allocation:
  private val threads = ManagementFactory.getThreadMXBean match
    case bean: com.sun.management.ThreadMXBean => Some(bean)
    case _ => None

  /** True if the running JVM can measure per-thread allocation. */
  def supported: Boolean = threads.exists(_.isThreadAllocatedMemorySupported)

  /**
   * Bytes allocated by `steps` executions of `body`, after the same number of warm-up
   * executions so that class loading and JIT compilation are not counted.
   */
  def perStep(steps: Int)(body: => Unit): Long =
    require(supported, "per-thread allocation measurement is not supported by this JVM")
    val bean = threads.get
    run(steps, body)
    val before = bean.getCurrentThreadAllocatedBytes
    run(steps, body)
    bean.getCurrentThreadAllocatedBytes - before

  private def run(steps: Int, body: => Unit): Unit =
    var i = 0
    while i < steps do
      body
      i += 1

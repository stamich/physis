package physis.core.live

import java.util.concurrent.atomic.AtomicInteger

/**
 * Lock-free exchange of three buffers between one producer and one consumer.
 *
 * The producer writes into [[back]] and calls [[publish]]; the consumer calls [[latest]] to get
 * the newest published buffer. At any moment each of the three buffers belongs to exactly one
 * role (back, middle or front), so neither side ever sees a buffer the other is writing, and
 * neither side ever blocks: a slow consumer simply skips intermediate buffers.
 *
 * The middle slot's index and a "fresh" bit live in one `AtomicInteger`; publishing and taking
 * are each a single atomic `getAndSet`, which also orders the buffer contents between threads.
 *
 * Exactly one thread may produce and one thread may consume.
 */
final class TripleBuffer[A](make: () => A):
  private val buffers: Vector[A] = Vector.fill(3)(make())

  // Producer-owned and consumer-owned indices; the third buffer is the middle slot.
  private var backIndex = 0
  private var frontIndex = 2
  private val middle = new AtomicInteger(1)

  /** The buffer the producer may write. Changes after every [[publish]]. */
  def back: A = buffers(backIndex)

  /** Hands the back buffer to the consumer and takes the previous middle one as the new back. */
  def publish(): Unit =
    backIndex = middle.getAndSet(backIndex | TripleBuffer.Fresh) & TripleBuffer.IndexMask

  /**
   * The newest published buffer, if something was published since the last call; it stays
   * valid (and unchanged) until the next call that returns a value.
   */
  def latest(): Option[A] =
    if (middle.get() & TripleBuffer.Fresh) == 0 then None
    else
      frontIndex = middle.getAndSet(frontIndex) & TripleBuffer.IndexMask
      Some(buffers(frontIndex))

  /** The buffer the consumer currently holds (the last one [[latest]] returned). */
  def front: A = buffers(frontIndex)

object TripleBuffer:
  private val IndexMask = 0x3
  private val Fresh = 0x4

package physis.core.live

import java.util.concurrent.atomic.AtomicBoolean

class TripleBufferSuite extends munit.FunSuite:

  test("nothing is available before the first publish") {
    val buffer = new TripleBuffer(() => new Array[Long](1))
    assertEquals(buffer.latest(), None)
  }

  test("the consumer gets the newest published value and nothing twice") {
    val buffer = new TripleBuffer(() => new Array[Long](1))
    for v <- 1L to 3L do
      buffer.back(0) = v
      buffer.publish()
    assertEquals(buffer.latest().map(_(0)), Some(3L))
    assertEquals(buffer.latest(), None)
    buffer.back(0) = 4L
    buffer.publish()
    assertEquals(buffer.latest().map(_(0)), Some(4L))
    assertEquals(buffer.front(0), 4L)
  }

  test("under concurrent use no buffer is ever read while it is written") {
    val width = 32
    val publishes = 1_000_000L
    val buffer = new TripleBuffer(() => new Array[Long](width))
    val done = new AtomicBoolean(false)
    @volatile var torn = 0L
    @volatile var backwards = 0L
    @volatile var reads = 0L

    val consumer = new Thread(() =>
      var last = 0L
      var r = 0L
      while !done.get() || r == 0 do
        buffer.latest() match
          case Some(values) =>
            val first = values(0)
            var i = 1
            while i < width do
              if values(i) != first then torn += 1
              i += 1
            if first < last then backwards += 1
            last = first
            r += 1
          case None => Thread.onSpinWait()
      reads = r
    )
    consumer.start()
    var v = 1L
    while v <= publishes do
      java.util.Arrays.fill(buffer.back, v)
      buffer.publish()
      v += 1
    done.set(true)
    consumer.join(10_000)

    assertEquals(torn, 0L)
    assertEquals(backwards, 0L)
    assert(reads > 0L)
  }

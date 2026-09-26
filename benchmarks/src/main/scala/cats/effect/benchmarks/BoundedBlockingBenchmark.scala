/*
 * Copyright 2020-2025 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect
package benchmarks

import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global

import org.openjdk.jmh.annotations._

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/**
 * `IO.blocking` with its concurrency bounded by a `Semaphore`, the way a resource with a
 * natural limit (a JDBC pool of N connections) would be guarded. Compare with
 * [[BlockingBenchmark]].
 *
 * To run the benchmark from within sbt:
 *
 * benchmarks/Jmh/run -i 5 -wi 3 -f 1 -t 1 cats.effect.benchmarks.BoundedBlockingBenchmark
 *
 * After each iteration the peak number of live JVM threads is printed, since bounding the
 * number of blocking threads is the point of the semaphore.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class BoundedBlockingBenchmark {

  @Param(Array("10000"))
  var size: Int = _

  @Param(Array("1000"))
  var fibers: Int = _

  @Param(Array("64", "1000"))
  var permits: Int = _

  private[this] var permit: Semaphore[IO] = _

  private[this] val threads = ManagementFactory.getThreadMXBean()

  @Setup(Level.Iteration)
  def setup(): Unit = {
    permit = Semaphore[IO](permits.toLong).unsafeRunSync()
    threads.resetPeakThreadCount()
  }

  @TearDown(Level.Iteration)
  def reportThreads(): Unit =
    println(s"\npeak JVM threads: ${threads.getPeakThreadCount()} (permits=$permits)")

  /*
   * Same as `BlockingBenchmark.fine`, with a permit around every call: the cost of the
   * semaphore on top of the in-place fast path.
   */
  @Benchmark
  def fine(): Int = {
    def loop(n: Int): IO[Int] =
      permit.permit.surround(IO.blocking(42)).flatMap { a =>
        if (n < size) loop(n + 1)
        else IO.pure(a)
      }

    loop(0).unsafeRunSync()
  }

  /*
   * Same as `BlockingBenchmark.concurrent`, but at most `permits` fibers block at once.
   */
  @Benchmark
  def concurrent(): Unit =
    permit.permit.surround(IO.blocking(Thread.sleep(1L))).parReplicateA_(fibers).unsafeRunSync()
}

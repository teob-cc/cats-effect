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
package unsafe

import cats.effect.tracing.TracingConstants

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Diagnostics for code which calls `scala.concurrent.blocking` (or `Await.result`, or anything
 * else that goes through `BlockContext.blockOn`) while running on a compute worker thread.
 *
 * Each such call converts the worker into an `io-compute-blocker` thread and spawns (or reuses)
 * a replacement worker. This is usually a sign that some library deep in the call stack is
 * blocking without the application being aware of it. When enabled with the
 * `cats.effect.detectBlockOn` system property, the JVM stack of the offending call (the blame)
 * and the trace of the fiber running at that moment are printed to stderr.
 *
 * Every distinct call site is reported only once, to avoid flooding the output when a hot path
 * blocks repeatedly.
 */
private[unsafe] object BlockOnDetector {

  final val Enabled: Boolean =
    java.lang.Boolean.getBoolean("cats.effect.detectBlockOn")

  private[this] final val MaxDepth: Int =
    Integer.getInteger("cats.effect.detectBlockOn.maxDepth", 64).intValue()

  private[this] final val MaxReportedSites = 1024

  private[this] val reported = new ConcurrentHashMap[String, java.lang.Boolean]()
  private[this] val reportedCount = new AtomicInteger(0)

  def report(worker: WorkerThread[?]): Unit = {
    val trace = trimmed(worker.getStackTrace())
    val key = trace.mkString("\n")

    if (reportedCount.get() < MaxReportedSites && reported.putIfAbsent(key, true) == null) {
      if (reportedCount.incrementAndGet() == MaxReportedSites) {
        System
          .err
          .println(
            s"[WARNING] cats.effect.detectBlockOn: reached $MaxReportedSites distinct blocking call sites, further reports are suppressed")
      }

      val fiber = worker.currentIOFiber
      val fiberTrace =
        if ((fiber ne null) && TracingConstants.isStackTracing) fiber.captureTrace().pretty
        else
          "  <unavailable, run with -Dcats.effect.trackFiberContext=true and cats.effect.tracing.mode=cached or full>"

      System.err.println(mkWarning(worker.getName(), trace, fiberTrace))
    }
  }

  // Drop the frames of `Thread.getStackTrace` and of the pool machinery itself, so that the
  // first frame shown is `scala.concurrent.blocking`, `Await.result` or similar.
  private[this] def trimmed(st: Array[StackTraceElement]): Array[StackTraceElement] = {
    var i = 0
    while (i < st.length && {
        val cn = st(i).getClassName()
        cn.startsWith("java.lang.Thread") || cn.startsWith("cats.effect.unsafe.")
      }) i += 1
    st.slice(i, i + MaxDepth)
  }

  private[this] def mkWarning(
      threadName: String,
      stackTrace: Array[StackTraceElement],
      fiberTrace: String): String = {
    val sb = new StringBuilder()
    var i = 0
    while (i < stackTrace.length) {
      sb.append("  at ")
      sb.append(stackTrace(i).toString())
      sb.append("\n")
      i += 1
    }

    s"""|[WARNING] A Cats Effect worker thread ($threadName) entered a blocking region via
        |`BlockContext.blockOn` and was converted into a blocker thread. Blocking call site:
        |${sb.toString()}Fiber trace:
        |$fiberTrace
        |This is usually caused by `scala.concurrent.blocking` or `Await.result` inside a
        |library called from `IO.delay`/`IO.apply`. Consider wrapping the call in `IO.blocking`,
        |or replacing it with a non-blocking alternative.""".stripMargin
  }
}

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

import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}

import scala.util.Try

/**
 * Opt-in support for running `IO.blocking` and `IO.interruptible` on JDK 21+ virtual threads.
 *
 * Enabled with the `cats.effect.virtualBlocking` system property. When enabled (and the running
 * JVM supports virtual threads):
 *
 *   - the default blocking `ExecutionContext` becomes a virtual-thread-per-task executor
 *     instead of an unbounded cached pool of platform threads;
 *   - the [[WorkStealingThreadPool]] no longer converts its worker into a blocker thread when a
 *     fiber runs `IO.blocking`; the fiber is shifted onto a virtual thread instead, so no
 *     platform thread is spawned or parked on account of blocking calls which the JDK can
 *     unmount (sockets, locks, sleeps, JDBC drivers built on those).
 *
 * The compute workers themselves remain platform threads: they are pinned, long-lived event
 * loops with their own queues and poller, which is exactly what virtual threads are not
 * designed for. Code which blocks via `scala.concurrent.blocking`/`Await` while on a worker
 * still goes through `BlockContext.blockOn` and the regular blocker conversion.
 *
 * All access to the JDK 21 API is reflective, since Cats Effect is compiled for JDK 8.
 */
private[effect] object VirtualThreads {

  private[this] final val Requested: Boolean =
    java.lang.Boolean.getBoolean("cats.effect.virtualBlocking")

  private[this] final val Factory: Option[String => ThreadFactory] =
    if (Requested) {
      val f = Try {
        val builderCls = Class.forName("java.lang.Thread$Builder")
        val ofVirtualCls = Class.forName("java.lang.Thread$Builder$OfVirtual")
        val ofVirtual = classOf[Thread].getMethod("ofVirtual")
        val name = ofVirtualCls.getMethod("name", classOf[String], java.lang.Long.TYPE)
        val factory = builderCls.getMethod("factory")

        (prefix: String) => {
          val builder =
            name.invoke(ofVirtual.invoke(null), s"$prefix-", java.lang.Long.valueOf(0L))
          factory.invoke(builder).asInstanceOf[ThreadFactory]
        }
      }.toOption

      if (f.isEmpty) {
        System
          .err
          .println(
            "[WARNING] cats.effect.virtualBlocking is set, but this JVM does not support virtual threads (JDK 21+ required); falling back to platform threads")
      }
      f
    } else None

  private[this] final val NewThreadPerTaskExecutor: Option[ThreadFactory => ExecutorService] =
    Factory.flatMap { _ =>
      Try {
        val m = classOf[Executors].getMethod("newThreadPerTaskExecutor", classOf[ThreadFactory])
        (tf: ThreadFactory) => m.invoke(null, tf).asInstanceOf[ExecutorService]
      }.toOption
    }

  /**
   * `true` if `IO.blocking` should be shifted onto virtual threads rather than blocking a
   * compute worker in place.
   */
  final val BlockingEnabled: Boolean = NewThreadPerTaskExecutor.isDefined

  /**
   * A virtual-thread-per-task executor whose threads are named `<prefix>-<n>`, or `None` if
   * virtual blocking is not enabled.
   */
  def newVirtualThreadPerTaskExecutor(prefix: String): Option[ExecutorService] =
    for {
      mkFactory <- Factory
      mkExecutor <- NewThreadPerTaskExecutor
    } yield mkExecutor(mkFactory(prefix))
}

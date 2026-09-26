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

package cats.effect.pekko

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

import java.util.concurrent.{AbstractExecutorService, RejectedExecutionException, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An `ExecutorService` view over a pool it does not own.
 *
 * Tasks go straight to `underlying`. When submitted from a Cats Effect worker thread they land
 * in that worker's local queue, so actor-to-actor messages keep the pool's locality. `shutdown`
 * only stops this view from accepting work; tasks already handed over belong to the underlying
 * pool and run to completion there, which is why `isTerminated` turns true as soon as the view
 * is shut down.
 */
final class BorrowedExecutorService(underlying: ExecutionContext)
    extends AbstractExecutorService
    with ExecutionContextExecutorService {

  private[this] val stopped = new AtomicBoolean(false)

  override def execute(task: Runnable): Unit =
    if (stopped.get()) throw new RejectedExecutionException("executor has been shut down")
    else underlying.execute(task)

  override def reportFailure(cause: Throwable): Unit = underlying.reportFailure(cause)

  override def shutdown(): Unit = stopped.set(true)

  override def shutdownNow(): java.util.List[Runnable] = {
    shutdown()
    java.util.Collections.emptyList[Runnable]()
  }

  override def isShutdown(): Boolean = stopped.get()

  override def isTerminated(): Boolean = stopped.get()

  override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = stopped.get()
}

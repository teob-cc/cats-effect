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

import cats.effect.unsafe.IORuntime

import com.typesafe.config.Config
import org.apache.pekko.dispatch.{
  DispatcherPrerequisites,
  ExecutorServiceConfigurator,
  ExecutorServiceFactory
}

import scala.concurrent.ExecutionContext

import java.util.concurrent.{ExecutorService, ThreadFactory}

/**
 * A Pekko executor that runs a dispatcher's mailboxes on the Cats Effect compute pool.
 *
 * {{{
 * pekko.actor.default-dispatcher {
 *   executor = "cats.effect.pekko.CatsEffectExecutorServiceConfigurator"
 * }
 * }}}
 *
 * The pool comes from [[PekkoCatsEffect.actorSystem]] when the `ActorSystem` is created that
 * way (the dispatcher config then carries a `cats-effect.runtime-key`), and from
 * `IORuntime.global` otherwise.
 *
 * The runtime is borrowed, never owned: when Pekko shuts the executor down (at termination, or
 * when an idle dispatcher passes its `shutdown-timeout`), only this executor's view closes and
 * the Cats Effect runtime keeps running.
 */
class CatsEffectExecutorServiceConfigurator(
    config: Config,
    prerequisites: DispatcherPrerequisites)
    extends ExecutorServiceConfigurator(config, prerequisites) {

  private[this] val compute: () => ExecutionContext = {
    val key = CatsEffectExecutorServiceConfigurator.RuntimeKeyPath
    if (config.hasPath(key)) {
      val id = config.getString(key)
      () => PekkoCatsEffect.lookup(id)
    } else { () => IORuntime.global.compute }
  }

  override def createExecutorServiceFactory(
      id: String,
      threadFactory: ThreadFactory): ExecutorServiceFactory =
    new ExecutorServiceFactory {
      override def createExecutorService: ExecutorService =
        new BorrowedExecutorService(compute())
    }
}

object CatsEffectExecutorServiceConfigurator {

  /**
   * Dispatcher config path naming the runtime registered by [[PekkoCatsEffect]].
   */
  final val RuntimeKeyPath = "cats-effect.runtime-key"
}

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

import cats.effect.{IO, Resource}

import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import org.apache.pekko.actor.ActorSystem

import scala.concurrent.ExecutionContext

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PekkoCatsEffect {

  private[this] val runtimes = new ConcurrentHashMap[String, ExecutionContext]()

  private[pekko] def lookup(id: String): ExecutionContext = {
    val ec = runtimes.get(id)
    if (ec eq null)
      throw new IllegalStateException(
        s"no Cats Effect runtime registered as '$id'; was the ActorSystem created outside PekkoCatsEffect.actorSystem?")
    ec
  }

  /**
   * An `ActorSystem` whose default dispatcher runs on the compute pool of the runtime this `IO`
   * runs on, terminated when the resource is released.
   *
   * Only `pekko.actor.default-dispatcher` is moved onto Cats Effect. Pekko's
   * `internal-dispatcher` keeps its own threads, so blocking in user actors can't starve
   * clustering or remoting. Actors that block should get a dispatcher of their own, e.g.
   * `executor = "virtual-thread-executor"` on JDK 21+.
   *
   * @param config
   *   application config; defaults to `ConfigFactory.load()`
   */
  def actorSystem(
      name: String,
      config: Config = ConfigFactory.load()): Resource[IO, ActorSystem] =
    for {
      compute <- Resource.eval(IO.executionContext)
      id <- Resource.make(IO {
        val id = UUID.randomUUID().toString
        runtimes.put(id, compute)
        id
      })(id => IO(runtimes.remove(id)).void)
      system <- Resource.make(IO.blocking(ActorSystem(name, withCatsEffect(config, id))))(
        system => IO.fromFuture(IO(system.terminate())).void)
    } yield system

  /**
   * `config` with the default dispatcher switched to [[CatsEffectExecutorServiceConfigurator]],
   * bound to the runtime registered as `id`.
   */
  private[pekko] def withCatsEffect(config: Config, id: String): Config =
    config
      .withValue(
        "pekko.actor.default-dispatcher.executor",
        ConfigValueFactory.fromAnyRef(classOf[CatsEffectExecutorServiceConfigurator].getName))
      .withValue(
        s"pekko.actor.default-dispatcher.${CatsEffectExecutorServiceConfigurator.RuntimeKeyPath}",
        ConfigValueFactory.fromAnyRef(id))
}

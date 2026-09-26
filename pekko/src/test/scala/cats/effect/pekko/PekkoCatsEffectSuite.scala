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

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import org.apache.pekko.actor.{Actor, ActorSystem, Props}

import scala.concurrent.{blocking, ExecutionContext, Promise}
import scala.concurrent.duration._

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.{RejectedExecutionException, TimeUnit}

import munit.FunSuite

class PekkoCatsEffectSuite extends FunSuite {

  override val munitTimeout = 30.seconds

  // replies with the name of the thread that processed the message
  private class ThreadName(inBlocking: Boolean) extends Actor {
    def receive = {
      case p: Promise[String] @unchecked =>
        p.success(
          if (inBlocking) blocking(Thread.currentThread().getName)
          else Thread.currentThread().getName)
    }
  }

  private def threadOf(system: ActorSystem, inBlocking: Boolean = false): IO[String] =
    IO.fromFuture(IO {
      val p = Promise[String]()
      system.actorOf(Props(new ThreadName(inBlocking))) ! p
      p.future
    })

  test("actors run on the Cats Effect compute pool") {
    val name = PekkoCatsEffect.actorSystem("compute").use(threadOf(_)).unsafeRunSync()
    assert(name.startsWith("io-compute-"), name)
  }

  test("blocking inside an actor goes through the worker's blocker handoff and is blamed") {
    val err = new ByteArrayOutputStream()
    val stderr = System.err
    System.setErr(new PrintStream(err, true))
    val name =
      try PekkoCatsEffect.actorSystem("blocking").use(threadOf(_, inBlocking = true)).unsafeRunSync()
      finally System.setErr(stderr)

    assert(name.startsWith("io-compute-blocker-"), name)
    val report = err.toString
    assert(report.contains("entered a blocking region via"), report)
    assert(report.contains("PekkoCatsEffectSuite$ThreadName"), report)
    assert(report.contains("such as a Pekko actor"), report)
  }

  test("terminating the ActorSystem leaves the runtime running") {
    PekkoCatsEffect.actorSystem("terminate").use(threadOf(_)).unsafeRunSync()
    val after = IO(Thread.currentThread().getName).unsafeRunSync()
    assert(after.startsWith("io-compute-"), after)
  }

  test("the runtime registration is removed on release") {
    val id = PekkoCatsEffect
      .actorSystem("registry")
      .use { system =>
        IO(
          system
            .settings
            .config
            .getString(
              s"pekko.actor.default-dispatcher.${CatsEffectExecutorServiceConfigurator.RuntimeKeyPath}"))
      }
      .unsafeRunSync()
    intercept[IllegalStateException](PekkoCatsEffect.lookup(id))
  }

  test("a shut-down view rejects work but does not stop the pool") {
    val view = new BorrowedExecutorService(ExecutionContext.global)
    view.shutdown()
    assert(view.isShutdown())
    assert(view.isTerminated())
    assert(view.awaitTermination(1, TimeUnit.SECONDS))
    assert(view.shutdownNow().isEmpty)
    intercept[RejectedExecutionException](view.execute(() => ()))

    val p = Promise[Unit]()
    ExecutionContext.global.execute(() => { p.success(()); () })
    IO.fromFuture(IO(p.future)).timeout(5.seconds).unsafeRunSync()
  }
}

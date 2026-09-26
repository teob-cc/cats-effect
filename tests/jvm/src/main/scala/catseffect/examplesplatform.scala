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

package catseffect

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.atomic.AtomicReference

package object examples {
  def exampleExecutionContext = ExecutionContext.global
}

package examples {

  object ShutdownHookImmediateTimeout extends IOApp.Simple {

    override protected def runtimeConfig =
      super.runtimeConfig.copy(shutdownHookTimeout = Duration.Zero)

    val run: IO[Unit] = IO(System.exit(0))
  }

  object FatalErrorUnsafeRun extends IOApp {
    import cats.effect.unsafe.implicits.global

    def run(args: List[String]): IO[ExitCode] =
      for {
        _ <- (0 until 100).toList.traverse(_ => IO.blocking(IO.never.unsafeRunSync()).start)
        _ <- IO.blocking(IO(throw new OutOfMemoryError("Boom!")).start.unsafeRunSync())
        _ <- IO.never[Unit]
      } yield ExitCode.Success
  }

  object EvalOnMainThread extends IOApp {
    def run(args: List[String]): IO[ExitCode] = {
      val mainThread = Thread.currentThread()

      IO(Thread.currentThread() eq mainThread).evalOn(MainThread) map {
        case true => ExitCode.Success
        case false => ExitCode.Error
      }
    }
  }

  object MainThreadReportFailure extends IOApp {

    val exitCode = new AtomicReference[ExitCode](ExitCode.Error)

    override def reportFailure(err: Throwable): IO[Unit] =
      IO(exitCode.set(ExitCode.Success))

    def run(args: List[String]): IO[ExitCode] =
      IO.raiseError(new Exception).startOn(MainThread) *>
        IO.sleep(1.second) *> IO(exitCode.get)

  }

  object MainThreadReportFailureRunnable extends IOApp {

    val exitCode = new AtomicReference[ExitCode](ExitCode.Error)

    override def reportFailure(err: Throwable): IO[Unit] =
      IO(exitCode.set(ExitCode.Success))

    def run(args: List[String]): IO[ExitCode] =
      IO(MainThread.execute(() => throw new Exception)) *>
        IO.sleep(1.second) *> IO(exitCode.get)

  }

  object BlockedThreads extends IOApp.Simple {

    override protected def blockedThreadDetectionEnabled = true

    // Loop prevents other worker threads from being parked and hence not
    // performing the blocked check. Cedeing makes the test more deterministic
    val run =
      IO.cede.foreverM.start >> IO(Thread.sleep(2.seconds.toMillis))
  }

  object DetectBlockOn extends IOApp.Simple {

    // read lazily on the first `blockOn`, so setting it here is early enough
    System.setProperty("cats.effect.detectBlockOn", "true")
    System.setProperty("cats.effect.trackFiberContext", "true")

    // stands in for a library which blocks without the caller knowing
    def hiddenBlockingCall(): Unit =
      scala.concurrent.blocking(Thread.sleep(10L))

    // the same call site is reported only once
    val run = IO(hiddenBlockingCall()).replicateA_(5)
  }

  object VirtualBlocking extends IOApp.Simple {

    // read lazily when the runtime creates its blocking pool, so setting it here is early enough
    System.setProperty("cats.effect.virtualBlocking", "true")

    def isVirtual(t: Thread): Boolean =
      classOf[Thread].getMethod("isVirtual").invoke(t).asInstanceOf[Boolean]

    val run = for {
      blocker <- IO.blocking(Thread.currentThread())
      interruptible <- IO.interruptible(Thread.currentThread())
      after <- IO(Thread.currentThread())
      _ <- IO.println(s"blocking virtual=${isVirtual(blocker)} name=${blocker.getName}")
      _ <- IO.println(s"interruptible virtual=${isVirtual(interruptible)}")
      _ <- IO.println(s"after virtual=${isVirtual(after)} name=${after.getName}")
    } yield ()
  }
}

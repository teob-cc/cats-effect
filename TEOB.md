# cc.teob fork of Cats Effect

This is [typelevel/cats-effect](https://github.com/typelevel/cats-effect) with a few opt-in
runtime features, published as `cc.teob` for wider testing. Artifact names, packages and
binary API are the same as upstream: MiMa checks every release against the `org.typelevel`
releases, so it is a drop-in replacement.

The fork's `series/3.x` is upstream `series/3.x` plus the fork's commits; work and releases
happen directly on it.

## Features

Both are off by default and switched on with JVM system properties.

| Property | Effect |
|---|---|
| `-Dcats.effect.virtualBlocking=true` | JDK 21+: `IO.blocking` / `IO.interruptible` run on a virtual thread per task instead of turning a compute worker into a blocker thread. Compute workers stay platform threads. |
| `-Dcats.effect.detectBlockOn=true` | When a compute worker enters `BlockContext.blockOn` (`scala.concurrent.blocking`, `Await.result`, usually deep inside a library), print the blocking stack and the fiber trace to stderr, once per call site. Add `-Dcats.effect.trackFiberContext=true` for the fiber trace. |

See discussion [#3927](https://github.com/typelevel/cats-effect/discussions/3927).

## Using it

Replace the upstream modules, and exclude them so that libraries such as fs2 or http4s don't
bring `org.typelevel` Cats Effect back in transitively:

```scala
val catsEffectTeob = "3.7.2-teob.1"

libraryDependencies ++= Seq(
  "cc.teob" %% "cats-effect" % catsEffectTeob,
  "cc.teob" %% "cats-effect-kernel" % catsEffectTeob,
  "cc.teob" %% "cats-effect-std" % catsEffectTeob
)

excludeDependencies ++= Seq(
  "org.typelevel" %% "cats-effect",
  "org.typelevel" %% "cats-effect-kernel",
  "org.typelevel" %% "cats-effect-std"
)
```

Do the same for `cats-effect-testkit`, `cats-effect-kernel-testkit` and `cats-effect-laws` if
you use them.

## Pekko on the Cats Effect pool

`cats-effect-pekko` (JVM, Scala 2.13 / 3, Pekko 1.7) runs Pekko's default dispatcher on
the Cats Effect compute pool, so actors and fibers share one set of CPU threads:

```scala
libraryDependencies += "cc.teob" %% "cats-effect-pekko" % catsEffectTeob
```

```scala
import cats.effect.{IO, IOApp}
import cats.effect.pekko.PekkoCatsEffect

object Main extends IOApp.Simple {
  val run = PekkoCatsEffect.actorSystem("app").use { system =>
    IO.never // start actors with `system` here
  }
}
```

- `actorSystem` binds the default dispatcher to the runtime it runs on and terminates the
  `ActorSystem` when the resource is released. The Cats Effect runtime is borrowed: Pekko
  shutting a dispatcher down (including an idle one after `shutdown-timeout`) never stops it.
- To configure it by hand instead, set
  `pekko.actor.default-dispatcher.executor = "cats.effect.pekko.CatsEffectExecutorServiceConfigurator"`;
  it then uses `IORuntime.global`.
- `internal-dispatcher` keeps its own threads, so blocking in user actors can't starve
  clustering or remoting.
- Actors that block should get their own dispatcher, e.g. on JDK 21+:
  ```hocon
  blocking-dispatcher {
    executor = "virtual-thread-executor"
    throughput = 1
  }
  ```
- `blocking {}` or `Await` inside an actor on the Cats Effect pool goes through the worker's
  blocker handoff, like in a fiber, and `-Dcats.effect.detectBlockOn=true` reports the actor's
  stack for it.

## Releasing

Run the **Release cc.teob to Sonatype** workflow from the Actions tab with a version such as
`3.7.2-teob.1`. It runs the JVM tests, publishes every module for Scala 2.13 and 3 and all
platforms (JVM, JS, Native) to Sonatype Central, and tags the commit `teob-v<version>`. Tick
`dry_run` to build, sign and stage everything without uploading or tagging.

Unlike upstream, the fork does not publish for Scala 2.12 (set in `teob.sbt`).

It uses these `teob-cc` organisation secrets:

- `SONATYPE_USER`, `SONATYPE_PASSWORD`: a Central portal user token from the account that owns
  the `cc.teob` namespace (verified through a TXT record on `teob.cc`).
- `GPG_SECRET`, `GPG_PASS`: the dedicated release signing key
  `teob-cc release signing <release@teob.cc>`, fingerprint
  `0B21 FAAE CFDD 6334 1495  CCEC 7F12 AC5B 3BB5 6BD1`, expiring 2029-09. The private key only
  exists in the secret; to rotate, generate a new key, replace both secrets and upload the
  public key to keyserver.ubuntu.com and keys.openpgp.org.

## Syncing with upstream

Use **Sync fork** on GitHub, or locally:

```sh
git pull upstream series/3.x   # merge upstream into the fork's series/3.x
git push
```

All fork-specific build changes are in `project/TeobFork.scala`, `teob.sbt` (fork-only modules
such as `cats-effect-pekko`), `.github/workflows/teob-release.yml` and this file, so `build.sbt`
does not conflict.

# cc.teob fork of Cats Effect

This is [typelevel/cats-effect](https://github.com/typelevel/cats-effect) with a few opt-in
runtime features, published as `cc.teob` for wider testing. Artifact names, packages and
binary API are the same as upstream: MiMa checks every release against the `org.typelevel`
releases, so it is a drop-in replacement.

Branch `teob/3.x` is upstream `series/3.x` plus the fork's commits.

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

## Releasing

Run the **Release cc.teob to Sonatype** workflow from the Actions tab with a version such as
`3.7.2-teob.1`. It runs the JVM tests, publishes every module for all Scala versions (2.12,
2.13, 3) and platforms (JVM, JS, Native) to Sonatype Central, and tags the commit
`teob-v<version>`.

It needs the same organisation secrets as `jacum/pekko-sensors`: `SONATYPE_USER`,
`SONATYPE_PASSWORD` (a Central portal token with the `cc.teob` namespace), `GPG_SECRET`
(ASCII-armoured private key) and `GPG_PASS`.

## Syncing with upstream

```sh
git fetch upstream
git rebase upstream/series/3.x teob/3.x   # or merge
```

All fork-specific build changes are in `project/TeobFork.scala`,
`.github/workflows/teob-release.yml` and this file, so `build.sbt` does not conflict.

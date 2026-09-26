// Fork-only projects (teob-cc), kept out of build.sbt so upstream syncs don't conflict.
// They are not aggregated by the upstream root projects: the release workflow publishes them
// explicitly, and they are tested with `pekko/test`.

val PekkoVersion = "1.7.0"
// build.sbt's vals are not visible here; keep in step with its MUnitVersion
val TeobMUnitVersion = "1.1.0"

/**
 * Runs Pekko dispatchers on the Cats Effect compute pool, so actors and fibers share one set of
 * CPU threads. JVM only.
 */
lazy val pekko = project
  .in(file("pekko"))
  .dependsOn(LocalProject("coreJVM"))
  .settings(
    name := "cats-effect-pekko",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor" % PekkoVersion,
      "org.scalameta" %% "munit" % TeobMUnitVersion % Test
    ),
    // new module: nothing upstream to check binary compatibility against
    mimaPreviousArtifacts := Set.empty,
    Test / fork := true,
    // exercised by the "blocking inside an actor" test
    Test / javaOptions ++= Seq(
      "-Dcats.effect.detectBlockOn=true",
      "-Dcats.effect.trackFiberContext=true")
  )

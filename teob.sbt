// Fork-only projects (teob-cc), kept out of build.sbt so upstream syncs don't conflict.
// They are not aggregated by the upstream root projects: the release workflow publishes them
// explicitly, and they are tested with `pekko/test`.

// The fork publishes for Scala 2.13 and 3 only. Filtering upstream's list (instead of
// hardcoding it) keeps later Scala bumps from build.sbt. teob.sbt loads after build.sbt.
ThisBuild / crossScalaVersions ~= (_.filterNot(_.startsWith("2.12.")))

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

// Every CI job runs on teob-cc's own runners, never on GitHub-hosted ones: the "test-public"
// pool (amd64 Linux only; setup-java still installs the JDK each job asks for). build.sbt stays
// as upstream; this narrows its matrix to that one pool:
//  - sbt-github-actions renders runs-on as [ "${{ matrix.os }}", <extra labels> ], so the
//    matrix os is the "self-hosted" label and "test-public" the extra label, for every
//    generated job (build, publish, dependency-submission).
//  - The arm64, Windows and macOS legs are dropped (no such runners). Exclusions that name a
//    value no longer in the matrix (those OSes, and Scala 2.12, dropped above) match nothing
//    and are removed, because the generator rejects them.
//  - Steps that upstream runs only on its primary OS (build.sbt's PrimaryOS, "ubuntu-latest")
//    get that condition rewritten to the self-hosted label, so they still run.
val TeobRunnerLabels = List("self-hosted", "test-public")
val UpstreamPrimaryOS = "ubuntu-latest"
ThisBuild / githubWorkflowOSes := TeobRunnerLabels.take(1)
ThisBuild / githubWorkflowGeneratedCI ~= (_.map { job =>
  val os = TeobRunnerLabels.head
  val matrix = Map(
    "os" -> List(os),
    "scala" -> job.scalas,
    "java" -> job.javas.map(_.render)) ++ job.matrixAdds
  def onPrimary(step: WorkflowStep): WorkflowStep =
    step.withCond(step.cond.map(_.replace(s"matrix.os == '$UpstreamPrimaryOS'", s"matrix.os == '$os'")))
  job
    .withOses(List(os))
    .withRunsOnExtraLabels(TeobRunnerLabels.tail)
    .withMatrixExcs(job.matrixExcs.filter(_.matching.forall {
      case (key, value) => matrix.get(key).exists(_.contains(value))
    }))
    .withSteps(job.steps.map(onPrimary))
})
// The generated clean.yml hardcodes runs-on: ubuntu-latest; .github/workflows/clean.yml is kept
// by hand instead, with the same labels.
ThisBuild / githubWorkflowIncludeClean := false

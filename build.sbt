ThisBuild / tlBaseVersion := "0.14"

ThisBuild / tlVersionIntroduced := Map("3" -> "0.12.0")

// Only publish on tagged releases, not snapshots on main
ThisBuild / tlCiReleaseBranches := Seq()

ThisBuild / githubWorkflowOSes := Seq("blacksmith-2vcpu-ubuntu-2204")

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("21"))

// CI: enable scalafix import ordering check
ThisBuild / tlCiScalafixCheck := true
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// CI: add coverage reporting step after tests
ThisBuild / githubWorkflowBuildPostamble ++= Seq(
  WorkflowStep.Sbt(
    List("coverage", "test", "coverageReport"),
    name = Some("Generate coverage report")
  )
)

ThisBuild / organization     := "ai.entrolution"
ThisBuild / organizationName := "Greg von Nessi"
ThisBuild / startYear        := Some(2023)
ThisBuild / licenses         := Seq(License.Apache2)
ThisBuild / developers ++= List(
  tlGitHubDev("gvonnessi", "Greg von Nessi")
)

ThisBuild / scalaVersion := DependencyVersions.scala2p13Version
ThisBuild / crossScalaVersions := Seq(
  DependencyVersions.scala2p13Version,
  DependencyVersions.scala3Version
)

Global / idePackagePrefix := Some("ai.entrolution")
Global / excludeLintKeys += idePackagePrefix

lazy val commonSettings = Seq(
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) =>
        Seq(
          "-Xlint:_",
          "-Ywarn-unused:-implicits",
          "-Ywarn-value-discard",
          "-Ywarn-dead-code"
        )
      case Some((3, _)) =>
        Seq(
          "-Wconf:cat=unchecked:s"
        )
      case _ => Seq()
    }
  }
)

lazy val bengalStm = (project in file("."))
  .settings(
    commonSettings,
    name := "bengal-stm",
    libraryDependencies ++= Dependencies.bengalStm,
    // src/test/scala/usercode is deliberately outside `ai.entrolution`, so that the
    // public API is compiled from a user's vantage point (see PublicApiSpec -- the
    // README's imports were broken for everyone but us, and no in-package test could
    // see it). sbt's default layering caches project classes in a SEPARATE class
    // loader from the test classes, and `STM[F]` extends `private[stm]` traits, which
    // the JVM enforces per-loader -- so a test outside the package fails to link.
    // A real user gets one flat classpath from the published jar and never hits this.
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
  )

// Throughput benchmarks. Deliberately NOT aggregated into the root project, so
// `sbt test` and CI never build them; run them explicitly with
// `sbt benchmarks/Jmh/run`. They exist to answer one question before a release --
// what did the correctness fixes cost? -- and the answer is only meaningful when
// measured against a prior revision, which is why the module depends on the
// library through its PUBLIC api only (every fix in this workstream was
// private[stm], so the same benchmark compiles against both).
lazy val benchmarks = (project in file("benchmarks"))
  .dependsOn(bengalStm)
  .enablePlugins(JmhPlugin, NoPublishPlugin)
  .settings(
    commonSettings,
    name := "bengal-stm-benchmarks",
    // JMH GENERATES Java, and its generated code trips -Werror on javac. sbt-typelevel
    // keys fatal warnings off GITHUB_ACTIONS, so running the CI-parity gate locally
    // (GITHUB_ACTIONS=true sbt benchmarks/Jmh/compile) fails on code nobody here wrote.
    // CI never builds this module -- it is not aggregated -- so the trap only springs on
    // a contributor doing the right thing.
    Jmh / javacOptions ~= (_.filterNot(_ == "-Werror"))
  )

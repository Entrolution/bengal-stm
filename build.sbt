ThisBuild / tlBaseVersion := "0.11"

ThisBuild / tlVersionIntroduced := Map("3" -> "0.11.0")

// Only publish on tagged releases, not snapshots on main
ThisBuild / tlCiReleaseBranches := Seq()

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("21"))

ThisBuild / organization     := "ai.entrolution"
ThisBuild / organizationName := "Greg von Nessi"
ThisBuild / startYear        := Some(2023)
ThisBuild / licenses         := Seq(License.Apache2)
ThisBuild / developers ++= List(
  tlGitHubDev("gvonness", "Greg von Nessi")
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
    libraryDependencies ++= Dependencies.bengalStm
  )

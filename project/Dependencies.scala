import sbt._

object DependencyVersions {
  val scala2p13Version = "2.13.16"
  val scala3Version    = "3.3.4"

  val catsEffectVersion = "3.6.3"
  val catsFreeVersion   = "2.13.0"

  val catsEffectTestingVersion = "1.6.0"

  val scalaTestVersion          = "3.2.19"
  val scalaCheckVersion         = "1.18.1"
  val scalaTestPlusCheckVersion = "3.2.19.0"
}

object Dependencies {
  import DependencyVersions._

  private val catsEffect: ModuleID =
    "org.typelevel" %% "cats-effect" % catsEffectVersion

  private val catsEffectTesting: ModuleID =
    "org.typelevel" %% "cats-effect-testing-scalatest" % catsEffectTestingVersion % "test"

  private val catsFree: ModuleID =
    "org.typelevel" %% "cats-free" % catsFreeVersion

  private val scalaTest: ModuleID =
    "org.scalatest" %% "scalatest" % scalaTestVersion % "test"

  private val scalaCheck: ModuleID =
    "org.scalacheck" %% "scalacheck" % scalaCheckVersion % "test"

  private val scalaTestPlusCheck: ModuleID =
    "org.scalatestplus" %% "scalacheck-1-18" % scalaTestPlusCheckVersion % "test"

  val bengalStm: Seq[ModuleID] =
    Seq(
      catsEffect,
      catsFree,
      catsEffectTesting,
      scalaTest,
      scalaCheck,
      scalaTestPlusCheck
    )
}

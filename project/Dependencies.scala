import sbt._

object DependencyVersions {
  val scala2p13Version = "2.13.13"

  val catsEffectVersion = "3.4.8"
  val catsFreeVersion   = "2.9.0"

  val catsEffectTestingVersion = "1.4.0"

  val scalaCheckVersion         = "1.17.0"
  val scalaTestPlusCheckVersion = "3.2.14.0"
}

object Dependencies {
  import DependencyVersions._

  private val catsEffect: ModuleID =
    "org.typelevel" %% "cats-effect" % catsEffectVersion

  private val catsEffectTesting: ModuleID =
    "org.typelevel" %% "cats-effect-testing-scalatest" % catsEffectTestingVersion % "test"

  private val catsFree: ModuleID =
    "org.typelevel" %% "cats-free" % catsFreeVersion

  private val scalaCheck: ModuleID =
    "org.scalacheck" %% "scalacheck" % scalaCheckVersion % "test"

  private val scalaTestPlusCheck: ModuleID =
    "org.scalatestplus" %% "scalacheck-1-17" % scalaTestPlusCheckVersion % "test"

  val bengalStm: Seq[ModuleID] =
    Seq(
      catsEffect,
      catsFree,
      catsEffectTesting,
      scalaCheck,
      scalaTestPlusCheck
    )
}

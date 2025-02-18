import play.core.PlayVersion
import sbt.Tests.{Group, SubProcess}
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin._
import uk.gov.hmrc.SbtAutoBuildPlugin

lazy val scoverageSettings = {
  import scoverage.ScoverageKeys
  Seq(
    // Semicolon-separated list of regexs matching classes to exclude
    ScoverageKeys.coverageExcludedPackages := """uk\.gov\.hmrc\.BuildInfo;.*\.Routes;.*\.RoutesPrefix;.*Filters?;MicroserviceAuditConnector;Module;GraphiteStartUp;.*\.Reverse[^.]*""",
    ScoverageKeys.coverageMinimum := 75,
    ScoverageKeys.coverageFailOnMinimum := true,
    ScoverageKeys.coverageHighlighting := true,
    parallelExecution in Test := false
  )
}

lazy val compileDeps = Seq(
  "uk.gov.hmrc" %% "bootstrap-play-26" % "1.16.0",
  "uk.gov.hmrc" %% "mongo-lock" % "6.23.0-play-26",
  "uk.gov.hmrc" %% "simple-reactivemongo" % "7.30.0-play-26",
  "uk.gov.hmrc" %% "play-scheduling" % "7.4.0-play-26",
  "com.typesafe.play" % "play-json-joda_2.12" % "2.6.0",
  "com.beachape" %% "enumeratum-play-json" % "1.6.0",
  "org.typelevel" %% "cats-core" % "2.1.1"
)

def testDeps(scope: String) = Seq(
  "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.3" % scope,
  "org.mockito" %% "mockito-scala-scalatest" % "1.7.1" % scope,
  "uk.gov.hmrc" %% "reactivemongo-test" % "4.21.0-play-26" % scope,
  "com.github.tomakehurst" % "wiremock" % "1.58" % scope
)

lazy val root = (project in file("."))
  .settings(
    name := "api-platform-jobs",
    organization := "uk.gov.hmrc",
    scalaVersion := "2.12.12",
    scalacOptions += "-Ypartial-unification",
    majorVersion := 0,
    PlayKeys.playDefaultPort := 6700,
    resolvers ++= Seq(
      Resolver.typesafeRepo("releases")
    ),
    libraryDependencies ++= compileDeps ++ testDeps("test"),
    publishingSettings,
    scoverageSettings,
  )
  .settings(
    unmanagedResourceDirectories in Test += baseDirectory.value / "test" / "resources"
  )
  .enablePlugins(PlayScala, SbtAutoBuildPlugin, SbtGitVersioning, SbtDistributablesPlugin, SbtArtifactory)
  .disablePlugins(JUnitXmlReportPlugin)

def oneForkedJvmPerTest(tests: Seq[TestDefinition]) = {
  tests.map { test =>
    new Group(test.name, Seq(test), SubProcess(ForkOptions().withRunJVMOptions(Vector(s"-Dtest.name=${test.name}"))))
  }
}

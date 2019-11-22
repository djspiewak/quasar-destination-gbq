import scala.collection.Seq

homepage in ThisBuild := Some(url("https://github.com/slamdata/quasar-destination-gbq"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/slamdata/quasar-destination-gbq"),
  "scm:git@github.com:slamdata/quasar-destination-gbq.git"))

val ArgonautVersion = "6.2.3"
val SpecsVersion = "4.7.0"
val SimpileLogging4Scala = "1.7.25"

// Include to also publish a project's tests
lazy val publishTestsSettings = Seq(
  publishArtifact in (Test, packageBin) := true)

lazy val QuasarVersion = IO.read(file("./quasar-version")).trim

lazy val root = project
  .in(file("."))
  .settings(noPublishSettings)
  .aggregate(core)
  .enablePlugins(AutomateHeaderPlugin)

lazy val core = project
  .in(file("core"))
  .settings(name := "quasar-destination-gbq")
  .settings(
    performMavenCentralSync := false,

    quasarPluginName := "gbq",
    quasarPluginQuasarVersion := QuasarVersion,
    quasarPluginDestinationFqcn := Some("quasar.destination.gbq.GBQDestinationModule$"),

    quasarPluginDependencies ++= Seq(
      "org.slf4s" %% "slf4s-api" % SimpileLogging4Scala,
      "io.argonaut"  %% "argonaut" % ArgonautVersion),

    libraryDependencies ++= Seq(
      "org.specs2" %% "specs2-core" % SpecsVersion % Test,
      "com.slamdata" %% "quasar-foundation" % QuasarVersion,
      "com.slamdata" %% "quasar-foundation" % QuasarVersion % Test classifier "tests",
      "org.specs2" %% "specs2-scalacheck" % SpecsVersion % Test,
      "org.specs2" %% "specs2-scalaz" % SpecsVersion % Test),

    publishAsOSSProject := true)
  .enablePlugins(AutomateHeaderPlugin, QuasarPlugin)

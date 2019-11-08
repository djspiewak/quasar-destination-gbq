import scala.collection.Seq

homepage in ThisBuild := Some(url("https://github.com/slamdata/quasar-destination-gbq"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/slamdata/quasar-destination-gbq"),
  "scm:git@github.com:slamdata/quasar-destination-gbq.git"))

val ArgonautVersion = "6.2.3"
val SpecsVersion = "4.7.0"

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
      "io.argonaut"  %% "argonaut" % ArgonautVersion),

    libraryDependencies ++= Seq(),

    publishAsOSSProject := true)
  .enablePlugins(AutomateHeaderPlugin, QuasarPlugin)

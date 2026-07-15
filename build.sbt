import org.goldenport.cozy.CozyPlugin.autoImport._
import sbt.Keys.*

ThisBuild / organization := "org.textus"
ThisBuild / version := "0.2.1-SNAPSHOT"

val cncfVersion = "0.5.1-SNAPSHOT"

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    name := "textus-ai-runtime",
    scalaVersion := "3.3.8",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    cozyGeneratorBackend := "cozy",
    cozyDelegateProjectDir := Some(file("/Users/asami/src/dev2025/cozy")),
    resolvers ++= Seq(
      Resolver.defaultLocal,
      Resolver.mavenLocal,
      "SimpleModeling.org" at "https://www.simplemodeling.org/repository/maven"
    ),
    libraryDependencies ++= Seq(
      "org.goldenport" % "goldenport-cncf_3" % cncfVersion,
      "org.simplemodeling" % "simplemodeling-model_3" % "0.1.8-SNAPSHOT",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    dependencyOverrides ++= Seq(
      "org.simplemodeling" % "simplemodeling-model_3" % "0.1.8-SNAPSHOT"
    ),
    cozyManifestMetadata ++= Map(
      "component" -> "textus-ai-runtime",
      "version" -> version.value,
      "boundedContext" -> "platform",
      "domain" -> "ai-runtime"
    ),
    publish := {
      val _ = cozyPublishCar.value
      ()
    },
    publishLocal := {
      val _ = cozyPublishLocalCar.value
      ()
    },
    Test / fork := false
  )

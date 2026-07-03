import org.goldenport.cozy.CozyPlugin.autoImport._
import sbt.Keys.*

ThisBuild / organization := "org.textus"
ThisBuild / version := "0.1.1-SNAPSHOT"

val cncfVersion = "0.4.13-SNAPSHOT"

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    name := "textus-ai-runtime",
    scalaVersion := "3.3.7",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    cozyGeneratorBackend := "cozy",
    cozyDelegateProjectDir := Some(file("/Users/asami/src/dev2025/cozy")),
    resolvers ++= Seq(
      Resolver.defaultLocal,
      Resolver.mavenLocal,
      "SimpleModeling.org" at "https://www.simplemodeling.org/repository/maven"
    ),
    libraryDependencies ++= Seq(
      "org.goldenport" %% "goldenport-cncf" % cncfVersion,
      "org.simplemodeling" %% "simplemodeling-model" % "0.1.7",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    dependencyOverrides ++= Seq(
      "org.simplemodeling" %% "simplemodeling-model" % "0.1.7"
    ),
    cozyManifestMetadata ++= Map(
      "component" -> "textus-ai-runtime",
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

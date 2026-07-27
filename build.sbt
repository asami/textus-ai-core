import org.goldenport.cozy.CozyPlugin.autoImport._
import sbt.Keys.*

lazy val root = project
  .in(file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := TextusAiProjectYamlBuild.requiredValue(cozyProjectMetadata.value, "project.organization"),
    name := TextusAiProjectYamlBuild.requiredValue(cozyProjectMetadata.value, "project.name"),
    version := TextusAiProjectYamlBuild.requiredValue(cozyProjectMetadata.value, "project.component.version"),
    scalaVersion := TextusAiProjectYamlBuild.requiredValue(cozyProjectMetadata.value, "build.scalaVersion"),
    useCoursier := false,

    resolvers += Resolver.defaultLocal,
    resolvers += Resolver.file("Local Ivy", file(Path.userHome.absolutePath + "/.ivy2/local"))(Resolver.ivyStylePatterns),
    resolvers += "Local Maven Repository" at ("file://" + Path.userHome.absolutePath + "/.m2/repository"),
    resolvers += "SimpleModeling.org" at "https://www.simplemodeling.org/repository/maven",
    libraryDependencies ++= TextusAiProjectYamlBuild.dependencies(cozyProjectMetadata.value),

    cozyGeneratorBackend := "cozy",
    cozyDelegateProjectDir := None,
    cozyDelegateCommand := Seq("cozy"),
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    Test / fork := false,
    cozyManifestMetadata ++=
      cozyProjectMetadata.value.mapUnder("packaging.car.manifest_metadata") ++
        Map("component" -> TextusAiProjectYamlBuild.requiredValue(cozyProjectMetadata.value, "project.component.name"))
  )

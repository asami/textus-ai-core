import org.goldenport.cozy.CozyPlugin.autoImport._
import org.goldenport.cozy.CozyProjectIdentityEvidence
import sbt.Keys.*

lazy val projectIdentityEvidence = settingKey[CozyProjectIdentityEvidence]("Admitted project.yaml component identity evidence")

lazy val root = project
  .in(file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    projectIdentityEvidence := TextusAiProjectYamlBuild.admitted(cozyProjectMetadata.value, scalaBinaryVersion.value),
    organization := TextusAiProjectYamlBuild.organization(projectIdentityEvidence.value),
    moduleName := TextusAiProjectYamlBuild.moduleName(projectIdentityEvidence.value),
    name := moduleName.value,
    version := TextusAiProjectYamlBuild.version(projectIdentityEvidence.value),
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
    cozyCarName := TextusAiProjectYamlBuild.carBaseName(projectIdentityEvidence.value),
    cozyManifestMetadata ++=
      cozyProjectMetadata.value.mapUnder("packaging.car.manifest_metadata") ++
        TextusAiProjectYamlBuild.manifestMetadata(projectIdentityEvidence.value)
  )

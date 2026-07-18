import org.goldenport.cozy.CozyProjectConfig
import sbt.*

object TextusAiProjectYamlBuild {
  def requiredValue(config: CozyProjectConfig, path: String): String =
    config.value(path).getOrElse(sys.error(s"$path is required in project.yaml"))

  def dependencies(config: CozyProjectConfig): Seq[ModuleID] =
    _dependencies(config, "compile", None) ++
      _dependencies(config, "test", Some(Test))

  def dependencyVersion(config: CozyProjectConfig, organization: String, artifact: String): String =
    config.list("build.dependencies.compile")
      .map(_module)
      .find(x => x.organization == organization && x.name == artifact)
      .map(_.revision)
      .getOrElse(sys.error(s"Compile dependency is required in project.yaml: $organization:$artifact"))

  private def _dependencies(
    config: CozyProjectConfig,
    scope: String,
    configuration: Option[Configuration]
  ): Seq[ModuleID] =
    config.list(s"build.dependencies.$scope").map { coordinate =>
      val module = _module(coordinate)
      configuration.fold(module)(module % _)
    }

  private def _module(coordinate: String): ModuleID =
    coordinate.split(":", -1).toList match {
      case organization :: "" :: artifact :: version :: Nil =>
        organization %% artifact % version
      case organization :: artifact :: version :: Nil =>
        organization % artifact % version
      case _ =>
        sys.error(
          s"Invalid project.yaml dependency '$coordinate'; expected organization:artifact:version or organization::artifact:version"
        )
    }
}

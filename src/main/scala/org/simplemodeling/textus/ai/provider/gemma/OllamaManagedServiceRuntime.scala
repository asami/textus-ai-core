package org.simplemodeling.textus.ai.provider.gemma

import java.net.URI
import scala.util.Try

import io.circe.Json
import org.goldenport.Consequence
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.servicecontainer.*
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.configuration.ResolvedConfiguration
import org.simplemodeling.textus.ai.runtime.{AiProfileConfig, HttpSupport}

/*
 * @since   Jul. 20, 2026
 * @version Jul. 22, 2026
 * @author  ASAMI, Tomoharu
 */
final case class OllamaManagedServiceConfig(
  image: String,
  volumeName: String,
  models: Vector[String],
  startupTimeoutMillis: Long,
  modelInstallTimeoutSeconds: Long
) {
  def serviceIdC: Consequence[ServiceContainerId] =
    ServiceContainerId.parseC("ollama")

  def definitionC: Consequence[ServiceContainerDefinition.RuntimeOwned] =
    for {
      serviceid <- serviceIdC
      ownerid <- ServiceContainerOwnerId.parseC("textus-ai-runtime")
      imageid <- ServiceContainerImage.parseC(image)
      portname <- ServiceContainerPortName.parseC("ollama-api")
      port <- ServiceContainerPort.createC(portname, 11434)
      probe <- ServiceContainerReadinessProbe.httpC(portname, "/api/tags")
      readiness <- ServiceContainerReadinessPolicy.createC(probe, startupTimeoutMillis, 250L)
      volumename <- ServiceContainerVolumeName.parseC(volumeName)
      volumetarget <- ServiceContainerMountPath.parseC("/root/.ollama")
      volume <- ServiceContainerVolume.createC(volumename, volumetarget)
      persistence <- ServiceContainerPersistence.namedVolumesC(Vector(volume))
      definition <- ServiceContainerDefinition.runtimeOwnedC(
        serviceid,
        ServiceContainerOwner(ServiceContainerOwnerKind.ComponentRuntime, ownerid),
        imageid,
        Vector(port),
        readiness,
        persistence,
        ServiceContainerReusePolicy.CreateOrReuse,
        ServiceContainerCleanupPolicy.Stop
      )
    } yield definition
}

object OllamaManagedServiceConfig {
  private val _default_image = "ollama/ollama:latest"
  private val _default_volume = "textus-ai-ollama"

  def fromConfiguration(
    configuration: Option[ResolvedConfiguration],
    profiles: AiProfileConfig
  ): Option[OllamaManagedServiceConfig] =
    if (!configuration.flatMap(GemmaConfig.fromConfiguration).exists { value =>
      value.runtime == "managed-docker" && value.configurationError.isEmpty
    })
      None
    else
      profiles.gemmaModelsC.toOption.flatten.map { models =>
        val startuptimeout = _positive_long(
          configuration,
          "service.startup-timeout-seconds",
          "service.startupTimeoutSeconds",
          "docker.startup-timeout-seconds",
          "docker.startupTimeoutSeconds"
        ).getOrElse(900L)
        val installtimeout = _positive_long(
          configuration,
          "service.model-install-timeout-seconds",
          "service.modelInstallTimeoutSeconds"
        ).getOrElse(startuptimeout)
        OllamaManagedServiceConfig(
          image = _string(configuration, "service.image", "docker.image").getOrElse(_default_image),
          volumeName = _string(configuration, "service.volume-name", "service.volumeName", "docker.volume-name", "docker.volumeName")
            .getOrElse(_default_volume),
          models = models,
          startupTimeoutMillis = startuptimeout * 1000L,
          modelInstallTimeoutSeconds = installtimeout
        )
      }

  private def _positive_long(
    configuration: Option[ResolvedConfiguration],
    leaves: String*
  ): Option[Long] =
    _string(configuration, leaves*).flatMap(_.toLongOption).filter(_ > 0L)

  private def _string(
    configuration: Option[ResolvedConfiguration],
    leaves: String*
  ): Option[String] =
    configuration.toVector.iterator.flatMap { value =>
      leaves.iterator.flatMap { leaf =>
        Vector(
          s"textus.ai.gemma.$leaf",
          s"textus.runtime.ai.gemma.$leaf",
          s"cncf.ai.gemma.$leaf",
          s"cncf.runtime.ai.gemma.$leaf"
        ).iterator
      }.flatMap(key => Try(RuntimeConfig.getString(value, key)).toOption.flatten)
    }.map(_.trim).find(_.nonEmpty)
}

final class OllamaManagedServiceBootstrap(
  subsystem: Subsystem,
  config: OllamaManagedServiceConfig
) {
  private var _endpoint: Option[URI] = None

  def ensureC(using context: ExecutionContext): Consequence[URI] = synchronized {
    _endpoint.map(Consequence.success).getOrElse {
      for {
        serviceid <- config.serviceIdC
        runtime <- subsystem.serviceContainerRuntimeC(serviceid)
        definition <- config.definitionC
        resolution <- runtime.resolveC(definition)
        endpoint = URI.create(resolution.endpoint.print)
        _ <- _install_models_c(endpoint)
      } yield {
        _endpoint = Some(endpoint)
        endpoint
      }
    }
  }

  private def _install_models_c(
    endpoint: URI
  )(using ExecutionContext): Consequence[Unit] =
    config.models.foldLeft(Consequence.unit) { (z, model) =>
      z.flatMap { _ =>
        HttpSupport.post(
          endpoint,
          "/api/pull",
          Json.obj(
            "model" -> Json.fromString(model),
            "stream" -> Json.False
          ),
          config.modelInstallTimeoutSeconds
        ).map(_ => ())
      }
    }
}

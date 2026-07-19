package org.simplemodeling.textus.ai.provider.gemma

import java.net.URI
import scala.util.Try
import org.goldenport.Consequence
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.processexecution.*
import org.goldenport.cncf.unitofwork.UnitOfWorkOp
import org.goldenport.configuration.ResolvedConfiguration
import org.simplemodeling.textus.ai.runtime.AiProfileConfig

/**
 * Trusted local Ollama provisioning for the built-in Gemma runtime profile.
 * An explicit Gemma endpoint always selects an externally managed Ollama
 * service and prevents this capability from being installed.
 */
final case class OllamaDockerConfig(
  executable: String,
  image: String,
  containerName: String,
  volumeName: String,
  host: String,
  port: Int,
  models: Vector[String],
  executionLimits: ProcessExecutionLimits
) {
  def endpoint: URI = URI.create(s"http://$host:$port")
}

object OllamaDockerConfig {
  private val _default_executable = "docker"
  private val _default_image = "ollama/ollama:latest"
  private val _default_container = "textus-ai-ollama"
  private val _default_volume = "textus-ai-ollama"
  private val _default_host = "127.0.0.1"
  private val _default_port = 11434

  def fromConfiguration(
    configuration: Option[ResolvedConfiguration],
    profiles: AiProfileConfig
  ): Option[OllamaDockerConfig] =
    if (configuration.flatMap(GemmaConfig.endpointFromConfiguration).nonEmpty)
      None
    else
      profiles.gemmaModelsC.toOption.flatten.map { models =>
        val config = configuration
        val executable = _string(config, "docker.executable").getOrElse(_default_executable)
        val image = _string(config, "docker.image").getOrElse(_default_image)
        val container = _string(config, "docker.container-name", "docker.containerName").getOrElse(_default_container)
        val volume = _string(config, "docker.volume-name", "docker.volumeName").getOrElse(_default_volume)
        val host = _string(config, "docker.host").getOrElse(_default_host)
        val port = _string(config, "docker.port").flatMap(_.toIntOption).filter(_ > 0).getOrElse(_default_port)
        val timeout = _string(config, "docker.startup-timeout-seconds", "docker.startupTimeoutSeconds")
          .flatMap(_.toLongOption)
          .filter(_ > 0L)
          .getOrElse(900L)
        OllamaDockerConfig(
          executable = executable,
          image = image,
          containerName = container,
          volumeName = volume,
          host = host,
          port = port,
          models = models,
          executionLimits = ProcessExecutionLimits(
            launchTimeoutMillis = Some(5000L),
            executionTimeoutMillis = Some(timeout * 1000L),
            terminationGraceMillis = Some(5000L),
            stdinBytes = Some(1L),
            stdoutBytes = Some(1048576L),
            stderrBytes = Some(65536L),
            argumentCount = Some(32L),
            argumentBytes = Some(4096L),
            artifactCount = Some(1L),
            artifactBytes = Some(1L),
            workAreaBytes = Some(1L)
          )
        )
      }

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

object OllamaDockerExecutionBinding {
  private val _inspect = "ollama-docker-inspect"
  private val _start = "ollama-docker-start"
  private val _run = "ollama-docker-run"

  def admissionC(config: OllamaDockerConfig): Consequence[ProcessExecutionAdmission] =
    for {
      binding <- definitionsAndGrantsC(config)
      (definitions, grants) = binding
      policy <- ProcessExecutionPolicy.createC(definitions)
      admission <- ProcessExecutionAdmission.createC(policy, grants)
    } yield admission

  private[ai] def definitionsAndGrantsC(
    config: OllamaDockerConfig
  ): Consequence[(Vector[ProcessProgramDefinition], Vector[ProcessExecutionGrant])] =
    _definitions_c(config).flatMap { definitions =>
      _grants_c(definitions).map(grants => definitions -> grants)
    }

  def inspectCapability: String = _inspect
  def startCapability: String = _start
  def runCapability: String = _run
  def pullCapability(index: Int): String = s"ollama-docker-pull-$index"

  private def _definitions_c(
    config: OllamaDockerConfig
  ): Consequence[Vector[ProcessProgramDefinition]] =
    val common = Vector(
      _definition_c(
        _inspect,
        Vector("container", "inspect", "--format", "{{.State.Running}}", config.containerName),
        config
      ),
      _definition_c(_start, Vector("start", config.containerName), config),
      _definition_c(
        _run,
        Vector(
          "run",
          "--detach",
          "--name",
          config.containerName,
          "--publish",
          s"${config.host}:${config.port}:11434",
          "--volume",
          s"${config.volumeName}:/root/.ollama",
          config.image
        ),
        config
      )
    )
    common.foldLeft(Consequence.success(Vector.empty[ProcessProgramDefinition])) { (z, current) =>
      z.flatMap(values => current.map(values :+ _))
    }.flatMap { definitions =>
      config.models.zipWithIndex.foldLeft(Consequence.success(definitions)) { case (z, (model, index)) =>
        z.flatMap { values =>
          _definition_c(
            pullCapability(index),
            Vector("exec", config.containerName, "ollama", "pull", model),
            config
          ).map(values :+ _)
        }
      }
    }

  private def _definition_c(
    capabilityname: String,
    fixedarguments: Vector[String],
    config: OllamaDockerConfig
  ): Consequence[ProcessProgramDefinition] =
    ProcessCapabilityId.parseC(capabilityname).flatMap { capability =>
      ProcessProgramDefinition.fromRuntimeC(
        capability = capability,
        safeprogramidentity = capabilityname,
        executablelocation = config.executable,
        fixedarguments = fixedarguments,
        argumentpolicy = ProcessArgumentPolicy(fixedarguments, Set.empty, Set(Vector.empty)),
        maximumlimits = config.executionLimits,
        allowedartifacts = Set.empty,
        allowedinputfiles = Set.empty,
        environment = Map.empty
      )
    }

  private def _grants_c(
    definitions: Vector[ProcessProgramDefinition]
  ): Consequence[Vector[ProcessExecutionGrant]] =
    definitions.foldLeft(Consequence.success(Vector.empty[ProcessExecutionGrant])) { (z, definition) =>
      z.flatMap { grants =>
        ProcessCapabilityId.parseC(definition.safeProgramIdentity).map { capability =>
          grants :+ ProcessExecutionGrant(capability)
        }
      }
    }
}

final class OllamaDockerBootstrap(config: OllamaDockerConfig) {
  private var _ready = false

  def ensureC(using context: ExecutionContext): Consequence[Unit] = synchronized {
    if (_ready)
      Consequence.unit
    else
      for {
        running <- _is_running_c
        _ <- if (running) Consequence.unit else _start_or_run_c
        _ <- _pull_models_c
      } yield {
        _ready = true
      }
  }

  private def _is_running_c(using ExecutionContext): Consequence[Boolean] =
    _execute_c(OllamaDockerExecutionBinding.inspectCapability).map { result =>
      result.termination match {
        case ProcessExecutionTermination.Exited(0) =>
          new String(result.stdout.content.toArray, java.nio.charset.StandardCharsets.UTF_8).trim.equalsIgnoreCase("true")
        case _ => false
      }
    }

  private def _start_or_run_c(using ExecutionContext): Consequence[Unit] =
    _execute_c(OllamaDockerExecutionBinding.startCapability).flatMap { result =>
      result.termination match {
        case ProcessExecutionTermination.Exited(0) => Consequence.unit
        case _ => _execute_c(OllamaDockerExecutionBinding.runCapability).flatMap(_require_success_c)
      }
    }

  private def _pull_models_c(using ExecutionContext): Consequence[Unit] =
    config.models.indices.foldLeft(Consequence.unit) { (z, index) =>
      z.flatMap { _ =>
        _execute_c(OllamaDockerExecutionBinding.pullCapability(index)).flatMap(_require_success_c)
      }
    }

  private def _execute_c(
    capabilityname: String
  )(using context: ExecutionContext): Consequence[ProcessExecutionResult] =
    for {
      capability <- ProcessCapabilityId.parseC(capabilityname)
      execution <- ProcessExecutionAdmission.resolveC(
        context.cncfCore.scope,
        ProcessExecutionRequest(capability)
      )
      result <- context.runtime.unitOfWorkInterpreter(UnitOfWorkOp.ProcessExec(execution))
    } yield result

  private def _require_success_c(
    result: ProcessExecutionResult
  ): Consequence[Unit] =
    result.termination match {
      case ProcessExecutionTermination.Exited(0) => Consequence.unit
      case _ => Consequence.serviceUnavailable("Textus AI could not prepare the local Ollama runtime")
    }
}

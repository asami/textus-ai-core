package org.simplemodeling.textus.ai.provider.claude

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.util.Try
import io.circe.parser.parse
import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ExtensionPoint, ServiceContract, VariationSelection}
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.processexecution.*
import org.goldenport.cncf.unitofwork.UnitOfWorkOp
import org.goldenport.configuration.ResolvedConfiguration
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.ai.ai.*
import org.simplemodeling.textus.ai.runtime.{AiRequestProperties, ChatService, GenerateService}

final case class ClaudeCodeExecutionProfile(name: String, model: String) {
  def capability: String = s"claude-code-profile-$name"
}

final case class ClaudeCodeRuntimeConfig(
  executable: String,
  executionProfiles: Map[String, ClaudeCodeExecutionProfile] = Map.empty,
  executionLimits: ProcessExecutionLimits = ClaudeCodeRuntimeConfig.defaultExecutionLimits
)

object ClaudeCodeRuntimeConfig {
  val defaultExecutionLimits: ProcessExecutionLimits = ProcessExecutionLimits(
    launchTimeoutMillis = Some(5000L),
    executionTimeoutMillis = Some(120000L),
    terminationGraceMillis = Some(5000L),
    stdinBytes = Some(131072L),
    stdoutBytes = Some(1048576L),
    stderrBytes = Some(65536L),
    argumentCount = Some(1L),
    argumentBytes = Some(16L),
    artifactCount = Some(1L),
    artifactBytes = Some(1L),
    workAreaBytes = Some(1L)
  )
}

object ClaudeCodeConfig {
  def fromConfiguration(
    configuration: ResolvedConfiguration,
    executions: Map[String, ClaudeCodeExecutionProfile]
  ): Option[ClaudeCodeRuntimeConfig] =
    if (!_enabled(configuration))
      None
    else
      _string(configuration, "executable", "executable-path")
        .flatMap(_absolute_executable)
        .map(path => ClaudeCodeRuntimeConfig(path, executions))

  private def _enabled(configuration: ResolvedConfiguration): Boolean =
    _string(configuration, "enabled").exists { value =>
      Set("true", "yes", "on", "1").contains(value.toLowerCase(java.util.Locale.ROOT))
    }

  private def _string(configuration: ResolvedConfiguration, leaves: String*): Option[String] =
    leaves.iterator.flatMap { leaf =>
      Vector(
        s"textus.ai.claude.$leaf",
        s"textus.ai.claude-code.$leaf",
        s"textus.runtime.ai.claude.$leaf",
        s"cncf.ai.claude.$leaf"
      ).iterator
    }.flatMap(key => Try(RuntimeConfig.getString(configuration, key)).toOption.flatten)
      .map(_.trim).find(_.nonEmpty)

  private def _absolute_executable(value: String): Option[String] =
    Try(Path.of(value)).toOption.filter(_.isAbsolute).map(_.normalize.toString).filter(_.nonEmpty)
}

final class ClaudeCodeGenerateService(config: ClaudeCodeRuntimeConfig, context: ExecutionContext) extends GenerateService {
  override def generate(req: GenerateRequest): Consequence[GenerateResponse] =
    for {
      _ <- AiRequestProperties.requireNoUnsupportedTools("claude", req.properties)
      _ <- AiRequestProperties.requireNoRecordSchema("claude", req.recordSchema)
      _ <- AiRequestProperties.requireNoModelOverride("claude", req.properties)
      _ <- _require_no_output_token_limit(req.maxTokens)
      profile <- _profile_c(req.properties)
      capability <- ProcessCapabilityId.parseC(profile.capability)
      result <- _execute_c(ProcessExecutionRequest(
        capability = capability,
        arguments = Vector("-"),
        input = ProcessExecutionInput.Bytes(req.prompt.getBytes(StandardCharsets.UTF_8).toVector)
      ))
      response <- _response_c(result, profile)
    } yield response

  private def _profile_c(properties: Vector[org.goldenport.protocol.Property]): Consequence[ClaudeCodeExecutionProfile] =
    AiRequestProperties.claudeCodeExecutionProfile(properties) match {
      case Some(name) => config.executionProfiles.get(name).map(Consequence.success).getOrElse(
        Consequence.configurationInvalid(s"Claude Code execution profile is not admitted: $name")
      )
      case None => Consequence.configurationInvalid("Claude Code requires an admitted runtime-profile binding")
    }

  private def _require_no_output_token_limit(maxTokens: Option[Int]): Consequence[Unit] =
    maxTokens match {
      case Some(_) => Consequence.configurationInvalid("AI maximum output tokens are not supported by provider 'claude'")
      case None => Consequence.unit
    }

  private def _execute_c(request: ProcessExecutionRequest): Consequence[ProcessExecutionResult] =
    given ExecutionContext = context
    ProcessExecutionAdmission.resolveC(context.scope, request).flatMap { execution =>
      context.unitOfWorkInterpreter(UnitOfWorkOp.ProcessExec(execution))
    }

  private def _response_c(
    result: ProcessExecutionResult,
    profile: ClaudeCodeExecutionProfile
  ): Consequence[GenerateResponse] =
    result.termination match {
      case ProcessExecutionTermination.Exited(0) =>
        val raw = new String(result.stdout.content.toArray, StandardCharsets.UTF_8).trim
        parse(raw).toOption.flatMap(_.hcursor.get[String]("result").toOption).map(_.trim).filter(_.nonEmpty) match {
          case Some(text) => Consequence.success(GenerateResponse(
            text,
            Some(profile.model),
            _metadata(raw) ++ Map("claude.finish_reason" -> "exited", "claude.profile" -> profile.name)
          ))
          case None => Consequence.valueInvalid("Claude Code returned no JSON result")
        }
      case ProcessExecutionTermination.Exited(_) => Consequence.serviceUnavailable("Claude Code exited unsuccessfully")
      case ProcessExecutionTermination.LaunchFailed => Consequence.serviceUnavailable("Claude Code is unavailable")
      case ProcessExecutionTermination.TimedOut => Consequence.serviceUnavailable("Claude Code timed out")
      case ProcessExecutionTermination.Cancelled => Consequence.operationIllegal("claude", "Claude Code execution was cancelled")
      case ProcessExecutionTermination.OutputLimitExceeded(_) => Consequence.operationIllegal("claude", "Claude Code output exceeded the configured limit")
      case ProcessExecutionTermination.ArtifactLimitExceeded => Consequence.operationIllegal("claude", "Claude Code artifacts exceeded the configured limit")
    }

  private def _metadata(raw: String): Map[String, String] =
    parse(raw).toOption.map { json =>
      Vector(
        "claude.session_id" -> json.hcursor.get[String]("session_id").toOption,
        "claude.duration_ms" -> json.hcursor.get[Long]("duration_ms").toOption.map(_.toString),
        "claude.num_turns" -> json.hcursor.get[Long]("num_turns").toOption.map(_.toString)
      ).collect { case (key, Some(value)) => key -> value }.toMap
    }.getOrElse(Map.empty)
}

final class ClaudeCodeChatService(config: ClaudeCodeRuntimeConfig, context: ExecutionContext) extends ChatService {
  private val _generate = new ClaudeCodeGenerateService(config, context)
  override def chat(req: ChatRequest): Consequence[ChatResponse] =
    _generate.generate(GenerateRequest(
      req.messages.map(message => s"${message.role.toString.toLowerCase}: ${message.content}").mkString("\n"),
      req.temperature,
      req.maxTokens,
      req.properties
    )).map(response => ChatResponse(Message(MessageRole.Assistant, response.text), response.model, response.metadata))
}

final class ClaudeCodeGenerateExtensionPoint(config: ClaudeCodeRuntimeConfig) extends ExtensionPoint[GenerateService] {
  private var _runtimecomponent: Option[Component] = None
  private[ai] def _with_runtime_component(component: Component): ClaudeCodeGenerateExtensionPoint = { _runtimecomponent = Some(component); this }
  override def supports(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "generate-service" && variation.provider.exists(_is_claude) && variation.mode.contains("local") && variation.engine.contains("claude-code")
  override def provide(contract: ServiceContract[GenerateService], variation: VariationSelection)(using caller: ExecutionContext): Consequence[GenerateService] =
    Consequence.success(new ClaudeCodeGenerateService(config, _context(_runtimecomponent)))
}

final class ClaudeCodeChatExtensionPoint(config: ClaudeCodeRuntimeConfig) extends ExtensionPoint[ChatService] {
  private var _runtimecomponent: Option[Component] = None
  private[ai] def _with_runtime_component(component: Component): ClaudeCodeChatExtensionPoint = { _runtimecomponent = Some(component); this }
  override def supports(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "chat-service" && variation.provider.exists(_is_claude) && variation.mode.contains("local") && variation.engine.contains("claude-code")
  override def provide(contract: ServiceContract[ChatService], variation: VariationSelection)(using caller: ExecutionContext): Consequence[ChatService] =
    Consequence.success(new ClaudeCodeChatService(config, _context(_runtimecomponent)))
}

private def _context(component: Option[Component])(using caller: ExecutionContext): ExecutionContext =
  component.map(_.logic.executionContext()).getOrElse(caller)

private def _is_claude(value: String): Boolean =
  Set("claude", "claude-code").contains(value.trim.toLowerCase(java.util.Locale.ROOT))

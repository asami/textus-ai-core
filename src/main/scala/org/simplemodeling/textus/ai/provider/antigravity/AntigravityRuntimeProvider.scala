package org.simplemodeling.textus.ai.provider.antigravity

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.util.Try
import io.circe.Json
import io.circe.parser.parse
import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ExtensionPoint, ServiceContract, VariationSelection}
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.processexecution.*
import org.goldenport.cncf.spi.ai.runner.AiTool
import org.goldenport.cncf.unitofwork.UnitOfWorkOp
import org.goldenport.configuration.ResolvedConfiguration
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.ai.ai.*
import org.simplemodeling.textus.ai.runtime.{AiRequestProperties, ChatService, GenerateService}

final case class AntigravityExecutionProfile(
  name: String,
  model: String,
  tools: Set[AiTool] = Set.empty
) {
  def plainCapability: String = s"antigravity-cli-profile-$name"
  def webCapability: String = s"${plainCapability}-web"

  def supports(requested: Set[AiTool]): Boolean =
    requested.subsetOf(tools) && requested.forall(AntigravityExecutionProfile.supportedTools.contains)
}

object AntigravityExecutionProfile {
  val supportedTools: Set[AiTool] = Set(AiTool.WebSearch, AiTool.UrlContext)
}

final case class AntigravityRuntimeConfig(
  executable: String,
  home: String,
  executionProfiles: Map[String, AntigravityExecutionProfile] = Map.empty,
  executionLimits: ProcessExecutionLimits = AntigravityRuntimeConfig.defaultExecutionLimits
)

object AntigravityRuntimeConfig {
  val defaultExecutionLimits: ProcessExecutionLimits = ProcessExecutionLimits(
    launchTimeoutMillis = Some(5000L),
    executionTimeoutMillis = Some(120000L),
    terminationGraceMillis = Some(5000L),
    stdinBytes = Some(131072L),
    stdoutBytes = Some(1048576L),
    stderrBytes = Some(65536L),
    argumentCount = Some(1L),
    argumentBytes = Some(131072L),
    artifactCount = Some(1L),
    artifactBytes = Some(1L),
    workAreaBytes = Some(1L)
  )
}

object AntigravityConfig {
  def fromConfigurationC(
    configuration: ResolvedConfiguration,
    executions: Map[String, AntigravityExecutionProfile]
  ): Consequence[Option[AntigravityRuntimeConfig]] =
    _enabled_c(configuration).flatMap {
      case false => Consequence.success(None)
      case true =>
        for {
          executable <- _string(configuration, "executable", "executable-path")
            .map(Consequence.success)
            .getOrElse(Consequence.configurationInvalid(
              "Enabled Antigravity CLI configuration requires an absolute executable path"
            ))
          home <- _string(configuration, "home", "home-directory")
            .map(_absolute_home_c)
            .getOrElse(Consequence.configurationInvalid(
              "Enabled Antigravity CLI configuration requires an absolute home directory path"
            ))
          path <- _absolute_executable_c(executable)
        } yield Some(AntigravityRuntimeConfig(path, home, executions))
    }

  private def _enabled_c(configuration: ResolvedConfiguration): Consequence[Boolean] =
    _string(configuration, "enabled") match {
      case None => Consequence.success(false)
      case Some(value) => value.toLowerCase(java.util.Locale.ROOT) match {
        case "true" | "yes" | "on" | "1" => Consequence.success(true)
        case "false" | "no" | "off" | "0" => Consequence.success(false)
        case _ => Consequence.configurationInvalid(
          "Antigravity CLI enabled setting must be a boolean value"
        )
      }
    }

  private def _string(configuration: ResolvedConfiguration, leaves: String*): Option[String] =
    leaves.iterator.flatMap { leaf =>
      Vector(
        s"textus.ai.antigravity-cli.$leaf",
        s"textus.runtime.ai.antigravity-cli.$leaf",
        s"cncf.ai.antigravity-cli.$leaf"
      ).iterator
    }.flatMap(key => Try(RuntimeConfig.getString(configuration, key)).toOption.flatten)
      .map(_.trim).find(_.nonEmpty)

  private def _absolute_executable_c(value: String): Consequence[String] =
    Try(Path.of(value)).toOption.filter(_.isAbsolute).map(_.normalize.toString).filter(_.nonEmpty)
      .map(Consequence.success)
      .getOrElse(Consequence.configurationInvalid(
        "Enabled Antigravity CLI configuration requires an absolute executable path"
      ))

  private def _absolute_home_c(value: String): Consequence[String] =
    Try(Path.of(value)).toOption.filter(_.isAbsolute).map(_.normalize.toString).filter(_.nonEmpty)
      .map(Consequence.success)
      .getOrElse(Consequence.configurationInvalid(
        "Enabled Antigravity CLI configuration requires an absolute home directory path"
      ))
}

final class AntigravityGenerateService(config: AntigravityRuntimeConfig, context: ExecutionContext) extends GenerateService {
  override def generate(req: GenerateRequest): Consequence[GenerateResponse] =
    for {
      _ <- AiRequestProperties.requireNoRecordSchema("antigravity-cli", req.recordSchema)
      _ <- AiRequestProperties.requireNoModelOverride("antigravity-cli", req.properties)
      _ <- _require_no_output_token_limit(req.maxTokens)
      profile <- _profile_c(req.properties)
      tools <- AiRequestProperties.validateTools(req.properties)
      _ <- _validate_tools_c(profile, tools.toSet)
      capability <- ProcessCapabilityId.parseC(if (tools.nonEmpty) profile.webCapability else profile.plainCapability)
      result <- _execute_c(ProcessExecutionRequest(
        capability = capability,
        arguments = Vector(req.prompt),
        requestedLimits = _requested_limits(req.properties)
      ))
      response <- _response_c(result, profile, tools.toSet)
    } yield response

  private def _profile_c(
    properties: Vector[org.goldenport.protocol.Property]
  ): Consequence[AntigravityExecutionProfile] =
    AiRequestProperties.antigravityExecutionProfile(properties) match {
      case Some(name) => config.executionProfiles.get(name).map(Consequence.success).getOrElse(
        Consequence.configurationInvalid(s"Antigravity CLI execution profile is not admitted: $name")
      )
      case None => Consequence.configurationInvalid("Antigravity CLI requires an admitted runtime-profile binding")
    }

  private def _validate_tools_c(
    profile: AntigravityExecutionProfile,
    requested: Set[AiTool]
  ): Consequence[Unit] =
    if (profile.supports(requested))
      Consequence.unit
    else
      Consequence.configurationInvalid(
        s"Antigravity CLI execution profile '${profile.name}' does not support requested tools: ${requested.toVector.map(_.id).sorted.mkString(",")}"
      )

  private def _require_no_output_token_limit(maxTokens: Option[Int]): Consequence[Unit] =
    maxTokens match {
      case Some(_) => Consequence.configurationInvalid("AI maximum output tokens are not supported by provider 'antigravity-cli'")
      case None => Consequence.unit
    }

  private def _requested_limits(
    properties: Vector[org.goldenport.protocol.Property]
  ): ProcessExecutionLimits =
    val clitimeout = AiRequestProperties.string(properties, Vector(
      "ai.antigravity-cli.timeout-millis",
      "textus.ai.antigravity-cli.timeout-millis",
      "cncf.ai.antigravity-cli.timeout-millis"
    )).flatMap(_.toLongOption).filter(_ > 0L)
    val purposetimeout = AiRequestProperties.purposeTimeoutSeconds(properties)
      .flatMap(value => Try(Math.multiplyExact(value, 1000L)).toOption)
    ProcessExecutionLimits(executionTimeoutMillis = clitimeout.orElse(purposetimeout))

  private def _execute_c(request: ProcessExecutionRequest): Consequence[ProcessExecutionResult] =
    given ExecutionContext = context
    ProcessExecutionAdmission.resolveC(context.scope, request).flatMap { execution =>
      context.unitOfWorkInterpreter(UnitOfWorkOp.ProcessExec(execution))
    }

  private def _response_c(
    result: ProcessExecutionResult,
    profile: AntigravityExecutionProfile,
    tools: Set[AiTool]
  ): Consequence[GenerateResponse] =
    result.termination match {
      case ProcessExecutionTermination.Exited(0) =>
        val raw = new String(result.stdout.content.toArray, StandardCharsets.UTF_8).trim
        parse(raw).toOption.flatMap { json =>
          _result_text(json).map { response =>
            GenerateResponse(
              response,
              _actual_model(json).orElse(Option.when(!profile.model.equalsIgnoreCase("auto"))(profile.model)),
              _metadata(json) ++ Map(
                "google.finish_reason" -> "exited",
                "google.antigravity_profile" -> profile.name,
                "google.antigravity_enabled_tools" -> tools.toVector.map(_.id).sorted.mkString(",")
              ).filter(_._2.nonEmpty)
            )
          }
        } match {
          case Some(response) => Consequence.success(response)
          case None => Consequence.valueInvalid("Antigravity CLI returned no JSON result")
        }
      case ProcessExecutionTermination.Exited(_) => Consequence.serviceUnavailable("Antigravity CLI exited unsuccessfully")
      case ProcessExecutionTermination.LaunchFailed => Consequence.serviceUnavailable("Antigravity CLI is unavailable")
      case ProcessExecutionTermination.TimedOut => Consequence.serviceUnavailable("Antigravity CLI timed out")
      case ProcessExecutionTermination.Cancelled => Consequence.operationIllegal("antigravity-cli", "Antigravity CLI execution was cancelled")
      case ProcessExecutionTermination.OutputLimitExceeded(_) => Consequence.operationIllegal("antigravity-cli", "Antigravity CLI output exceeded the configured limit")
      case ProcessExecutionTermination.ArtifactLimitExceeded => Consequence.operationIllegal("antigravity-cli", "Antigravity CLI artifacts exceeded the configured limit")
    }

  private def _result_text(json: Json): Option[String] =
    Vector("result", "response", "output_text").iterator
      .flatMap(name => json.hcursor.get[String](name).toOption)
      .map(_.trim)
      .find(_.nonEmpty)

  private def _actual_model(json: Json): Option[String] =
    Vector("model", "model_name").iterator
      .flatMap(name => json.hcursor.get[String](name).toOption)
      .map(_.trim)
      .find(_.nonEmpty)

  private def _metadata(json: Json): Map[String, String] = {
    val usage = json.hcursor.downField("usage")
    def _value_(names: String*): Option[String] =
      names.iterator.flatMap(name => usage.get[Long](name).toOption)
        .find(_ >= 0L).map(_.toString)
    def _root_value_(names: String*): Option[String] =
      names.iterator.flatMap(name => json.hcursor.get[String](name).toOption)
        .map(_.trim).find(_.nonEmpty)
    Vector(
      "google.antigravity_conversation_id" -> _root_value_("conversation_id", "conversationId", "session_id"),
      "google.antigravity_tool_calls" -> _value_("tool_calls", "toolCalls"),
      "google.usage.input_tokens" -> _value_("input_tokens", "inputTokens", "prompt_tokens", "promptTokens"),
      "google.usage.cached_input_tokens" -> _value_("cached_input_tokens", "cachedInputTokens"),
      "google.usage.output_tokens" -> _value_("output_tokens", "outputTokens", "candidate_tokens", "candidateTokens"),
      "google.usage.reasoning_tokens" -> _value_("reasoning_tokens", "reasoningTokens", "thinking_tokens", "thinkingTokens"),
      "google.usage.total_tokens" -> _value_("total_tokens", "totalTokens")
    ).collect { case (key, Some(value)) => key -> value }.toMap
  }
}

final class AntigravityChatService(config: AntigravityRuntimeConfig, context: ExecutionContext) extends ChatService {
  private val _generate = new AntigravityGenerateService(config, context)

  override def chat(req: ChatRequest): Consequence[ChatResponse] =
    _generate.generate(GenerateRequest(
      req.messages.map(message => s"${message.role.toString.toLowerCase}: ${message.content}").mkString("\n"),
      req.temperature,
      req.maxTokens,
      req.properties
    )).map(response => ChatResponse(Message(MessageRole.Assistant, response.text), response.model, response.metadata))
}

final class AntigravityGenerateExtensionPoint(config: AntigravityRuntimeConfig) extends ExtensionPoint[GenerateService] {
  private var _runtimecomponent: Option[Component] = None
  private[ai] def withRuntimeComponent(component: Component): AntigravityGenerateExtensionPoint = { _runtimecomponent = Some(component); this }
  override def supports(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "generate-service" && variation.provider.exists(_is_google) && variation.mode.contains("local") && variation.engine.contains("antigravity-cli")
  override def provide(contract: ServiceContract[GenerateService], variation: VariationSelection)(using caller: ExecutionContext): Consequence[GenerateService] =
    Consequence.success(new AntigravityGenerateService(config, _context(_runtimecomponent)))
}

final class AntigravityChatExtensionPoint(config: AntigravityRuntimeConfig) extends ExtensionPoint[ChatService] {
  private var _runtimecomponent: Option[Component] = None
  private[ai] def withRuntimeComponent(component: Component): AntigravityChatExtensionPoint = { _runtimecomponent = Some(component); this }
  override def supports(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "chat-service" && variation.provider.exists(_is_google) && variation.mode.contains("local") && variation.engine.contains("antigravity-cli")
  override def provide(contract: ServiceContract[ChatService], variation: VariationSelection)(using caller: ExecutionContext): Consequence[ChatService] =
    Consequence.success(new AntigravityChatService(config, _context(_runtimecomponent)))
}

private def _context(component: Option[Component])(using caller: ExecutionContext): ExecutionContext =
  component.map(_.logic.executionContext()).getOrElse(caller)

private def _is_google(value: String): Boolean =
  Set("google", "antigravity", "antigravity-cli").contains(value.trim.toLowerCase(java.util.Locale.ROOT))

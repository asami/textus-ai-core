package org.simplemodeling.textus.ai.provider.codex

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.util.Try
import io.circe.{Json, JsonObject}
import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ExtensionPoint, ServiceContract, VariationSelection}
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.processexecution.*
import org.goldenport.cncf.spi.ai.runner.AiTool
import org.goldenport.cncf.unitofwork.UnitOfWorkOp
import org.goldenport.configuration.ResolvedConfiguration
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.airuntime.ai.*
import org.simplemodeling.textus.ai.runtime.{AiRequestProperties, ChatService, GenerateService}

/*
 * @since   Jul. 17, 2026
 * @version Aug. 21, 2026
 * @author  ASAMI, Tomoharu
 */

final case class CodexExecutionProfile(
  name: String,
  model: String,
  reasoningLevel: Option[CodexReasoningLevel] = None,
  tools: Set[AiTool] = Set.empty,
  minimumCliVersion: Option[CodexCliVersion] = None
) {
  def plainCapability: String = s"codex-cli-profile-$name"
  def webCapability: String = s"${plainCapability}-web"
  def versionCapability: String = s"${plainCapability}-version"
  def requiredMinimumCliVersion: Option[CodexCliVersion] =
    (CodexCliVersion.minimumForModel(model).toVector ++ minimumCliVersion.toVector).maxOption

  def supports(requested: Set[AiTool]): Boolean =
    requested.subsetOf(tools) &&
      (!requested.contains(AiTool.UrlContext) || requested.contains(AiTool.WebSearch))

  def supportsWeb: Boolean =
    tools.contains(AiTool.WebSearch)
}

final case class CodexCliVersion(
  major: Int,
  minor: Int,
  patch: Int
) extends Ordered[CodexCliVersion] {
  override def compare(that: CodexCliVersion): Int =
    val majorcomparison = Integer.compare(major, that.major)
    if (majorcomparison != 0)
      majorcomparison
    else {
      val minorcomparison = Integer.compare(minor, that.minor)
      if (minorcomparison != 0)
        minorcomparison
      else
        Integer.compare(patch, that.patch)
    }

  def render: String = s"$major.$minor.$patch"
}

object CodexCliVersion {
  val Gpt56Minimum: CodexCliVersion = CodexCliVersion(0, 144, 0)

  def minimumForModel(model: String): Option[CodexCliVersion] =
    Option.when(Set("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna").contains(
      model.trim.toLowerCase(java.util.Locale.ROOT)
    ))(Gpt56Minimum)

  def parse(value: String): Option[CodexCliVersion] =
    """(?:^|\D)(\d+)\.(\d+)\.(\d+)(?:\D|$)""".r
      .findFirstMatchIn(value)
      .flatMap { matched =>
        for {
          major <- matched.group(1).toIntOption
          minor <- matched.group(2).toIntOption
          patch <- matched.group(3).toIntOption
        } yield CodexCliVersion(major, minor, patch)
      }
}

enum CodexReasoningLevel(val id: String) {
  case Minimal extends CodexReasoningLevel("minimal")
  case Low extends CodexReasoningLevel("low")
  case Medium extends CodexReasoningLevel("medium")
  case High extends CodexReasoningLevel("high")
  case XHigh extends CodexReasoningLevel("xhigh")
}

object CodexReasoningLevel {
  def parse(value: String): Option[CodexReasoningLevel] =
    values.find(_.id.equalsIgnoreCase(value.trim))
}

final case class CodexRuntimeConfig(
  mode: String = "local",
  engine: String = "codex-cli",
  schemaMaximumBytes: Long = 65536L,
  executable: String = "/runtime/codex-cli",
  executionLimits: ProcessExecutionLimits = CodexRuntimeConfig.defaultExecutionLimits,
  executionProfiles: Map[String, CodexExecutionProfile] = Map.empty
)

object CodexRuntimeConfig {
  val defaultExecutionLimits: ProcessExecutionLimits = ProcessExecutionLimits(
    launchTimeoutMillis = Some(5000L),
    executionTimeoutMillis = Some(120000L),
    terminationGraceMillis = Some(5000L),
    stdinBytes = Some(131072L),
    stdoutBytes = Some(1048576L),
    stderrBytes = Some(65536L),
    argumentCount = Some(3L),
    argumentBytes = Some(128L),
    artifactCount = Some(1L),
    artifactBytes = Some(1048576L),
    workAreaBytes = Some(1048576L)
  )
}

object CodexConfig {
  def fromConfiguration(
    configuration: ResolvedConfiguration,
    executionprofiles: Map[String, CodexExecutionProfile] = Map.empty
  ): Option[CodexRuntimeConfig] =
    if (!_enabled(configuration))
      None
    else
      _config_string(configuration, "executable", "executable-path")
        .flatMap(_absolute_executable)
        .map { executable =>
          CodexRuntimeConfig(
            mode = _config_string(configuration, "mode").getOrElse("local"),
            engine = _config_string(configuration, "engine").getOrElse("codex-cli"),
            executable = executable,
            schemaMaximumBytes = _config_string(configuration, "schema-maximum-bytes", "schemaMaximumBytes")
              .flatMap(_.toLongOption)
              .filter(_ > 0L)
              .getOrElse(65536L),
            executionProfiles = executionprofiles
          )
        }

  private def _enabled(configuration: ResolvedConfiguration): Boolean =
    _config_string(configuration, "enabled").exists { value =>
      value.toLowerCase(java.util.Locale.ROOT) match
        case "true" | "yes" | "on" | "1" => true
        case _ => false
    }

  private def _config_string(
    configuration: ResolvedConfiguration,
    leaves: String*
  ): Option[String] =
    leaves.iterator
      .flatMap { leaf =>
        Vector(
          s"textus.ai.codex.$leaf",
          s"textus.runtime.ai.codex.$leaf",
          s"cncf.ai.codex.$leaf",
          s"cncf.runtime.ai.codex.$leaf"
        ).iterator
      }
      .flatMap(key => Try(RuntimeConfig.getString(configuration, key)).toOption.flatten)
      .map(_.trim)
      .find(_.nonEmpty)

  private def _absolute_executable(value: String): Option[String] =
    Try(Path.of(value)).toOption
      .filter(_.isAbsolute)
      .map(_.normalize.toString)
      .filter(_.nonEmpty)

}

final class CodexGenerateService(config: CodexRuntimeConfig, context: ExecutionContext) extends GenerateService {
  override def generate(req: GenerateRequest): Consequence[GenerateResponse] =
    for {
      _ <- AiRequestProperties.requireNoModelOverride("codex", req.properties)
      _ <- _require_no_output_token_limit(req.maxTokens)
      executionprofile <- _execution_profile_c(req.properties)
      _ <- _validate_cli_version_c(executionprofile)
      request <- _request_c(req.prompt, req.recordSchema, req.properties, executionprofile)
      result <- _execute_c(request)
      response <- _response_c(result, executionprofile)
    } yield response

  private def _request_c(
    prompt: String,
    schema: Option[org.goldenport.record.Record],
    properties: Vector[org.goldenport.protocol.Property],
    executionprofile: ExecutionProfile
  ): Consequence[ProcessExecutionRequest] =
    for {
      capability <- ProcessCapabilityId.parseC(executionprofile.capability)
      request <- schema match {
        case Some(value) => _record_request_c(capability, prompt, value, properties)
        case None => Consequence.success(_plain_request(capability, prompt, properties))
      }
    } yield request

  private def _execution_profile_c(
    properties: Vector[org.goldenport.protocol.Property]
  ): Consequence[ExecutionProfile] =
    for {
      tools <- AiRequestProperties.validateTools(properties)
      profile <- _configured_profile_c(properties, tools.toSet)
    } yield profile

  private def _configured_profile_c(
    properties: Vector[org.goldenport.protocol.Property],
    tools: Set[AiTool]
  ): Consequence[ExecutionProfile] =
    AiRequestProperties.codexExecutionProfile(properties) match {
      case Some(name) =>
        config.executionProfiles.get(name) match {
          case Some(profile) if profile.supports(tools) =>
            val capability = if (tools.nonEmpty) profile.webCapability else profile.plainCapability
            Consequence.success(ExecutionProfile(capability, Some(profile), tools))
          case Some(_) =>
            Consequence.configurationInvalid(
              s"Codex execution profile '$name' does not support requested tools: ${tools.toVector.map(_.id).sorted.mkString(",")}"
            )
          case None =>
            Consequence.configurationInvalid(s"Codex execution profile is not admitted: $name")
        }
      case None if tools.nonEmpty =>
        Consequence.configurationInvalid(
          s"Codex tools require an admitted runtime-profile binding: ${tools.toVector.map(_.id).sorted.mkString(",")}"
        )
      case None =>
        Consequence.success(ExecutionProfile("codex-cli", None, Set.empty))
    }

  private def _plain_request(
    capability: ProcessCapabilityId,
    prompt: String,
    properties: Vector[org.goldenport.protocol.Property]
  ): ProcessExecutionRequest =
    ProcessExecutionRequest(
      capability = capability,
      arguments = Vector("-"),
      input = ProcessExecutionInput.Bytes(prompt.getBytes(StandardCharsets.UTF_8).toVector),
      requestedLimits = _requested_limits(properties)
    )

  private def _record_request_c(
    capability: ProcessCapabilityId,
    prompt: String,
    schema: org.goldenport.record.Record,
    properties: Vector[org.goldenport.protocol.Property]
  ): Consequence[ProcessExecutionRequest] =
    for {
      name <- ProcessArtifactName.parseC("schema")
      path <- WorkAreaRelativePath.parseC("schema.json")
      bytes = CodexJsonSchema.render(schema).getBytes(StandardCharsets.UTF_8).toVector
      input <- ProcessExecutionInputFile.createC(name, path, bytes, config.schemaMaximumBytes)
    } yield ProcessExecutionRequest(
      capability = capability,
      arguments = Vector("--output-schema", "schema.json", "-"),
      input = ProcessExecutionInput.Bytes(prompt.getBytes(StandardCharsets.UTF_8).toVector),
      inputFiles = Vector(input),
      requestedLimits = _requested_limits(properties)
    )

  private def _requested_limits(
    properties: Vector[org.goldenport.protocol.Property]
  ): ProcessExecutionLimits =
    val codextimeout = AiRequestProperties.string(properties, Vector(
      "ai.codex.timeout-millis",
      "textus.ai.codex.timeout-millis",
      "cncf.ai.codex.timeout-millis"
    )).flatMap(_.toLongOption).filter(_ > 0L)
    val purposetimeout = AiRequestProperties.purposeTimeoutSeconds(properties)
      .flatMap(value => Try(Math.multiplyExact(value, 1000L)).toOption)
    ProcessExecutionLimits(executionTimeoutMillis = codextimeout.orElse(purposetimeout))

  private def _require_no_output_token_limit(
    maxTokens: Option[Int]
  ): Consequence[Unit] =
    maxTokens match
      case Some(_) =>
        Consequence.configurationInvalid("AI maximum output tokens are not supported by provider 'codex'")
      case None =>
        Consequence.unit

  private def _validate_cli_version_c(
    executionprofile: ExecutionProfile
  ): Consequence[Unit] =
    executionprofile.profile match {
      case Some(profile) =>
        profile.requiredMinimumCliVersion match {
          case Some(minimum) =>
            for {
              capability <- ProcessCapabilityId.parseC(profile.versionCapability)
              result <- _execute_c(ProcessExecutionRequest(capability))
              actual <- _cli_version_c(result)
              _ <- if (actual >= minimum)
                Consequence.unit
              else
                Consequence.configurationInvalid(
                  s"Codex CLI ${actual.render} does not satisfy required version ${minimum.render}"
                )
            } yield ()
          case None =>
            Consequence.unit
        }
      case None =>
        Consequence.unit
    }

  private def _cli_version_c(
    result: ProcessExecutionResult
  ): Consequence[CodexCliVersion] =
    result.termination match {
      case ProcessExecutionTermination.Exited(0) =>
        CodexCliVersion.parse(new String(result.stdout.content.toArray, StandardCharsets.UTF_8)) match {
          case Some(version) => Consequence.success(version)
          case None => Consequence.serviceUnavailable("Codex CLI version output is invalid")
        }
      case _ =>
        Consequence.serviceUnavailable("Codex CLI version check failed")
    }

  private def _execute_c(request: ProcessExecutionRequest): Consequence[ProcessExecutionResult] =
    given ExecutionContext = context
    ProcessExecutionAdmission.resolveC(context.scope, request).flatMap { execution =>
      context.unitOfWorkInterpreter(UnitOfWorkOp.ProcessExec(execution))
    }

  private def _response_c(
    result: ProcessExecutionResult,
    executionprofile: ExecutionProfile
  ): Consequence[GenerateResponse] =
    result.termination match {
      case ProcessExecutionTermination.Exited(0) =>
        val text = new String(result.stdout.content.toArray, StandardCharsets.UTF_8).trim
        if (text.nonEmpty)
          Consequence.success(GenerateResponse(
            text,
            executionprofile.profile.map(_.model),
            Map("codex.finish_reason" -> "exited") ++ executionprofile.metadata
          ))
        else
          Consequence.valueInvalid("Codex CLI returned empty output")
      case ProcessExecutionTermination.Exited(_) =>
        Consequence.serviceUnavailable("Codex CLI exited unsuccessfully")
      case ProcessExecutionTermination.LaunchFailed =>
        Consequence.serviceUnavailable("Codex CLI is unavailable")
      case ProcessExecutionTermination.TimedOut =>
        Consequence.serviceUnavailable("Codex CLI timed out")
      case ProcessExecutionTermination.Cancelled =>
        Consequence.operationIllegal("codex", "Codex CLI execution was cancelled")
      case ProcessExecutionTermination.OutputLimitExceeded(_) =>
        Consequence.operationIllegal("codex", "Codex CLI output exceeded the configured limit")
      case ProcessExecutionTermination.ArtifactLimitExceeded =>
        Consequence.operationIllegal("codex", "Codex CLI artifacts exceeded the configured limit")
    }

  private final case class ExecutionProfile(
    capability: String,
    profile: Option[CodexExecutionProfile],
    tools: Set[AiTool]
  ) {
    def metadata: Map[String, String] =
      profile.map { value =>
        Map(
          "codex.profile" -> value.name,
          "codex.reasoning_level" -> value.reasoningLevel.map(_.id).getOrElse(""),
          "codex.enabled_tools" -> tools.toVector.map(_.id).sorted.mkString(",")
        ).filter(_._2.nonEmpty)
      }.getOrElse(Map.empty)
  }
}

final class CodexChatService(config: CodexRuntimeConfig, context: ExecutionContext) extends ChatService {
  private val _generate = new CodexGenerateService(config, context)

  override def chat(req: ChatRequest): Consequence[ChatResponse] =
    _generate.generate(GenerateRequest(
      prompt = req.messages.map(message => s"${message.role.toString.toLowerCase}: ${message.content}").mkString("\n"),
      temperature = req.temperature,
      maxTokens = req.maxTokens,
      properties = req.properties
    )).map { response =>
      ChatResponse(Message(MessageRole.Assistant, response.text), response.model, response.metadata)
    }
}

final class CodexGenerateExtensionPoint(config: CodexRuntimeConfig) extends ExtensionPoint[GenerateService] {
  private var _runtimecomponent: Option[Component] = None

  private[ai] def _with_runtime_component(component: Component): CodexGenerateExtensionPoint = {
    _runtimecomponent = Some(component)
    this
  }

  override def supports(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "generate-service" &&
      variation.provider.exists(_is_codex_provider) &&
      variation.mode.contains(config.mode) &&
      variation.engine.contains(config.engine)

  override def provide(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Consequence[GenerateService] =
    Consequence.success(new CodexGenerateService(config, _provider_execution_context(_runtimecomponent)))
}

final class CodexChatExtensionPoint(config: CodexRuntimeConfig) extends ExtensionPoint[ChatService] {
  private var _runtimecomponent: Option[Component] = None

  private[ai] def _with_runtime_component(component: Component): CodexChatExtensionPoint = {
    _runtimecomponent = Some(component)
    this
  }

  override def supports(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "chat-service" &&
      variation.provider.exists(_is_codex_provider) &&
      variation.mode.contains(config.mode) &&
      variation.engine.contains(config.engine)

  override def provide(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Consequence[ChatService] =
    Consequence.success(new CodexChatService(config, _provider_execution_context(_runtimecomponent)))
}

private def _provider_execution_context(
  runtimecomponent: Option[Component]
)(using caller: ExecutionContext): ExecutionContext =
  runtimecomponent.map(_.logic.executionContext()).getOrElse(caller)

private def _is_codex_provider(value: String): Boolean =
  value.trim.equalsIgnoreCase("codex") || value.trim.equalsIgnoreCase("codex-cli")

private object CodexJsonSchema {
  def render(schema: org.goldenport.record.Record): String =
    Json.fromJsonObject(_object_schema(schema)).noSpaces

  private def _object_schema(schema: org.goldenport.record.Record): JsonObject = {
    val domainrequired = _strings(schema.getAny("required"))
    val fields = _records(schema.getAny("fields")).flatMap { value =>
      _string(value.getAny("name")).orElse(_string(value.getAny("field"))).map { name =>
        name -> _field_schema(value, domainrequired.contains(name))
      }
    }
    val arrays = _records(schema.getAny("arrays")).flatMap { value =>
      _string(value.getAny("name")).orElse(_string(value.getAny("field"))).map { name =>
        val array = Json.fromJsonObject(JsonObject(
          "type" -> Json.fromString("array"),
          "items" -> Json.fromJsonObject(_object_schema(value))
        ))
        name -> _nullable(array, _boolean(value.getAny("optional")).contains(true) || !domainrequired.contains(name))
      }
    }
    val declared = (fields ++ arrays).toMap
    val names = (domainrequired ++ fields.map(_._1) ++ arrays.map(_._1)).distinct
    val properties = names.map { name =>
      name -> declared.getOrElse(name, Json.obj("type" -> Json.fromString("string")))
    }
    JsonObject(
      "type" -> Json.fromString("object"),
      "additionalProperties" -> Json.fromBoolean(false),
      // Codex strict output requires every declared property to be required;
      // domain-optional fields are represented as nullable instead.
      "required" -> Json.fromValues(names.map(Json.fromString)),
      "properties" -> Json.fromJsonObject(JsonObject.fromIterable(properties))
    )
  }

  private def _field_schema(
    field: org.goldenport.record.Record,
    domainrequired: Boolean
  ): Json = {
    val datatype = _string(field.getAny("type")).getOrElse("string").toLowerCase(java.util.Locale.ROOT) match {
      case "int" | "integer" | "long" => "integer"
      case "double" | "float" | "decimal" | "number" => "number"
      case "bool" | "boolean" => "boolean"
      case _ => "string"
    }
    val base = Json.obj("type" -> Json.fromString(datatype))
    _nullable(base, _boolean(field.getAny("optional")).contains(true) || !domainrequired)
  }

  private def _nullable(schema: Json, nullable: Boolean): Json =
    if (!nullable)
      schema
    else
      schema.mapObject { value =>
        value.add("type", Json.fromValues(Vector(
          value("type").getOrElse(Json.fromString("string")),
          Json.fromString("null")
        )))
      }

  private def _records(value: Any): Vector[org.goldenport.record.Record] =
    value match {
      case null => Vector.empty
      case record: org.goldenport.record.Record => Vector(record)
      case Some(x) => _records(x)
      case values: Seq[?] => values.toVector.flatMap(_records)
      case values: Array[?] => values.toVector.flatMap(_records)
      case _ => Vector.empty
    }

  private def _strings(value: Any): Vector[String] =
    value match {
      case null => Vector.empty
      case Some(x) => _strings(x)
      case values: Seq[?] => values.toVector.flatMap(_strings)
      case values: Array[?] => values.toVector.flatMap(_strings)
      case text: String => text.split("[,\\s]+").toVector.map(_.trim).filter(_.nonEmpty)
      case other => Vector(other.toString)
    }

  private def _string(value: Any): Option[String] =
    _strings(value).headOption

  private def _boolean(value: Any): Option[Boolean] =
    value match {
      case null => None
      case Some(x) => _boolean(x)
      case value: Boolean => Some(value)
      case value: String => value.trim.toBooleanOption
      case _ => None
    }
}

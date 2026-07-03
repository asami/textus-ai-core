package org.simplemodeling.textus.ai.provider.openai

import java.net.URI

import io.circe.{ACursor, HCursor, Json, JsonObject}
import scala.util.Try
import org.goldenport.Consequence
import org.goldenport.cncf.component.{ExtensionPoint, ServiceContract, VariationSelection}
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.configuration.ResolvedConfiguration
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.ai.ai.*
import org.simplemodeling.textus.ai.runtime.{AiRequestProperties, ChatService, GenerateService, HttpSupport}

final case class OpenAiRuntimeConfig(
  provider: String = "openai",
  mode: String = "remote",
  engine: String = "openai",
  endpoint: URI,
  apiKey: String,
  model: String,
  timeoutSeconds: Long = 30L
)

object OpenAiConfig:
  def fromConfiguration(
    configuration: ResolvedConfiguration
  ): Option[OpenAiRuntimeConfig] =
    for
      apiKey <- _config_strings(configuration, Vector(
        "textus.ai.openai.api-key",
        "textus.ai.openai.apiKey",
        "textus.runtime.ai.openai.api-key",
        "cncf.ai.openai.api-key",
        "cncf.runtime.ai.openai.api-key"
      )).headOption
      model <- _config_strings(configuration, Vector(
        "textus.ai.openai.model",
        "textus.runtime.ai.openai.model",
        "cncf.ai.openai.model",
        "cncf.runtime.ai.openai.model",
        "textus.ai.llm.model",
        "cncf.ai.llm.model"
      )).headOption
    yield
      OpenAiRuntimeConfig(
        endpoint = URI.create(
          _config_strings(configuration, Vector(
            "textus.ai.openai.endpoint",
            "textus.runtime.ai.openai.endpoint",
            "cncf.ai.openai.endpoint",
            "cncf.runtime.ai.openai.endpoint"
          )).headOption.getOrElse("https://api.openai.com")
        ),
        apiKey = apiKey,
        model = model,
        timeoutSeconds = _config_strings(configuration, Vector(
          "textus.ai.openai.timeout-seconds",
          "textus.runtime.ai.openai.timeout-seconds",
          "cncf.ai.openai.timeout-seconds",
          "cncf.runtime.ai.openai.timeout-seconds"
        )).headOption
          .orElse(sys.env.get("AI_OPENAI_TIMEOUT_SECONDS"))
          .flatMap(_.toLongOption)
          .getOrElse(30L)
      )

  def fromEnvironment(): Option[OpenAiRuntimeConfig] =
    for
      apiKey <- sys.env.get("OPENAI_API_KEY").orElse(sys.env.get("AI_OPENAI_API_KEY"))
      model <- sys.env.get("AI_OPENAI_MODEL").orElse(sys.env.get("AI_LLM_MODEL"))
    yield
      OpenAiRuntimeConfig(
        endpoint = URI.create(sys.env.getOrElse("AI_OPENAI_ENDPOINT", "https://api.openai.com")),
        apiKey = apiKey,
        model = model,
        timeoutSeconds = sys.env.get("AI_OPENAI_TIMEOUT_SECONDS").flatMap(_.toLongOption).getOrElse(30L)
      )

  private def _config_strings(
    configuration: ResolvedConfiguration,
    keys: Vector[String]
  ): Vector[String] =
    keys.flatMap(key => Try(RuntimeConfig.getString(configuration, key)).toOption.flatten)
      .flatMap(value => Option(value))
      .map(_.trim)
      .filter(_.nonEmpty)

private object OpenAiJson:
  def generateRequest(request: GenerateRequest, model: String): Json =
    val base = JsonObject(
      "model" -> Json.fromString(model),
      "messages" -> Json.arr(
        Json.obj(
          "role" -> Json.fromString("user"),
          "content" -> Json.fromString(request.prompt)
        )
      ),
      "stream" -> Json.False
    )
    Json.fromJsonObject(_with_generation_options(base, request.temperature, request.maxTokens))

  def chatRequest(request: ChatRequest, model: String): Json =
    val messages = request.messages.map { message =>
      Json.obj(
        "role" -> Json.fromString(_role(message.role)),
        "content" -> Json.fromString(message.content)
      )
    }
    Json.fromJsonObject(
      _with_generation_options(
        JsonObject(
        "model" -> Json.fromString(model),
        "messages" -> Json.fromValues(messages),
        "stream" -> Json.False
        ),
        request.temperature,
        request.maxTokens
      )
    )

  def extractText(json: Json): Consequence[String] =
    _string_at(json.hcursor, List("choices", "0", "message", "content"))

  private def _with_generation_options(
    base: JsonObject,
    temperature: Option[Double],
    maxTokens: Option[Int]
  ): JsonObject =
    val withTemperature = temperature.map(x => base.add("temperature", Json.fromDoubleOrNull(x))).getOrElse(base)
    maxTokens.map(x => withTemperature.add("max_completion_tokens", Json.fromInt(x))).getOrElse(withTemperature)

  private def _role(role: MessageRole): String = role match
    case MessageRole.System => "system"
    case MessageRole.User => "user"
    case MessageRole.Assistant => "assistant"

  private def _string_at(cursor: HCursor, path: List[String]): Consequence[String] =
    path.foldLeft(Option(cursor: ACursor)) { (z, key) =>
      z.map { c =>
        key.toIntOption match
          case Some(i) => c.downN(i)
          case None => c.downField(key)
      }
    } match
      case Some(c) if c.succeeded =>
        c.as[String] match
          case Right(s) => Consequence.success(s)
          case Left(e) => Consequence.valueInvalid(e.getMessage)
      case None =>
        Consequence.valueInvalid(s"Missing path: ${path.mkString(".")}")
      case _ =>
        Consequence.valueInvalid(s"Missing path: ${path.mkString(".")}")

final class OpenAiGenerateService(config: OpenAiRuntimeConfig, context: ExecutionContext) extends GenerateService:
  override def generate(req: GenerateRequest): Consequence[GenerateResponse] =
    given ExecutionContext = context
    HttpSupport.post(
      config.endpoint,
      "/v1/chat/completions",
      OpenAiJson.generateRequest(req, config.model),
      AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
      headers = Vector("Authorization" -> s"Bearer ${config.apiKey}"),
      properties = req.properties
    ).flatMap(OpenAiJson.extractText).map(text => GenerateResponse(text, Some(config.model)))

final class OpenAiChatService(config: OpenAiRuntimeConfig, context: ExecutionContext) extends ChatService:
  override def chat(req: ChatRequest): Consequence[ChatResponse] =
    given ExecutionContext = context
    HttpSupport.post(
      config.endpoint,
      "/v1/chat/completions",
      OpenAiJson.chatRequest(req, config.model),
      AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
      headers = Vector("Authorization" -> s"Bearer ${config.apiKey}"),
      properties = req.properties
    ).flatMap(OpenAiJson.extractText).map(x => ChatResponse(Message(MessageRole.Assistant, x), Some(config.model)))

final class OpenAiGenerateExtensionPoint(config: OpenAiRuntimeConfig)
  extends ExtensionPoint[GenerateService]:
  override def supports(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "generate-service" &&
      variation.provider.contains("openai") &&
      variation.engine.contains("openai")

  override def provide(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Consequence[GenerateService] =
    Consequence.success(new OpenAiGenerateService(config, summon[ExecutionContext]))

final class OpenAiChatExtensionPoint(config: OpenAiRuntimeConfig)
  extends ExtensionPoint[ChatService]:
  override def supports(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "chat-service" &&
      variation.provider.contains("openai") &&
      variation.engine.contains("openai")

  override def provide(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Consequence[ChatService] =
    Consequence.success(new OpenAiChatService(config, summon[ExecutionContext]))

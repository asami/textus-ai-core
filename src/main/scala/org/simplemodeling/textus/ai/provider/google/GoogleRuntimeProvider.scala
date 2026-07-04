package org.simplemodeling.textus.ai.provider.google

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

final case class GoogleRuntimeConfig(
  provider: String = "google",
  mode: String = "remote",
  engine: String = "gemini",
  endpoint: URI,
  apiKey: String,
  model: String,
  timeoutSeconds: Long = 30L
)

object GoogleConfig:
  def fromConfiguration(
    configuration: ResolvedConfiguration
  ): Option[GoogleRuntimeConfig] =
    for
      apiKey <- _config_strings(configuration, Vector(
        "textus.ai.google.api-key",
        "textus.ai.google.apiKey",
        "textus.runtime.ai.google.api-key",
        "cncf.ai.google.api-key",
        "cncf.runtime.ai.google.api-key"
      )).headOption
      model <- _config_strings(configuration, Vector(
        "textus.ai.google.model",
        "textus.runtime.ai.google.model",
        "cncf.ai.google.model",
        "cncf.runtime.ai.google.model",
        "textus.ai.llm.model",
        "cncf.ai.llm.model"
      )).headOption
    yield
      GoogleRuntimeConfig(
        endpoint = URI.create(
          _config_strings(configuration, Vector(
            "textus.ai.google.endpoint",
            "textus.runtime.ai.google.endpoint",
            "cncf.ai.google.endpoint",
            "cncf.runtime.ai.google.endpoint"
          )).headOption.getOrElse("https://generativelanguage.googleapis.com")
        ),
        apiKey = apiKey,
        model = model,
        timeoutSeconds = _config_strings(configuration, Vector(
          "textus.ai.google.timeout-seconds",
          "textus.runtime.ai.google.timeout-seconds",
          "cncf.ai.google.timeout-seconds",
          "cncf.runtime.ai.google.timeout-seconds"
        )).headOption
          .orElse(sys.env.get("AI_GOOGLE_TIMEOUT_SECONDS"))
          .flatMap(_.toLongOption)
          .getOrElse(30L)
      )

  def fromEnvironment(): Option[GoogleRuntimeConfig] =
    for
      apiKey <- sys.env.get("GOOGLE_API_KEY").orElse(sys.env.get("GEMINI_API_KEY")).orElse(sys.env.get("AI_GOOGLE_API_KEY"))
      model <- sys.env.get("AI_GOOGLE_MODEL").orElse(sys.env.get("AI_LLM_MODEL"))
    yield
      GoogleRuntimeConfig(
        endpoint = URI.create(sys.env.getOrElse("AI_GOOGLE_ENDPOINT", "https://generativelanguage.googleapis.com")),
        apiKey = apiKey,
        model = model,
        timeoutSeconds = sys.env.get("AI_GOOGLE_TIMEOUT_SECONDS").flatMap(_.toLongOption).getOrElse(30L)
      )

  private def _config_strings(
    configuration: ResolvedConfiguration,
    keys: Vector[String]
  ): Vector[String] =
    keys.flatMap(key => Try(RuntimeConfig.getString(configuration, key)).toOption.flatten)
      .flatMap(value => Option(value))
      .map(_.trim)
      .filter(_.nonEmpty)

private object GoogleJson:
  private def _safe_string(value: String): String =
    Option(value).getOrElse("")

  def generateRequest(request: GenerateRequest): Json =
    Json.obj(
      "contents" -> Json.arr(
        Json.obj(
          "role" -> Json.fromString("user"),
          "parts" -> Json.arr(Json.obj("text" -> Json.fromString(_safe_string(request.prompt))))
        )
      ),
      "generationConfig" -> _generation_config(request.temperature, request.maxTokens)
    )

  def chatRequest(request: ChatRequest): Json =
    val (systemMessages, nonSystemMessages) = request.messages.partition(_.role == MessageRole.System)
    val base = JsonObject(
      "contents" -> Json.fromValues(nonSystemMessages.map(_message)),
      "generationConfig" -> _generation_config(request.temperature, request.maxTokens)
    )
    val withSystem =
      if systemMessages.isEmpty then
        base
      else
        base.add(
          "systemInstruction",
          Json.obj(
            "parts" -> Json.fromValues(systemMessages.map(x => Json.obj("text" -> Json.fromString(_safe_string(x.content)))))
          )
        )
    Json.fromJsonObject(withSystem)

  def extractText(json: Json): Consequence[String] =
    _string_at(json.hcursor, List("candidates", "0", "content", "parts", "0", "text"))

  private def _generation_config(
    temperature: Option[Double],
    maxTokens: Option[Int]
  ): Json =
    Json.fromJsonObject(
      maxTokens
        .map(x => JsonObject("maxOutputTokens" -> Json.fromInt(x)))
        .getOrElse(JsonObject.empty)
        .deepMerge(
          temperature
            .map(x => JsonObject("temperature" -> Json.fromDoubleOrNull(x)))
            .getOrElse(JsonObject.empty)
        )
    )

  private def _message(message: Message): Json =
    Json.obj(
      "role" -> Json.fromString(_role(message.role)),
      "parts" -> Json.arr(Json.obj("text" -> Json.fromString(_safe_string(message.content))))
    )

  private def _role(role: MessageRole): String = role match
    case MessageRole.System => "user"
    case MessageRole.User => "user"
    case MessageRole.Assistant => "model"

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

final class GoogleGenerateService(config: GoogleRuntimeConfig, context: ExecutionContext) extends GenerateService:
  override def generate(req: GenerateRequest): Consequence[GenerateResponse] =
    given ExecutionContext = context
    val model = AiRequestProperties.effectiveModel(config.model, req.properties, "google")
    GoogleRuntimeException.guard("google generate") {
      HttpSupport.post(
        config.endpoint,
        s"/v1beta/models/${Option(model).getOrElse("")}:generateContent?key=${Option(config.apiKey).getOrElse("")}",
        GoogleJson.generateRequest(req),
        AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
        properties = req.properties
      ).flatMap(GoogleJson.extractText).map(text => GenerateResponse(text, Some(model)))
    }

final class GoogleChatService(config: GoogleRuntimeConfig, context: ExecutionContext) extends ChatService:
  override def chat(req: ChatRequest): Consequence[ChatResponse] =
    given ExecutionContext = context
    val model = AiRequestProperties.effectiveModel(config.model, req.properties, "google")
    GoogleRuntimeException.guard("google chat") {
      HttpSupport.post(
        config.endpoint,
        s"/v1beta/models/${Option(model).getOrElse("")}:generateContent?key=${Option(config.apiKey).getOrElse("")}",
        GoogleJson.chatRequest(req),
        AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
        properties = req.properties
      ).flatMap(GoogleJson.extractText).map(x => ChatResponse(Message(MessageRole.Assistant, x), Some(model)))
    }

private object GoogleRuntimeException:
  def guard[A](label: String)(body: => Consequence[A]): Consequence[A] =
    try
      body
    catch
      case e: Throwable =>
        val at = e.getStackTrace.headOption.map(_.toString).getOrElse("unknown")
        Consequence.serviceUnavailable(
          HttpSupport.redactSensitive(s"$label failed: ${e.getClass.getName}: ${Option(e.getMessage).getOrElse("")} at $at")
        )

final class GoogleGenerateExtensionPoint(config: GoogleRuntimeConfig)
  extends ExtensionPoint[GenerateService]:
  override def supports(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "generate-service" &&
      variation.provider.contains("google") &&
      variation.engine.contains("gemini")

  override def provide(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Consequence[GenerateService] =
    Consequence.success(new GoogleGenerateService(config, summon[ExecutionContext]))

final class GoogleChatExtensionPoint(config: GoogleRuntimeConfig)
  extends ExtensionPoint[ChatService]:
  override def supports(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "chat-service" &&
      variation.provider.contains("google") &&
      variation.engine.contains("gemini")

  override def provide(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Consequence[ChatService] =
    Consequence.success(new GoogleChatService(config, summon[ExecutionContext]))

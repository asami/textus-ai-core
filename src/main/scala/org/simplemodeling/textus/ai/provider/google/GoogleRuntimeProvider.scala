package org.simplemodeling.textus.ai.provider.google

import java.net.URI

import io.circe.{ACursor, HCursor, Json, JsonObject}
import org.goldenport.Consequence
import org.goldenport.cncf.component.{ExtensionPoint, ServiceContract, VariationSelection}
import org.goldenport.cncf.context.ExecutionContext
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.ai.ai.*
import org.simplemodeling.textus.ai.runtime.{ChatService, GenerateService, HttpSupport}

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

private object GoogleJson:
  def generateRequest(request: GenerateRequest): Json =
    Json.obj(
      "contents" -> Json.arr(
        Json.obj(
          "role" -> Json.fromString("user"),
          "parts" -> Json.arr(Json.obj("text" -> Json.fromString(request.prompt)))
        )
      ),
      "generationConfig" -> _generation_config(request.temperature, request.maxTokens)
    )

  def chatRequest(request: ChatRequest): Json =
    val (systemMessages, nonSystemMessages) = request.messages.partition(_.role == MessageRole.System)
    val base = JsonObject(
      "contents" -> Json.fromValues(nonSystemMessages.map(_message)),
      "generationConfig" -> Json.obj()
    )
    val withSystem =
      if systemMessages.isEmpty then
        base
      else
        base.add(
          "systemInstruction",
          Json.obj(
            "parts" -> Json.fromValues(systemMessages.map(x => Json.obj("text" -> Json.fromString(x.content))))
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
      "parts" -> Json.arr(Json.obj("text" -> Json.fromString(message.content)))
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

final class GoogleGenerateService(config: GoogleRuntimeConfig) extends GenerateService:
  private val _client = HttpSupport.client(config.timeoutSeconds)

  override def generate(req: GenerateRequest): Consequence[GenerateResponse] =
    HttpSupport.post(
      _client,
      config.endpoint,
      s"/v1beta/models/${config.model}:generateContent?key=${config.apiKey}",
      GoogleJson.generateRequest(req),
      config.timeoutSeconds
    ).flatMap(GoogleJson.extractText).map(GenerateResponse.apply)

final class GoogleChatService(config: GoogleRuntimeConfig) extends ChatService:
  private val _client = HttpSupport.client(config.timeoutSeconds)

  override def chat(req: ChatRequest): Consequence[ChatResponse] =
    HttpSupport.post(
      _client,
      config.endpoint,
      s"/v1beta/models/${config.model}:generateContent?key=${config.apiKey}",
      GoogleJson.chatRequest(req),
      config.timeoutSeconds
    ).flatMap(GoogleJson.extractText).map(x => ChatResponse(Message(MessageRole.Assistant, x)))

final class GoogleGenerateExtensionPoint(config: GoogleRuntimeConfig)
  extends ExtensionPoint[GenerateService]:
  override def supports(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "generate-service" &&
      variation.provider.contains("google") &&
      variation.engine.contains("gemini")

  override def provide(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Consequence[GenerateService] =
    Consequence.success(new GoogleGenerateService(config))

final class GoogleChatExtensionPoint(config: GoogleRuntimeConfig)
  extends ExtensionPoint[ChatService]:
  override def supports(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "chat-service" &&
      variation.provider.contains("google") &&
      variation.engine.contains("gemini")

  override def provide(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Consequence[ChatService] =
    Consequence.success(new GoogleChatService(config))

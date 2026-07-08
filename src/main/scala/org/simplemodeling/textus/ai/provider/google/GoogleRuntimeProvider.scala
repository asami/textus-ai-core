package org.simplemodeling.textus.ai.provider.google

import java.net.URI

import io.circe.{ACursor, HCursor, Json, JsonObject}
import scala.util.Try
import org.goldenport.Consequence
import org.goldenport.cncf.component.{ExtensionPoint, ServiceContract, VariationSelection}
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.ai.runner.AiTool
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

  def interactionGenerateRequest(request: GenerateRequest, model: String): Json =
    _interaction_request(model, Json.fromString(_safe_string(request.prompt)), request.properties)

  def interactionChatRequest(request: ChatRequest, model: String): Json =
    val input = Json.fromValues(
      request.messages.map { message =>
        Json.obj(
          "role" -> Json.fromString(_role(message.role)),
          "content" -> Json.fromString(_safe_string(message.content))
        )
      }
    )
    _interaction_request(model, input, request.properties)

  def extractInteractionText(json: Json): Consequence[String] =
    json.hcursor.get[String]("output_text") match
      case Right(s) => Consequence.success(s)
      case Left(_) =>
        _string_at(json.hcursor, List("steps", "0", "content", "0", "text"))

  def interactionMetadata(json: Json, properties: Vector[org.goldenport.protocol.Property]): Map[String, String] =
    val tools = AiRequestProperties.tools(properties)
    val steps = json.hcursor.downField("steps").focus.flatMap(_.asArray).getOrElse(Vector.empty)
    Map(
      "ai.tools" -> tools.map(_.id).mkString(","),
      "ai.provider_tools" -> _provider_tools(tools).map(_._1).mkString(","),
      "google.google_search_calls" -> _count_steps(steps, "google_search_call").toString,
      "google.google_search_results" -> _count_steps(steps, "google_search_result").toString,
      "google.url_context_calls" -> _count_steps(steps, "url_context_call").toString,
      "google.url_citations" -> _count_annotations(steps, "url_citation").toString
    )

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

  private def _interaction_request(
    model: String,
    input: Json,
    properties: Vector[org.goldenport.protocol.Property]
  ): Json =
    Json.obj(
      "model" -> Json.fromString(model),
      "input" -> input,
      "tools" -> Json.fromValues(_provider_tools(AiRequestProperties.tools(properties)).map(_._2))
    )

  private def _provider_tools(tools: Vector[AiTool]): Vector[(String, Json)] =
    tools.distinct.flatMap {
      case AiTool.UrlContext => Some("url_context" -> Json.obj("type" -> Json.fromString("url_context")))
      case AiTool.WebSearch => Some("google_search" -> Json.obj("type" -> Json.fromString("google_search")))
      case AiTool.Unknown(_) => None
    }

  private def _count_steps(steps: Vector[Json], kind: String): Int =
    steps.count(_.hcursor.get[String]("type").toOption.contains(kind))

  private def _count_annotations(steps: Vector[Json], kind: String): Int =
    steps.flatMap { step =>
      step.hcursor.downField("content").focus.flatMap(_.asArray).getOrElse(Vector.empty)
    }.flatMap { content =>
      content.hcursor.downField("annotations").focus.flatMap(_.asArray).getOrElse(Vector.empty)
    }.count(_.hcursor.get[String]("type").toOption.contains(kind))

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
      AiRequestProperties.validateTools(req.properties).flatMap { tools =>
      if (tools.isEmpty)
        HttpSupport.post(
          config.endpoint,
          s"/v1beta/models/${Option(model).getOrElse("")}:generateContent?key=${Option(config.apiKey).getOrElse("")}",
          GoogleJson.generateRequest(req),
          AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
          properties = req.properties
        ).flatMap(GoogleJson.extractText).map(text => GenerateResponse(text, Some(model)))
      else
        HttpSupport.post(
          config.endpoint,
          "/v1beta/interactions",
          GoogleJson.interactionGenerateRequest(req, model),
          AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
          headers = Vector("x-goog-api-key" -> Option(config.apiKey).getOrElse("")),
          properties = req.properties
        ).flatMap { json =>
          GoogleJson.extractInteractionText(json).map { text =>
            GenerateResponse(text, Some(model), GoogleJson.interactionMetadata(json, req.properties))
          }
        }
      }
    }

final class GoogleChatService(config: GoogleRuntimeConfig, context: ExecutionContext) extends ChatService:
  override def chat(req: ChatRequest): Consequence[ChatResponse] =
    given ExecutionContext = context
    val model = AiRequestProperties.effectiveModel(config.model, req.properties, "google")
    GoogleRuntimeException.guard("google chat") {
      AiRequestProperties.validateTools(req.properties).flatMap { tools =>
      if (tools.isEmpty)
        HttpSupport.post(
          config.endpoint,
          s"/v1beta/models/${Option(model).getOrElse("")}:generateContent?key=${Option(config.apiKey).getOrElse("")}",
          GoogleJson.chatRequest(req),
          AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
          properties = req.properties
        ).flatMap(GoogleJson.extractText).map(x => ChatResponse(Message(MessageRole.Assistant, x), Some(model)))
      else
        HttpSupport.post(
          config.endpoint,
          "/v1beta/interactions",
          GoogleJson.interactionChatRequest(req, model),
          AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
          headers = Vector("x-goog-api-key" -> Option(config.apiKey).getOrElse("")),
          properties = req.properties
        ).flatMap { json =>
          GoogleJson.extractInteractionText(json).map { text =>
            ChatResponse(Message(MessageRole.Assistant, text), Some(model), GoogleJson.interactionMetadata(json, req.properties))
          }
        }
      }
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

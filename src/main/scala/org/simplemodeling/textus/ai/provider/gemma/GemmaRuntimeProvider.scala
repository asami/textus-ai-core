package org.simplemodeling.textus.ai.provider.gemma

import java.net.URI

import io.circe.Json
import org.goldenport.Consequence
import org.goldenport.cncf.component.{ExtensionPoint, ServiceContract, VariationSelection}
import org.goldenport.cncf.context.ExecutionContext
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.ai.ai.*
import org.simplemodeling.textus.ai.runtime.{ChatService, GenerateService, HttpSupport}

final case class GemmaRuntimeConfig(
  provider: String = "gemma",
  mode: String = "local",
  engine: String = "ollama",
  endpoint: URI,
  fallbackEndpoint: Option[URI] = None,
  model: String = "gemma:2b",
  timeoutSeconds: Long = 30L,
  maxConcurrency: Int = 2
)

object GemmaConfig:
  def fromEnvironment(): GemmaRuntimeConfig =
    val endpoint = sys.env.getOrElse("AI_LLM_ENDPOINT", "http://ollama:11434")
    val fallback = sys.env.get("AI_LLM_FALLBACK_ENDPOINT").orElse(sys.env.get("AI_LLM_REMOTE_ENDPOINT"))
    val model = sys.env.getOrElse("AI_GEMMA_MODEL", sys.env.getOrElse("AI_LLM_MODEL", "gemma:2b"))
    val timeout = sys.env.get("AI_LLM_TIMEOUT_SECONDS").flatMap(_.toLongOption).getOrElse(30L)
    val concurrency = sys.env.get("AI_LLM_MAX_CONCURRENCY").flatMap(_.toIntOption).getOrElse(2)
    GemmaRuntimeConfig(
      provider = sys.env.getOrElse("AI_GEMMA_PROVIDER", sys.env.getOrElse("AI_LLM_PROVIDER", "gemma")),
      mode = sys.env.getOrElse("AI_GEMMA_MODE", sys.env.getOrElse("AI_LLM_MODE", "local")),
      engine = sys.env.getOrElse("AI_GEMMA_ENGINE", sys.env.getOrElse("AI_LLM_LOCAL_ENGINE", "ollama")),
      endpoint = URI.create(endpoint),
      fallbackEndpoint = fallback.map(URI.create),
      model = model,
      timeoutSeconds = timeout,
      maxConcurrency = concurrency
    )

private object GemmaSupport:
  def endpoints(config: GemmaRuntimeConfig): Vector[URI] =
    config.mode match
      case "remote" => Vector(config.endpoint)
      case _ => Vector(config.endpoint) ++ config.fallbackEndpoint.toVector

final class GemmaOllamaGenerateService(config: GemmaRuntimeConfig) extends GenerateService:
  private val _client = HttpSupport.client(config.timeoutSeconds)

  override def generate(req: GenerateRequest): Consequence[GenerateResponse] =
    val body = Json.obj(
      "model" -> Json.fromString(config.model),
      "prompt" -> Json.fromString(req.prompt),
      "stream" -> Json.False
    )
    _request_with_fallback(GemmaSupport.endpoints(config), "/api/generate", body) { json =>
      json.hcursor.get[String]("response") match
        case Right(text) => Consequence.success(GenerateResponse(text))
        case Left(e) => Consequence.valueInvalid(e.getMessage)
    }

  private def _request_with_fallback[A](
    endpoints: Vector[URI],
    path: String,
    body: Json
  )(extract: Json => Consequence[A]): Consequence[A] =
    endpoints.toList match
      case Nil => Consequence.serviceUnavailable("Gemma/Ollama request failed")
      case x :: xs =>
        HttpSupport.post(_client, x, path, body, config.timeoutSeconds).flatMap(extract) recoverWith {
          case _ => _request_with_fallback(xs.toVector, path, body)(extract)
        }

final class GemmaOllamaChatService(config: GemmaRuntimeConfig) extends ChatService:
  private val _client = HttpSupport.client(config.timeoutSeconds)

  override def chat(req: ChatRequest): Consequence[ChatResponse] =
    val body = Json.obj(
      "model" -> Json.fromString(config.model),
      "messages" -> Json.fromValues(
        req.messages.map { message =>
          Json.obj(
            "role" -> Json.fromString(message.role.toString.toLowerCase),
            "content" -> Json.fromString(message.content)
          )
        }
      ),
      "stream" -> Json.False
    )
    _request_with_fallback(GemmaSupport.endpoints(config), "/api/chat", body) { json =>
      json.hcursor.downField("message").get[String]("content") match
        case Right(text) => Consequence.success(ChatResponse(Message(MessageRole.Assistant, text)))
        case Left(e) => Consequence.valueInvalid(e.getMessage)
    }

  private def _request_with_fallback[A](
    endpoints: Vector[URI],
    path: String,
    body: Json
  )(extract: Json => Consequence[A]): Consequence[A] =
    endpoints.toList match
      case Nil => Consequence.serviceUnavailable("Gemma/Ollama request failed")
      case x :: xs =>
        HttpSupport.post(_client, x, path, body, config.timeoutSeconds).flatMap(extract) recoverWith {
          case _ => _request_with_fallback(xs.toVector, path, body)(extract)
        }

final class GemmaGenerateExtensionPoint(config: GemmaRuntimeConfig)
  extends ExtensionPoint[GenerateService]:
  override def supports(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "generate-service" &&
      variation.provider.contains("gemma") &&
      variation.engine.contains("ollama")

  override def provide(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Consequence[GenerateService] =
    Consequence.success(new GemmaOllamaGenerateService(config))

final class GemmaChatExtensionPoint(config: GemmaRuntimeConfig)
  extends ExtensionPoint[ChatService]:
  override def supports(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "chat-service" &&
      variation.provider.contains("gemma") &&
      variation.engine.contains("ollama")

  override def provide(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Consequence[ChatService] =
    Consequence.success(new GemmaOllamaChatService(config))

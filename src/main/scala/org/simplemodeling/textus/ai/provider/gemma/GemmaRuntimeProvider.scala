package org.simplemodeling.textus.ai.provider.gemma

import java.net.URI

import io.circe.Json
import scala.util.Try
import org.goldenport.Consequence
import org.goldenport.cncf.component.{ExtensionPoint, ServiceContract, VariationSelection}
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.configuration.ResolvedConfiguration
import org.goldenport.protocol.Property
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.ai.ai.*
import org.simplemodeling.textus.ai.runtime.{AiRequestProperties, ChatService, GenerateService, HttpSupport}

final case class GemmaRuntimeConfig(
  provider: String = "gemma",
  mode: String = "local",
  engine: String = "ollama",
  endpoint: URI,
  fallbackEndpoint: Option[URI] = None,
  model: String = "gemma:2b",
  timeoutSeconds: Long = 30L,
  maxConcurrency: Int = 2,
  bootstrap: Option[OllamaDockerBootstrap] = None
)

object GemmaConfig:
  val default: GemmaRuntimeConfig = GemmaRuntimeConfig(
    endpoint = URI.create("http://ollama:11434")
  )

  def fromConfiguration(
    configuration: ResolvedConfiguration
  ): Option[GemmaRuntimeConfig] = {
    val endpoint = _config_string(configuration, "endpoint")
    val fallbackendpoint = _config_string(configuration, "fallback-endpoint", "fallbackEndpoint")
    val provider = _config_string(configuration, "provider")
    val mode = _config_string(configuration, "mode")
    val engine = _config_string(configuration, "engine")
    val model = _config_string(configuration, "model")
    val timeout = _config_string(configuration, "timeout-seconds", "timeoutSeconds")
    val concurrency = _config_string(configuration, "max-concurrency", "maxConcurrency")
    Option.when(
      Vector(endpoint, fallbackendpoint, provider, mode, engine, model, timeout, concurrency).exists(_.nonEmpty)
    )(
      GemmaRuntimeConfig(
        provider = provider.getOrElse("gemma"),
        mode = mode.getOrElse("local"),
        engine = engine.getOrElse("ollama"),
        endpoint = URI.create(endpoint.getOrElse("http://ollama:11434")),
        fallbackEndpoint = fallbackendpoint.map(URI.create),
        model = model.getOrElse("gemma:2b"),
        timeoutSeconds = timeout.flatMap(_.toLongOption).getOrElse(30L),
        maxConcurrency = concurrency.flatMap(_.toIntOption).getOrElse(2)
      )
    )
  }

  def endpointFromConfiguration(
    configuration: ResolvedConfiguration
  ): Option[String] =
    _config_string(configuration, "endpoint")

  private def _config_string(
    configuration: ResolvedConfiguration,
    leaves: String*
  ): Option[String] =
    leaves.iterator
      .flatMap { leaf =>
        Vector(
          s"textus.ai.gemma.$leaf",
          s"textus.runtime.ai.gemma.$leaf",
          s"cncf.ai.gemma.$leaf",
          s"cncf.runtime.ai.gemma.$leaf"
        ).iterator
      }
      .flatMap(key => Try(RuntimeConfig.getString(configuration, key)).toOption.flatten)
      .map(_.trim)
      .find(_.nonEmpty)

private object GemmaSupport:
  def endpoints(config: GemmaRuntimeConfig): Vector[URI] =
    config.mode match
      case "remote" => Vector(config.endpoint)
      case _ => Vector(config.endpoint) ++ config.fallbackEndpoint.toVector

  def responseMetadata(json: Json): Map[String, String] = {
    val input = json.hcursor.get[Long]("prompt_eval_count").toOption
    val output = json.hcursor.get[Long]("eval_count").toOption
    Vector(
      "gemma.finish_reason" -> json.hcursor.get[String]("done_reason").toOption,
      "gemma.usage.input_tokens" -> input.map(_.toString),
      "gemma.usage.output_tokens" -> output.map(_.toString),
      "gemma.usage.total_tokens" -> (for x <- input; y <- output yield (x + y).toString)
    ).collect { case (key, Some(value)) if value.trim.nonEmpty => key -> value.trim }.toMap
  }

  def generationOptions(maxTokens: Option[Int]): Json =
    maxTokens
      .map(value => Json.obj(
        "options" -> Json.obj("num_predict" -> Json.fromInt(value))
      ))
      .getOrElse(Json.obj())

final class GemmaOllamaGenerateService(config: GemmaRuntimeConfig, context: ExecutionContext) extends GenerateService:
  override def generate(req: GenerateRequest): Consequence[GenerateResponse] =
    given ExecutionContext = context
    for
      _ <- AiRequestProperties.requireNoUnsupportedTools("gemma", req.properties)
      _ <- config.bootstrap.map(_.ensureC).getOrElse(Consequence.unit)
      model = AiRequestProperties.effectiveModel(config.model, req.properties, "gemma")
      body = Json.obj(
        "model" -> Json.fromString(model),
        "prompt" -> Json.fromString(req.prompt),
        "stream" -> Json.False
      ).deepMerge(GemmaSupport.generationOptions(req.maxTokens))
      response <- _request_with_fallback(GemmaSupport.endpoints(config), "/api/generate", body, req.properties) { json =>
        json.hcursor.get[String]("response") match
          case Right(text) => Consequence.success(GenerateResponse(text, Some(model), GemmaSupport.responseMetadata(json)))
          case Left(e) => Consequence.valueInvalid(e.getMessage)
      }
    yield response

  private def _request_with_fallback[A](
    endpoints: Vector[URI],
    path: String,
    body: Json,
    properties: Vector[Property]
  )(extract: Json => Consequence[A])(using ExecutionContext): Consequence[A] =
    endpoints.toList match
      case Nil => Consequence.serviceUnavailable("Gemma/Ollama request failed")
      case x :: xs =>
        HttpSupport.post(
          x,
          path,
          body,
          AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, properties),
          properties = properties
        ).flatMap(extract) recoverWith {
          case conclusion if xs.nonEmpty => _request_with_fallback(xs.toVector, path, body, properties)(extract)
          case conclusion => Consequence.Failure(conclusion)
        }

final class GemmaOllamaChatService(config: GemmaRuntimeConfig, context: ExecutionContext) extends ChatService:
  override def chat(req: ChatRequest): Consequence[ChatResponse] =
    given ExecutionContext = context
    for
      _ <- AiRequestProperties.requireNoUnsupportedTools("gemma", req.properties)
      _ <- config.bootstrap.map(_.ensureC).getOrElse(Consequence.unit)
      model = AiRequestProperties.effectiveModel(config.model, req.properties, "gemma")
      body = Json.obj(
        "model" -> Json.fromString(model),
        "messages" -> Json.fromValues(
          req.messages.map { message =>
            Json.obj(
              "role" -> Json.fromString(message.role.toString.toLowerCase),
              "content" -> Json.fromString(message.content)
            )
          }
        ),
        "stream" -> Json.False
      ).deepMerge(GemmaSupport.generationOptions(req.maxTokens))
      response <- _request_with_fallback(GemmaSupport.endpoints(config), "/api/chat", body, req.properties) { json =>
        json.hcursor.downField("message").get[String]("content") match
          case Right(text) => Consequence.success(ChatResponse(Message(MessageRole.Assistant, text), Some(model), GemmaSupport.responseMetadata(json)))
          case Left(e) => Consequence.valueInvalid(e.getMessage)
      }
    yield response

  private def _request_with_fallback[A](
    endpoints: Vector[URI],
    path: String,
    body: Json,
    properties: Vector[Property]
  )(extract: Json => Consequence[A])(using ExecutionContext): Consequence[A] =
    endpoints.toList match
      case Nil => Consequence.serviceUnavailable("Gemma/Ollama request failed")
      case x :: xs =>
        HttpSupport.post(
          x,
          path,
          body,
          AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, properties),
          properties = properties
        ).flatMap(extract) recoverWith {
          case conclusion if xs.nonEmpty => _request_with_fallback(xs.toVector, path, body, properties)(extract)
          case conclusion => Consequence.Failure(conclusion)
        }

final class GemmaGenerateExtensionPoint(config: GemmaRuntimeConfig)
  extends ExtensionPoint[GenerateService]:
  override def supports(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "generate-service" &&
      variation.provider.contains("gemma") &&
      variation.engine.contains("ollama")

  override def provide(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Consequence[GenerateService] =
    Consequence.success(new GemmaOllamaGenerateService(config, summon[ExecutionContext]))

final class GemmaChatExtensionPoint(config: GemmaRuntimeConfig)
  extends ExtensionPoint[ChatService]:
  override def supports(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "chat-service" &&
      variation.provider.contains("gemma") &&
      variation.engine.contains("ollama")

  override def provide(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Consequence[ChatService] =
    Consequence.success(new GemmaOllamaChatService(config, summon[ExecutionContext]))

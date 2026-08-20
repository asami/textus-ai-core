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
import org.simplemodeling.textus.airuntime.ai.*
import org.simplemodeling.textus.ai.runtime.{AiRequestProperties, ChatService, GenerateService, HttpSupport, ToolCallingChatService}

/*
 * @since   Jul.  2, 2026
 * @version Aug. 21, 2026
 * @author  ASAMI, Tomoharu
 */

final case class GemmaRuntimeConfig(
  provider: String = "gemma",
  mode: String = "local",
  engine: String = "ollama",
  endpoint: URI,
  runtime: String = "native",
  fallbackEndpoint: Option[URI] = None,
  model: String = "gemma:2b",
  timeoutSeconds: Long = 30L,
  maxConcurrency: Int = 2,
  bootstrap: Option[OllamaManagedServiceBootstrap] = None,
  configurationError: Option[String] = None
)

object GemmaConfig:
  val default: GemmaRuntimeConfig = GemmaRuntimeConfig(
    endpoint = URI.create("http://127.0.0.1:11434")
  )

  def fromConfiguration(
    configuration: ResolvedConfiguration
  ): Option[GemmaRuntimeConfig] = {
    val endpoint = _config_string(configuration, "endpoint")
    val fallbackendpoint = _config_string(configuration, "fallback-endpoint", "fallbackEndpoint")
    val provider = _config_string(configuration, "provider")
    val mode = _config_string(configuration, "mode")
    val engine = _config_string(configuration, "engine")
    val runtime = _config_string(configuration, "runtime")
    val model = _config_string(configuration, "model")
    val timeout = _config_string(configuration, "timeout-seconds", "timeoutSeconds")
    val concurrency = _config_string(configuration, "max-concurrency", "maxConcurrency")
    Option.when(
      Vector(endpoint, fallbackendpoint, provider, mode, engine, runtime, model, timeout, concurrency).exists(_.nonEmpty)
    )(
      {
        val selectedruntime = runtime.map(_.toLowerCase(java.util.Locale.ROOT)).getOrElse("native")
        val configurationerror = selectedruntime match {
          case "native" => None
          case "managed-docker" if endpoint.isEmpty => None
          case "managed-docker" => Some("Gemma managed-docker runtime may not set textus.ai.gemma.endpoint")
          case other => Some(s"Unsupported Gemma runtime: $other")
        }
        GemmaRuntimeConfig(
          provider = provider.getOrElse("gemma"),
          mode = mode.getOrElse("local"),
          engine = engine.getOrElse("ollama"),
          endpoint = URI.create(endpoint.getOrElse("http://127.0.0.1:11434")),
          runtime = selectedruntime,
          fallbackEndpoint = fallbackendpoint.map(URI.create),
          model = model.getOrElse("gemma:2b"),
          timeoutSeconds = timeout.flatMap(_.toLongOption).getOrElse(30L),
          maxConcurrency = concurrency.flatMap(_.toIntOption).getOrElse(2),
          configurationError = configurationerror
        )
      }
    )
  }

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
  def requireValidConfiguration(config: GemmaRuntimeConfig): Consequence[Unit] =
    config.configurationError.map(Consequence.configurationInvalid).getOrElse(Consequence.unit)

  def endpoints(
    config: GemmaRuntimeConfig,
    primary: URI
  ): Vector[URI] =
    config.mode match
      case "remote" => Vector(primary)
      case _ => Vector(primary) ++ config.fallbackEndpoint.toVector.filterNot(_ == primary)

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

  def request[A](
    config: GemmaRuntimeConfig,
    primary: URI,
    path: String,
    body: Json,
    properties: Vector[Property]
  )(extract: Json => Consequence[A])(using context: ExecutionContext): Consequence[A] =
    _request(config, GemmaSupport.endpoints(config, primary), path, body, properties)(extract)

  private def _request[A](
    config: GemmaRuntimeConfig,
    endpoints: Vector[URI],
    path: String,
    body: Json,
    properties: Vector[Property]
  )(extract: Json => Consequence[A])(using context: ExecutionContext): Consequence[A] =
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
          case conclusion if xs.nonEmpty => _request(config, xs.toVector, path, body, properties)(extract)
          case conclusion => Consequence.Failure(conclusion)
        }

final class GemmaOllamaGenerateService(config: GemmaRuntimeConfig, context: ExecutionContext) extends GenerateService:
  override def generate(req: GenerateRequest): Consequence[GenerateResponse] =
    given ExecutionContext = context
    for
      _ <- GemmaSupport.requireValidConfiguration(config)
      _ <- AiRequestProperties.requireNoUnsupportedTools("gemma", req.properties)
      endpoint <- config.bootstrap.map(_.ensureC).getOrElse(Consequence.success(config.endpoint))
      model = AiRequestProperties.effectiveModel(config.model, req.properties, "gemma")
      body = Json.obj(
        "model" -> Json.fromString(model),
        "prompt" -> Json.fromString(req.prompt),
        "stream" -> Json.False
      ).deepMerge(GemmaSupport.generationOptions(req.maxTokens))
      response <- GemmaSupport.request(config, endpoint, "/api/generate", body, req.properties) { json =>
        json.hcursor.get[String]("response") match
          case Right(text) => Consequence.success(GenerateResponse(text, Some(model), GemmaSupport.responseMetadata(json)))
          case Left(e) => Consequence.valueInvalid(e.getMessage)
      }
    yield response

final class GemmaOllamaChatService(config: GemmaRuntimeConfig, context: ExecutionContext) extends ToolCallingChatService:
  override def chat(req: ChatRequest): Consequence[ChatResponse] =
    given ExecutionContext = context
    for
      _ <- GemmaSupport.requireValidConfiguration(config)
      _ <- AiRequestProperties.requireNoUnsupportedTools("gemma", req.properties)
      endpoint <- config.bootstrap.map(_.ensureC).getOrElse(Consequence.success(config.endpoint))
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
      response <- GemmaSupport.request(config, endpoint, "/api/chat", body, req.properties) { json =>
        json.hcursor.downField("message").get[String]("content") match
          case Right(text) => Consequence.success(ChatResponse(Message(MessageRole.Assistant, text), Some(model), GemmaSupport.responseMetadata(json)))
          case Left(e) => Consequence.valueInvalid(e.getMessage)
      }
    yield response

  override def chatWithTools(req: ToolChatRequest): Consequence[ToolChatResponse] =
    given ExecutionContext = context
    for
      _ <- GemmaSupport.requireValidConfiguration(config)
      endpoint <- config.bootstrap.map(_.ensureC).getOrElse(Consequence.success(config.endpoint))
      model = AiRequestProperties.effectiveModel(config.model, req.properties, "gemma")
      body = Json.obj(
        "model" -> Json.fromString(model),
        "messages" -> Json.fromValues(req.messages.map(_tool_message_json)),
        "tools" -> Json.fromValues(req.tools.map { tool =>
          Json.obj("type" -> Json.fromString("function"), "function" -> Json.obj(
            "name" -> Json.fromString(tool.name),
            "description" -> tool.description.map(Json.fromString).getOrElse(Json.Null),
            "parameters" -> tool.inputSchema
          ))
        }),
        "stream" -> Json.False
      ).deepMerge(GemmaSupport.generationOptions(req.maxTokens))
      response <- GemmaSupport.request(config, endpoint, "/api/chat", body, req.properties) { json =>
        val cursor = json.hcursor.downField("message")
        for
          content <- cursor.get[String]("content").orElse(Right("")) match
            case Right(value) => Consequence.success(value)
            case Left(error) => Consequence.valueInvalid(error.getMessage)
          calls <- cursor.get[Vector[Json]]("tool_calls").getOrElse(Vector.empty).foldLeft(
            Consequence.success(Vector.empty[ToolCall])
          ) { (z, call) =>
            for
              values <- z
              function <- call.hcursor.downField("function").focus.map(Consequence.success).getOrElse(
                Consequence.valueInvalid("Gemma tool call has no function")
              )
              name <- function.hcursor.get[String]("name") match
                case Right(value) if value.trim.nonEmpty => Consequence.success(value.trim)
                case _ => Consequence.valueInvalid("Gemma tool call has no function name")
              arguments <- function.hcursor.get[Json]("arguments") match
                case Right(value) => Consequence.success(value)
                case Left(_) => Consequence.valueInvalid("Gemma tool call has no arguments")
            yield values :+ ToolCall(name, arguments)
          }
        yield ToolChatResponse(
          ToolChatMessage("assistant", content, calls),
          Some(model),
          GemmaSupport.responseMetadata(json)
        )
      }
    yield response

  private def _tool_message_json(message: ToolChatMessage): Json = {
    val base = Json.obj(
      "role" -> Json.fromString(message.role),
      "content" -> Json.fromString(message.content)
    )
    val withcalls = Option.when(message.toolCalls.nonEmpty)(Json.fromValues(message.toolCalls.map { call =>
      Json.obj("function" -> Json.obj(
        "name" -> Json.fromString(call.name),
        "arguments" -> call.arguments
      ))
    })).map(value => base.deepMerge(Json.obj("tool_calls" -> value))).getOrElse(base)
    message.toolName.map(value => withcalls.deepMerge(Json.obj("tool_name" -> Json.fromString(value)))).getOrElse(withcalls)
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

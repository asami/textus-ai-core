package org.simplemodeling.textus.ai.gemma

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

import org.goldenport.Consequence
import org.goldenport.cncf.component.*
import org.goldenport.cncf.context.ExecutionContext
import org.simplemodeling.textus.ai.ai.*

final case class GenerateRequirement(
  provider: Option[String] = Some("gemma"),
  mode: Option[String] = Some("local"),
  engine: Option[String] = Some("ollama")
)

trait GenerateService:
  def generate(req: GenerateRequest): Consequence[GenerateResponse]

trait ChatService:
  def chat(req: ChatRequest): Consequence[ChatResponse]

trait GeneratePortApi extends PortApi[GenerateRequirement, GenerateService]:
  private val contract =
    ServiceContract[GenerateService](
      name = "generate-service",
      runtimeClass = classOf[GenerateService]
    )

  override def resolve(req: GenerateRequirement): Consequence[ServiceContract[GenerateService]] =
    Consequence.success(contract)

trait ChatPortApi extends PortApi[GenerateRequirement, ChatService]:
  private val contract =
    ServiceContract[ChatService](
      name = "chat-service",
      runtimeClass = classOf[ChatService]
    )

  override def resolve(req: GenerateRequirement): Consequence[ServiceContract[ChatService]] =
    Consequence.success(contract)

trait GenerateVariationPoint extends VariationPoint[GenerateRequirement]:
  override def current(req: GenerateRequirement)(using ExecutionContext): Consequence[VariationSelection] =
    Consequence.success(
      VariationSelection(
        provider = req.provider,
        mode = req.mode,
        engine = req.engine
      )
    )

  override def inject(
    req: GenerateRequirement,
    selection: VariationSelection
  )(using ExecutionContext): Consequence[GenerateRequirement] =
    Consequence.success(
      req.copy(
        provider = selection.provider.orElse(req.provider),
        mode = selection.mode.orElse(req.mode),
        engine = selection.engine.orElse(req.engine)
      )
    )

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

private def gemmaEndpoints(config: GemmaRuntimeConfig): Vector[URI] =
  config.mode match
    case "remote" => Vector(config.endpoint)
    case "local-first" => Vector(config.endpoint) ++ config.fallbackEndpoint.toVector
    case _ => Vector(config.endpoint) ++ config.fallbackEndpoint.toVector

final class GemmaOllamaGenerateService(config: GemmaRuntimeConfig) extends GenerateService:
  private val client =
    HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(config.timeoutSeconds))
      .build()

  override def generate(req: GenerateRequest): Consequence[GenerateResponse] = {
    val body = JsonCodec.generateRequest(req, config.model)
    val endpoints = gemmaEndpoints(config)
    requestWithFallback(
      endpoints,
      "/api/generate",
      body,
      config.timeoutSeconds
    )(JsonCodec.extractGenerateText).map(GenerateResponse.apply)
  }

  private def requestWithFallback[A](
    endpoints: Vector[URI],
    path: String,
    body: String,
    timeoutSeconds: Long
  )(extract: String => A): Consequence[A] =
    endpoints.foldLeft(Option.empty[A]) { (acc, endpoint) =>
      acc.orElse {
        val request =
          HttpRequest.newBuilder(endpoint.resolve(path))
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        try {
          val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
          if response.statusCode() / 100 != 2 then None
          else Some(extract(response.body()))
        } catch {
          case _: Exception => None
        }
      }
    } match {
      case Some(value) => Consequence.success(value)
      case None => Consequence.failure(s"Gemma/Ollama request failed for $path")
    }

final class GemmaOllamaChatService(config: GemmaRuntimeConfig) extends ChatService:
  private val client =
    HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(config.timeoutSeconds))
      .build()

  override def chat(req: ChatRequest): Consequence[ChatResponse] = {
    val body = JsonCodec.chatRequest(req, config.model)
    val endpoints = gemmaEndpoints(config)
    requestWithFallback(
      endpoints,
      "/api/chat",
      body,
      config.timeoutSeconds
    )(JsonCodec.extractChatMessage).map(ChatResponse.apply)
  }

  private def requestWithFallback[A](
    endpoints: Vector[URI],
    path: String,
    body: String,
    timeoutSeconds: Long
  )(extract: String => A): Consequence[A] =
    endpoints.foldLeft(Option.empty[A]) { (acc, endpoint) =>
      acc.orElse {
        val request =
          HttpRequest.newBuilder(endpoint.resolve(path))
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        try {
          val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
          if response.statusCode() / 100 != 2 then None
          else Some(extract(response.body()))
        } catch {
          case _: Exception => None
        }
      }
    } match {
      case Some(value) => Consequence.success(value)
      case None => Consequence.failure(s"Gemma/Ollama request failed for $path")
    }

final class GemmaGenerateExtensionPoint(config: GemmaRuntimeConfig)
  extends ExtensionPoint[GenerateService]:
  override def supports(
    contract: ServiceContract[GenerateService],
    variation: VariationSelection
  )(using ExecutionContext): Boolean =
    contract.name == "generate-service" &&
      variation.provider.contains("gemma") &&
      variation.mode.contains("local") &&
      variation.engine.contains("ollama")

  override def provide(
    contract: ServiceContract[GenerateService],
    variation: VariationSelection
  )(using ExecutionContext): Consequence[GenerateService] =
    Consequence.success(new GemmaOllamaGenerateService(config))

final class GemmaChatExtensionPoint(config: GemmaRuntimeConfig)
  extends ExtensionPoint[ChatService]:
  override def supports(
    contract: ServiceContract[ChatService],
    variation: VariationSelection
  )(using ExecutionContext): Boolean =
    contract.name == "chat-service" &&
      variation.provider.contains("gemma") &&
      variation.mode.contains("local") &&
      variation.engine.contains("ollama")

  override def provide(
    contract: ServiceContract[ChatService],
    variation: VariationSelection
  )(using ExecutionContext): Consequence[ChatService] =
    Consequence.success(new GemmaOllamaChatService(config))

object GemmaGenerateBinding:
  def create(config: GemmaRuntimeConfig): Component.Binding[GenerateRequirement, GenerateService] =
    Component.Binding(
      Port(
        api = new GeneratePortApi {},
        spi = Vector(new GemmaGenerateExtensionPoint(config)),
        variation = new GenerateVariationPoint {}
      )
    )

  def register(component: Component, config: GemmaRuntimeConfig): Component =
    component.withBinding("generate", create(config))

  def install(component: Component, req: GenerateRequirement)(using ExecutionContext): Consequence[Component] =
    component.install_binding[GenerateRequirement, GenerateService]("generate", req)

object GemmaChatBinding:
  def create(config: GemmaRuntimeConfig): Component.Binding[GenerateRequirement, ChatService] =
    Component.Binding(
      Port(
        api = new ChatPortApi {},
        spi = Vector(new GemmaChatExtensionPoint(config)),
        variation = new GenerateVariationPoint {}
      )
    )

  def register(component: Component, config: GemmaRuntimeConfig): Component =
    component.withBinding("chat", create(config))

  def install(component: Component, req: GenerateRequirement)(using ExecutionContext): Consequence[Component] =
    component.install_binding[GenerateRequirement, ChatService]("chat", req)

object GemmaConfig:
  def fromEnvironment(): GemmaRuntimeConfig =
    val endpoint = sys.env.getOrElse("AI_LLM_ENDPOINT", "http://ollama:11434")
    val fallback = sys.env.get("AI_LLM_FALLBACK_ENDPOINT").orElse(sys.env.get("AI_LLM_REMOTE_ENDPOINT"))
    val model = sys.env.getOrElse("AI_LLM_MODEL", "gemma:2b")
    val timeout = sys.env.get("AI_LLM_TIMEOUT_SECONDS").flatMap(_.toLongOption).getOrElse(30L)
    val concurrency = sys.env.get("AI_LLM_MAX_CONCURRENCY").flatMap(_.toIntOption).getOrElse(2)
    GemmaRuntimeConfig(
      provider = sys.env.getOrElse("AI_LLM_PROVIDER", "gemma"),
      mode = sys.env.getOrElse("AI_LLM_MODE", "local"),
      engine = sys.env.getOrElse("AI_LLM_LOCAL_ENGINE", "ollama"),
      endpoint = URI.create(endpoint),
      fallbackEndpoint = fallback.map(URI.create),
      model = model,
      timeoutSeconds = timeout,
      maxConcurrency = concurrency
    )

private object JsonCodec:
  def generateRequest(request: GenerateRequest, model: String): String =
    val prompt = escape(request.prompt)
    val temperature = request.temperature.map(_.toString).getOrElse("null")
    val maxTokens = request.maxTokens.map(_.toString).getOrElse("null")
    s"""{"model":"$model","prompt":"$prompt","temperature":$temperature,"max_tokens":$maxTokens,"stream":false}"""

  def extractGenerateText(json: String): String =
    val needle = """"response":"""
    val idx = json.indexOf(needle)
    if idx < 0 then
      throw new IllegalStateException("Missing response field")
    val start = json.indexOf('"', idx + needle.length)
    val end = json.indexOf('"', start + 1)
    if start < 0 || end < 0 then
      throw new IllegalStateException("Invalid response field")
    json.substring(start + 1, end)

  def chatRequest(request: ChatRequest, model: String): String =
    val messages = request.messages.map { message =>
      val content = escape(message.content)
      val role = escape(message.role.toString.toLowerCase)
      s"""{"role":"$role","content":"$content"}"""
    }.mkString(",")
    s"""{"model":"$model","messages":[$messages],"stream":false}"""

  def extractChatMessage(json: String): Message =
    val content = extractNestedField(json, "content")
    Message(MessageRole.Assistant, content)

  private def extractNestedField(json: String, field: String): String =
    val needle = s""""$field":"""
    val idx = json.indexOf(needle)
    if idx < 0 then
      throw new IllegalStateException(s"Missing $field field")
    val start = json.indexOf('"', idx + needle.length)
    val end = json.indexOf('"', start + 1)
    if start < 0 || end < 0 then
      throw new IllegalStateException(s"Invalid $field field")
    json.substring(start + 1, end)

  private def escape(text: String): String =
    text
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")

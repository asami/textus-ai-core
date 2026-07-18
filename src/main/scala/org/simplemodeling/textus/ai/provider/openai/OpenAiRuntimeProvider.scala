package org.simplemodeling.textus.ai.provider.openai

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

final case class OpenAiRuntimeConfig(
  provider: String = "openai",
  mode: String = "remote",
  engine: String = "gpt",
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

  def responseGenerateRequest(request: GenerateRequest, model: String): Json =
    _response_request(model, Json.fromString(request.prompt), request.properties, request.maxTokens)

  def responseChatRequest(request: ChatRequest, model: String): Json =
    val input = Json.fromValues(
      request.messages.map { message =>
        Json.obj(
          "role" -> Json.fromString(_role(message.role)),
          "content" -> Json.fromString(message.content)
        )
      }
    )
    _response_request(model, input, request.properties, request.maxTokens)

  def extractResponseText(json: Json): Consequence[String] =
    json.hcursor.get[String]("output_text") match
      case Right(s) => Consequence.success(s)
      case Left(_) =>
        _string_at(json.hcursor, List("output", "0", "content", "0", "text"))

  def responseMetadata(json: Json, properties: Vector[org.goldenport.protocol.Property]): Map[String, String] =
    val tools = AiRequestProperties.tools(properties)
    val webcalls =
      json.hcursor.downField("output").focus.flatMap(_.asArray).getOrElse(Vector.empty)
        .count(_.hcursor.get[String]("type").toOption.contains("web_search_call"))
    Map(
      "ai.tools" -> tools.map(_.id).mkString(","),
      "ai.provider_tools" -> _provider_tools(tools, properties).map(_._1).mkString(","),
      "openai.web_search_calls" -> webcalls.toString
    ) ++ _response_facts(json)

  private def _response_request(
    model: String,
    input: Json,
    properties: Vector[org.goldenport.protocol.Property],
    maxTokens: Option[Int]
  ): Json =
    val base = JsonObject(
      "model" -> Json.fromString(model),
      "input" -> input,
      "tools" -> Json.fromValues(_provider_tools(AiRequestProperties.tools(properties), properties).map(_._2))
    )
    Json.fromJsonObject(_with_response_options(base, properties, maxTokens))

  private def _provider_tools(
    tools: Vector[AiTool],
    properties: Vector[org.goldenport.protocol.Property]
  ): Vector[(String, Json)] =
    Option.when(tools.exists(t => t == AiTool.WebSearch || t == AiTool.UrlContext)) {
      "web_search" -> Json.fromJsonObject(
        _with_openai_web_search_options(JsonObject("type" -> Json.fromString("web_search")), properties)
      )
    }.toVector

  private def _with_response_options(
    base: JsonObject,
    properties: Vector[org.goldenport.protocol.Property],
    maxTokens: Option[Int]
  ): JsonObject = {
    val withmax = maxTokens.map(value => base.add("max_output_tokens", Json.fromInt(value))).getOrElse(base)
    AiRequestProperties.string(properties, Vector(
      "ai.openai.reasoning.effort",
      "textus.ai.openai.reasoning.effort",
      "openai.reasoning.effort"
    )).map(value =>
      withmax.add("reasoning", Json.obj("effort" -> Json.fromString(value)))
    ).getOrElse(withmax)
  }

  private def _with_openai_web_search_options(
    base: JsonObject,
    properties: Vector[org.goldenport.protocol.Property]
  ): JsonObject =
    Vector(
      "search_context_size" -> AiRequestProperties.string(properties, Vector(
        "ai.openai.web_search.search_context_size",
        "ai.openai.web-search.search-context-size",
        "textus.ai.openai.web_search.search_context_size",
        "openai.web_search.search_context_size"
      )),
      "return_token_budget" -> AiRequestProperties.string(properties, Vector(
        "ai.openai.web_search.return_token_budget",
        "ai.openai.web-search.return-token-budget",
        "textus.ai.openai.web_search.return_token_budget",
        "openai.web_search.return_token_budget"
      ))
    ).foldLeft(base) {
      case (z, (key, Some(value))) => z.add(key, Json.fromString(value))
      case (z, _) => z
    }

  private def _with_generation_options(
    base: JsonObject,
    temperature: Option[Double],
    maxTokens: Option[Int]
  ): JsonObject =
    val withTemperature = temperature.map(x => base.add("temperature", Json.fromDoubleOrNull(x))).getOrElse(base)
    maxTokens.map(x => withTemperature.add("max_completion_tokens", Json.fromInt(x))).getOrElse(withTemperature)

  private def _response_facts(json: Json): Map[String, String] =
    _optional_facts(Vector(
      "openai.response_id" -> json.hcursor.get[String]("id").toOption,
      "openai.finish_reason" -> _string_at_paths(json.hcursor, Vector(
        List("choices", "0", "finish_reason"),
        List("incomplete_details", "reason")
      )),
      "openai.usage.input_tokens" -> _long_at_paths(json.hcursor, Vector(
        List("usage", "input_tokens"),
        List("usage", "prompt_tokens")
      )),
      "openai.usage.output_tokens" -> _long_at_paths(json.hcursor, Vector(
        List("usage", "output_tokens"),
        List("usage", "completion_tokens")
      )),
      "openai.usage.cached_input_tokens" -> _long_at_paths(json.hcursor, Vector(
        List("usage", "input_tokens_details", "cached_tokens"),
        List("usage", "prompt_tokens_details", "cached_tokens")
      )),
      "openai.usage.reasoning_tokens" -> _long_at_paths(json.hcursor, Vector(
        List("usage", "output_tokens_details", "reasoning_tokens"),
        List("usage", "completion_tokens_details", "reasoning_tokens")
      )),
      "openai.usage.total_tokens" -> _long_at_paths(json.hcursor, Vector(List("usage", "total_tokens")))
    ))

  private def _optional_facts(values: Vector[(String, Option[String])]): Map[String, String] =
    values.collect { case (key, Some(value)) if value.trim.nonEmpty => key -> value.trim }.toMap

  private def _string_at_paths(cursor: HCursor, paths: Vector[List[String]]): Option[String] =
    paths.iterator.flatMap(path => _cursor_at(cursor, path).flatMap(_.as[String].toOption)).map(_.trim).find(_.nonEmpty)

  private def _long_at_paths(cursor: HCursor, paths: Vector[List[String]]): Option[String] =
    paths.iterator.flatMap(path => _cursor_at(cursor, path).flatMap(_.as[Long].toOption)).map(_.toString).toSeq.headOption

  private def _cursor_at(cursor: HCursor, path: List[String]): Option[ACursor] =
    path.foldLeft(Option(cursor: ACursor)) { (z, key) =>
      z.map { c =>
        key.toIntOption match
          case Some(i) => c.downN(i)
          case None => c.downField(key)
      }
    }.filter(_.succeeded)

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
    val model = AiRequestProperties.effectiveModel(config.model, req.properties, "openai")
    AiRequestProperties.validateTools(req.properties).flatMap { tools =>
    if (tools.isEmpty)
      HttpSupport.post(
        config.endpoint,
        "/v1/chat/completions",
        OpenAiJson.generateRequest(req, model),
        AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
        headers = Vector("Authorization" -> s"Bearer ${config.apiKey}"),
        properties = req.properties
      ).flatMap { json =>
        OpenAiJson.extractText(json).map(text => GenerateResponse(text, Some(model), OpenAiJson.responseMetadata(json, req.properties)))
      }
    else
      HttpSupport.post(
        config.endpoint,
        "/v1/responses",
        OpenAiJson.responseGenerateRequest(req, model),
        AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
        headers = Vector("Authorization" -> s"Bearer ${config.apiKey}"),
        properties = req.properties
      ).flatMap { json =>
        OpenAiJson.extractResponseText(json).map { text =>
          GenerateResponse(text, Some(model), OpenAiJson.responseMetadata(json, req.properties))
        }
      }
    }

final class OpenAiChatService(config: OpenAiRuntimeConfig, context: ExecutionContext) extends ChatService:
  override def chat(req: ChatRequest): Consequence[ChatResponse] =
    given ExecutionContext = context
    val model = AiRequestProperties.effectiveModel(config.model, req.properties, "openai")
    AiRequestProperties.validateTools(req.properties).flatMap { tools =>
    if (tools.isEmpty)
      HttpSupport.post(
        config.endpoint,
        "/v1/chat/completions",
        OpenAiJson.chatRequest(req, model),
        AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
        headers = Vector("Authorization" -> s"Bearer ${config.apiKey}"),
        properties = req.properties
      ).flatMap { json =>
        OpenAiJson.extractText(json).map { text =>
          ChatResponse(Message(MessageRole.Assistant, text), Some(model), OpenAiJson.responseMetadata(json, req.properties))
        }
      }
    else
      HttpSupport.post(
        config.endpoint,
        "/v1/responses",
        OpenAiJson.responseChatRequest(req, model),
        AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
        headers = Vector("Authorization" -> s"Bearer ${config.apiKey}"),
        properties = req.properties
      ).flatMap { json =>
        OpenAiJson.extractResponseText(json).map { text =>
          ChatResponse(Message(MessageRole.Assistant, text), Some(model), OpenAiJson.responseMetadata(json, req.properties))
        }
      }
    }

final class OpenAiGenerateExtensionPoint(config: OpenAiRuntimeConfig)
  extends ExtensionPoint[GenerateService]:
  override def supports(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "generate-service" &&
      variation.provider.contains("openai") &&
      variation.engine.contains("gpt")

  override def provide(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Consequence[GenerateService] =
    Consequence.success(new OpenAiGenerateService(config, summon[ExecutionContext]))

final class OpenAiChatExtensionPoint(config: OpenAiRuntimeConfig)
  extends ExtensionPoint[ChatService]:
  override def supports(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "chat-service" &&
      variation.provider.contains("openai") &&
      variation.engine.contains("gpt")

  override def provide(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Consequence[ChatService] =
    Consequence.success(new OpenAiChatService(config, summon[ExecutionContext]))

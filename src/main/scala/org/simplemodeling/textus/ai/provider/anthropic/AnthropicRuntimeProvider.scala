package org.simplemodeling.textus.ai.provider.anthropic

import java.net.URI

import io.circe.{Json, JsonObject}
import scala.util.Try
import org.goldenport.Consequence
import org.goldenport.cncf.component.{ExtensionPoint, ServiceContract, VariationSelection}
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.configuration.ResolvedConfiguration
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.ai.ai.*
import org.simplemodeling.textus.ai.runtime.{AiRequestProperties, ChatService, GenerateService, HttpSupport, ToolCallingChatService}

/** Direct Anthropic Messages API provider. Claude Code is a separate local provider. */
final case class AnthropicRuntimeConfig(
  provider: String = "anthropic",
  mode: String = "remote",
  engine: String = "claude",
  endpoint: URI,
  apiKey: String,
  model: String,
  timeoutSeconds: Long = 30L,
  apiVersion: String = "2023-06-01"
)

object AnthropicConfig {
  val defaultModel = "claude-sonnet-4-20250514"

  def fromConfiguration(
    configuration: ResolvedConfiguration
  ): Option[AnthropicRuntimeConfig] =
    for {
      apiKey <- _strings(configuration, "api-key", "apiKey").headOption
    } yield AnthropicRuntimeConfig(
      endpoint = URI.create(_strings(configuration, "endpoint").headOption.getOrElse("https://api.anthropic.com")),
      apiKey = apiKey,
      model = _strings(configuration, "model").headOption.getOrElse(defaultModel),
      timeoutSeconds = _strings(configuration, "timeout-seconds").headOption.flatMap(_.toLongOption).filter(_ > 0).getOrElse(30L),
      apiVersion = _strings(configuration, "api-version").headOption.getOrElse("2023-06-01")
    )

  private def _strings(configuration: ResolvedConfiguration, leaves: String*): Vector[String] =
    leaves.toVector.flatMap { leaf =>
      Vector(
        s"textus.ai.anthropic.$leaf",
        s"textus.runtime.ai.anthropic.$leaf",
        s"cncf.ai.anthropic.$leaf",
        s"cncf.runtime.ai.anthropic.$leaf"
      )
    }.flatMap(key => Try(RuntimeConfig.getString(configuration, key)).toOption.flatten)
      .map(_.trim)
      .filter(_.nonEmpty)
}

private object AnthropicJson {
  def generateRequest(request: GenerateRequest, model: String): Json =
    _request(
      model,
      Json.arr(Json.obj(
        "role" -> Json.fromString("user"),
        "content" -> Json.fromString(request.prompt)
      )),
      request.temperature,
      request.maxTokens
    )

  def chatRequest(request: ChatRequest, model: String): Json = {
    val (system, messages) = request.messages.partition(_.role == MessageRole.System)
    val base = _request(
      model,
      Json.fromValues(messages.map(_message)),
      request.temperature,
      request.maxTokens
    ).asObject.getOrElse(JsonObject.empty)
    Json.fromJsonObject(
      if (system.isEmpty) base
      else base.add("system", Json.fromString(system.map(_.content).mkString("\n")))
    )
  }

  def toolChatRequest(request: ToolChatRequest, model: String): Consequence[Json] =
    _tool_messages_c(request.messages).map { messages =>
      val base = _request(model, Json.fromValues(messages), request.temperature, request.maxTokens)
        .asObject.getOrElse(JsonObject.empty)
      Json.fromJsonObject(base.add("tools", Json.fromValues(request.tools.map(_tool_definition))))
    }

  def extractToolResponse(json: Json): Consequence[ToolChatResponse] = {
    val content = json.hcursor.downField("content").focus.flatMap(_.asArray).getOrElse(Vector.empty)
    val text = content
      .filter(_.hcursor.get[String]("type").toOption.contains("text"))
      .flatMap(_.hcursor.get[String]("text").toOption)
      .mkString
    content.foldLeft(Consequence.success(Vector.empty[ToolCall])) { (z, block) =>
      if (!block.hcursor.get[String]("type").toOption.contains("tool_use"))
        z
      else
        for {
          calls <- z
          id <- block.hcursor.get[String]("id") match {
            case Right(value) if value.trim.nonEmpty => Consequence.success(value.trim)
            case _ => Consequence.valueInvalid("Anthropic tool use has no id")
          }
          name <- block.hcursor.get[String]("name") match {
            case Right(value) if value.trim.nonEmpty => Consequence.success(value.trim)
            case _ => Consequence.valueInvalid("Anthropic tool use has no function name")
          }
          input <- block.hcursor.get[Json]("input") match {
            case Right(value) => Consequence.success(value)
            case Left(_) => Consequence.valueInvalid("Anthropic tool use has no input")
          }
        } yield calls :+ ToolCall(name, input)._with_provider_call_id(Some(id))
    }.map { calls =>
      ToolChatResponse(
        ToolChatMessage("assistant", text, calls),
        json.hcursor.get[String]("model").toOption,
        metadata(json)
      )
    }
  }

  def extractText(json: Json): Consequence[String] = {
    val texts = json.hcursor.downField("content").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      .filter(_.hcursor.get[String]("type").toOption.contains("text"))
      .flatMap(_.hcursor.get[String]("text").toOption)
      .map(_.trim)
      .filter(_.nonEmpty)
    texts.headOption.map(Consequence.success).getOrElse(
      Consequence.valueInvalid("Anthropic response did not contain text content")
    )
  }

  def metadata(json: Json): Map[String, String] =
    val facts = _optional(Vector(
      "anthropic.response_id" -> json.hcursor.get[String]("id").toOption,
      "anthropic.finish_reason" -> json.hcursor.get[String]("stop_reason").toOption,
      "anthropic.usage.input_tokens" -> json.hcursor.downField("usage").get[Long]("input_tokens").toOption.map(_.toString),
      "anthropic.usage.cached_input_tokens" -> json.hcursor.downField("usage").get[Long]("cache_read_input_tokens").toOption.map(_.toString),
      "anthropic.usage.output_tokens" -> json.hcursor.downField("usage").get[Long]("output_tokens").toOption.map(_.toString)
    ))
    val total = for {
      input <- facts.get("anthropic.usage.input_tokens").flatMap(_.toLongOption)
      output <- facts.get("anthropic.usage.output_tokens").flatMap(_.toLongOption)
    } yield input + output
    facts ++ total.map(value => "anthropic.usage.total_tokens" -> value.toString)

  private def _request(
    model: String,
    messages: Json,
    temperature: Option[Double],
    maxTokens: Option[Int]
  ): Json =
    val base = JsonObject(
      "model" -> Json.fromString(model),
      "max_tokens" -> Json.fromInt(maxTokens.getOrElse(1024)),
      "messages" -> messages
    )
    Json.fromJsonObject(temperature.map(value => base.add("temperature", Json.fromDoubleOrNull(value))).getOrElse(base))

  private def _message(message: Message): Json =
    Json.obj(
      "role" -> Json.fromString(message.role match {
        case MessageRole.Assistant => "assistant"
        case _ => "user"
      }),
      "content" -> Json.fromString(message.content)
    )

  private def _tool_definition(tool: ToolDefinition): Json = {
    val base = JsonObject(
      "name" -> Json.fromString(tool.name),
      "input_schema" -> tool.inputSchema
    )
    Json.fromJsonObject(tool.description.map(value => base.add("description", Json.fromString(value))).getOrElse(base))
  }

  private def _tool_messages_c(messages: Vector[ToolChatMessage]): Consequence[Vector[Json]] =
    messages.foldLeft(Consequence.success(Vector.empty[Json])) { (z, message) =>
      z.flatMap { values =>
        _tool_message_c(message).map { rendered =>
          if (message.role == "tool" && values.lastOption.flatMap(_.hcursor.get[String]("role").toOption).contains("user"))
            values.dropRight(1) :+ _append_content(values.last, rendered.hcursor.downField("content").focus.getOrElse(Json.arr()))
          else
            values :+ rendered
        }
      }
    }

  private def _tool_message_c(message: ToolChatMessage): Consequence[Json] = message.role match {
    case "user" => Consequence.success(Json.obj(
      "role" -> Json.fromString("user"),
      "content" -> Json.fromString(message.content)
    ))
    case "assistant" =>
      val blocks = message.content.trim match {
        case "" => Vector.empty
        case value => Vector(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(value)))
      }
      val calls = message.toolCalls.map { call =>
        call._provider_call_id_option.map { id =>
          Json.obj(
            "type" -> Json.fromString("tool_use"),
            "id" -> Json.fromString(id),
            "name" -> Json.fromString(call.name),
            "input" -> call.arguments
          )
        }.toRight(s"Anthropic tool continuation has no provider call id for ${call.name}")
      }
      calls.foldLeft(Consequence.success(Vector.empty[Json])) { (z, call) =>
        for {
          values <- z
          value <- call.fold(Consequence.valueInvalid, Consequence.success)
        } yield values :+ value
      }.map { values =>
        Json.obj(
          "role" -> Json.fromString("assistant"),
          "content" -> Json.fromValues(blocks ++ values)
        )
      }
    case "tool" =>
      for {
        name <- message.toolName.map(Consequence.success).getOrElse(
          Consequence.valueInvalid("Anthropic tool result has no function name")
        )
        id <- message._provider_call_id_option.map(Consequence.success).getOrElse(
          Consequence.valueInvalid(s"Anthropic tool result has no provider call id for $name")
        )
      } yield Json.obj(
        "role" -> Json.fromString("user"),
        "content" -> Json.arr(Json.obj(
          "type" -> Json.fromString("tool_result"),
          "tool_use_id" -> Json.fromString(id),
          "content" -> Json.fromString(message.content)
        ))
      )
    case value => Consequence.valueInvalid(s"Anthropic tool message role is unsupported: $value")
  }

  private def _append_content(message: Json, content: Json): Json =
    message.asObject.map { fields =>
      val current = fields("content").flatMap(_.asArray).getOrElse(Vector.empty)
      Json.fromJsonObject(fields.add("content", Json.fromValues(current ++ content.asArray.getOrElse(Vector.empty))))
    }.getOrElse(message)

  private def _optional(values: Vector[(String, Option[String])]): Map[String, String] =
    values.collect { case (key, Some(value)) if value.trim.nonEmpty => key -> value.trim }.toMap

}

final class AnthropicGenerateService(config: AnthropicRuntimeConfig, context: ExecutionContext) extends GenerateService {
  override def generate(req: GenerateRequest): Consequence[GenerateResponse] =
    given ExecutionContext = context
    for {
      _ <- AiRequestProperties.requireNoUnsupportedTools("anthropic", req.properties)
      _ <- AiRequestProperties.requireNoRecordSchema("anthropic", req.recordSchema)
      model <- Consequence.success(AiRequestProperties.effectiveModel(config.model, req.properties, "anthropic"))
      json <- HttpSupport.post(
        config.endpoint,
        "/v1/messages",
        AnthropicJson.generateRequest(req, model),
        AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
        _headers,
        req.properties
      )
      text <- AnthropicJson.extractText(json)
    } yield GenerateResponse(text, Some(model), AnthropicJson.metadata(json))

  private def _headers: Vector[(String, String)] = Vector(
    "x-api-key" -> config.apiKey,
    "anthropic-version" -> config.apiVersion
  )
}

final class AnthropicChatService(config: AnthropicRuntimeConfig, context: ExecutionContext) extends ToolCallingChatService {
  override def chat(req: ChatRequest): Consequence[ChatResponse] =
    given ExecutionContext = context
    for {
      _ <- AiRequestProperties.requireNoUnsupportedTools("anthropic", req.properties)
      model = AiRequestProperties.effectiveModel(config.model, req.properties, "anthropic")
      json <- HttpSupport.post(
        config.endpoint,
        "/v1/messages",
        AnthropicJson.chatRequest(req, model),
        AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
        _headers,
        req.properties
      )
      text <- AnthropicJson.extractText(json)
    } yield ChatResponse(Message(MessageRole.Assistant, text), Some(model), AnthropicJson.metadata(json))

  override def chatWithTools(req: ToolChatRequest): Consequence[ToolChatResponse] =
    given ExecutionContext = context
    val model = AiRequestProperties.effectiveModel(config.model, req.properties, "anthropic")
    for {
      body <- AnthropicJson.toolChatRequest(req, model)
      json <- HttpSupport.post(
        config.endpoint,
        "/v1/messages",
        body,
        AiRequestProperties.effectiveTimeoutSeconds(config.timeoutSeconds, req.properties),
        _headers,
        req.properties
      )
      response <- AnthropicJson.extractToolResponse(json)
    } yield response.copy(model = Some(model))

  private def _headers: Vector[(String, String)] = Vector(
    "x-api-key" -> config.apiKey,
    "anthropic-version" -> config.apiVersion
  )
}

final class AnthropicGenerateExtensionPoint(config: AnthropicRuntimeConfig) extends ExtensionPoint[GenerateService] {
  override def supports(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "generate-service" && variation.provider.contains("anthropic") && variation.mode.contains("remote") && variation.engine.contains("claude")

  override def provide(contract: ServiceContract[GenerateService], variation: VariationSelection)(using ExecutionContext): Consequence[GenerateService] =
    Consequence.success(new AnthropicGenerateService(config, summon[ExecutionContext]))
}

final class AnthropicChatExtensionPoint(config: AnthropicRuntimeConfig) extends ExtensionPoint[ChatService] {
  override def supports(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Boolean =
    contract.name == "chat-service" && variation.provider.contains("anthropic") && variation.mode.contains("remote") && variation.engine.contains("claude")

  override def provide(contract: ServiceContract[ChatService], variation: VariationSelection)(using ExecutionContext): Consequence[ChatService] =
    Consequence.success(new AnthropicChatService(config, summon[ExecutionContext]))
}

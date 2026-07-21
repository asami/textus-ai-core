package org.simplemodeling.textus.ai.ai

import org.goldenport.protocol.Property
import org.goldenport.record.Record
import io.circe.Json
import org.simplemodeling.model.value.MessageRole

final case class Message(
  role: MessageRole,
  content: String
)

final case class GenerateRequest(
  prompt: String,
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
  properties: Vector[Property] = Vector.empty,
  recordSchema: Option[Record] = None
)

final case class GenerateResponse(
  text: String,
  model: Option[String] = None,
  metadata: Map[String, String] = Map.empty
)

final case class ChatRequest(
  messages: Vector[Message],
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
  properties: Vector[Property] = Vector.empty
)

final case class ChatResponse(
  message: Message,
  model: Option[String] = None,
  metadata: Map[String, String] = Map.empty
)

/** Runtime-owned function definition presented to a tool-capable provider. */
final case class ToolDefinition(
  name: String,
  description: Option[String],
  inputSchema: Json
)

/** Provider-neutral function call requested by a model response. */
final case class ToolCall(
  name: String,
  arguments: Json
) {
  // Provider-only continuation identity must not alter this public value type's ABI.
  private var _provider_call_id: Option[String] = None

  private[textus] def _with_provider_call_id(value: Option[String]): ToolCall = {
    _provider_call_id = value.filter(_.nonEmpty)
    this
  }

  private[textus] def _provider_call_id_option: Option[String] = _provider_call_id
}

/** A tool-loop message. Only the runtime constructs assistant/tool messages. */
final case class ToolChatMessage(
  role: String,
  content: String = "",
  toolCalls: Vector[ToolCall] = Vector.empty,
  toolName: Option[String] = None
) {
  // Kept outside the constructor to preserve the existing public message ABI.
  private var _provider_call_id: Option[String] = None
  // Opaque provider state keeps native continuation items out of public metadata.
  private var _provider_continuation: Option[Json] = None

  private[textus] def _with_provider_call_id(value: Option[String]): ToolChatMessage = {
    _provider_call_id = value.filter(_.nonEmpty)
    this
  }

  private[textus] def _provider_call_id_option: Option[String] = _provider_call_id

  private[textus] def _with_provider_continuation(value: Option[Json]): ToolChatMessage = {
    _provider_continuation = value
    this
  }

  private[textus] def _provider_continuation_option: Option[Json] = _provider_continuation
}

final case class ToolChatRequest(
  messages: Vector[ToolChatMessage],
  tools: Vector[ToolDefinition],
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
  properties: Vector[Property] = Vector.empty
)

final case class ToolChatResponse(
  message: ToolChatMessage,
  model: Option[String] = None,
  metadata: Map[String, String] = Map.empty
)

trait LlmAdapter:
  def generate(request: GenerateRequest): GenerateResponse
  def chat(request: ChatRequest): ChatResponse

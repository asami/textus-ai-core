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
)

/** A tool-loop message. Only the runtime constructs assistant/tool messages. */
final case class ToolChatMessage(
  role: String,
  content: String = "",
  toolCalls: Vector[ToolCall] = Vector.empty,
  toolName: Option[String] = None
)

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

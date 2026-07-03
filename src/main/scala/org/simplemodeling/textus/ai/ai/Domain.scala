package org.simplemodeling.textus.ai.ai

import org.goldenport.protocol.Property
import org.simplemodeling.model.value.MessageRole

final case class Message(
  role: MessageRole,
  content: String
)

final case class GenerateRequest(
  prompt: String,
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
  properties: Vector[Property] = Vector.empty
)

final case class GenerateResponse(
  text: String,
  model: Option[String] = None
)

final case class ChatRequest(
  messages: Vector[Message],
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
  properties: Vector[Property] = Vector.empty
)

final case class ChatResponse(
  message: Message,
  model: Option[String] = None
)

trait LlmAdapter:
  def generate(request: GenerateRequest): GenerateResponse
  def chat(request: ChatRequest): ChatResponse

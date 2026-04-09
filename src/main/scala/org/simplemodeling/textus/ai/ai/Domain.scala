package org.simplemodeling.textus.ai.ai

enum MessageRole:
  case System, User, Assistant

final case class Message(
  role: MessageRole,
  content: String
)

final case class GenerateRequest(
  prompt: String,
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None
)

final case class GenerateResponse(
  text: String
)

final case class ChatRequest(
  messages: Vector[Message]
)

final case class ChatResponse(
  message: Message
)

trait LlmAdapter:
  def generate(request: GenerateRequest): GenerateResponse
  def chat(request: ChatRequest): ChatResponse

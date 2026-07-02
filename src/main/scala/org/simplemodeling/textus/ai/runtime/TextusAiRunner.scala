package org.simplemodeling.textus.ai.runtime

import org.goldenport.Consequence
import org.goldenport.cncf.component.Component
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.{SpiContract, SpiProvider, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.*
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.ai.ai.{ChatRequest, ChatResponse, GenerateRequest, GenerateResponse, Message}

/*
 * CNCF AI runner SPI adapter for the Textus AI runtime bindings.
 *
 * The provider resolves existing generate/chat bindings lazily, so the SPI is
 * an additional publication surface and does not replace the public component
 * operations.
 *
 * @since   Jul.  2, 2026
 * @version Jul.  2, 2026
 * @author  ASAMI, Tomoharu
 */
final class TextusAiRunner(
  provider: TextusAiRunnerProvider,
  selection: SpiSelection
) extends AiRunner {
  def generate(req: AiGenerateRequest)(using ExecutionContext): Consequence[AiGenerateResponse] =
    for {
      service <- provider.generateService(_effective_selection(req.requirement))
      response <- service.generate(
        GenerateRequest(
          prompt = req.prompt,
          temperature = req.temperature,
          maxTokens = req.maxTokens
        )
      )
    } yield _to_ai_generate_response(response)

  def chat(req: AiChatRequest)(using ExecutionContext): Consequence[AiChatResponse] =
    for {
      service <- provider.chatService(_effective_selection(req.requirement))
      response <- service.chat(
        ChatRequest(
          messages = req.messages.map(_to_textus_message)
        )
      )
    } yield _to_ai_chat_response(response)

  private def _effective_selection(
    requirement: AiRunnerRequirement
  ): SpiSelection =
    if (
      requirement.provider.isEmpty &&
      requirement.mode.isEmpty &&
      requirement.engine.isEmpty
    )
      selection
    else
      SpiSelection(
        provider = requirement.provider.orElse(selection.provider),
        mode = requirement.mode.orElse(selection.mode),
        engine = requirement.engine.orElse(selection.engine)
      )

  private def _to_ai_generate_response(
    response: GenerateResponse
  ): AiGenerateResponse =
    AiGenerateResponse(response.text)

  private def _to_ai_chat_response(
    response: ChatResponse
  ): AiChatResponse =
    AiChatResponse(_to_ai_message(response.message))

  private def _to_textus_message(
    message: AiMessage
  ): Message =
    Message(
      role = MessageRole.parse(message.role).getOrElse(MessageRole.User),
      content = message.content
    )

  private def _to_ai_message(
    message: Message
  ): AiMessage =
    AiMessage(
      role = message.role.toString.toLowerCase(java.util.Locale.ROOT),
      content = message.content
    )
}

final class TextusAiRunnerProvider(
  component: Component
) extends SpiProvider[AiRunner] {
  def supports(
    contract: SpiContract[AiRunner],
    selection: SpiSelection
  )(using ExecutionContext): Boolean =
    contract.name == "ai-runner" &&
      contract.runtimeClass == classOf[AiRunner] &&
      _is_success(generateService(selection)) &&
      _is_success(chatService(selection))

  def provide(
    contract: SpiContract[AiRunner],
    selection: SpiSelection
  )(using ExecutionContext): Consequence[AiRunner] = {
    for {
      generate <- generateService(selection)
      chat <- chatService(selection)
    } yield new TextusAiRunner(this, selection)
  }

  def generateService(
    selection: SpiSelection
  )(using ExecutionContext): Consequence[GenerateService] =
    component.binding("generate") match {
      case Some(binding: Component.Binding[?, ?]) =>
        binding
          .asInstanceOf[Component.Binding[GenerateRequirement, GenerateService]]
          .bind(_requirement(selection))
      case None =>
        Consequence.serviceUnavailable("generate binding not found")
    }

  def chatService(
    selection: SpiSelection
  )(using ExecutionContext): Consequence[ChatService] =
    component.binding("chat") match {
      case Some(binding: Component.Binding[?, ?]) =>
        binding
          .asInstanceOf[Component.Binding[GenerateRequirement, ChatService]]
          .bind(_requirement(selection))
      case None =>
        Consequence.serviceUnavailable("chat binding not found")
    }

  private def _requirement(
    selection: SpiSelection
  ): GenerateRequirement =
    GenerateRequirement(
      provider = selection.provider.orElse(Some("gemma")),
      mode = selection.mode.orElse(Some("local")),
      engine = selection.engine.orElse(Some("ollama"))
    )

  private def _is_success[A](p: Consequence[A]): Boolean =
    p match {
      case Consequence.Success(_) => true
      case Consequence.Failure(_) => false
    }
}

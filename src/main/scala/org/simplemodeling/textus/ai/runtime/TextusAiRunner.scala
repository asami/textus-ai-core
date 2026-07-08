package org.simplemodeling.textus.ai.runtime

import org.goldenport.Consequence
import org.goldenport.cncf.component.Component
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.{SpiContract, SpiProvider, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.*
import org.goldenport.protocol.Property
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
 * @version Jul.  9, 2026
 * @author  ASAMI, Tomoharu
 */
final class TextusAiRunner(
  provider: TextusAiRunnerProvider,
  selection: SpiSelection,
  profiles: AiProfileConfig = AiProfileConfig.empty
) extends AiRunner {
  def generate(req: AiGenerateRequest)(using ExecutionContext): Consequence[AiGenerateResponse] = {
    val requirement = _effective_requirement(req.requirement)
    _with_generate_calltree(req, requirement) {
      for {
        service <- provider.generateService(_effective_selection(requirement))
        response <- service.generate(
          GenerateRequest(
            prompt = req.prompt,
            temperature = req.temperature,
            maxTokens = req.maxTokens,
            properties = _request_properties(req.properties, requirement)
          )
        )
      } yield _to_ai_generate_response(response)
    }
  }

  def chat(req: AiChatRequest)(using ExecutionContext): Consequence[AiChatResponse] = {
    val requirement = _effective_requirement(req.requirement)
    _with_chat_calltree(req, requirement) {
      for {
        service <- provider.chatService(_effective_selection(requirement))
        response <- service.chat(
          ChatRequest(
            messages = req.messages.map(_to_textus_message),
            temperature = req.temperature,
            maxTokens = req.maxTokens,
            properties = _request_properties(req.properties, requirement)
          )
        )
      } yield _to_ai_chat_response(response)
    }
  }

  private def _with_generate_calltree(
    req: AiGenerateRequest,
    requirement: AiRunnerRequirement
  )(
    body: => Consequence[AiGenerateResponse]
  )(using ctx: ExecutionContext): Consequence[AiGenerateResponse] = {
    val calltree = ctx.observability.callTreeContext
    if (calltree.isEnabled) {
      calltree.enter(
        "provider:textus-ai-runner:generate",
        _generate_request_calltree_attributes(req, requirement)
      )
      try {
        val result = body
        result match {
          case Consequence.Success(response) =>
            calltree.leave(_generate_response_calltree_attributes(req, response))
          case Consequence.Failure(conclusion) =>
            calltree.leave(Map(
              "outcome" -> "failure",
              "status" -> conclusion.status.webCode.code.toString,
              "error" -> conclusion.display
            ))
        }
        result
      } catch {
        case e: Throwable =>
          calltree.leave(Map(
            "outcome" -> "failure",
            "error" -> Option(e.getMessage).getOrElse(e.getClass.getName)
          ))
          throw e
      }
    } else {
      body
    }
  }

  private def _with_chat_calltree(
    req: AiChatRequest,
    requirement: AiRunnerRequirement
  )(
    body: => Consequence[AiChatResponse]
  )(using ctx: ExecutionContext): Consequence[AiChatResponse] = {
    val calltree = ctx.observability.callTreeContext
    if (calltree.isEnabled) {
      calltree.enter(
        "provider:textus-ai-runner:chat",
        _chat_request_calltree_attributes(req, requirement)
      )
      try {
        val result = body
        result match {
          case Consequence.Success(response) =>
            calltree.leave(_chat_response_calltree_attributes(req, response))
          case Consequence.Failure(conclusion) =>
            calltree.leave(Map(
              "outcome" -> "failure",
              "status" -> conclusion.status.webCode.code.toString,
              "error" -> conclusion.display
            ))
        }
        result
      } catch {
        case e: Throwable =>
          calltree.leave(Map(
            "outcome" -> "failure",
            "error" -> Option(e.getMessage).getOrElse(e.getClass.getName)
          ))
          throw e
      }
    } else {
      body
    }
  }

  private def _generate_request_calltree_attributes(
    req: AiGenerateRequest,
    requirement: AiRunnerRequirement
  ): Map[String, String] =
    _common_request_calltree_attributes(
      requirement,
      req.temperature,
      req.maxTokens,
      req.trace.promptConfidentiality.label,
      req.trace.responseConfidentiality.label,
      req.metadata
    ) ++ Map(
      "prompt_chars" -> req.prompt.length.toString,
      "prompt" -> req.trace.calltreePrompt(req.prompt),
      "prompt_preview" -> _calltree_text_preview(req.trace.calltreePrompt(req.prompt))
    )

  private def _chat_request_calltree_attributes(
    req: AiChatRequest,
    requirement: AiRunnerRequirement
  ): Map[String, String] = {
    val prompttext = req.messages.map(message => s"${message.role}: ${message.content}").mkString("\n")
    _common_request_calltree_attributes(
      requirement,
      req.temperature,
      req.maxTokens,
      req.trace.promptConfidentiality.label,
      req.trace.responseConfidentiality.label,
      req.metadata
    ) ++ Map(
      "message_count" -> req.messages.length.toString,
      "prompt_chars" -> prompttext.length.toString,
      "prompt" -> req.trace.calltreePrompt(prompttext),
      "prompt_preview" -> _calltree_text_preview(req.trace.calltreePrompt(prompttext))
    )
  }

  private def _common_request_calltree_attributes(
    requirement: AiRunnerRequirement,
    temperature: Option[Double],
    maxtokens: Option[Int],
    promptconfidentiality: String,
    responseconfidentiality: String,
    metadata: Map[String, String]
  ): Map[String, String] = {
    val effective = _effective_selection(requirement)
    Map(
      "calltree_kind" -> "provider-step",
      "operation" -> metadata.getOrElse("operation", ""),
      "task" -> metadata.getOrElse("task", metadata.getOrElse("dsl", "")),
      "provider" -> effective.provider.getOrElse(""),
      "mode" -> effective.mode.getOrElse(""),
      "engine" -> effective.engine.getOrElse(""),
      "purpose" -> requirement.purpose.getOrElse(""),
      "requested_model" -> requirement.model.getOrElse(""),
      "ai_tools" -> requirement.tools.map(_.id).mkString(","),
      "temperature" -> temperature.map(_.toString).getOrElse(""),
      "max_tokens" -> maxtokens.map(_.toString).getOrElse(""),
      "prompt_confidentiality" -> promptconfidentiality,
      "response_confidentiality" -> responseconfidentiality
    ) ++ metadata.toVector.map { case (key, value) => s"metadata.$key" -> value }.toMap
  }

  private def _generate_response_calltree_attributes(
    req: AiGenerateRequest,
    response: AiGenerateResponse
  ): Map[String, String] =
    Map(
      "outcome" -> "success",
      "model" -> response.model.getOrElse(""),
      "response_chars" -> response.text.length.toString,
      "response" -> req.trace.calltreeResponse(response.text),
      "response_preview" -> _calltree_text_preview(req.trace.calltreeResponse(response.text))
    ) ++ response.metadata.toVector.map { case (key, value) => s"response_metadata.$key" -> value }.toMap

  private def _chat_response_calltree_attributes(
    req: AiChatRequest,
    response: AiChatResponse
  ): Map[String, String] = {
    val responsetext = response.message.content
    Map(
      "outcome" -> "success",
      "model" -> response.model.getOrElse(""),
      "response_chars" -> responsetext.length.toString,
      "response" -> req.trace.calltreeResponse(responsetext),
      "response_preview" -> _calltree_text_preview(req.trace.calltreeResponse(responsetext))
    ) ++ response.metadata.toVector.map { case (key, value) => s"response_metadata.$key" -> value }.toMap
  }

  private def _calltree_text_preview(text: String): String = {
    val normalized = text.replace("\r\n", "\n")
    val limit = 6000
    if (normalized.length <= limit)
      normalized
    else
      normalized.take(limit) + s"\n... truncated (${normalized.length - limit} chars omitted)"
  }

  private def _effective_selection(
    requirement: AiRunnerRequirement
  ): SpiSelection = {
    val provider = requirement.provider.orElse(selection.provider)
    val shouldinherit = requirement.provider.isEmpty || requirement.provider == selection.provider
    val providername = provider.getOrElse("gemma")
    if (
      requirement.provider.isEmpty &&
      requirement.mode.isEmpty &&
      requirement.engine.isEmpty
    )
      selection
    else
      SpiSelection(
        provider = provider,
        mode = requirement.mode.
          orElse(if (shouldinherit) selection.mode else None).
          orElse(Some(_default_mode(providername))),
        engine = requirement.engine.
          orElse(if (shouldinherit) selection.engine else None).
          orElse(Some(_default_engine(providername)))
      )
  }

  private def _effective_requirement(
    requirement: AiRunnerRequirement
  ): AiRunnerRequirement =
    profiles.resolve(requirement)

  private def _request_properties(
    properties: Vector[Property],
    requirement: AiRunnerRequirement
  ): Vector[Property] = {
    val modelproperty = requirement.model.map(value => Property("ai.model", value, None))
    val purposeproperty = requirement.purpose.map(value => Property("ai.purpose", value, None))
    val toolsproperty =
      Option.when(requirement.tools.nonEmpty)(
        Property("ai.tools", requirement.tools.map(_.id).mkString(","), None)
      )
    properties ++ modelproperty ++ purposeproperty ++ toolsproperty
  }

  private def _to_ai_generate_response(
    response: GenerateResponse
  ): AiGenerateResponse =
    AiGenerateResponse(response.text, _effective_model(response.model), response.metadata)

  private def _to_ai_chat_response(
    response: ChatResponse
  ): AiChatResponse =
    AiChatResponse(_to_ai_message(response.message), _effective_model(response.model), response.metadata)

  private def _effective_model(
    model: Option[String]
  ): Option[String] =
    model.orElse(selection.engine).orElse(selection.provider)

  private def _default_mode(provider: String): String =
    provider match {
      case "google" | "openai" => "remote"
      case _ => "local"
    }

  private def _default_engine(provider: String): String =
    provider match {
      case "google" => "gemini"
      case "openai" => "gpt"
      case _ => "ollama"
    }

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
  component: Component,
  defaultselection: SpiSelection = SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
  profiles: AiProfileConfig = AiProfileConfig.empty
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
    val effective = _effective_selection(selection)
    for {
      generate <- generateService(effective)
      chat <- chatService(effective)
    } yield new TextusAiRunner(this, effective, profiles)
  }

  def generateService(
    selection: SpiSelection
  )(using ExecutionContext): Consequence[GenerateService] =
    component.binding("generate") match {
      case Some(binding: Component.Binding[?, ?]) =>
        binding
          .asInstanceOf[Component.Binding[GenerateRequirement, GenerateService]]
          .bind(_requirement(_effective_selection(selection)))
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
          .bind(_requirement(_effective_selection(selection)))
      case None =>
        Consequence.serviceUnavailable("chat binding not found")
    }

  private def _effective_selection(
    selection: SpiSelection
  ): SpiSelection = {
    val provider = selection.provider.orElse(defaultselection.provider)
    val inheritsdefault =
      selection.provider.isEmpty ||
      selection.provider == defaultselection.provider
    val providername = provider.getOrElse("gemma")
    SpiSelection(
      provider = provider,
      mode = selection.mode.orElse(if (inheritsdefault) defaultselection.mode else Some(_default_mode(providername))),
      engine = selection.engine.orElse(if (inheritsdefault) defaultselection.engine else Some(_default_engine(providername)))
    )
  }

  private def _requirement(
    selection: SpiSelection
  ): GenerateRequirement = {
    val provider = selection.provider.getOrElse("gemma")
    GenerateRequirement(
      provider = Some(provider),
      mode = selection.mode.orElse(Some(_default_mode(provider))),
      engine = selection.engine.orElse(Some(_default_engine(provider)))
    )
  }

  private def _default_mode(provider: String): String =
    provider match {
      case "google" | "openai" => "remote"
      case _ => "local"
    }

  private def _default_engine(provider: String): String =
    provider match {
      case "google" => "gemini"
      case "openai" => "gpt"
      case _ => "ollama"
    }

  private def _is_success[A](p: Consequence[A]): Boolean =
    p match {
      case Consequence.Success(_) => true
      case Consequence.Failure(_) => false
    }
}

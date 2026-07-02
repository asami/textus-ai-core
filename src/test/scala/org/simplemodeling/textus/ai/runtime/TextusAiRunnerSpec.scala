package org.simplemodeling.textus.ai.runtime

import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ExtensionPoint, Port}
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.{AiChatRequest, AiGenerateRequest, AiMessage, AiRunner, AiRunnerRequirement}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.ai.ComponentFactory
import org.simplemodeling.textus.ai.ai.{ChatRequest, ChatResponse, GenerateRequest, GenerateResponse, Message}

/*
 * @since   Jul.  2, 2026
 * @version Jul.  2, 2026
 * @author  ASAMI, Tomoharu
 */
final class TextusAiRunnerSpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen {

  "TextusAiRunner" should {
    "delegate generate and chat requests through existing Textus AI runtime bindings" in {
      Given("a component with existing generate and chat runtime bindings")
      given ExecutionContext = ExecutionContext.create()
      val component = _component()
      val provider = new TextusAiRunnerProvider(component)
      val selection = SpiSelection(mode = Some("remote"), engine = Some("http"))

      When("the provider creates an AI runner for the selected runtime")
      val runner = provider
        .provide(SpiContract("ai-runner", classOf[AiRunner]), selection)
        .toOption
        .get
      val generated = runner.generate(AiGenerateRequest(" hello "))
      val chatted = runner.chat(
        AiChatRequest(
          Vector(
            AiMessage("system", "Ready."),
            AiMessage("user", "Hello")
          )
        )
      )

      Then("requests and responses are translated without changing provider behavior")
      provider.supports(SpiContract("ai-runner", classOf[AiRunner]), selection) shouldBe true
      generated.toOption.get.text shouldBe "generated:remote: hello "
      chatted.toOption.get.message shouldBe AiMessage("assistant", "chat:remote:Hello")
    }

    "honor per-request AI runner requirement when it overrides the socket selection" in {
      Given("an AI runner bound with a remote default selection")
      given ExecutionContext = ExecutionContext.create()
      val provider = new TextusAiRunnerProvider(_component())
      val runner = provider
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(mode = Some("remote"), engine = Some("http"))
        )
        .toOption
        .get

      When("a generate request asks for a local runtime")
      val generated = runner.generate(
        AiGenerateRequest(
          prompt = "hello",
          requirement = AiRunnerRequirement(mode = Some("local"), engine = Some("ollama"))
        )
      )

      Then("the request-level requirement is used for that call")
      generated.toOption.get.text shouldBe "generated:local:hello"
    }

    "register an AI runner provider on standalone component creation" in {
      Given("a standalone Textus AI component")
      val component = ComponentFactory.createStandalone()

      When("the component port is inspected")
      val provider = component.port.get[TextusAiRunnerProvider]

      Then("the CNCF AI runner SPI provider is published")
      provider should not be empty
    }
  }

  private def _component(): Component =
    new Component() {}
      .withBinding("generate", _generate_binding())
      .withBinding("chat", _chat_binding())

  private def _generate_binding(): Component.Binding[GenerateRequirement, GenerateService] =
    Component.Binding(
      Port(
        api = new GeneratePortApi {},
        spi = Vector(
          _GenerateExtension("local", "local", "ollama"),
          _GenerateExtension("remote", "remote", "http")
        ),
        variation = new GenerateVariationPoint {}
      )
    )

  private def _chat_binding(): Component.Binding[GenerateRequirement, ChatService] =
    Component.Binding(
      Port(
        api = new ChatPortApi {},
        spi = Vector(
          _ChatExtension("local", "local", "ollama"),
          _ChatExtension("remote", "remote", "http")
        ),
        variation = new GenerateVariationPoint {}
      )
    )

  private final case class _GenerateExtension(
    name: String,
    mode: String,
    engine: String
  ) extends ExtensionPoint[GenerateService] {
    def supports(
      contract: org.goldenport.cncf.component.ServiceContract[GenerateService],
      variation: org.goldenport.cncf.component.VariationSelection
    )(using ExecutionContext): Boolean =
      contract.name == "generate-service" &&
        variation.provider.contains("gemma") &&
        variation.mode.contains(mode) &&
        variation.engine.contains(engine)

    def provide(
      contract: org.goldenport.cncf.component.ServiceContract[GenerateService],
      variation: org.goldenport.cncf.component.VariationSelection
    )(using ExecutionContext): Consequence[GenerateService] =
      Consequence.success(_GenerateService(name))
  }

  private final case class _ChatExtension(
    name: String,
    mode: String,
    engine: String
  ) extends ExtensionPoint[ChatService] {
    def supports(
      contract: org.goldenport.cncf.component.ServiceContract[ChatService],
      variation: org.goldenport.cncf.component.VariationSelection
    )(using ExecutionContext): Boolean =
      contract.name == "chat-service" &&
        variation.provider.contains("gemma") &&
        variation.mode.contains(mode) &&
        variation.engine.contains(engine)

    def provide(
      contract: org.goldenport.cncf.component.ServiceContract[ChatService],
      variation: org.goldenport.cncf.component.VariationSelection
    )(using ExecutionContext): Consequence[ChatService] =
      Consequence.success(_ChatService(name))
  }

  private final case class _GenerateService(
    name: String
  ) extends GenerateService {
    def generate(req: GenerateRequest): Consequence[GenerateResponse] =
      Consequence.success(GenerateResponse(s"generated:$name:${req.prompt}"))
  }

  private final case class _ChatService(
    name: String
  ) extends ChatService {
    def chat(req: ChatRequest): Consequence[ChatResponse] = {
      val last =
        req.messages.reverse.find(_.role == MessageRole.User).map(_.content).getOrElse("hello")
      Consequence.success(
        ChatResponse(
          Message(MessageRole.Assistant, s"chat:$name:$last")
        )
      )
    }
  }
}

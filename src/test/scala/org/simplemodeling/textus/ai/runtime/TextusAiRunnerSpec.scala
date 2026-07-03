package org.simplemodeling.textus.ai.runtime

import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ExtensionPoint, Port}
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.observability.ObservabilityEngine
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.{AiChatRequest, AiGenerateRequest, AiMessage, AiRunner, AiRunnerRequirement, AiRunnerTracePolicy}
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.goldenport.protocol.Property
import org.goldenport.record.Record
import org.goldenport.schema.DataConfidentiality
import org.scalatest.GivenWhenThen
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.ai.ComponentFactory
import org.simplemodeling.textus.ai.ai.{ChatRequest, ChatResponse, GenerateRequest, GenerateResponse, Message}
import org.simplemodeling.textus.ai.provider.openai.OpenAiConfig

/*
 * @since   Jul.  2, 2026
 * @version Jul.  4, 2026
 * @author  ASAMI, Tomoharu
 */
final class TextusAiRunnerSpec
  extends AnyWordSpec
  with Matchers
  with OptionValues
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
      generated.toOption.get.model shouldBe Some("remote")
      chatted.toOption.get.message shouldBe AiMessage("assistant", "chat:remote:Hello")
      chatted.toOption.get.model shouldBe Some("remote")
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

    "use provider defaults when a request switches provider without mode or engine" in {
      Given("an AI runner bound with the default local Gemma selection and a Google runtime is available")
      given ExecutionContext = ExecutionContext.create()
      val provider = new TextusAiRunnerProvider(_component())
      val runner = provider
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama"))
        )
        .toOption
        .get

      When("a generate request asks only for the Google provider")
      val generated = runner.generate(
        AiGenerateRequest(
          prompt = "hello",
          requirement = AiRunnerRequirement(provider = Some("google"))
        )
      )

      Then("the runner does not inherit Gemma mode and engine for the Google request")
      generated.toOption.get.text shouldBe "generated:google:hello"
    }

    "use configured default selection when the SPI socket does not request a provider" in {
      Given("an AI runner provider configured to default to Google")
      given ExecutionContext = ExecutionContext.create()
      val provider = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("google"), mode = Some("remote"), engine = Some("gemini"))
      )
      val runner = provider
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection()
        )
        .toOption
        .get

      When("a generate request has no provider requirement")
      val generated = runner.generate(AiGenerateRequest("hello"))

      Then("the configured default provider is used instead of falling back to Gemma")
      generated.toOption.get.text shouldBe "generated:google:hello"
    }

    "propagate request properties to Textus AI runtime requests" in {
      Given("an AI runner created from existing Textus AI bindings")
      given ExecutionContext = ExecutionContext.create()
      val runner = new TextusAiRunnerProvider(_component())
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(mode = Some("remote"), engine = Some("http"))
        )
        .toOption
        .get

      When("a generate request carries an execution property")
      val generated = runner.generate(
        AiGenerateRequest(
          prompt = "inspect-properties",
          properties = Vector(Property("ai.timeout-seconds", "180", None))
        )
      )

      Then("the property is preserved for provider-side execution")
      generated.toOption.get.text shouldBe "timeout:180"
    }

    "record direct SPI generate calls in the CNCF CallTree" in {
      Given("an AI runner invoked directly through the SPI surface")
      given ExecutionContext =
        ExecutionContext.withFrameworkCallTreeEnabled(ExecutionContext.create(), enabled = true)
      val runner = new TextusAiRunnerProvider(_component())
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(mode = Some("remote"), engine = Some("http"))
        )
        .toOption
        .get

      When("generate is called without a component-local wrapper")
      val generated = runner.generate(
        AiGenerateRequest(
          prompt = "trace this prompt",
          trace = AiRunnerTracePolicy(
            promptConfidentiality = DataConfidentiality.Public,
            responseConfidentiality = DataConfidentiality.Public
          ),
          metadata = Map("operation" -> "direct-generate", "task" -> "calltree-spec")
        )
      )

      Then("the prompt response and model are visible through CallTree metadata")
      generated.toOption.get.model shouldBe Some("remote")
      val calltree = summon[ExecutionContext].observability.callTreeContext.build().value
      val record = ObservabilityEngine.callTreeRecord(calltree)
      val nodes = record.asMap("calltree").asInstanceOf[Seq[Record]]
      val node = nodes.find(_.getString("label").contains("provider:textus-ai-runner:generate")).value
      node.getString("operation") shouldBe Some("direct-generate")
      node.getString("task") shouldBe Some("calltree-spec")
      node.getString("prompt").value should include ("trace this prompt")
      node.getString("response").value should include ("generated:remote:trace this prompt")
      node.getString("model") shouldBe Some("remote")
    }

    "register an AI runner provider on standalone component creation" in {
      Given("a standalone Textus AI component")
      val component = ComponentFactory.createStandalone()

      When("the component port is inspected")
      val provider = component.port.get[TextusAiRunnerProvider]

      Then("the CNCF AI runner SPI provider is published")
      provider should not be empty
    }

    "read OpenAI credentials from CNCF configuration while provider selection stays separate" in {
      Given("a merged configuration with personal OpenAI secrets and project AI selection")
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.openai.api-key" -> ConfigurationValue.StringValue("test-openai-key"),
          "textus.ai.openai.model" -> ConfigurationValue.StringValue("gpt-test"),
          "textus.ai.openai.timeout-seconds" -> ConfigurationValue.StringValue("180"),
          "textus.ai.provider" -> ConfigurationValue.StringValue("openai"),
          "textus.ai.mode" -> ConfigurationValue.StringValue("remote"),
          "textus.ai.engine" -> ConfigurationValue.StringValue("openai")
        )),
        ConfigurationTrace.empty
      )

      When("the OpenAI runtime config is created")
      val config = OpenAiConfig.fromConfiguration(configuration)

      Then("only the OpenAI runtime keys are used for credentials and model settings")
      config.map(_.apiKey) shouldBe Some("test-openai-key")
      config.map(_.model) shouldBe Some("gpt-test")
      config.map(_.timeoutSeconds) shouldBe Some(180L)
    }

    "redact provider secrets from HTTP error diagnostics" in {
      Given("a Google API failure message containing query credentials")
      val message =
        "HTTP 429 for https://generativelanguage.googleapis.com/v1beta/models/gemini:generateContent?key=secret-key&other=value: quota"

      When("the diagnostic text is redacted")
      val redacted = HttpSupport.redactSensitive(message)

      Then("the API key is not exposed")
      redacted should include ("key=<redacted>")
      redacted should include ("other=value")
      redacted should not include ("secret-key")
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
          _GenerateExtension("remote", "remote", "http"),
          _GenerateExtension("google", "remote", "gemini", provider = "google")
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
          _ChatExtension("remote", "remote", "http"),
          _ChatExtension("google", "remote", "gemini", provider = "google")
        ),
        variation = new GenerateVariationPoint {}
      )
    )

  private final case class _GenerateExtension(
    name: String,
    mode: String,
    engine: String,
    provider: String = "gemma"
  ) extends ExtensionPoint[GenerateService] {
    def supports(
      contract: org.goldenport.cncf.component.ServiceContract[GenerateService],
      variation: org.goldenport.cncf.component.VariationSelection
    )(using ExecutionContext): Boolean =
      contract.name == "generate-service" &&
        variation.provider.contains(provider) &&
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
    engine: String,
    provider: String = "gemma"
  ) extends ExtensionPoint[ChatService] {
    def supports(
      contract: org.goldenport.cncf.component.ServiceContract[ChatService],
      variation: org.goldenport.cncf.component.VariationSelection
    )(using ExecutionContext): Boolean =
      contract.name == "chat-service" &&
        variation.provider.contains(provider) &&
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
      if (req.prompt == "inspect-properties")
        Consequence.success(GenerateResponse(s"timeout:${_property(req, "ai.timeout-seconds").getOrElse("none")}", Some(name)))
      else
        Consequence.success(GenerateResponse(s"generated:$name:${req.prompt}", Some(name)))

    private def _property(
      req: GenerateRequest,
      name: String
    ): Option[String] =
      req.properties.find(_.name == name).map(x => String.valueOf(x.value))
  }

  private final case class _ChatService(
    name: String
  ) extends ChatService {
    def chat(req: ChatRequest): Consequence[ChatResponse] = {
      val last =
        req.messages.reverse.find(_.role == MessageRole.User).map(_.content).getOrElse("hello")
      Consequence.success(
        ChatResponse(
          Message(MessageRole.Assistant, s"chat:$name:$last"),
          Some(name)
        )
      )
    }
  }
}

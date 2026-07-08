package org.simplemodeling.textus.ai.runtime

import java.net.URI
import java.nio.charset.StandardCharsets
import cats.~>
import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ExtensionPoint, Port}
import org.goldenport.cncf.context.{ExecutionContext, RuntimeContext}
import org.goldenport.cncf.http.HttpDriver
import org.goldenport.cncf.observability.ObservabilityEngine
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.{AiChatRequest, AiGenerateRequest, AiMessage, AiRunner, AiRunnerRequirement, AiRunnerTracePolicy, AiTool}
import org.goldenport.cncf.unitofwork.{UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.goldenport.bag.Bag
import org.goldenport.datatype.{ContentType, MimeType}
import org.goldenport.http.{HttpResponse, HttpStatus}
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
import org.simplemodeling.textus.ai.provider.gemma.{GemmaOllamaGenerateService, GemmaRuntimeConfig}
import org.simplemodeling.textus.ai.provider.google.{GoogleGenerateService, GoogleRuntimeConfig}
import org.simplemodeling.textus.ai.provider.openai.{OpenAiGenerateService, OpenAiRuntimeConfig}
import org.simplemodeling.textus.ai.provider.openai.OpenAiConfig

/*
 * @since   Jul.  2, 2026
 * @version Jul.  9, 2026
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

    "use GPT engine default when a request switches to the OpenAI provider" in {
      Given("an AI runner bound with the default local Gemma selection and an OpenAI runtime is available")
      given ExecutionContext = ExecutionContext.create()
      val provider = new TextusAiRunnerProvider(_component())
      val runner = provider
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama"))
        )
        .toOption
        .get

      When("a generate request asks only for the OpenAI provider")
      val generated = runner.generate(
        AiGenerateRequest(
          prompt = "hello",
          requirement = AiRunnerRequirement(provider = Some("openai"))
        )
      )

      Then("the runner uses the canonical GPT engine for the OpenAI runtime")
      generated.toOption.get.text shouldBe "generated:openai:hello"
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

    "propagate request purpose and model to provider-side execution" in {
      Given("an AI runner created from existing Textus AI bindings")
      given ExecutionContext = ExecutionContext.create()
      val runner = new TextusAiRunnerProvider(_component())
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(mode = Some("remote"), engine = Some("http"))
        )
        .toOption
        .get

      When("a generate request carries a purpose-specific model requirement")
      val generated = runner.generate(
        AiGenerateRequest(
          prompt = "inspect-ai-selection",
          requirement = AiRunnerRequirement(
            purpose = Some("linear-feature.worker.anchor-plan"),
            model = Some("gpt-4.1-mini")
          )
        )
      )

      Then("the requirement is visible to the concrete provider request")
      generated.toOption.get.text shouldBe "purpose:linear-feature.worker.anchor-plan;model:gpt-4.1-mini"
    }

    "resolve purpose-specific model profiles from CNCF configuration" in {
      Given("an AI runner configured with a purpose profile and a model profile")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.linear-feature.worker.anchor-plan.model-profile" -> ConfigurationValue.StringValue("linear-worker"),
          "textus.ai.model-profiles.linear-worker.provider" -> ConfigurationValue.StringValue("google"),
          "textus.ai.model-profiles.linear-worker.model" -> ConfigurationValue.StringValue("gemini-worker"),
          "textus.ai.model-profiles.linear-worker.role" -> ConfigurationValue.StringValue("worker"),
          "textus.ai.model-profiles.linear-worker.quality" -> ConfigurationValue.StringValue("standard"),
          "textus.ai.model-profiles.linear-worker.cost" -> ConfigurationValue.StringValue("low"),
          "textus.ai.model-profiles.linear-worker.latency" -> ConfigurationValue.StringValue("low")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        AiProfileConfig.fromConfiguration(Some(configuration))
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      When("a generate request specifies only the purpose")
      val generated = runner.generate(
        AiGenerateRequest(
          prompt = "inspect-ai-selection",
          requirement = AiRunnerRequirement(
            purpose = Some("linear-feature.worker.anchor-plan")
          )
        )
      )

      Then("the configured profile chooses the provider and model for that purpose")
      generated.toOption.get.text shouldBe "purpose:linear-feature.worker.anchor-plan;model:gemini-worker"
      generated.toOption.get.model shouldBe Some("google")
    }

    "prefer direct request model over a configured purpose profile" in {
      Given("an AI runner configured with a purpose model profile")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.linear-feature.judge.ambiguity-resolution.model-profile" -> ConfigurationValue.StringValue("linear-judge"),
          "textus.ai.model-profiles.linear-judge.provider" -> ConfigurationValue.StringValue("google"),
          "textus.ai.model-profiles.linear-judge.model" -> ConfigurationValue.StringValue("gemini-judge")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        AiProfileConfig.fromConfiguration(Some(configuration))
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      When("a generate request explicitly specifies a model")
      val generated = runner.generate(
        AiGenerateRequest(
          prompt = "inspect-ai-selection",
          requirement = AiRunnerRequirement(
            purpose = Some("linear-feature.judge.ambiguity-resolution"),
            model = Some("operator-selected-model")
          )
        )
      )

      Then("the profile may choose the provider but does not override the request model")
      generated.toOption.get.text shouldBe "purpose:linear-feature.judge.ambiguity-resolution;model:operator-selected-model"
      generated.toOption.get.model shouldBe Some("google")
    }

    "resolve purpose-specific AI tools and pass them to provider requests" in {
      Given("an AI runner configured with purpose-level tool defaults")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-fetch.provider" -> ConfigurationValue.StringValue("google"),
          "textus.ai.purposes.artscene-exhibition-fetch.tools" -> ConfigurationValue.StringValue("url_context, web_search")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        AiProfileConfig.fromConfiguration(Some(configuration))
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      When("a generate request specifies only the purpose")
      val generated = runner.generate(
        AiGenerateRequest(
          prompt = "inspect-ai-tools",
          requirement = AiRunnerRequirement(purpose = Some("artscene-exhibition-fetch"))
        )
      )

      Then("the configured tools are visible to the concrete provider request")
      generated.toOption.get.text shouldBe "tools:url_context,web_search"
    }

    "prefer direct request tools over configured purpose tools" in {
      Given("an AI runner configured with purpose-level tool defaults")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.tool-defaults.provider" -> ConfigurationValue.StringValue("google"),
          "textus.ai.purposes.tool-defaults.tools" -> ConfigurationValue.StringValue("url_context, web_search")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        AiProfileConfig.fromConfiguration(Some(configuration))
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      When("a generate request explicitly specifies one tool")
      val generated = runner.generate(
        AiGenerateRequest(
          prompt = "inspect-ai-tools",
          requirement = AiRunnerRequirement(
            purpose = Some("tool-defaults"),
            tools = Vector(AiTool.UrlContext)
          )
        )
      )

      Then("the request-level tool set is used")
      generated.toOption.get.text shouldBe "tools:url_context"
    }

    "send Gemini tool requests through the Interactions API" in {
      Given("a Google provider service with a fake HTTP driver")
      val driver = new _FakeHttpDriver(
        """{"output_text":"grounded","steps":[{"type":"google_search_call"},{"type":"model_output","content":[{"type":"text","text":"grounded","annotations":[{"type":"url_citation"}]}]}]}"""
      )
      given ExecutionContext = _context(driver)
      val service = new GoogleGenerateService(
        GoogleRuntimeConfig(
          endpoint = URI.create("https://generativelanguage.googleapis.com"),
          apiKey = "test-google-key",
          model = "gemini-test"
        ),
        summon[ExecutionContext]
      )

      When("a generate request enables URL context and web search")
      val response = service.generate(
        GenerateRequest(
          prompt = "find official page",
          properties = Vector(Property("ai.tools", "url_context,web_search", None))
        )
      ).toOption.get

      Then("the provider maps logical tools to Gemini tool names")
      driver.calls.head should include ("/v1beta/interactions")
      driver.headers.get("x-goog-api-key") shouldBe Some("test-google-key")
      driver.body.value should include (""""type":"url_context"""")
      driver.body.value should include (""""type":"google_search"""")
      response.metadata("ai.provider_tools") shouldBe "url_context,google_search"
      response.metadata("google.google_search_calls") shouldBe "1"
      response.metadata("google.url_citations") shouldBe "1"
    }

    "send OpenAI tool requests through the Responses API" in {
      Given("an OpenAI provider service with a fake HTTP driver")
      val driver = new _FakeHttpDriver(
        """{"id":"resp_test","output_text":"grounded","output":[{"type":"web_search_call"}]}"""
      )
      given ExecutionContext = _context(driver)
      val service = new OpenAiGenerateService(
        OpenAiRuntimeConfig(
          endpoint = URI.create("https://api.openai.com"),
          apiKey = "test-openai-key",
          model = "gpt-test"
        ),
        summon[ExecutionContext]
      )

      When("a generate request enables URL context through the provider-neutral tool contract")
      val response = service.generate(
        GenerateRequest(
          prompt = "find official page",
          properties = Vector(
            Property("ai.tools", "url_context", None),
            Property("ai.openai.web_search.search_context_size", "low", None)
          )
        )
      ).toOption.get

      Then("the provider maps the logical tool to OpenAI web search")
      driver.calls.head should include ("/v1/responses")
      driver.headers.get("Authorization") shouldBe Some("Bearer test-openai-key")
      driver.body.value should include (""""type":"web_search"""")
      driver.body.value should include (""""search_context_size":"low"""")
      response.metadata("ai.provider_tools") shouldBe "web_search"
      response.metadata("openai.web_search_calls") shouldBe "1"
      response.metadata("openai.response_id") shouldBe "resp_test"
    }

    "reject unknown AI tool names before provider execution" in {
      Given("a local provider service and a request with an unknown tool")
      given ExecutionContext = ExecutionContext.create()
      val service = new GemmaOllamaGenerateService(
        GemmaRuntimeConfig(endpoint = URI.create("http://ollama:11434")),
        summon[ExecutionContext]
      )

      When("the generate request is executed")
      val result = service.generate(
        GenerateRequest(
          prompt = "hello",
          properties = Vector(Property("ai.tools", "vendor_special", None))
        )
      )

      Then("the request fails before the provider silently ignores the tool")
      result shouldBe a[Consequence.Failure[_]]
      result match
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("Unknown AI tools: vendor_special")
        case _ =>
          fail("unknown AI tool request should fail")
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
      given ExecutionContext = ExecutionContext.create()
      val component = ComponentFactory.createStandalone()

      When("the component port is inspected")
      val provider = component.port.get[TextusAiRunnerProvider]

      Then("the CNCF AI runner SPI provider is published")
      provider should not be empty
      provider.value.supports(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ) shouldBe true
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
          "textus.ai.engine" -> ConfigurationValue.StringValue("gpt")
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
          _GenerateExtension("google", "remote", "gemini", provider = "google"),
          _GenerateExtension("openai", "remote", "gpt", provider = "openai")
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
          _ChatExtension("google", "remote", "gemini", provider = "google"),
          _ChatExtension("openai", "remote", "gpt", provider = "openai")
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
      else if (req.prompt == "inspect-ai-selection")
        Consequence.success(GenerateResponse(
          s"purpose:${_property(req, "ai.purpose").getOrElse("none")};model:${_property(req, "ai.model").getOrElse("none")}",
          Some(name)
        ))
      else if (req.prompt == "inspect-ai-tools")
        Consequence.success(GenerateResponse(
          s"tools:${_property(req, "ai.tools").getOrElse("none")}",
          Some(name)
        ))
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

  private final class _FakeHttpDriver(response: String) extends HttpDriver {
    var calls: Vector[String] = Vector.empty
    var body: Option[String] = None
    var headers: Map[String, String] = Map.empty

    override def get(
      path: String,
      headers: Map[String, String],
      properties: Vector[Property] = Vector.empty
    ): HttpResponse =
      _http_response(response)

    override def post(
      path: String,
      body: Option[String],
      headers: Map[String, String],
      properties: Vector[Property] = Vector.empty
    ): HttpResponse = {
      calls = calls :+ s"POST $path"
      this.body = body
      this.headers = headers
      _http_response(response)
    }

    override def put(
      path: String,
      body: Option[String],
      headers: Map[String, String],
      properties: Vector[Property] = Vector.empty
    ): HttpResponse =
      _http_response(response)
  }

  private def _http_response(body: String): HttpResponse =
    HttpResponse.Text(
      HttpStatus.Ok,
      ContentType(MimeType("application/json"), Some(StandardCharsets.UTF_8)),
      Bag.text(body, StandardCharsets.UTF_8)
    )

  private def _context(driver: HttpDriver): ExecutionContext = {
    val base = ExecutionContext.create()
    var runtime: RuntimeContext = null
    lazy val context: ExecutionContext = ExecutionContext.withRuntimeContext(base, runtime)
    lazy val uow = new UnitOfWork(context)
    runtime = new RuntimeContext(
      core = RuntimeContext.core(
        name = "textus-ai-runtime-spec",
        parent = None,
        observabilityContext = base.observability,
        httpDriverOption = Some(driver)
      ),
      unitOfWorkSupplier = () => uow,
      unitOfWorkInterpreterFn = new (UnitOfWorkOp ~> Consequence) {
        def apply[A](fa: UnitOfWorkOp[A]): Consequence[A] =
          new UnitOfWorkInterpreter(uow).interpret(fa)
      },
      commitAction = _ => (),
      abortAction = _ => (),
      disposeAction = _ => (),
      token = "textus-ai-runtime-spec"
    )
    context
  }
}

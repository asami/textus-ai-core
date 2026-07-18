package org.simplemodeling.textus.ai.runtime

import java.net.URI
import java.nio.charset.StandardCharsets
import cats.~>
import org.goldenport.Consequence
import org.goldenport.cncf.admission.{ConcurrencyGrant, ConcurrencyScopeId, ScopedConcurrencyAdmission}
import org.goldenport.cncf.component.{Component, ExtensionPoint, Port}
import org.goldenport.cncf.context.{ExecutionContext, RuntimeContext, ScopeContext, ScopeKind}
import org.goldenport.cncf.http.HttpDriver
import org.goldenport.cncf.observability.ObservabilityEngine
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.{AiChatRequest, AiGenerateRequest, AiMessage, AiRecordRequest, AiRunner, AiRunnerRequirement, AiRunnerTracePolicy, AiTool}
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
import org.simplemodeling.textus.ai.provider.gemma.{GemmaConfig, GemmaOllamaGenerateService, GemmaRuntimeConfig}
import org.simplemodeling.textus.ai.provider.google.{GoogleGenerateService, GoogleRuntimeConfig}
import org.simplemodeling.textus.ai.provider.openai.{OpenAiGenerateService, OpenAiRuntimeConfig}
import org.simplemodeling.textus.ai.provider.openai.OpenAiConfig

/*
 * @since   Jul.  2, 2026
 * @version Jul. 18, 2026
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

    "publish normalized execution facts for generate chat and record responses" in {
      Given("an AI runner with a local default and a Google request requirement")
      given ExecutionContext = ExecutionContext.create()
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama"))
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get
      val requirement = AiRunnerRequirement(
        provider = Some("google"),
        purpose = Some("car-review.semantic-consistency"),
        tools = Vector(AiTool.WebSearch, AiTool.UrlContext)
      )

      When("all three AI runner operations execute through the selected provider")
      val generated = runner.generate(AiGenerateRequest("execution-facts", requirement = requirement)).toOption.get
      val chatted = runner.chat(
        AiChatRequest(Vector(AiMessage("user", "execution facts")), requirement = requirement)
      ).toOption.get
      val recorded = runner.generateRecord(
        AiRecordRequest("strict-record", _artscene_record_schema, requirement = requirement)
      ).toOption.get

      Then("each response reports the effective provider-neutral selection")
      Vector(generated.metadata, chatted.metadata, recorded.metadata).foreach { metadata =>
        metadata(AiExecutionFacts.PROVIDER) shouldBe "google"
        metadata(AiExecutionFacts.MODE) shouldBe "remote"
        metadata(AiExecutionFacts.ENGINE) shouldBe "gemini"
        metadata(AiExecutionFacts.MODEL) shouldBe "google"
        metadata(AiExecutionFacts.PURPOSE) shouldBe "car-review.semantic-consistency"
        metadata(AiExecutionFacts.LOCATION) shouldBe "remote"
        metadata(AiExecutionFacts.TOOLS) shouldBe "url_context,web_search"
        metadata(AiExecutionFacts.RESPONSE_ID) shouldBe "response-google"
        metadata(AiExecutionFacts.FINISH_REASON) shouldBe "stop"
        metadata(AiExecutionFacts.INPUT_TOKENS) shouldBe "13"
        metadata(AiExecutionFacts.OUTPUT_TOKENS) shouldBe "8"
        metadata(AiExecutionFacts.TOTAL_TOKENS) shouldBe "21"
        metadata(AiExecutionFacts.OUTPUT_DIGEST) should startWith ("sha256:")
        metadata(AiExecutionFacts.LIMITATION_CODES) shouldBe
          "cancellation_not_propagated,concurrency_not_enforced"
      }
      generated.metadata(AiExecutionFacts.INPUT_DIGEST) shouldBe AiExecutionFacts.digest("execution-facts")
      chatted.metadata(AiExecutionFacts.INPUT_DIGEST) shouldBe AiExecutionFacts.digest("user: execution facts")
      recorded.metadata(AiExecutionFacts.INPUT_DIGEST) shouldBe AiExecutionFacts.digest("strict-record")
      recorded.metadata(AiExecutionFacts.NORMALIZATION_MODE) shouldBe "strict-json"
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

    "resolve purpose-specific mode and engine without overriding a request" in {
      Given("an AI runner with a locally selected default and a remote purpose profile")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.sanpomap-scenario-generation.provider" -> ConfigurationValue.StringValue("gemma"),
          "textus.ai.purposes.sanpomap-scenario-generation.mode" -> ConfigurationValue.StringValue("remote"),
          "textus.ai.purposes.sanpomap-scenario-generation.engine" -> ConfigurationValue.StringValue("http"),
          "textus.ai.purposes.sanpomap-scenario-generation.model" -> ConfigurationValue.StringValue("operator-profile-model")
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

      When("a request selects the purpose without provider selection fields")
      val profiled = runner.generate(
        AiGenerateRequest(
          prompt = "inspect-ai-selection",
          requirement = AiRunnerRequirement(purpose = Some("sanpomap-scenario-generation"))
        )
      )

      And("another request explicitly selects local Gemma")
      val overridden = runner.generate(
        AiGenerateRequest(
          prompt = "inspect-ai-selection",
          requirement = AiRunnerRequirement(
            purpose = Some("sanpomap-scenario-generation"),
            mode = Some("local"),
            engine = Some("ollama"),
            model = Some("operator-request-model")
          )
        )
      )

      Then("the profile controls the first request and explicit request fields retain precedence")
      profiled.toOption.get.model shouldBe Some("remote")
      profiled.toOption.get.text shouldBe "purpose:sanpomap-scenario-generation;model:operator-profile-model"
      overridden.toOption.get.model shouldBe Some("local")
      overridden.toOption.get.text shouldBe "purpose:sanpomap-scenario-generation;model:operator-request-model"
    }

    "report an unavailable purpose provider without silently using the default provider" in {
      Given("an AI runner whose purpose profile selects no installed provider")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.sanpomap-location-investigation.provider" -> ConfigurationValue.StringValue("unavailable-provider"),
          "textus.ai.purposes.sanpomap-location-investigation.mode" -> ConfigurationValue.StringValue("remote"),
          "textus.ai.purposes.sanpomap-location-investigation.engine" -> ConfigurationValue.StringValue("http")
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

      When("a request selects the unavailable profile")
      val result = runner.generate(
        AiGenerateRequest(
          prompt = "inspect-ai-selection",
          requirement = AiRunnerRequirement(purpose = Some("sanpomap-location-investigation"))
        )
      )

      Then("the request fails instead of falling back to the local default provider")
      result shouldBe a[Consequence.Failure[_]]
      result match
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("unavailable-provider")
        case _ => fail("an unavailable purpose provider must fail explicitly")
    }

    "reject an unresolved required purpose before any AI operation reaches a provider" in {
      Given("an AI runner with no profile for a required purpose")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "required-purpose-profile-unconfigured"
      _GenerateServiceState.reset(prompt)
      val runner = new TextusAiRunnerProvider(_component())
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(mode = Some("remote"), engine = Some("http"))
        )
        .toOption
        .get
      val requirement = AiRunnerRequirement(
        purpose = Some("artscene-exhibition-web-research"),
        purposeRequired = true
      )

      When("generate, record generation, and chat request that purpose")
      val generated = runner.generate(AiGenerateRequest(prompt, requirement = requirement))
      val recorded = runner.generateRecord(AiRecordRequest(prompt, _artscene_record_schema, requirement = requirement))
      val chatted = runner.chat(
        AiChatRequest(Vector(AiMessage("user", prompt)), requirement = requirement)
      )
      val missing = runner.generate(
        AiGenerateRequest(prompt, requirement = AiRunnerRequirement(purposeRequired = true))
      )
      val blank = runner.chat(
        AiChatRequest(
          Vector(AiMessage("user", prompt)),
          requirement = AiRunnerRequirement(purpose = Some(" "), purposeRequired = true)
        )
      )

      Then("each invalid purpose fails structurally without invoking the generate provider")
      Vector(generated, recorded, chatted).foreach {
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("AI purpose profile not configured")
        case _ =>
          fail("a required purpose without a profile must fail before execution")
      }
      Vector(missing, blank).foreach {
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("AI purpose is required")
        case _ =>
          fail("a missing or blank required purpose must fail before execution")
      }
      _GenerateServiceState.count(prompt) shouldBe 0
    }

    "reject a required purpose profile that does not select a provider" in {
      Given("an AI runner with a descriptive but providerless purpose profile")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "required-purpose-profile-providerless"
      _GenerateServiceState.reset(prompt)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.role" ->
            ConfigurationValue.StringValue("extractor")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(mode = Some("remote"), engine = Some("http")),
        AiProfileConfig.fromConfiguration(Some(configuration))
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      When("generation requires that purpose")
      val result = runner.generate(
        AiGenerateRequest(
          prompt,
          requirement = AiRunnerRequirement(
            purpose = Some("artscene-exhibition-extraction-from-source"),
            purposeRequired = true
          )
        )
      )

      Then("the default provider is not used")
      result shouldBe a[Consequence.Failure[_]]
      result match
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("AI purpose profile must select a provider")
        case _ =>
          fail("a providerless required purpose profile must fail before execution")
      _GenerateServiceState.count(prompt) shouldBe 0
    }

    "resolve a configured required purpose through its selected provider" in {
      Given("an AI runner with an ArtScene purpose profile that selects Google")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-web-research.provider" ->
            ConfigurationValue.StringValue("google")
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

      When("generation requires the configured purpose")
      val result = runner.generate(
        AiGenerateRequest(
          "required-purpose-profile-configured",
          requirement = AiRunnerRequirement(
            purpose = Some("artscene-exhibition-web-research"),
            purposeRequired = true
          )
        )
      )

      Then("the configured provider executes instead of the component default")
      result.toOption.get.text shouldBe "generated:google:required-purpose-profile-configured"
      result.toOption.get.model shouldBe Some("google")
    }

    "resolve every ArtScene purpose through its configured provider-neutral profile" in {
      Given("the three ArtScene purposes are configured without provider details in requests")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.provider" ->
            ConfigurationValue.StringValue("gemma"),
          "textus.ai.purposes.artscene-exhibition-web-research.provider" ->
            ConfigurationValue.StringValue("google"),
          "textus.ai.purposes.artscene-exhibition-web-research.tools" ->
            ConfigurationValue.StringValue("url_context,web_search"),
          "textus.ai.purposes.artscene-exhibition-managed-research.provider" ->
            ConfigurationValue.StringValue("openai")
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
      def _required_(purpose: String): AiRunnerRequirement =
        AiRunnerRequirement(purpose = Some(purpose), purposeRequired = true)

      When("each purpose is requested through the provider-neutral AI runner contract")
      val extraction = runner.generateRecord(
        AiRecordRequest(
          "strict-record",
          _artscene_record_schema,
          requirement = _required_("artscene-exhibition-extraction-from-source")
        )
      ).toOption.get
      val web = runner.generate(
        AiGenerateRequest(
          "inspect-ai-tools",
          requirement = _required_("artscene-exhibition-web-research")
        )
      ).toOption.get
      val managed = runner.generate(
        AiGenerateRequest(
          "managed-research-purpose",
          requirement = _required_("artscene-exhibition-managed-research")
        )
      ).toOption.get

      Then("the profile selects the expected provider and logical tools without request provider fields")
      extraction.record.getAny("exhibitions") should not be empty
      extraction.metadata(AiExecutionFacts.PROVIDER) shouldBe "gemma"
      extraction.metadata(AiExecutionFacts.PURPOSE) shouldBe "artscene-exhibition-extraction-from-source"
      web.text shouldBe "tools:url_context,web_search"
      web.metadata(AiExecutionFacts.PROVIDER) shouldBe "google"
      web.metadata(AiExecutionFacts.PURPOSE) shouldBe "artscene-exhibition-web-research"
      web.metadata(AiExecutionFacts.TOOLS) shouldBe "url_context,web_search"
      managed.metadata(AiExecutionFacts.PROVIDER) shouldBe "openai"
      managed.metadata(AiExecutionFacts.PURPOSE) shouldBe "artscene-exhibition-managed-research"
    }

    "apply bounded execution defaults from a purpose profile while preserving request overrides" in {
      Given("an AI runner with output-token and timeout defaults for a purpose")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-web-research.provider" ->
            ConfigurationValue.StringValue("google"),
          "textus.ai.purposes.artscene-exhibition-web-research.max-output-tokens" ->
            ConfigurationValue.StringValue("240"),
          "textus.ai.purposes.artscene-exhibition-web-research.timeout-seconds" ->
            ConfigurationValue.StringValue("90")
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
      val requirement = AiRunnerRequirement(purpose = Some("artscene-exhibition-web-research"))

      When("a request uses the profile and another request supplies direct limits")
      val profiled = runner.generate(
        AiGenerateRequest("inspect-profile-policy", requirement = requirement)
      )
      val overridden = runner.generate(
        AiGenerateRequest(
          "inspect-profile-policy",
          maxTokens = Some(12),
          requirement = requirement,
          properties = Vector(Property("ai.timeout-seconds", "17", None))
        )
      )

      Then("the profile fills missing limits but never replaces a request limit")
      profiled.toOption.get.text shouldBe "max:240;timeout:90"
      overridden.toOption.get.text shouldBe "max:12;timeout:17"
    }

    "apply a purpose record retry default before record generation starts" in {
      Given("a purpose profile with two record retries")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "empty-twice-then-strict-record"
      _GenerateServiceState.reset(prompt)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.provider" ->
            ConfigurationValue.StringValue("gemma"),
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.record-retry-limit" ->
            ConfigurationValue.StringValue("2")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(mode = Some("remote"), engine = Some("http")),
        AiProfileConfig.fromConfiguration(Some(configuration))
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      When("structured generation initially receives two blank responses")
      val result = runner.generateRecord(
        AiRecordRequest(
          prompt,
          _artscene_record_schema,
          requirement = AiRunnerRequirement(
            purpose = Some("artscene-exhibition-extraction-from-source")
          )
        )
      )

      Then("the profile retry policy permits the third provider attempt")
      result.toOption.get.record.getAny("exhibitions") should not be empty
      _GenerateServiceState.count(prompt) shouldBe 3
    }

    "enforce a purpose concurrency limit through the provider component scope" in {
      Given("a required purpose with one configured runtime-owned concurrency permit")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "purpose-concurrency-limit"
      _GenerateServiceState.reset(prompt)
      val key = ConcurrencyScopeId.parseC("artscene-exhibition-web-research").toOption.get
      val admission = ScopedConcurrencyAdmission.createC(Vector(ConcurrencyGrant(key, 1))).toOption.get
      val component = _component()
      component.withScopeContext(ScopeContext(
        kind = ScopeKind.Component,
        name = "textus-ai-concurrency-spec",
        parent = None,
        observabilityContext = summon[ExecutionContext].observability,
        scopedConcurrencyAdmissionOption = Some(admission)
      ))
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-web-research.provider" ->
            ConfigurationValue.StringValue("google"),
          "textus.ai.purposes.artscene-exhibition-web-research.max-concurrent" ->
            ConfigurationValue.StringValue("1")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        component,
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        AiProfileConfig.fromConfiguration(Some(configuration))
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get
      val requirement = AiRunnerRequirement(
        purpose = Some("artscene-exhibition-web-research"),
        purposeRequired = true
      )

      When("another operation already holds the purpose permit")
      val lease = admission.acquireC(key).toOption.get
      val saturated = runner.generate(AiGenerateRequest(prompt, requirement = requirement))
      lease.release()
      val admitted = runner.generate(AiGenerateRequest(prompt, requirement = requirement))

      Then("the saturated call fails without provider execution and release restores the same purpose")
      saturated shouldBe a[Consequence.Failure[_]]
      saturated match {
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("Concurrency admission is saturated")
        case _ =>
          fail("a saturated purpose must fail before provider execution")
      }
      _GenerateServiceState.count(prompt) shouldBe 1
      admitted.toOption.get.metadata(AiExecutionFacts.POLICY_MAX_CONCURRENT) shouldBe "1"
      admitted.toOption.get.metadata(AiExecutionFacts.LIMITATION_CODES) should not include "concurrency_not_enforced"
    }

    "reject malformed purpose policy values before invoking a provider" in {
      Given("a required purpose profile with an invalid output-token limit")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "invalid-purpose-policy"
      _GenerateServiceState.reset(prompt)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-managed-research.provider" ->
            ConfigurationValue.StringValue("google"),
          "textus.ai.purposes.artscene-exhibition-managed-research.max-output-tokens" ->
            ConfigurationValue.StringValue("zero")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(mode = Some("remote"), engine = Some("http")),
        AiProfileConfig.fromConfiguration(Some(configuration))
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      When("generation requires the malformed purpose profile")
      val result = runner.generate(
        AiGenerateRequest(
          prompt,
          requirement = AiRunnerRequirement(
            purpose = Some("artscene-exhibition-managed-research"),
            purposeRequired = true
          )
        )
      )

      Then("the policy failure is structured and no provider call occurs")
      result shouldBe a[Consequence.Failure[_]]
      result match
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("Invalid AI purpose max-output-tokens")
        case _ =>
          fail("a malformed purpose policy must fail before execution")
      _GenerateServiceState.count(prompt) shouldBe 0
    }

    "reject malformed purpose concurrency before invoking a provider" in {
      Given("a required purpose profile with an invalid concurrency limit")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "invalid-purpose-concurrency"
      _GenerateServiceState.reset(prompt)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-managed-research.provider" ->
            ConfigurationValue.StringValue("google"),
          "textus.ai.purposes.artscene-exhibition-managed-research.max-concurrent" ->
            ConfigurationValue.StringValue("zero")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(mode = Some("remote"), engine = Some("http")),
        AiProfileConfig.fromConfiguration(Some(configuration))
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      When("generation requires the malformed bounded purpose")
      val result = runner.generate(
        AiGenerateRequest(
          prompt,
          requirement = AiRunnerRequirement(
            purpose = Some("artscene-exhibition-managed-research"),
            purposeRequired = true
          )
        )
      )

      Then("configuration fails before the runtime attempts provider admission")
      result shouldBe a[Consequence.Failure[_]]
      result match {
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("Invalid AI purpose max-concurrent")
        case _ =>
          fail("a malformed concurrency limit must fail before execution")
      }
      _GenerateServiceState.count(prompt) shouldBe 0
    }

    "reject a purpose profile that references an unknown model profile" in {
      Given("a required purpose profile with a missing model profile")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "missing-model-profile"
      _GenerateServiceState.reset(prompt)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-managed-research.model-profile" ->
            ConfigurationValue.StringValue("not-configured")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(mode = Some("remote"), engine = Some("http")),
        AiProfileConfig.fromConfiguration(Some(configuration))
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      When("generation requires that purpose")
      val result = runner.generate(
        AiGenerateRequest(
          prompt,
          requirement = AiRunnerRequirement(
            purpose = Some("artscene-exhibition-managed-research"),
            purposeRequired = true
          )
        )
      )

      Then("the missing model profile is a configuration failure without a provider call")
      result shouldBe a[Consequence.Failure[_]]
      result match
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("AI model profile not configured")
        case _ =>
          fail("an unknown model profile must fail before execution")
      _GenerateServiceState.count(prompt) shouldBe 0
    }

    "require profile-owned prompt and output-schema identities without owning their content" in {
      Given("a record purpose that names a caller-owned prompt contract and output schema")
      given ExecutionContext = ExecutionContext.create()
      val rejectedprompt = "structured-policy-plain"
      val rejectedschema = "structured-policy-record"
      val mismatchschema = "structured-policy-mismatch"
      _GenerateServiceState.reset(rejectedprompt)
      _GenerateServiceState.reset(rejectedschema)
      _GenerateServiceState.reset(mismatchschema)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.provider" ->
            ConfigurationValue.StringValue("gemma"),
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.output-schema-id" ->
            ConfigurationValue.StringValue("artscene.exhibitions.v1"),
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.prompt-contract-id" ->
            ConfigurationValue.StringValue("artscene.exhibition.extract.v1")
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
      val requirement = AiRunnerRequirement(
        purpose = Some("artscene-exhibition-extraction-from-source"),
        purposeRequired = true
      )
      val identities = Vector(
        Property("ai.output-schema-id", "artscene.exhibitions.v1", None),
        Property("ai.prompt-contract-id", "artscene.exhibition.extract.v1", None)
      )

      When("the caller requests a matching record and incompatible operations")
      val accepted = runner.generateRecord(
        AiRecordRequest("strict-record", _artscene_record_schema, requirement = requirement, properties = identities)
      )
      val plain = runner.generate(
        AiGenerateRequest(rejectedprompt, requirement = requirement, properties = identities)
      )
      val missingschema = runner.generateRecord(
        AiRecordRequest(
          rejectedschema,
          _artscene_record_schema,
          requirement = requirement,
          properties = identities.filterNot(_.name == "ai.output-schema-id")
        )
      )
      val mismatchedschema = runner.generateRecord(
        AiRecordRequest(
          mismatchschema,
          _artscene_record_schema,
          requirement = requirement,
          properties = identities.updated(0, Property("ai.output-schema-id", "other.schema.v1", None))
        )
      )
      val missingprompt = runner.chat(
        AiChatRequest(
          Vector(AiMessage("user", "missing prompt contract")),
          requirement = requirement,
          properties = identities.filterNot(_.name == "ai.prompt-contract-id")
        )
      )

      Then("only a matching generateRecord call reaches the provider")
      accepted.toOption.get.record.getAny("exhibitions") should not be empty
      Vector(plain, missingschema, mismatchedschema, missingprompt).foreach {
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("AI purpose")
        case _ =>
          fail("a purpose policy mismatch must fail before provider execution")
      }
      _GenerateServiceState.count(rejectedprompt) shouldBe 0
      _GenerateServiceState.count(rejectedschema) shouldBe 0
      _GenerateServiceState.count(mismatchschema) shouldBe 0
    }

    "reject malformed structured-output and prompt-policy identities before provider execution" in {
      Given("a required purpose profile with an invalid output-schema identity")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "invalid-structured-policy"
      _GenerateServiceState.reset(prompt)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-managed-research.provider" ->
            ConfigurationValue.StringValue("google"),
          "textus.ai.purposes.artscene-exhibition-managed-research.output-schema-id" ->
            ConfigurationValue.StringValue("invalid schema id")
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

      When("the caller requests the malformed required purpose")
      val result = runner.generateRecord(
        AiRecordRequest(
          prompt,
          _artscene_record_schema,
          requirement = AiRunnerRequirement(
            purpose = Some("artscene-exhibition-managed-research"),
            purposeRequired = true
          )
        )
      )

      Then("configuration validation fails before a provider operation")
      result shouldBe a[Consequence.Failure[_]]
      result match {
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("Invalid AI purpose output-schema-id")
        case _ =>
          fail("an invalid output schema identity must fail before execution")
      }
      _GenerateServiceState.count(prompt) shouldBe 0
    }

    "reject profile-owned prompt content before it can reach a provider" in {
      Given("a required purpose profile that embeds a system instruction")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "unsupported-profile-prompt"
      _GenerateServiceState.reset(prompt)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-managed-research.provider" ->
            ConfigurationValue.StringValue("google"),
          "textus.ai.purposes.artscene-exhibition-managed-research.system-instruction" ->
            ConfigurationValue.StringValue("Do not store this prompt content.")
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

      When("the caller requests that purpose")
      val result = runner.generate(
        AiGenerateRequest(
          prompt,
          requirement = AiRunnerRequirement(
            purpose = Some("artscene-exhibition-managed-research"),
            purposeRequired = true
          )
        )
      )

      Then("the raw prompt configuration fails without exposing its content")
      result shouldBe a[Consequence.Failure[_]]
      result match {
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("system-instruction is not supported")
          conclusion.display should not include "Do not store this prompt content."
        case _ =>
          fail("profile-owned prompt content must fail before execution")
      }
      _GenerateServiceState.count(prompt) shouldBe 0
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

    "reject unsupported purpose tools before a provider operation executes" in {
      Given("a required purpose profile that selects Gemma and URL context")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "unsupported-purpose-tools"
      _GenerateServiceState.reset(prompt)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.provider" ->
            ConfigurationValue.StringValue("gemma"),
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.tools" ->
            ConfigurationValue.StringValue("url_context")
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
      val requirement = AiRunnerRequirement(
        purpose = Some("artscene-exhibition-extraction-from-source"),
        purposeRequired = true
      )

      When("generate, record, and chat request that purpose")
      val generated = runner.generate(AiGenerateRequest(prompt, requirement = requirement))
      val recorded = runner.generateRecord(AiRecordRequest(prompt, _artscene_record_schema, requirement = requirement))
      val chatted = runner.chat(
        AiChatRequest(Vector(AiMessage("user", prompt)), requirement = requirement)
      )

      Then("admission rejects every operation before the generate service executes")
      Vector(generated, recorded, chatted).foreach {
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("AI tools are not supported by provider 'gemma'")
        case _ =>
          fail("unsupported purpose tools must fail before provider execution")
      }
      _GenerateServiceState.count(prompt) shouldBe 0
    }

    "resolve a generic purpose through its logical level without caller provider fields" in {
      Given("a standard-consideration level and a generic analysis purpose")
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.levels.standard-consideration.model-profile" -> ConfigurationValue.StringValue("openai-standard-consideration"),
          "textus.ai.model-profiles.openai-standard-consideration.provider" -> ConfigurationValue.StringValue("openai"),
          "textus.ai.model-profiles.openai-standard-consideration.mode" -> ConfigurationValue.StringValue("remote"),
          "textus.ai.model-profiles.openai-standard-consideration.engine" -> ConfigurationValue.StringValue("gpt"),
          "textus.ai.model-profiles.openai-standard-consideration.model" -> ConfigurationValue.StringValue("gpt-5"),
          "textus.ai.model-profiles.openai-standard-consideration.reasoning-level" -> ConfigurationValue.StringValue("medium"),
          "textus.ai.generic-purposes.analysis.level" -> ConfigurationValue.StringValue("standard-consideration"),
          "textus.ai.generic-purposes.analysis.max-output-tokens" -> ConfigurationValue.StringValue("480")
        )),
        ConfigurationTrace.empty
      )
      val profiles = AiProfileConfig.fromConfiguration(Some(configuration))

      When("a caller requires only the generic purpose")
      val result = profiles.resolveRequired(AiRunnerRequirement(
        purpose = Some("analysis"),
        purposeRequired = true
      ))

      Then("the level selects the approved provider/model/reasoning policy")
      result.toOption.map(_.requirement.provider) shouldBe Some(Some("openai"))
      result.toOption.map(_.requirement.model) shouldBe Some(Some("gpt-5"))
      result.toOption.flatMap(_.genericPurpose) shouldBe Some("analysis")
      result.toOption.flatMap(_.logicalLevel) shouldBe Some("standard-consideration")
      result.toOption.flatMap(_.reasoningLevel) shouldBe Some("medium")
      result.toOption.map(_.policy.maxOutputTokens) shouldBe Some(Some(480))
      result.toOption.flatMap(_.requestProperties(Vector.empty).find { property =>
        property.name == "ai.openai.reasoning.effort" && property.value.toString == "medium"
      }) should not be empty
    }

    "permit an application purpose to narrow but not broaden its generic base purpose" in {
      Given("a Web-research generic purpose and one narrowed application purpose")
      val values = Map(
        "textus.ai.levels.standard-consideration.model-profile" -> ConfigurationValue.StringValue("openai-web-research"),
        "textus.ai.model-profiles.openai-web-research.provider" -> ConfigurationValue.StringValue("openai"),
        "textus.ai.model-profiles.openai-web-research.mode" -> ConfigurationValue.StringValue("remote"),
        "textus.ai.model-profiles.openai-web-research.engine" -> ConfigurationValue.StringValue("gpt"),
        "textus.ai.model-profiles.openai-web-research.model" -> ConfigurationValue.StringValue("gpt-5"),
        "textus.ai.generic-purposes.web-research.level" -> ConfigurationValue.StringValue("standard-consideration"),
        "textus.ai.generic-purposes.web-research.tools" -> ConfigurationValue.StringValue("url_context,web_search"),
        "textus.ai.generic-purposes.web-research.max-output-tokens" -> ConfigurationValue.StringValue("480"),
        "textus.ai.generic-purposes.web-research.timeout-seconds" -> ConfigurationValue.StringValue("90"),
        "textus.ai.generic-purposes.web-research.max-concurrent" -> ConfigurationValue.StringValue("2"),
        "textus.ai.purposes.artscene-exhibition-web-research.base-purpose" -> ConfigurationValue.StringValue("web-research"),
        "textus.ai.purposes.artscene-exhibition-web-research.tools" -> ConfigurationValue.StringValue("web_search"),
        "textus.ai.purposes.artscene-exhibition-web-research.max-output-tokens" -> ConfigurationValue.StringValue("240"),
        "textus.ai.purposes.artscene-exhibition-web-research.timeout-seconds" -> ConfigurationValue.StringValue("45"),
        "textus.ai.purposes.artscene-exhibition-web-research.max-concurrent" -> ConfigurationValue.StringValue("1"),
        "textus.ai.purposes.invalid-web-research.base-purpose" -> ConfigurationValue.StringValue("web-research"),
        "textus.ai.purposes.invalid-web-research.tools" -> ConfigurationValue.StringValue("url_context,web_search"),
        "textus.ai.purposes.invalid-web-research.timeout-seconds" -> ConfigurationValue.StringValue("120")
      )
      val profiles = AiProfileConfig.fromConfiguration(Some(ResolvedConfiguration(
        Configuration(values),
        ConfigurationTrace.empty
      )))

      When("the narrowed and broadened application purposes are resolved")
      val narrowed = profiles.resolveRequired(AiRunnerRequirement(
        purpose = Some("artscene-exhibition-web-research"),
        purposeRequired = true
      ))
      val broadened = profiles.resolveRequired(AiRunnerRequirement(
        purpose = Some("invalid-web-research"),
        purposeRequired = true
      ))

      Then("only the application policy that stays within the generic bound is admitted")
      narrowed.toOption.map(_.requirement.tools.map(_.id)) shouldBe Some(Vector("web_search"))
      narrowed.toOption.map(_.policy.maxOutputTokens) shouldBe Some(Some(240))
      narrowed.toOption.map(_.policy.timeoutSeconds) shouldBe Some(Some(45L))
      narrowed.toOption.map(_.policy.maxConcurrent) shouldBe Some(Some(1))
      profiles.concurrencyAdmissionC.toOption.flatten should not be empty
      broadened.isFaillure shouldBe true
      broadened.toString should include ("may not broaden inherited execution policy")
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
          maxTokens = Some(120),
          properties = Vector(Property("ai.tools", "url_context,web_search", None))
        )
      ).toOption.get

      Then("the provider maps logical tools to Gemini tool names")
      driver.calls.head should include ("/v1beta/interactions")
      driver.headers.get("x-goog-api-key") shouldBe Some("test-google-key")
      driver.body.value should include (""""type":"url_context"""")
      driver.body.value should include (""""type":"google_search"""")
      driver.body.value should include ("\"generation_config\":{\"max_output_tokens\":120}")
      response.metadata("ai.provider_tools") shouldBe "url_context,google_search"
      response.metadata("google.google_search_calls") shouldBe "1"
      response.metadata("google.url_citations") shouldBe "1"
    }

    "read Gemini Interactions model output when output_text is absent" in {
      Given("a Google provider response with a thought step before model output")
      val driver = new _FakeHttpDriver(
        """{"id":"v1_test","status":"completed","steps":[{"type":"thought","signature":"opaque"},{"type":"model_output","content":[{"type":"text","text":"{\"exhibitions\":[]}"}]},{"type":"url_context_call"},{"type":"url_context_result","result":[{"url":"https://example.com","status":"success"}]}],"model":"gemini-test"}"""
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

      When("a generate request enables Gemini tools")
      val response = service.generate(
        GenerateRequest(
          prompt = "find official page",
          properties = Vector(Property("ai.tools", "url_context,web_search", None))
        )
      ).toOption.get

      Then("the model output text is used as the AI response")
      response.text shouldBe """{"exhibitions":[]}"""
      response.metadata("google.url_context_calls") shouldBe "1"
    }

    "classify Gemini Interactions responses with no model output text" in {
      Given("a Google provider response where tools ran but the model returned no text")
      val driver = new _FakeHttpDriver(
        """{"id":"v1_test","status":"completed","steps":[{"type":"google_search_call"},{"type":"google_search_result"},{"type":"url_context_call"}],"model":"gemini-test"}"""
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

      When("a generate request enables Gemini tools")
      val response = service.generate(
        GenerateRequest(
          prompt = "find official page",
          properties = Vector(Property("ai.tools", "url_context,web_search", None))
        )
      )

      Then("the provider returns a structured diagnostic instead of a fixed-path missing-property error")
      response shouldBe a[Consequence.Failure[_]]
      response match
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("Google Interactions response did not contain model output text")
          conclusion.display should include ("google_search_call")
          conclusion.display should not include ("steps.0.content.0.text")
        case _ => fail("missing model output should fail")
    }

    "prefer Gemini Interactions model output when output_text is blank" in {
      Given("a Google provider response with blank output_text and model output in steps")
      val driver = new _FakeHttpDriver(
        """{"id":"v1_test","status":"completed","output_text":"  ","steps":[{"type":"model_output","content":[{"type":"text","text":"{\"exhibitions\":[]}"}]}],"model":"gemini-test"}"""
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

      When("a generate request enables Gemini tools")
      val response = service.generate(
        GenerateRequest(
          prompt = "find official page",
          properties = Vector(Property("ai.tools", "url_context,web_search", None))
        )
      ).toOption.get

      Then("the blank top-level output does not hide usable model output text")
      response.text shouldBe """{"exhibitions":[]}"""
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
          maxTokens = Some(120),
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
      driver.body.value should include ("\"max_output_tokens\":120")
      response.metadata("ai.provider_tools") shouldBe "web_search"
      response.metadata("openai.web_search_calls") shouldBe "1"
      response.metadata("openai.response_id") shouldBe "resp_test"
    }

    "map Gemma output and timeout policies to the CNCF HTTP boundary" in {
      Given("a Gemma service with a deterministic HTTP driver")
      val driver = new _FakeHttpDriver(
        """{"response":"gemma answer","done_reason":"stop","prompt_eval_count":9,"eval_count":5}"""
      )
      given ExecutionContext = _context(driver)
      val service = new GemmaOllamaGenerateService(
        GemmaRuntimeConfig(endpoint = URI.create("http://ollama:11434")),
        summon[ExecutionContext]
      )

      When("a bounded generation request reaches the local provider")
      val response = service.generate(
        GenerateRequest(
          "bounded local answer",
          maxTokens = Some(17),
          properties = Vector(Property("ai.timeout-seconds", "44", None))
        )
      ).toOption.get

      Then("Ollama receives its output cap and CNCF receives the effective timeout")
      response.text shouldBe "gemma answer"
      driver.body.value should include ("\"options\":{\"num_predict\":17}")
      driver.lastProperties.find(_.name == "http.timeout-seconds").map(_.value.toString) shouldBe Some("44")
    }

    "reject provider responses that exceed an effective output-token maximum" in {
      Given("a runner whose deterministic Google binding reports eight output tokens")
      given ExecutionContext = ExecutionContext.create()
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("google"), mode = Some("remote"), engine = Some("gemini"))
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get
      val requirement = AiRunnerRequirement(provider = Some("google"))

      When("generate, record, and chat each request a lower output-token maximum")
      val results = Vector(
        runner.generate(AiGenerateRequest("execution-facts", maxTokens = Some(7), requirement = requirement)),
        runner.generateRecord(AiRecordRequest("strict-record", _artscene_record_schema, maxTokens = Some(7), requirement = requirement)),
        runner.chat(AiChatRequest(Vector(AiMessage("user", "limited")), maxTokens = Some(7), requirement = requirement))
      )

      Then("all operations return a structured limit failure rather than a successful over-budget response")
      results.foreach {
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("AI provider output tokens exceeded maximum: limit=7 actual=8")
        case _ =>
          fail("an over-budget provider response must not be returned as success")
      }
    }

    "preserve provider response facts for plain provider endpoints" in {
      Given("plain Google OpenAI and Gemma provider responses")
      val googleDriver = new _FakeHttpDriver(
        """{"responseId":"google_plain","candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"google answer"}]}}],"usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":7,"totalTokenCount":18}}"""
      )
      val googleContext = _context(googleDriver)
      val google = new GoogleGenerateService(
        GoogleRuntimeConfig(
          endpoint = URI.create("https://generativelanguage.googleapis.com"),
          apiKey = "test-google-key",
          model = "gemini-test"
        ),
        googleContext
      ).generate(GenerateRequest("plain")).toOption.get

      val openAiDriver = new _FakeHttpDriver(
        """{"id":"chatcmpl_plain","choices":[{"finish_reason":"stop","message":{"content":"openai answer"}}],"usage":{"prompt_tokens":12,"completion_tokens":6,"total_tokens":18}}"""
      )
      val openAiContext = _context(openAiDriver)
      val openai = new OpenAiGenerateService(
        OpenAiRuntimeConfig(
          endpoint = URI.create("https://api.openai.com"),
          apiKey = "test-openai-key",
          model = "gpt-test"
        ),
        openAiContext
      ).generate(GenerateRequest("plain")).toOption.get

      val gemmaDriver = new _FakeHttpDriver(
        """{"response":"gemma answer","done_reason":"stop","prompt_eval_count":9,"eval_count":5}"""
      )
      val gemmaContext = _context(gemmaDriver)
      val gemma = new GemmaOllamaGenerateService(
        GemmaRuntimeConfig(endpoint = URI.create("http://ollama:11434")),
        gemmaContext
      ).generate(GenerateRequest("plain")).toOption.get

      When("each provider returns a successful plain response")

      Then("its safe response facts remain available for Textus normalization")
      google.metadata("google.response_id") shouldBe "google_plain"
      google.metadata("google.finish_reason") shouldBe "STOP"
      google.metadata("google.usage.total_tokens") shouldBe "18"
      openai.metadata("openai.response_id") shouldBe "chatcmpl_plain"
      openai.metadata("openai.finish_reason") shouldBe "stop"
      openai.metadata("openai.usage.input_tokens") shouldBe "12"
      gemma.metadata("gemma.finish_reason") shouldBe "stop"
      gemma.metadata("gemma.usage.output_tokens") shouldBe "5"
      gemma.metadata("gemma.usage.total_tokens") shouldBe "14"
    }

    "run Gemma structured records through the registered runtime binding" in {
      Given("a Gemma runtime binding backed by a deterministic Ollama response")
      val config = GemmaRuntimeConfig(endpoint = URI.create("http://ollama:11434"))
      val successContext = _context(new _FakeHttpDriver(
        """{"response":"{\"exhibitions\":[{\"title\":\"Gemma Exhibition\",\"period_start\":\"2026-07-01\",\"period_end\":\"2026-07-31\",\"confidence\":88}]}","done_reason":"stop","prompt_eval_count":10,"eval_count":8}"""
      ))
      val successRunner = _gemma_runner(config, successContext)

      When("a structured record request selects the local Gemma provider")
      val success = {
        given ExecutionContext = successContext
        successRunner.generateRecord(AiRecordRequest("structured review", _artscene_record_schema))
      }

      Then("the actual Gemma binding produces a normalized schema-valid record")
      success.toOption.get.record.getAny("exhibitions") should not be empty
      success.toOption.get.metadata(AiExecutionFacts.PROVIDER) shouldBe "gemma"
      success.toOption.get.metadata("gemma.usage.total_tokens") shouldBe "18"

      Given("the same registered binding with an explicit provider failure")
      val failureContext = _context(new _FakeHttpDriver("private provider body", HttpStatus.BadRequest))
      val failureRunner = _gemma_runner(config, failureContext)

      When("structured record generation reaches the failing Gemma endpoint")
      val failure = {
        given ExecutionContext = failureContext
        failureRunner.generateRecord(AiRecordRequest("structured review", _artscene_record_schema))
      }

      Then("the failure is explicit and does not expose the provider body")
      failure shouldBe a[Consequence.Failure[_]]
      failure match
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("category=invalid_request")
          conclusion.display should not include "private provider body"
        case _ => fail("Gemma provider failure was expected")
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

    "classify provider HTTP failures without exposing provider error bodies" in {
      Given("provider HTTP failures containing sensitive error details")
      val cases = Vector(
        429 -> ("quota exhausted for account secret-account", "quota_exhausted"),
        429 -> ("too many requests for secret-account", "rate_limited"),
        504 -> ("upstream body must not be published", "timeout"),
        404 -> ("model gemini-private is unavailable", "model_unavailable"),
        503 -> ("provider internal detail", "unavailable")
      )

      When("failure categories are resolved and an HTTP boundary returns a bad request")
      val categoryResults = cases.map { case (status, (body, _)) =>
        HttpSupport.failureCategory(status, body)
      }
      given ExecutionContext = _context(new _FakeHttpDriver("provider body with secret-account", HttpStatus.BadRequest))
      val httpResult = HttpSupport.post(
        URI.create("https://provider.example"),
        "/v1/generate?key=secret-key",
        io.circe.Json.obj(),
        30L
      )

      Then("categories are stable and the HTTP failure excludes the body and credential")
      categoryResults.zip(cases).foreach { case (actual, (_, (_, expected))) =>
        actual shouldBe expected
      }
      httpResult match
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("category=invalid_request")
          conclusion.display should include ("status=400")
          conclusion.display should not include "secret-account"
          conclusion.display should not include "secret-key"
        case _ => fail("bad request should fail")
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

      Then("only bounded execution facts and payload digests are visible through CallTree metadata")
      generated.toOption.get.model shouldBe Some("remote")
      val calltree = summon[ExecutionContext].observability.callTreeContext.build().value
      val record = ObservabilityEngine.callTreeRecord(calltree)
      val nodes = record.asMap("calltree").asInstanceOf[Seq[Record]]
      val node = nodes.find(_.getString("label").contains("provider:textus-ai-runner:generate")).value
      node.getString("operation") shouldBe Some("generate")
      node.getString("input_digest") shouldBe Some(AiExecutionFacts.digest("trace this prompt"))
      node.getString("output_digest") shouldBe Some(AiExecutionFacts.digest("generated:remote:trace this prompt"))
      node.getString("prompt") shouldBe empty
      node.getString("response") shouldBe empty
      node.getString("task") shouldBe empty
      node.getString("model") shouldBe Some("remote")
    }

    "record structured generation digests instead of raw payloads in the CNCF CallTree" in {
      Given("an AI runner invoked for structured record generation")
      given ExecutionContext =
        ExecutionContext.withFrameworkCallTreeEnabled(ExecutionContext.create(), enabled = true)
      val runner = new TextusAiRunnerProvider(_component())
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(mode = Some("remote"), engine = Some("http"))
        )
        .toOption
        .get

      When("generateRecord normalizes a provider response")
      val generated = runner.generateRecord(
        AiRecordRequest(
          prompt = "strict-record",
          schema = _artscene_record_schema,
          trace = AiRunnerTracePolicy(
            promptConfidentiality = DataConfidentiality.Public,
            responseConfidentiality = DataConfidentiality.Public
          ),
          metadata = Map("operation" -> "record-fetch", "task" -> "calltree-record-spec")
        )
      )

      Then("the normalized facts and digests are visible without raw payloads")
      generated.toOption.get.record.getAny("exhibitions") should not be empty
      val calltree = summon[ExecutionContext].observability.callTreeContext.build().value
      val record = ObservabilityEngine.callTreeRecord(calltree)
      val nodes = record.asMap("calltree").asInstanceOf[Seq[Record]]
      val node = nodes.find(_.getString("label").contains("provider:textus-ai-runner:generate-record")).value
      node.getString("operation") shouldBe Some("generate-record")
      node.getString("input_digest") shouldBe Some(AiExecutionFacts.digest("strict-record"))
      node.getString("output_digest") should not be empty
      node.getString("prompt") shouldBe empty
      node.getString("response") shouldBe empty
      node.getString("output_chars").value.toInt should be > 0
      node.getString("normalization_mode") shouldBe Some("strict-json")
      node.getString("model") shouldBe Some("remote")
    }

    "publish effective purpose policy facts in response metadata and the CNCF CallTree" in {
      Given("a record purpose with bounded policy and caller-owned contract identities")
      given ExecutionContext =
        ExecutionContext.withFrameworkCallTreeEnabled(ExecutionContext.create(), enabled = true)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.provider" ->
            ConfigurationValue.StringValue("gemma"),
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.max-output-tokens" ->
            ConfigurationValue.StringValue("240"),
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.timeout-seconds" ->
            ConfigurationValue.StringValue("90"),
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.record-retry-limit" ->
            ConfigurationValue.StringValue("2"),
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.output-schema-id" ->
            ConfigurationValue.StringValue("artscene.exhibitions.v1"),
          "textus.ai.purposes.artscene-exhibition-extraction-from-source.prompt-contract-id" ->
            ConfigurationValue.StringValue("artscene.exhibition.extract.v1")
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
      val requirement = AiRunnerRequirement(
        purpose = Some("artscene-exhibition-extraction-from-source"),
        purposeRequired = true
      )
      val identities = Vector(
        Property("ai.output-schema-id", "artscene.exhibitions.v1", None),
        Property("ai.prompt-contract-id", "artscene.exhibition.extract.v1", None)
      )

      When("the caller generates a matching structured record")
      val generated = runner.generateRecord(
        AiRecordRequest("strict-record", _artscene_record_schema, requirement = requirement, properties = identities)
      ).toOption.get

      Then("safe effective policy values are attributable without prompt or schema content")
      generated.metadata(AiExecutionFacts.POLICY_MAX_OUTPUT_TOKENS) shouldBe "240"
      generated.metadata(AiExecutionFacts.POLICY_TIMEOUT_SECONDS) shouldBe "90"
      generated.metadata(AiExecutionFacts.POLICY_RECORD_RETRY_LIMIT) shouldBe "2"
      generated.metadata(AiExecutionFacts.POLICY_OUTPUT_SCHEMA_ID) shouldBe "artscene.exhibitions.v1"
      generated.metadata(AiExecutionFacts.POLICY_PROMPT_CONTRACT_ID) shouldBe "artscene.exhibition.extract.v1"
      val calltree = summon[ExecutionContext].observability.callTreeContext.build().value
      val record = ObservabilityEngine.callTreeRecord(calltree)
      val nodes = record.asMap("calltree").asInstanceOf[Seq[Record]]
      val node = nodes.find(_.getString("label").contains("provider:textus-ai-runner:generate-record")).value
      node.getString(AiExecutionFacts.POLICY_MAX_OUTPUT_TOKENS) shouldBe Some("240")
      node.getString(AiExecutionFacts.POLICY_TIMEOUT_SECONDS) shouldBe Some("90")
      node.getString(AiExecutionFacts.POLICY_RECORD_RETRY_LIMIT) shouldBe Some("2")
      node.getString(AiExecutionFacts.POLICY_OUTPUT_SCHEMA_ID) shouldBe Some("artscene.exhibitions.v1")
      node.getString(AiExecutionFacts.POLICY_PROMPT_CONTRACT_ID) shouldBe Some("artscene.exhibition.extract.v1")
      node.getString("prompt") shouldBe empty
      node.getString("response") shouldBe empty
    }

    "record structured generation failure facts without raw payloads" in {
      Given("an AI runner whose structured response does not satisfy the schema")
      given ExecutionContext =
        ExecutionContext.withFrameworkCallTreeEnabled(ExecutionContext.create(), enabled = true)
      val runner = new TextusAiRunnerProvider(_component())
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(mode = Some("remote"), engine = Some("http"))
        )
        .toOption
        .get

      When("generateRecord rejects the provider response")
      val generated = runner.generateRecord(
        AiRecordRequest(
          prompt = "missing-record-field",
          schema = _artscene_record_schema,
          trace = AiRunnerTracePolicy(
            promptConfidentiality = DataConfidentiality.Public,
            responseConfidentiality = DataConfidentiality.Public
          ),
          metadata = Map("operation" -> "record-fetch", "task" -> "calltree-record-failure-spec")
        )
      )

      Then("the failure retains only bounded facts and no error body or raw response")
      generated shouldBe a[Consequence.Failure[_]]
      val calltree = summon[ExecutionContext].observability.callTreeContext.build().value
      val record = ObservabilityEngine.callTreeRecord(calltree)
      val nodes = record.asMap("calltree").asInstanceOf[Seq[Record]]
      val node = nodes.find(_.getString("label").contains("provider:textus-ai-runner:generate-record")).value
      node.getString("outcome") shouldBe Some("failure")
      node.getString("error") shouldBe empty
      node.getString("prompt") shouldBe empty
      node.getString("response") shouldBe empty
      node.getString("input_digest") shouldBe Some(AiExecutionFacts.digest("missing-record-field"))
      node.getString("output_digest") should not be empty
      node.getString("output_chars").value.toInt should be > 0
      node.getString("model") shouldBe Some("remote")
    }

    "normalize structured record generation responses through the AI runner operation" in {
      Given("an AI runner backed by the existing generate service")
      given ExecutionContext = ExecutionContext.create()
      val runner = new TextusAiRunnerProvider(_component())
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(mode = Some("remote"), engine = Some("http"))
        )
        .toOption
        .get

      When("strict JSON is requested as a structured record")
      val result = runner.generateRecord(
        AiRecordRequest(
          prompt = "strict-record",
          schema = _artscene_record_schema,
          requirement = AiRunnerRequirement(purpose = Some("artscene-exhibition-fetch"))
        )
      )

      Then("the JSON is returned as a CNCF Record")
      val response = result.toOption.get
      response.record.getAny("exhibitions") should not be empty
      response.model shouldBe Some("remote")
      response.metadata(AiExecutionFacts.NORMALIZATION_MODE) shouldBe "strict-json"
      response.metadata should not contain "normalization_mode"
    }

    "accept fenced and embedded JSON for structured record generation" in {
      Given("an AI runner backed by provider output variants")
      given ExecutionContext = ExecutionContext.create()
      val runner = new TextusAiRunnerProvider(_component())
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(mode = Some("remote"), engine = Some("http"))
        )
        .toOption
        .get

      When("providers wrap JSON in markdown or prose")
      val fenced = runner.generateRecord(AiRecordRequest("fenced-record", _artscene_record_schema))
      val embedded = runner.generateRecord(AiRecordRequest("embedded-record", _artscene_record_schema))

      Then("textus-ai normalizes both before consumers see the record")
      fenced.toOption.get.metadata(AiExecutionFacts.NORMALIZATION_MODE) shouldBe "fenced-json"
      embedded.toOption.get.metadata(AiExecutionFacts.NORMALIZATION_MODE) shouldBe "embedded-json"
      fenced.toOption.get.metadata should not contain "normalization_mode"
      embedded.toOption.get.metadata should not contain "normalization_mode"
    }

    "reject structured record responses that do not satisfy the schema" in {
      Given("an AI runner that receives incomplete JSON")
      given ExecutionContext = ExecutionContext.create()
      val runner = new TextusAiRunnerProvider(_component())
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(mode = Some("remote"), engine = Some("http"))
        )
        .toOption
        .get

      When("a required field is missing")
      val result = runner.generateRecord(AiRecordRequest("missing-record-field", _artscene_record_schema))

      Then("the operation fails deterministically")
      result shouldBe a[Consequence.Failure[_]]
      result match
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("AI record schema mismatch")
        case _ =>
          fail("schema mismatch should fail")
    }

    "retry structured record generation once for empty provider output" in {
      Given("an AI runner whose first provider response is blank")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "empty-then-strict-record"
      _GenerateServiceState.reset(prompt)
      val runner = new TextusAiRunnerProvider(_component())
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(mode = Some("remote"), engine = Some("http"))
        )
        .toOption
        .get

      When("the retry returns a valid structured record")
      val result = runner.generateRecord(AiRecordRequest(prompt, _artscene_record_schema))

      Then("the retry result is normalized")
      result.toOption.get.record.getAny("exhibitions") should not be empty
      _GenerateServiceState.count(prompt) shouldBe 2
    }

    "not retry structured record generation for schema mismatches" in {
      Given("an AI runner that receives schema-invalid JSON")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "missing-record-field-no-retry"
      _GenerateServiceState.reset(prompt)
      val runner = new TextusAiRunnerProvider(_component())
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(mode = Some("remote"), engine = Some("http"))
        )
        .toOption
        .get

      When("the response parses but does not satisfy the requested schema")
      val result = runner.generateRecord(AiRecordRequest(prompt, _artscene_record_schema))

      Then("the schema failure is returned without another provider call")
      result shouldBe a[Consequence.Failure[_]]
      _GenerateServiceState.count(prompt) shouldBe 1
    }

    "allow structured record retry to be disabled per request" in {
      Given("an AI runner whose first provider response is blank")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "empty-then-strict-record"
      _GenerateServiceState.reset(prompt)
      val runner = new TextusAiRunnerProvider(_component())
        .provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection(mode = Some("remote"), engine = Some("http"))
        )
        .toOption
        .get

      When("the request disables structured generation retry")
      val result = runner.generateRecord(
        AiRecordRequest(
          prompt,
          _artscene_record_schema,
          properties = Vector(Property("ai.record.retry-limit", "0", None))
        )
      )

      Then("the empty response is reported without a second provider call")
      result shouldBe a[Consequence.Failure[_]]
      _GenerateServiceState.count(prompt) shouldBe 1
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
      provider.value.provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption should not be empty
      component.port.get[AiRunner] shouldBe empty
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

    "read Gemma/Ollama endpoint and local runtime selection from CNCF configuration" in {
      Given("a merged configuration with a project-local Gemma runtime profile")
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.gemma.endpoint" -> ConfigurationValue.StringValue("http://127.0.0.1:11434"),
          "textus.ai.gemma.fallback-endpoint" -> ConfigurationValue.StringValue("http://localhost:11435"),
          "textus.ai.gemma.provider" -> ConfigurationValue.StringValue("gemma"),
          "textus.ai.gemma.mode" -> ConfigurationValue.StringValue("local"),
          "textus.ai.gemma.engine" -> ConfigurationValue.StringValue("ollama"),
          "textus.ai.gemma.model" -> ConfigurationValue.StringValue("gemma3:4b"),
          "textus.ai.gemma.timeout-seconds" -> ConfigurationValue.StringValue("45"),
          "textus.ai.gemma.max-concurrency" -> ConfigurationValue.StringValue("3")
        )),
        ConfigurationTrace.empty
      )

      When("the Gemma runtime config is created")
      val config = GemmaConfig.fromConfiguration(configuration)

      Then("only Gemma runtime settings are selected without environment access")
      config.map(_.endpoint.toString) shouldBe Some("http://127.0.0.1:11434")
      config.flatMap(_.fallbackEndpoint.map(_.toString)) shouldBe Some("http://localhost:11435")
      config.map(_.model) shouldBe Some("gemma3:4b")
      config.map(_.timeoutSeconds) shouldBe Some(45L)
      config.map(_.maxConcurrency) shouldBe Some(3)
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

  private def _gemma_runner(
    config: GemmaRuntimeConfig,
    context: ExecutionContext
  ): AiRunner = {
    val component = new Component() {}
      .withBinding("generate", AiRuntimeGenerateBinding.create(Some(config), None, None, None))
      .withBinding("chat", AiRuntimeChatBinding.create(Some(config), None, None, None))
    val provider = new TextusAiRunnerProvider(
      component,
      SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama"))
    )
    given ExecutionContext = context
    provider.provide(
      SpiContract("ai-runner", classOf[AiRunner]),
      SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama"))
    ).toOption.get
  }

  private def _artscene_record_schema: Record =
    Record.dataAuto(
      "required" -> Vector("exhibitions"),
      "arrays" -> Vector(Record.dataAuto(
        "name" -> "exhibitions",
        "required" -> Vector("title", "period_start", "period_end", "confidence")
      ))
    )

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

  private object _GenerateServiceState {
    private var _counts: Map[String, Int] = Map.empty

    def record(prompt: String): Unit =
      _counts = _counts.updated(prompt, count(prompt) + 1)

    def count(prompt: String): Int =
      _counts.getOrElse(prompt, 0)

    def reset(prompt: String): Unit =
      _counts = _counts - prompt
  }

  private final case class _GenerateService(
    name: String
  ) extends GenerateService {
    def generate(req: GenerateRequest): Consequence[GenerateResponse] = {
      _GenerateServiceState.record(req.prompt)
      if (req.prompt == "inspect-properties")
        Consequence.success(GenerateResponse(s"timeout:${_property(req, "ai.timeout-seconds").getOrElse("none")}", Some(name)))
      else if (req.prompt == "inspect-profile-policy")
        Consequence.success(GenerateResponse(
          s"max:${req.maxTokens.map(_.toString).getOrElse("none")};timeout:${_property(req, "ai.timeout-seconds").getOrElse("none")}",
          Some(name)
        ))
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
      else if (req.prompt == "strict-record")
        Consequence.success(GenerateResponse(_record_json("Strict Record Exhibition"), Some(name), _provider_metadata(name)))
      else if (req.prompt == "execution-facts")
        Consequence.success(GenerateResponse(s"generated:$name:${req.prompt}", Some(name), _provider_metadata(name)))
      else if (req.prompt == "fenced-record")
        Consequence.success(GenerateResponse(s"```json\n${_record_json("Fenced Record Exhibition")}\n```", Some(name)))
      else if (req.prompt == "embedded-record")
        Consequence.success(GenerateResponse(s"Here is the structured result:\n${_record_json("Embedded Record Exhibition")}\nUse it as JSON.", Some(name)))
      else if (req.prompt == "empty-then-strict-record" && _GenerateServiceState.count(req.prompt) == 1)
        Consequence.success(GenerateResponse("  ", Some(name)))
      else if (req.prompt == "empty-then-strict-record")
        Consequence.success(GenerateResponse(_record_json("Retried Record Exhibition"), Some(name)))
      else if (req.prompt == "empty-twice-then-strict-record" && _GenerateServiceState.count(req.prompt) <= 2)
        Consequence.success(GenerateResponse("  ", Some(name)))
      else if (req.prompt == "empty-twice-then-strict-record")
        Consequence.success(GenerateResponse(_record_json("Retried Twice Record Exhibition"), Some(name)))
      else if (req.prompt == "missing-record-field" || req.prompt == "missing-record-field-no-retry")
        Consequence.success(GenerateResponse("""{"exhibitions":[{"title":"Missing Date","confidence":51}]}""", Some(name)))
      else
        Consequence.success(GenerateResponse(s"generated:$name:${req.prompt}", Some(name)))
    }

    private def _record_json(
      title: String
    ): String =
      s"""{"exhibitions":[{"title":"$title","period_start":"2026-07-01","period_end":"2026-07-31","confidence":77}]}"""

    private def _property(
      req: GenerateRequest,
      name: String
    ): Option[String] =
      req.properties.find(_.name == name).map(x => String.valueOf(x.value))

    private def _provider_metadata(name: String): Map[String, String] =
      Map(
        s"$name.response_id" -> s"response-$name",
        s"$name.finish_reason" -> "stop",
        s"$name.usage.input_tokens" -> "13",
        s"$name.usage.output_tokens" -> "8",
        s"$name.usage.total_tokens" -> "21"
      )
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
          Some(name),
          _provider_metadata(name)
        )
      )
    }

    private def _provider_metadata(name: String): Map[String, String] =
      Map(
        s"$name.response_id" -> s"response-$name",
        s"$name.finish_reason" -> "stop",
        s"$name.usage.input_tokens" -> "13",
        s"$name.usage.output_tokens" -> "8",
        s"$name.usage.total_tokens" -> "21"
      )
  }

  private final class _FakeHttpDriver(
    response: String,
    status: HttpStatus = HttpStatus.Ok
  ) extends HttpDriver {
    var calls: Vector[String] = Vector.empty
    var body: Option[String] = None
    var headers: Map[String, String] = Map.empty
    private var _last_properties: Vector[Property] = Vector.empty

    def lastProperties: Vector[Property] = _last_properties

    override def get(
      path: String,
      headers: Map[String, String],
      properties: Vector[Property] = Vector.empty
    ): HttpResponse =
      _http_response(response, status)

    override def post(
      path: String,
      body: Option[String],
      headers: Map[String, String],
      properties: Vector[Property] = Vector.empty
    ): HttpResponse = {
      calls = calls :+ s"POST $path"
      this.body = body
      this.headers = headers
      _last_properties = properties
      _http_response(response, status)
    }

    override def put(
      path: String,
      body: Option[String],
      headers: Map[String, String],
      properties: Vector[Property] = Vector.empty
    ): HttpResponse =
      _http_response(response, status)
  }

  private def _http_response(body: String, status: HttpStatus = HttpStatus.Ok): HttpResponse =
    HttpResponse.Text(
      status,
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

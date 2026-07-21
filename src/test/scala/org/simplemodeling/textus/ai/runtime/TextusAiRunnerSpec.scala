package org.simplemodeling.textus.ai.runtime

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CountDownLatch, TimeUnit}
import cats.~>
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt
import org.goldenport.Consequence
import org.goldenport.cncf.admission.{ConcurrencyGrant, ConcurrencyScopeId, ScopedConcurrencyAdmission}
import org.goldenport.cncf.component.{Component, ExtensionPoint, Port}
import org.goldenport.cncf.context.{ExecutionContext, RuntimeContext, ScopeContext, ScopeKind}
import org.goldenport.cncf.http.HttpDriver
import org.goldenport.cncf.mcp.client.McpServerSetId
import org.goldenport.cncf.operationtool.OperationToolSetId
import org.goldenport.cncf.observability.ObservabilityEngine
import org.goldenport.cncf.spi.{SpiContract, SpiResolver, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.{AiChatRequest, AiExecutionClass, AiGenerateRequest, AiMessage, AiRecordRequest, AiRunner, AiRunnerApplicationPurpose, AiRunnerApplicationPurposePolicy, AiRunnerApplicationPurposeRegistration, AiRunnerApplicationPurposeRegistrationSocketSet, AiRunnerRequirement, AiRunnerTracePolicy, AiTool}
import org.goldenport.cncf.subsystem.Subsystem
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
import org.simplemodeling.textus.ai.provider.anthropic.{AnthropicConfig, AnthropicGenerateService, AnthropicRuntimeConfig}
import org.simplemodeling.textus.ai.provider.claude.{ClaudeCodeGenerateService, ClaudeCodeRuntimeConfig}
import org.simplemodeling.textus.ai.provider.gemma.{GemmaConfig, GemmaOllamaGenerateService, GemmaRuntimeConfig}
import org.simplemodeling.textus.ai.provider.google.{GoogleConfig, GoogleGenerateService, GoogleRuntimeConfig}
import org.simplemodeling.textus.ai.provider.openai.{OpenAiGenerateService, OpenAiRuntimeConfig}
import org.simplemodeling.textus.ai.provider.openai.OpenAiConfig

/*
 * @since   Jul.  2, 2026
 * @version Jul. 22, 2026
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
        metadata(AiExecutionFacts.INPUT_TOKENS_SOURCE) shouldBe "reported"
        metadata(AiExecutionFacts.OUTPUT_TOKENS) shouldBe "8"
        metadata(AiExecutionFacts.OUTPUT_TOKENS_SOURCE) shouldBe "reported"
        metadata(AiExecutionFacts.TOTAL_TOKENS) shouldBe "21"
        metadata(AiExecutionFacts.TOTAL_TOKENS_SOURCE) shouldBe "reported"
        metadata(AiExecutionFacts.OUTPUT_DIGEST) should startWith ("sha256:")
        metadata(AiExecutionFacts.LIMITATION_CODES) shouldBe
          "cancellation_not_propagated,concurrency_not_enforced,rate_schedule_unavailable"
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

    "resolve an application purpose through a runtime-profile class binding" in {
      Given("an AI runner configured with a Gemini profile and application-purpose mapping")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
          "textus.ai.execution-classes.standard-work.model" -> ConfigurationValue.StringValue("gemini-worker")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        _profiles(configuration, "linear-feature.worker.anchor-plan" -> "structured-extraction")
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

      Then("the runtime profile chooses the provider and model for that purpose")
      generated.toOption.get.text shouldBe "purpose:linear-feature.worker.anchor-plan;model:gemini-worker"
      generated.toOption.get.model shouldBe Some("google")
    }

    "route a Gemma profile purpose through the registered Ollama binding" in {
      Given("a Gemma profile and the existing Gemma/Ollama HTTP bindings")
      val driver = new _FakeHttpDriver(
        """{"response":"local answer","done_reason":"stop","prompt_eval_count":7,"eval_count":3}"""
      )
      given ExecutionContext = _context(driver)
      val configuration = ResolvedConfiguration(
        Configuration(Map("textus.ai.profile" -> ConfigurationValue.StringValue("gemma"))),
        ConfigurationTrace.empty
      )
      val component = new Component() {}
        .withBinding("generate", AiRuntimeGenerateBinding.create(Some(GemmaConfig.default), None, None, None))
        .withBinding("chat", AiRuntimeChatBinding.create(Some(GemmaConfig.default), None, None, None))
      val runner = new TextusAiRunnerProvider(
        component,
        SpiSelection(),
        AiProfileConfig.fromConfiguration(Some(configuration))
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      When("a caller specifies only the standard simple-work purpose")
      val response = runner.generate(AiGenerateRequest(
        "answer locally",
        requirement = AiRunnerRequirement(purpose = Some("simple-work"))
      )).toOption.get

      Then("the profile-owned Gemma model reaches the registered Ollama endpoint")
      response.text shouldBe "local answer"
      response.model shouldBe Some("gemma:2b")
      response.metadata(AiExecutionFacts.PROVIDER) shouldBe "gemma"
      driver.calls.head should include ("/api/generate")
      driver.body.value should include ("\"model\":\"gemma:2b\"")
    }

    "execute an explicit Gemma-first profile and expose safe attempt lineage" in {
      Given("a Gemma-first profile whose local provider is unavailable")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "gemma-unavailable"
      _GenerateServiceState.reset(prompt)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemma-first-gemini"),
          "textus.ai.execution-classes.standard-work.max-cost-microunits" -> ConfigurationValue.StringValue("1000000"),
          "textus.ai.execution-classes.standard-work.max-output-tokens" -> ConfigurationValue.StringValue("100"),
          "textus.ai.execution-classes.standard-work.rate-schedule" -> ConfigurationValue.StringValue("gemma-standard"),
          "textus.ai.execution-classes.standard-work.fallback-rate-schedule" -> ConfigurationValue.StringValue("gemini-standard"),
          "textus.ai.rate-schedules.gemma-standard.input-microunits-per-million-tokens" -> ConfigurationValue.StringValue("0"),
          "textus.ai.rate-schedules.gemma-standard.cached-input-microunits-per-million-tokens" -> ConfigurationValue.StringValue("0"),
          "textus.ai.rate-schedules.gemma-standard.output-microunits-per-million-tokens" -> ConfigurationValue.StringValue("0"),
          "textus.ai.rate-schedules.gemma-standard.reasoning-microunits-per-million-tokens" -> ConfigurationValue.StringValue("0"),
          "textus.ai.rate-schedules.gemini-standard.input-microunits-per-million-tokens" -> ConfigurationValue.StringValue("100"),
          "textus.ai.rate-schedules.gemini-standard.cached-input-microunits-per-million-tokens" -> ConfigurationValue.StringValue("20"),
          "textus.ai.rate-schedules.gemini-standard.output-microunits-per-million-tokens" -> ConfigurationValue.StringValue("500"),
          "textus.ai.rate-schedules.gemini-standard.reasoning-microunits-per-million-tokens" -> ConfigurationValue.StringValue("0")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        _profiles(configuration, "sanpomap-scenario-generation" -> "software-implementation")
      ).provide(SpiContract("ai-runner", classOf[AiRunner]), SpiSelection()).toOption.value

      When("the application requests only its registered purpose")
      val response = runner.generate(AiGenerateRequest(
        prompt,
        requirement = AiRunnerRequirement(
          purpose = Some("sanpomap-scenario-generation"),
          purposeRequired = true
        )
      )).toOption.value

      Then("Gemma is attempted first and the admitted Gemini fallback becomes final")
      response.text shouldBe "generated:google:gemma-unavailable"
      response.metadata(AiExecutionFacts.OPERATIONAL_STRATEGY) shouldBe "structured"
      response.metadata(AiExecutionFacts.ATTEMPT_LINEAGE) shouldBe
        "1:gemma:initial:failure,2:google:commercial-fallback:success"
      response.metadata(AiExecutionFacts.ESCALATION_REASON) shouldBe "availability"
      response.metadata(AiExecutionFacts.FINAL_PROVIDER) shouldBe "google"
      response.metadata(AiExecutionFacts.REPAIR_COUNT) shouldBe "0"
      response.metadata(AiExecutionFacts.LIMITATION_CODES) should include ("cost_estimated")
      response.metadata(AiExecutionFacts.LIMITATION_CODES) should not include ("rate_schedule_unavailable")
      response.metadata(AiExecutionFacts.DURATION_MILLIS).toLong should be >= 0L
      response.metadata.keys.exists(key =>
        key.toLowerCase.contains("credential") ||
          key.toLowerCase.contains("raw_payload") ||
          key.toLowerCase.contains("prompt_text")
      ) shouldBe false
      response.metadata.values.exists(_.contains(prompt)) shouldBe false
      _GenerateServiceState.count(prompt) shouldBe 2
    }

    "execute a tool-grounded strategy through the runtime-owned MCP path" in {
      Given("a Gemma-first web-analysis purpose with one operator-owned MCP server set")
      given ExecutionContext = ExecutionContext.create()
      var selectedserverset: Option[String] = None
      var selectedoperationtoolset: Option[String] = None
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemma-first-gemini"),
          "textus.ai.execution-classes.deep-consideration.mcp-server-set" ->
            ConfigurationValue.StringValue("research-tools")
        )),
        ConfigurationTrace.empty
      )
      val provider = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        _profiles(configuration, "sanpomap-location-investigation" -> "web-analysis")
      ) {
        override private[runtime] def generateWithToolsC(
          selection: SpiSelection,
          mcpServerSet: Option[McpServerSetId],
          operationToolSet: Option[OperationToolSetId],
          prompt: String,
          temperature: Option[Double],
          maxTokens: Option[Int],
          properties: Vector[Property]
        )(using ExecutionContext): Consequence[GenerateResponse] = {
          selectedserverset = mcpServerSet.map(_.print)
          selectedoperationtoolset = operationToolSet.map(_.print)
          Consequence.success(GenerateResponse(
            "tool-grounded result",
            Some("gemma-fixture"),
            Map(
              "gemma.usage.input_tokens" -> "4",
              "gemma.usage.output_tokens" -> "2",
              "gemma.usage.total_tokens" -> "6",
              "gemma.tool_calls" -> "1",
              "gemma.tool_turns" -> "2",
              "gemma.mcp_calls" -> "1",
              "gemma.operation_calls" -> "0",
              "gemma.mcp_turns" -> "2",
              "gemma.mcp_catalog_digest" -> "sha256:fixture"
            )
          ))
        }
      }
      val runner = provider.provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.value

      When("the application requests only its registered investigation purpose")
      val response = runner.generate(AiGenerateRequest(
        "research a station",
        requirement = AiRunnerRequirement(
          purpose = Some("sanpomap-location-investigation"),
          purposeRequired = true
        )
      )).toOption.value

      Then("the selected runtime path receives the resolved server set and publishes safe evidence")
      selectedserverset shouldBe Some("research-tools")
      selectedoperationtoolset shouldBe None
      response.text shouldBe "tool-grounded result"
      response.metadata(AiExecutionFacts.OPERATIONAL_STRATEGY) shouldBe "tool-grounded"
      response.metadata(AiExecutionFacts.ATTEMPT_LINEAGE) shouldBe "1:gemma:tool-grounded:success"
      response.metadata("gemma.mcp_calls") shouldBe "1"
      response.metadata("gemma.mcp_turns") shouldBe "2"
      response.metadata.values.exists(_.contains("research a station")) shouldBe false
    }

    "never convert an input or policy failure into commercial fallback" in {
      Given("the same explicit Gemma-first profile and a terminal primary failure")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "strategy-input-denied"
      _GenerateServiceState.reset(prompt)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemma-first-gemini")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        _profiles(configuration, "sanpomap-scenario-generation" -> "software-implementation")
      ).provide(SpiContract("ai-runner", classOf[AiRunner]), SpiSelection()).toOption.value

      When("the primary provider returns a terminal input failure")
      val result = runner.generate(AiGenerateRequest(
        prompt,
        requirement = AiRunnerRequirement(
          purpose = Some("sanpomap-scenario-generation"),
          purposeRequired = true
        )
      ))

      Then("the original structured failure remains terminal and the commercial provider is not called")
      result should matchPattern { case Consequence.Failure(_) => }
      val detail = result match {
        case Consequence.Failure(conclusion) => conclusion.display
        case _ => fail("expected a terminal strategy failure")
      }
      detail should include ("fixture input denied")
      detail should not include "without admitted fallback"
      _GenerateServiceState.count(prompt) shouldBe 1
    }

    "report a safe class when the admitted commercial fallback also fails" in {
      Given("a Gemma availability failure followed by a remote availability failure")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "all-providers-unavailable"
      _GenerateServiceState.reset(prompt)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemma-first-gemini")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        _profiles(configuration, "sanpomap-scenario-generation" -> "software-implementation")
      ).provide(SpiContract("ai-runner", classOf[AiRunner]), SpiSelection()).toOption.value

      When("both provider attempts fail")
      val result = runner.generate(AiGenerateRequest(
        prompt,
        requirement = AiRunnerRequirement(
          purpose = Some("sanpomap-scenario-generation"),
          purposeRequired = true
        )
      ))

      Then("the response exposes only the terminal fallback class")
      val detail = result match {
        case Consequence.Failure(conclusion) => conclusion.display
        case _ => fail("expected fallback execution failure")
      }
      detail should include ("Commercial fallback execution failed: availability")
      detail should not include prompt
      _GenerateServiceState.count(prompt) shouldBe 2
    }

    "use the factory-owned subsystem for application acceptance operations" in {
      Given("a provider whose captured component predates subsystem attachment")
      given ExecutionContext = ExecutionContext.create()
      val subsystem = new Subsystem(
        "textus-ai-acceptance-owner-spec",
        configuration = ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty)
      )
      val provider = new TextusAiRunnerProvider(
        _component(),
        assembledSubsystem = Some(subsystem)
      )

      When("an acceptance operation is resolved")
      val result = provider.evaluateCandidateC(
        Some("Missing.Evaluation.evaluate"),
        Some("fixture-purpose"),
        "candidate",
        0,
        1
      )

      Then("the explicit subsystem is used instead of the stale component copy")
      val detail = result match {
        case Consequence.Failure(conclusion) => conclusion.display
        case _ => fail("expected the missing fixture operation to fail")
      }
      detail should include ("Application acceptance operation invocation failed")
      detail should not include "requires an assembled subsystem"
    }

    "apply bounded Gemma repair before accepting a candidate" in {
      Given("a Gemma-first profile and a deterministic application acceptance gate")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemma-first-gemini"),
          "textus.ai.application-purposes.sanpomap-scenario-generation.acceptance-operation" ->
            ConfigurationValue.StringValue("Sanpomap.Evaluation.evaluateAiCandidate")
        )),
        ConfigurationTrace.empty
      )
      val provider = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        _profiles(configuration, "sanpomap-scenario-generation" -> "software-implementation")
      ) {
        override private[runtime] def evaluateCandidateC(
          operation: Option[String],
          purpose: Option[String],
          candidate: String,
          repairCount: Int,
          maxRepairs: Int
        ): Consequence[AiCandidateAcceptance] =
          if (repairCount == 0)
            Consequence.success(AiCandidateAcceptance(
              "repair",
              "diagnostics: [{code: missing-route}]",
              "scenario.route_intent",
              None
            ))
          else
            Consequence.success(AiCandidateAcceptance("accept", "", "", None))
      }
      val runner = provider.provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.value

      When("the first Gemma candidate requires one repair")
      val response = runner.generate(AiGenerateRequest(
        "repair-once",
        requirement = AiRunnerRequirement(
          purpose = Some("sanpomap-scenario-generation"),
          purposeRequired = true
        )
      )).toOption.value

      Then("the repaired Gemma candidate is final without commercial escalation")
      response.metadata(AiExecutionFacts.REPAIR_COUNT) shouldBe "1"
      response.metadata(AiExecutionFacts.FINAL_PROVIDER) shouldBe "gemma"
      response.metadata(AiExecutionFacts.ATTEMPT_LINEAGE) shouldBe
        "1:gemma:initial:success,2:gemma:repair:success"
      response.metadata.get(AiExecutionFacts.ESCALATION_REASON) shouldBe None
    }

    "escalate only after the configured Gemma repair bound is exhausted" in {
      Given("an acceptance gate that keeps rejecting Gemma domain output but accepts Gemini")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemma-first-gemini"),
          "textus.ai.application-purposes.sanpomap-scenario-generation.acceptance-operation" ->
            ConfigurationValue.StringValue("Sanpomap.Evaluation.evaluateAiCandidate")
        )),
        ConfigurationTrace.empty
      )
      val provider = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        _profiles(configuration, "sanpomap-scenario-generation" -> "software-implementation")
      ) {
        override private[runtime] def evaluateCandidateC(
          operation: Option[String],
          purpose: Option[String],
          candidate: String,
          repairCount: Int,
          maxRepairs: Int
        ): Consequence[AiCandidateAcceptance] =
          if (candidate.startsWith("generated:google:"))
            Consequence.success(AiCandidateAcceptance("accept", "", "", None))
          else
            Consequence.success(AiCandidateAcceptance(
              "repair",
              "diagnostics: [{code: domain-invalid}]",
              "scenario.route_intent",
              None
            ))
      }
      val runner = provider.provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.value

      When("Gemma still fails after its one admitted repair")
      val response = runner.generate(AiGenerateRequest(
        "repair-then-escalate",
        requirement = AiRunnerRequirement(
          purpose = Some("sanpomap-scenario-generation"),
          purposeRequired = true
        )
      )).toOption.value

      Then("the explicit profile performs one commercial attempt and records why")
      response.metadata(AiExecutionFacts.REPAIR_COUNT) shouldBe "1"
      response.metadata(AiExecutionFacts.ESCALATION_REASON) shouldBe "domain-validation"
      response.metadata(AiExecutionFacts.FINAL_PROVIDER) shouldBe "google"
      response.metadata(AiExecutionFacts.ATTEMPT_LINEAGE) shouldBe
        "1:gemma:initial:success,2:gemma:repair:success,3:google:commercial-fallback:success"
    }

    "reject a caller attempt to override an application-purpose runtime binding" in {
      Given("an AI runner with a mapped application purpose and operator class binding")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
          "textus.ai.execution-classes.standard-work.model" -> ConfigurationValue.StringValue("operator-profile-model")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        _profiles(configuration, "sanpomap-scenario-generation" -> "structured-extraction")
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

      And("another request attempts to select local Gemma")
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

      Then("the runtime profile controls the first request and rejects caller selection")
      profiled.toOption.get.model shouldBe Some("google")
      profiled.toOption.get.text shouldBe "purpose:sanpomap-scenario-generation;model:operator-profile-model"
      overridden.isFaillure shouldBe true
      overridden.toString should include ("callers may select only an application purpose")
    }

    "reject operator attempts to replace the selected runtime provider" in {
      Given("a configured application purpose that attempts to choose a provider")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
          "textus.ai.application-purposes.sanpomap-location-investigation.provider" -> ConfigurationValue.StringValue("unavailable-provider")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        _profiles(configuration, "sanpomap-location-investigation" -> "software-analysis")
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      When("a request selects the invalid application purpose")
      val result = runner.generate(
        AiGenerateRequest(
          prompt = "inspect-ai-selection",
          requirement = AiRunnerRequirement(purpose = Some("sanpomap-location-investigation"))
        )
      )

      Then("the request fails instead of accepting an application-owned provider")
      result shouldBe a[Consequence.Failure[_]]
      result match
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("application purpose may not configure this key")
        case _ => fail("application purposes must not choose a provider")
    }

    "reject an unknown required application purpose before any AI operation reaches a provider" in {
      Given("an AI runner with a selected runtime profile but no application mapping")
      given ExecutionContext = ExecutionContext.create()
      val prompt = "required-application-purpose-unconfigured"
      _GenerateServiceState.reset(prompt)
      val configuration = ResolvedConfiguration(
        Configuration(Map("textus.ai.profile" -> ConfigurationValue.StringValue("gemini"))),
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
      val requirement = AiRunnerRequirement(
        purpose = Some("artscene-exhibition-web-research"),
        purposeRequired = true
      )

      When("generate, record generation, and chat request that purpose")
      val generated = runner.generate(AiGenerateRequest(prompt, requirement = requirement))
      val recorded = runner.generateRecord(AiRecordRequest(prompt, _artscene_record_schema, requirement = requirement))
      val chatted = runner.chat(AiChatRequest(Vector(AiMessage("user", prompt)), requirement = requirement))

      Then("each unknown application purpose fails structurally without invoking the provider")
      Vector(generated, recorded, chatted).foreach {
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("AI application purpose is not registered")
        case _ => fail("an unknown application purpose must fail before execution")
      }
      _GenerateServiceState.count(prompt) shouldBe 0
    }

    "admit a purpose registered through a Port after the runtime scope and enforce its concurrency limit" in {
      Given("a runtime scope with no bootstrap application admission")
      given ExecutionContext = ExecutionContext.create()
      given scalacontext: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
          "textus.ai.execution-classes.standard-work.provider" -> ConfigurationValue.StringValue("gemma"),
          "textus.ai.execution-classes.standard-work.mode" -> ConfigurationValue.StringValue("local"),
          "textus.ai.execution-classes.standard-work.engine" -> ConfigurationValue.StringValue("ollama"),
          "textus.ai.execution-classes.standard-work.model" -> ConfigurationValue.StringValue("fixture")
        )),
        ConfigurationTrace.empty
      )
      val registrations = new AiRunnerApplicationPurposeRegistrationSocketSet {}
      val profiles = AiProfileConfig.fromConfiguration(
        Some(configuration),
        AiApplicationPurposeCatalog.fromSocket(registrations)
      )
      val state = new AiConcurrencyAdmissionState()
      val component = _component()
      component.withScopeContext(ScopeContext(
        ScopeKind.Component,
        "textus-ai-late-concurrency-port-spec",
        None,
        summon[ExecutionContext].observability
      ))
      state.registerBootstrap(Set.empty)
      val provider = new TextusAiRunnerProvider(
        component,
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        profiles,
        state
      )
      val runtime = component.withPort(
        Component.Port
          .of(provider)
          .orElse(Component.Port.input(registrations))
          .orElse(component.port)
      )
      val application = new Component() {}.withPort(Component.Port.of(
        AiRunnerApplicationPurposeRegistration(Vector(AiRunnerApplicationPurpose(
          "sanpomap-location-investigation",
          "software-implementation",
          AiRunnerApplicationPurposePolicy(maxConcurrent = Some(1))
        )))
      ))

      When("the application registration is bound through its Port after the runtime scope exists")
      SpiResolver.resolve(Vector(application, runtime)) shouldBe a[Consequence.Success[_]]
      val runner = provider.provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama"))
      ).toOption.get
      val policy = profiles.resolveRequired(AiRunnerRequirement(
        purpose = Some("sanpomap-location-investigation"),
        purposeRequired = true
      )).toOption.get.policy
      val started = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val first = Future {
        provider.withConcurrencyAdmissionC(policy) {
          started.countDown()
          release.await(2, TimeUnit.SECONDS)
          Consequence.success("first")
        }
      }
      started.await(1, TimeUnit.SECONDS) shouldBe true
      val second = provider.withConcurrencyAdmissionC(policy)(Consequence.success("second"))
      release.countDown()

      Then("the late purpose is resolved from the Port and its one-request admission is enforced")
      second shouldBe a[Consequence.Failure[_]]
      Await.result(first, 2.seconds) shouldBe a[Consequence.Success[_]]
      runner.generate(AiGenerateRequest(
        "late-concurrency-registration",
        requirement = AiRunnerRequirement(
          purpose = Some("sanpomap-location-investigation"),
          purposeRequired = true
        )
      )) shouldBe a[Consequence.Success[_]]
    }

    "preserve a bootstrap admission while admitting a late-registered application purpose" in {
      Given("a component scope with one bootstrap concurrency grant and a distinct late application purpose")
      given ExecutionContext = ExecutionContext.create()
      val bootstrapscope = ConcurrencyScopeId.parseC("bootstrap-web-analysis").toOption.get
      val admission = ScopedConcurrencyAdmission.createC(Vector(ConcurrencyGrant(bootstrapscope, 1))).toOption.get
      val state = new AiConcurrencyAdmissionState()
      state.registerBootstrap(Set(bootstrapscope))
      val scope = ScopeContext(
        ScopeKind.Component,
        "textus-ai-late-concurrency-spec",
        None,
        summon[ExecutionContext].observability,
        scopedConcurrencyAdmissionOption = Some(admission)
      )
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
          "textus.ai.execution-classes.standard-work.provider" -> ConfigurationValue.StringValue("gemma"),
          "textus.ai.execution-classes.standard-work.mode" -> ConfigurationValue.StringValue("local"),
          "textus.ai.execution-classes.standard-work.engine" -> ConfigurationValue.StringValue("ollama"),
          "textus.ai.execution-classes.standard-work.model" -> ConfigurationValue.StringValue("fixture")
        )),
        ConfigurationTrace.empty
      )
      val profiles = AiProfileConfig.fromConfiguration(
        Some(configuration),
        AiApplicationPurposeCatalog.fromRegistrations(Vector(
          AiRunnerApplicationPurposeRegistration(Vector(
            AiRunnerApplicationPurpose(
              "bootstrap-web-analysis",
              "software-implementation",
              AiRunnerApplicationPurposePolicy(maxConcurrent = Some(1))
            ),
            AiRunnerApplicationPurpose(
              "late-web-analysis",
              "software-implementation",
              AiRunnerApplicationPurposePolicy(maxConcurrent = Some(1))
            )
          ))
        ))
      )
      val runner = new TextusAiRunnerProvider(
        _component().withScopeContext(scope),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        profiles,
        state
      ).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama"))
      ).toOption.get
      val lease = admission.acquireC(bootstrapscope).toOption

      When("the bootstrap and late purposes run after the bootstrap scope is established")
      val bootstrap = runner.generate(AiGenerateRequest(
        "bootstrap-concurrency-registration",
        requirement = AiRunnerRequirement(
          purpose = Some("bootstrap-web-analysis"),
          purposeRequired = true
        )
      ))
      lease.foreach(_.release())
      val late = runner.generate(AiGenerateRequest(
        "late-concurrency-registration-with-bootstrap",
        requirement = AiRunnerRequirement(
          purpose = Some("late-web-analysis"),
          purposeRequired = true
        )
      ))

      Then("the bootstrap limit remains active and the late purpose receives its own admission")
      bootstrap.isFaillure shouldBe true
      late shouldBe a[Consequence.Success[_]]
    }

    "apply an application-purpose policy to a runtime profile without caller selection" in {
      Given("a Gemini profile, a bounded standard class, and a mapped record purpose")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
          "textus.ai.execution-classes.standard-work.max-input-tokens" -> ConfigurationValue.StringValue("128"),
          "textus.ai.execution-classes.standard-work.max-output-tokens" -> ConfigurationValue.StringValue("240"),
          "textus.ai.execution-classes.standard-work.timeout-seconds" -> ConfigurationValue.StringValue("90"),
          "textus.ai.execution-classes.standard-work.record-retry-limit" -> ConfigurationValue.StringValue("2"),
          "textus.ai.application-purposes.artscene-exhibition-extraction.output-schema-id" -> ConfigurationValue.StringValue("artscene.exhibitions.v1"),
          "textus.ai.application-purposes.artscene-exhibition-extraction.prompt-contract-id" -> ConfigurationValue.StringValue("artscene.exhibition.extract.v1")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        _profiles(configuration, "artscene-exhibition-extraction" -> "structured-extraction")
      ).provide(SpiContract("ai-runner", classOf[AiRunner]), SpiSelection()).toOption.get
      val requirement = AiRunnerRequirement(
        purpose = Some("artscene-exhibition-extraction"),
        purposeRequired = true
      )
      val identities = Vector(
        Property("ai.output-schema-id", "artscene.exhibitions.v1", None),
        Property("ai.prompt-contract-id", "artscene.exhibition.extract.v1", None)
      )

      When("a caller requests the mapped structured record without concrete selection")
      val result = runner.generateRecord(
        AiRecordRequest("strict-record", _artscene_record_schema, requirement = requirement, properties = identities)
      )

      Then("the runtime profile selects Gemini and publishes the merged policy")
      result.toOption.get.metadata(AiExecutionFacts.PROVIDER) shouldBe "google"
      result.toOption.get.metadata(AiExecutionFacts.POLICY_RUNTIME_PROFILE) shouldBe "gemini"
      result.toOption.get.metadata(AiExecutionFacts.POLICY_EFFECTIVE_EXECUTION_CLASS) shouldBe "standard-work"
      result.toOption.get.metadata(AiExecutionFacts.POLICY_MAX_INPUT_TOKENS) shouldBe "128"
      result.toOption.get.metadata(AiExecutionFacts.POLICY_MAX_OUTPUT_TOKENS) shouldBe "240"
      result.toOption.get.metadata(AiExecutionFacts.POLICY_TIMEOUT_SECONDS) shouldBe "90"
      result.toOption.get.metadata(AiExecutionFacts.POLICY_RECORD_RETRY_LIMIT) shouldBe "2"
    }

    "reject an application purpose that broadens its runtime-class policy" in {
      Given("a bounded standard runtime execution and a broader application purpose")
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
          "textus.ai.execution-classes.deep-consideration.max-output-tokens" -> ConfigurationValue.StringValue("240"),
          "textus.ai.application-purposes.invalid-web-analysis.max-output-tokens" -> ConfigurationValue.StringValue("241")
        )),
        ConfigurationTrace.empty
      )

      When("the application purpose is resolved")
      val result = _profiles(configuration, "invalid-web-analysis" -> "web-analysis").resolveRequired(
        AiRunnerRequirement(purpose = Some("invalid-web-analysis"), purposeRequired = true)
      )

      Then("the policy is rejected before provider selection")
      result.isFaillure shouldBe true
      result.toString should include ("may not broaden inherited execution policy")
    }

    "apply runtime-owned tool defaults through a standard purpose" in {
      Given("a Gemini deep-consideration override that enables logical Web tools")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
          "textus.ai.execution-classes.deep-consideration.tools" -> ConfigurationValue.StringValue("url_context,web_search")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        AiProfileConfig.fromConfiguration(Some(configuration))
      ).provide(SpiContract("ai-runner", classOf[AiRunner]), SpiSelection()).toOption.get

      When("a caller selects web-analysis without a tool override")
      val result = runner.generate(AiGenerateRequest(
        "inspect-ai-tools",
        requirement = AiRunnerRequirement(purpose = Some("web-analysis"), purposeRequired = true)
      ))

      Then("the profile supplies the admitted logical tool set")
      result.toOption.get.text shouldBe "tools:url_context,web_search"
      result.toOption.get.metadata(AiExecutionFacts.TOOLS) shouldBe "url_context,web_search"
    }

    "permit an application purpose to narrow but not broaden its standard-purpose policy" in {
      Given("a bounded Web-analysis execution class and application-purpose mappings")
      val values = Map(
        "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
        "textus.ai.execution-classes.deep-consideration.max-input-tokens" -> ConfigurationValue.StringValue("100"),
        "textus.ai.execution-classes.deep-consideration.max-output-tokens" -> ConfigurationValue.StringValue("480"),
        "textus.ai.execution-classes.deep-consideration.timeout-seconds" -> ConfigurationValue.StringValue("90"),
        "textus.ai.execution-classes.deep-consideration.max-concurrent" -> ConfigurationValue.StringValue("2"),
        "textus.ai.application-purposes.artscene-exhibition-web-research.max-input-tokens" -> ConfigurationValue.StringValue("50"),
        "textus.ai.application-purposes.artscene-exhibition-web-research.max-output-tokens" -> ConfigurationValue.StringValue("240"),
        "textus.ai.application-purposes.artscene-exhibition-web-research.timeout-seconds" -> ConfigurationValue.StringValue("45"),
        "textus.ai.application-purposes.artscene-exhibition-web-research.max-concurrent" -> ConfigurationValue.StringValue("1"),
        "textus.ai.application-purposes.invalid-web-analysis.timeout-seconds" -> ConfigurationValue.StringValue("120"),
        "textus.ai.application-purposes.invalid-input-web-analysis.max-input-tokens" -> ConfigurationValue.StringValue("101")
      )
      val profiles = _profiles(ResolvedConfiguration(
        Configuration(values),
        ConfigurationTrace.empty
      ),
        "artscene-exhibition-web-research" -> "web-analysis",
        "invalid-web-analysis" -> "web-analysis",
        "invalid-input-web-analysis" -> "web-analysis"
      )

      When("the narrowed and broadened application purposes are resolved")
      val narrowed = profiles.resolveRequired(AiRunnerRequirement(
        purpose = Some("artscene-exhibition-web-research"),
        purposeRequired = true
      ))
      val broadened = profiles.resolveRequired(AiRunnerRequirement(
        purpose = Some("invalid-web-analysis"),
        purposeRequired = true
      ))
      val broadenedinput = profiles.resolveRequired(AiRunnerRequirement(
        purpose = Some("invalid-input-web-analysis"),
        purposeRequired = true
      ))

      Then("only the application policy that stays within the standard bound is admitted")
      narrowed.toOption.map(_.policy.maxInputTokens) shouldBe Some(Some(50))
      narrowed.toOption.map(_.policy.maxOutputTokens) shouldBe Some(Some(240))
      narrowed.toOption.map(_.policy.timeoutSeconds) shouldBe Some(Some(45L))
      narrowed.toOption.map(_.policy.maxConcurrent) shouldBe Some(Some(1))
      profiles.concurrencyAdmissionC.toOption.flatten should not be empty
      broadened.isFaillure shouldBe true
      broadened.toString should include ("may not broaden inherited execution policy")
      broadenedinput.isFaillure shouldBe true
      broadenedinput.toString should include ("may not broaden inherited execution policy")
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

    "send Anthropic API requests through Messages without selecting Claude Code" in {
      Given("an Anthropic Messages service with a fake HTTP driver")
      val driver = new _FakeHttpDriver(
        """{"id":"msg_test","content":[{"type":"text","text":"anthropic answer"}],"stop_reason":"end_turn","usage":{"input_tokens":11,"cache_read_input_tokens":2,"output_tokens":7}}"""
      )
      given ExecutionContext = _context(driver)
      val service = new AnthropicGenerateService(
        AnthropicRuntimeConfig(
          endpoint = URI.create("https://api.anthropic.com"),
          apiKey = "test-anthropic-key",
          model = "claude-test"
        ),
        summon[ExecutionContext]
      )

      When("a plain generate request uses the direct Anthropic API")
      val response = service.generate(GenerateRequest("direct API prompt", maxTokens = Some(120))).toOption.get
      val toolRequest = service.generate(GenerateRequest(
        "tool prompt",
        properties = Vector(Property("ai.tools", "web_search", None))
      ))
      val structuredRequest = service.generate(GenerateRequest(
        "structured prompt",
        recordSchema = Some(Record.dataAuto("type" -> "object"))
      ))

      Then("the provider uses Messages credentials and returns safe response facts")
      driver.calls.head should include ("/v1/messages")
      driver.headers.get("x-api-key") shouldBe Some("test-anthropic-key")
      driver.headers.get("anthropic-version") shouldBe Some("2023-06-01")
      driver.body.value should include ("\"model\":\"claude-test\"")
      driver.body.value should include ("\"max_tokens\":120")
      response.text shouldBe "anthropic answer"
      response.metadata("anthropic.response_id") shouldBe "msg_test"
      response.metadata("anthropic.usage.total_tokens") shouldBe "18"
      toolRequest.isFaillure shouldBe true
      toolRequest.toString should include ("AI tools are not supported by provider 'anthropic'")
      structuredRequest.isFaillure shouldBe true
      structuredRequest.toString should include ("AI structured record generation is not supported by provider 'anthropic'")
      driver.calls should have size 1
    }

    "keep Claude Code as a fixed managed CLI without runtime-owned tool catalog access" in {
      Given("a Claude Code service without an admitted execution profile")
      given ExecutionContext = ExecutionContext.create()
      val service = new ClaudeCodeGenerateService(
        ClaudeCodeRuntimeConfig("/usr/local/bin/claude"),
        summon[ExecutionContext]
      )

      When("a structured record or function-tool request is made")
      val structured = service.generate(GenerateRequest(
        "structured prompt",
        recordSchema = Some(Record.dataAuto("type" -> "object"))
      ))
      val tools = service.generate(GenerateRequest(
        "tool prompt",
        properties = Vector(Property("ai.tools", "web_search", None))
      ))

      Then("it fails before attempting a local process execution")
      structured.isFaillure shouldBe true
      structured.toString should include ("AI structured record generation is not supported by provider 'claude'")
      tools.isFaillure shouldBe true
      tools.toString should include ("AI tools are not supported by provider 'claude'")
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
        """{"responseId":"google_plain","candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"google answer"}]}}],"usageMetadata":{"promptTokenCount":11,"cachedContentTokenCount":3,"candidatesTokenCount":7,"thoughtsTokenCount":2,"totalTokenCount":18}}"""
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
        """{"id":"chatcmpl_plain","choices":[{"finish_reason":"stop","message":{"content":"openai answer"}}],"usage":{"prompt_tokens":12,"prompt_tokens_details":{"cached_tokens":4},"completion_tokens":6,"completion_tokens_details":{"reasoning_tokens":2},"total_tokens":18}}"""
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
      google.metadata("google.usage.cached_input_tokens") shouldBe "3"
      google.metadata("google.usage.reasoning_tokens") shouldBe "2"
      openai.metadata("openai.response_id") shouldBe "chatcmpl_plain"
      openai.metadata("openai.finish_reason") shouldBe "stop"
      openai.metadata("openai.usage.input_tokens") shouldBe "12"
      openai.metadata("openai.usage.cached_input_tokens") shouldBe "4"
      openai.metadata("openai.usage.reasoning_tokens") shouldBe "2"
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
      Given("a mapped record purpose with bounded policy and caller-owned contract identities")
      given ExecutionContext =
        ExecutionContext.withFrameworkCallTreeEnabled(ExecutionContext.create(), enabled = true)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
          "textus.ai.execution-classes.standard-work.max-input-tokens" ->
            ConfigurationValue.StringValue("128"),
          "textus.ai.execution-classes.standard-work.max-output-tokens" ->
            ConfigurationValue.StringValue("240"),
          "textus.ai.execution-classes.standard-work.timeout-seconds" ->
            ConfigurationValue.StringValue("90"),
          "textus.ai.execution-classes.standard-work.record-retry-limit" ->
            ConfigurationValue.StringValue("2"),
          "textus.ai.application-purposes.artscene-exhibition-extraction-from-source.output-schema-id" ->
            ConfigurationValue.StringValue("artscene.exhibitions.v1"),
          "textus.ai.application-purposes.artscene-exhibition-extraction-from-source.prompt-contract-id" ->
            ConfigurationValue.StringValue("artscene.exhibition.extract.v1")
        )),
        ConfigurationTrace.empty
      )
      val runner = new TextusAiRunnerProvider(
        _component(),
        SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
        _profiles(configuration, "artscene-exhibition-extraction-from-source" -> "structured-extraction")
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
      generated.metadata(AiExecutionFacts.POLICY_MAX_INPUT_TOKENS) shouldBe "128"
      generated.metadata(AiExecutionFacts.POLICY_INPUT_BUDGET_BASIS) shouldBe AiInputTokenEstimator.basis
      generated.metadata(AiExecutionFacts.INPUT_TOKENS_SOURCE) shouldBe "reported"
      generated.metadata(AiExecutionFacts.POLICY_TIMEOUT_SECONDS) shouldBe "90"
      generated.metadata(AiExecutionFacts.POLICY_RECORD_RETRY_LIMIT) shouldBe "2"
      generated.metadata(AiExecutionFacts.POLICY_OUTPUT_SCHEMA_ID) shouldBe "artscene.exhibitions.v1"
      generated.metadata(AiExecutionFacts.POLICY_PROMPT_CONTRACT_ID) shouldBe "artscene.exhibition.extract.v1"
      val calltree = summon[ExecutionContext].observability.callTreeContext.build().value
      val record = ObservabilityEngine.callTreeRecord(calltree)
      val nodes = record.asMap("calltree").asInstanceOf[Seq[Record]]
      val node = nodes.find(_.getString("label").contains("provider:textus-ai-runner:generate-record")).value
      node.getString(AiExecutionFacts.POLICY_MAX_OUTPUT_TOKENS) shouldBe Some("240")
      node.getString(AiExecutionFacts.POLICY_MAX_INPUT_TOKENS) shouldBe Some("128")
      node.getString(AiExecutionFacts.POLICY_INPUT_BUDGET_BASIS) shouldBe Some(AiInputTokenEstimator.basis)
      node.getString(AiExecutionFacts.POLICY_TIMEOUT_SECONDS) shouldBe Some("90")
      node.getString(AiExecutionFacts.POLICY_RECORD_RETRY_LIMIT) shouldBe Some("2")
      node.getString(AiExecutionFacts.POLICY_OUTPUT_SCHEMA_ID) shouldBe Some("artscene.exhibitions.v1")
      node.getString(AiExecutionFacts.POLICY_PROMPT_CONTRACT_ID) shouldBe Some("artscene.exhibition.extract.v1")
      node.getString(AiExecutionFacts.POLICY_APPLICATION_PURPOSE) shouldBe
        Some("artscene-exhibition-extraction-from-source")
      node.getString(AiExecutionFacts.POLICY_EFFECTIVE_STANDARD_PURPOSE) shouldBe
        Some("structured-extraction")
      node.getString(s"response_metadata.${AiExecutionFacts.INPUT_TOKENS_SOURCE}") shouldBe Some("reported")
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

    "default commercial provider timeouts from CNCF configuration only" in {
      Given("OpenAI and Google configurations without timeout settings")
      val openaiconfiguration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.openai.api-key" -> ConfigurationValue.StringValue("test-openai-key"),
          "textus.ai.openai.model" -> ConfigurationValue.StringValue("gpt-test")
        )),
        ConfigurationTrace.empty
      )
      val googleconfiguration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.google.api-key" -> ConfigurationValue.StringValue("test-google-key"),
          "textus.ai.google.model" -> ConfigurationValue.StringValue("gemini-test")
        )),
        ConfigurationTrace.empty
      )

      When("the commercial runtime configurations are resolved")
      val openai = OpenAiConfig.fromConfiguration(openaiconfiguration)
      val google = GoogleConfig.fromConfiguration(googleconfiguration)

      Then("both use the code-owned timeout default without ambient input")
      openai.map(_.timeoutSeconds) shouldBe Some(30L)
      google.map(_.timeoutSeconds) shouldBe Some(30L)
    }

    "read direct Anthropic API configuration with its model fallback" in {
      Given("merged configuration for the direct Anthropic Messages API")
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.anthropic.api-key" -> ConfigurationValue.StringValue("test-anthropic-key"),
          "textus.ai.anthropic.timeout-seconds" -> ConfigurationValue.StringValue("45")
        )),
        ConfigurationTrace.empty
      )

      When("the direct API configuration is resolved")
      val config = AnthropicConfig.fromConfiguration(configuration)

      Then("it remains a remote Claude API runtime")
      config.map(_.provider) shouldBe Some("anthropic")
      config.map(_.engine) shouldBe Some("claude")
      config.map(_.model) shouldBe Some(AnthropicConfig.defaultModel)
      config.map(_.timeoutSeconds) shouldBe Some(45L)
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

  private def _profiles(
    configuration: ResolvedConfiguration,
    purposes: (String, String)*
  ): AiProfileConfig =
    AiProfileConfig.fromConfiguration(
      Some(configuration),
      AiApplicationPurposeCatalog.fromRegistrations(Vector(
        AiRunnerApplicationPurposeRegistration(purposes.map { case (name, standardpurpose) =>
          AiRunnerApplicationPurpose(name, standardpurpose)
        }.toVector)
      ))
    )

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

  private object _ChatServiceState {
    private var _counts: Map[String, Int] = Map.empty

    def record(input: String): Unit =
      _counts = _counts.updated(input, count(input) + 1)

    def count(input: String): Int =
      _counts.getOrElse(input, 0)

    def reset(input: String): Unit =
      _counts = _counts - input
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
      else if (req.prompt == "all-providers-unavailable")
        Consequence.serviceUnavailable("fixture provider unavailable")
      else if (req.prompt == "gemma-unavailable" && name == "local")
        Consequence.serviceUnavailable("fixture Gemma unavailable")
      else if (req.prompt == "strategy-input-denied" && name == "local")
        Consequence.operationIllegal("fixture-input", "fixture input denied")
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
      _ChatServiceState.record(last)
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

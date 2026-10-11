package org.simplemodeling.textus.ai.runtime

import java.nio.charset.StandardCharsets
import cats.~>
import org.goldenport.Consequence
import org.goldenport.cncf.component.Component
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.{ExecutionContext, GlobalContext, RuntimeContext}
import org.goldenport.cncf.processexecution.*
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.{AiChatRequest, AiGenerateRequest, AiManagedExecutionMetadata, AiMessage, AiRecordRequest, AiRunner, AiRunnerRequirement, AiTool}
import org.goldenport.cncf.unitofwork.{UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.goldenport.cncf.workarea.WorkAreaSpace
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.goldenport.protocol.Property
import org.goldenport.record.Record
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.simplemodeling.textus.ai.provider.codex.{CodexCliVersion, CodexConfig, CodexExecutionProfile, CodexReasoningLevel, CodexRuntimeConfig}

/*
 * Executable specification for the managed Codex CLI provider. The test
 * profile proves adapter intent without a Codex binary, account, or network.
 *
 * @since   Jul. 17, 2026
 *  version Jul. 26, 2026
 * @version Oct. 11, 2026
 * @author  ASAMI, Tomoharu
 */
final class CodexRuntimeProviderSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Codex runtime provider" should {
    "require explicit Codex provider enablement from runtime configuration" in {
      Given("configuration with Codex settings but no enabled flag")
      val disabled = _configuration(Map(
        "textus.ai.codex.engine" -> "codex-cli"
      ))
      val enabled = _configuration(Map(
        "textus.ai.profile" -> "codex-cli",
        "textus.ai.codex.enabled" -> "true",
        "textus.ai.codex.executable" -> "/runtime/codex-cli",
        "textus.ai.codex.schema-maximum-bytes" -> "4096"
      ))
      val unsafe = _configuration(Map(
        "textus.ai.codex.enabled" -> "true",
        "textus.ai.codex.executable" -> "codex"
      ))

      When("the Codex adapter configuration is resolved")
      val disabledconfig = CodexConfig.fromConfiguration(disabled)
      val profiles = AiProfileConfig.fromConfiguration(Some(enabled))
      val enabledconfig = CodexConfig.fromConfiguration(enabled, _codex_executions(profiles))
      val unsafeconfig = CodexConfig.fromConfiguration(unsafe)
      val legacy = CodexRuntimeConfig("local", "codex-cli", 4096L)

      Then("only the explicitly enabled runtime installs the provider adapter")
      disabledconfig shouldBe None
      enabledconfig.map(_.schemaMaximumBytes) shouldBe Some(4096L)
      enabledconfig.map(_.executable) shouldBe Some("/runtime/codex-cli")
      enabledconfig.flatMap(_.executionProfiles.get("runtime-simple-thinking")).map(_.reasoningLevel) shouldBe Some(Some(CodexReasoningLevel.Medium))
      unsafeconfig shouldBe None
      legacy.schemaMaximumBytes shouldBe 4096L
      CodexCliVersion.minimumForModel("gpt-5.6-sol") shouldBe Some(CodexCliVersion.Gpt56Minimum)
      CodexCliVersion.minimumForModel("gpt-5.6-terra") shouldBe Some(CodexCliVersion.Gpt56Minimum)
      CodexCliVersion.minimumForModel("gpt-5.6-luna") shouldBe Some(CodexCliVersion.Gpt56Minimum)
    }

    "run generate and record requests through the admitted CNCF process capability" in {
      Given("Spec: docs/spec/ai-execution-facts.md; Rule: R8; Example: E8; a controlled managed Codex result, not actual model execution")
      val fixture = _fixture(_result("{\"title\":\"Codex result\"}"))
      given ExecutionContext = _context(fixture)
      val runner = _runner()

      When("plain and record generation select the codex-cli provider alias")
      val generated = runner.generate(
        AiGenerateRequest("plain prompt", requirement = AiRunnerRequirement(provider = Some("codex-cli")),
          metadata = Map(AiManagedExecutionMetadata.programIdentity -> "caller-forged-program",
            AiManagedExecutionMetadata.durationMillis -> "999999"))
      )
      val recorded = runner.generateRecord(
        AiRecordRequest(
          "record prompt",
          _schema,
          requirement = AiRunnerRequirement(provider = Some("codex"))
        )
      )
      val chatted = runner.chat(
        AiChatRequest(
          Vector(AiMessage("user", "chat prompt")),
          requirement = AiRunnerRequirement(provider = Some("codex"))
        )
      )
      val generatedresponse = generated match {
        case Consequence.Success(value) => value
        case Consequence.Failure(conclusion) => fail(conclusion.display)
      }

      Then("the adapter emits admitted stdin and schema-file intents without direct process access")
      generatedresponse.text shouldBe "{\"title\":\"Codex result\"}"
      generatedresponse.metadata.get(AiExecutionFacts.PROVIDER) shouldBe Some("codex")
      generatedresponse.metadata.get(AiExecutionFacts.FINISH_REASON) shouldBe Some("exited")
      generatedresponse.metadata.get(AiManagedExecutionMetadata.programIdentity) shouldBe Some("codex-cli")
      generatedresponse.metadata.get(AiManagedExecutionMetadata.durationMillis) shouldBe Some("1")
      generatedresponse.metadata.get(AiManagedExecutionMetadata.providerProfile) shouldBe None
      generatedresponse.metadata.get(AiManagedExecutionMetadata.reasoningEffort) shouldBe None
      recorded.toOption.flatMap(_.metadata.get(AiManagedExecutionMetadata.programIdentity)) shouldBe Some("codex-cli")
      chatted.toOption.flatMap(_.metadata.get(AiManagedExecutionMetadata.programIdentity)) shouldBe Some("codex-cli")
      recorded.toOption.flatMap(_.record.getAny("title")) shouldBe Some("Codex result")
      chatted.toOption.map(_.message.content) shouldBe Some("{\"title\":\"Codex result\"}")
      fixture.profile.driver.executions.map(_.request.arguments) shouldBe Vector(
        Vector("-"),
        Vector("--output-schema", "schema.json", "-"),
        Vector("-")
      )
      val plain = fixture.profile.driver.executions.head.request
      new String(plain.input.asInstanceOf[ProcessExecutionInput.Bytes].value.toArray, StandardCharsets.UTF_8) shouldBe "plain prompt"
      val record = fixture.profile.driver.executions(1).request
      record.inputFiles.map(_.name.print) shouldBe Vector("schema")
      val recordschema = new String(record.inputFiles.head.content.toArray, StandardCharsets.UTF_8)
      recordschema should include ("\"title\"")
      recordschema should include ("\"additionalProperties\":false")
      recordschema should include ("\"confidence\":{\"type\":[\"integer\",\"null\"]}")
      val chat = fixture.profile.driver.executions(2).request
      new String(chat.input.asInstanceOf[ProcessExecutionInput.Bytes].value.toArray, StandardCharsets.UTF_8) should include ("chat prompt")
    }

    "surface unavailable Codex CLI execution without exposing process output" in {
      Given("an admitted Codex capability whose runtime cannot launch the program")
      val fixture = _fixture(_result("", ProcessExecutionTermination.LaunchFailed))
      given ExecutionContext = _context(fixture)
      val runner = _runner()

      When("generation is requested")
      val result = runner.generate(
        AiGenerateRequest("confidential prompt", requirement = AiRunnerRequirement(provider = Some("codex")))
      )

      Then("the caller receives a structured unavailable-service failure")
      result.isFaillure shouldBe true
      result.toString should not include "confidential prompt"
    }

    "reject unsupported tools and unsafe terminal process outcomes explicitly" in {
      Given("controlled Codex terminal outcomes and a request for a provider web tool")
      val outcomes = Vector(
        ProcessExecutionTermination.Exited(9),
        ProcessExecutionTermination.TimedOut,
        ProcessExecutionTermination.Cancelled,
        ProcessExecutionTermination.OutputLimitExceeded(ProcessExecutionStream.Stdout)
      )

      When("each outcome and unsupported request is sent through the provider")
      val results = outcomes.map { outcome =>
        val fixture = _fixture(_result("sensitive stdout", outcome))
        given ExecutionContext = _context(fixture)
        _runner().generate(AiGenerateRequest("confidential prompt"))
      }
      val toolfixture = _fixture(_result("{}"))
      given ExecutionContext = _context(toolfixture)
      val toolresult = _runner().generate(
        AiGenerateRequest(
          "confidential prompt",
          requirement = AiRunnerRequirement(tools = Vector(AiTool.UrlContext))
        )
      )

      Then("no terminal output is exposed and unsupported tools never reach the process driver")
      results.foreach { result =>
        result.isFaillure shouldBe true
        result.toString should not include "sensitive stdout"
        result.toString should not include "confidential prompt"
      }
      toolresult.isFaillure shouldBe true
      toolfixture.profile.driver.executions shouldBe Vector.empty
    }

    "reject generic and Codex-scoped model overrides before invoking the managed Codex CLI" in {
      Given("an admitted Codex capability and generic or provider-scoped model overrides")
      val fixture = _fixture(_result("{}"))
      given ExecutionContext = _context(fixture)
      val genericrequest = AiGenerateRequest(
        "confidential prompt",
        requirement = AiRunnerRequirement(model = Some("unconfigured-model"))
      )
      val scopedrequest = AiGenerateRequest(
        "confidential prompt",
        properties = Vector(Property("ai.codex-cli.model", "unconfigured-cli-model", None))
      )
      val outputlimitedrequest = AiGenerateRequest(
        "confidential prompt",
        maxTokens = Some(64)
      )

      When("generation is requested through the Codex provider")
      val results = Vector(
        _runner().generate(genericrequest),
        _runner().generate(scopedrequest),
        _runner().generate(outputlimitedrequest)
      )

      Then("the unsupported override fails before any process execution")
      results.foreach { result =>
        result.isFaillure shouldBe true
        result.toString should not include "unconfigured"
      }
      fixture.profile.driver.executions shouldBe Vector.empty
    }

    "submit purpose timeout through the admitted managed-process limit" in {
      Given("an admitted Codex capability and a generic purpose timeout")
      val fixture = _fixture(_result("{}"))
      given ExecutionContext = _context(fixture)

      When("generation is routed through the Codex provider")
      val result = _runner().generate(
        AiGenerateRequest(
          "bounded prompt",
          properties = Vector(Property("ai.timeout-seconds", "121", None))
        )
      )

      Then("the capability admission rejects the converted limit before process execution")
      result shouldBe a[Consequence.Failure[_]]
      result match
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("121000")
        case _ =>
          fail("an over-broad process timeout must be rejected by admission")
      fixture.profile.driver.executions shouldBe Vector.empty
    }

    "run a Web-research purpose through its fixed admitted Codex profile" in {
      Given("an admitted fixed Web profile and controlled JSONL output, not actual AI execution")
      val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map(
        "textus.ai.profile" -> "codex-cli",
        "textus.ai.execution-classes.deep-thinking.reasoning-level" -> "high",
        "textus.ai.execution-classes.deep-thinking.tools" -> "url_context,web_search"
      ))))
      val profile = _codex_executions(profiles)("runtime-deep-thinking")
      val fixture = _fixture(
        _result(_events("{\"title\":\"Web result\"}")),
        profile.webCapability,
        versioncapabilityname = Some(profile.versionCapability)
      )
      given ExecutionContext = _context(fixture)

      When("a caller names only the required purpose")
      val result = _runner(
        CodexRuntimeConfig(executionProfiles = Map(profile.name -> profile)),
        profiles
      ).generateRecord(AiRecordRequest(
        "research the supplied official URL",
        _schema,
        requirement = AiRunnerRequirement(
          purpose = Some("web-analysis"),
          purposeRequired = true
        )
      ))

      Then("the selected profile controls the model and Web capability without caller CLI input")
      withClue(result.toString) {
        result.toOption.flatMap(_.record.getAny("title")) shouldBe Some("Web result")
      }
      result.toOption.flatMap(_.metadata.get(AiExecutionFacts.POLICY_RUNTIME_PROFILE)) shouldBe Some("codex-cli")
      result.toOption.flatMap(_.metadata.get(AiExecutionFacts.POLICY_REASONING_LEVEL)) shouldBe Some("high")
      result.toOption.flatMap(_.metadata.get(AiExecutionFacts.ENABLED_TOOLS)) shouldBe Some("url_context,web_search")
      fixture.profile.driver.executions.map(_.request.capability.print) shouldBe Vector(profile.versionCapability, profile.webCapability)
      fixture.profile.driver.executions.map(_.request.arguments) shouldBe Vector(Vector.empty, Vector("--output-schema", "schema.json", "-"))
    }

    "consume the named-profile JSONL response" which {
      "return only the completed agent message and provider control context through the normal runner" in {
        Given("Spec: docs/spec/ai-execution-facts.md; Rule: R9; Example: E10; a named managed capability and controlled JSONL, not actual AI execution")
        val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map("textus.ai.profile" -> "codex-cli"))))
        val profile = _codex_executions(profiles)("runtime-standard-work")
        val forged = Map(
          AiManagedExecutionMetadata.contextIdentity -> "caller-forged-context",
          AiManagedExecutionMetadata.programIdentity -> "caller-forged-program",
          AiManagedExecutionMetadata.providerProfile -> "caller-forged-profile",
          AiManagedExecutionMetadata.reasoningEffort -> "caller-forged-effort",
          AiManagedExecutionMetadata.durationMillis -> "999999"
        )
        val title = "result with {\"thread_id\":\"agent-forged-context\",\"program\":\"agent-forged-program\",\"profile\":\"agent-forged-profile\",\"reasoning\":\"agent-forged-effort\",\"duration\":999999}"
        val text = io.circe.Json.obj("title" -> io.circe.Json.fromString(title)).noSpaces
        val unrelated = Map(
          "ai.execution.unrelated_annotation" -> "private annotation detail",
          "ai.execution.unrelated_tool" -> "private tool detail",
          "ai.execution.unrelated_error" -> "private error detail"
        )
        val annotation = io.circe.Json.obj(
          "type" -> io.circe.Json.fromString("future.annotation"),
          "metadata" -> io.circe.Json.obj(unrelated.toVector.map { case (key, value) =>
            key -> io.circe.Json.fromString(value)
          }*),
          "error" -> io.circe.Json.obj("message" -> io.circe.Json.fromString("private error detail"))
        ).noSpaces
        val tool = io.circe.Json.obj(
          "type" -> io.circe.Json.fromString("item.completed"),
          "item" -> io.circe.Json.obj(
            "type" -> io.circe.Json.fromString("command_execution"),
            "aggregated_output" -> io.circe.Json.fromString("private tool detail"),
            "metadata" -> io.circe.Json.obj("ai.execution.unrelated_tool" -> io.circe.Json.fromString("private tool detail"))
          )
        ).noSpaces
        val stream = _events(text) + "\n" + annotation + "\n" + tool
        val fixture = _fixture(_result(stream), profile.plainCapability, Some(profile.versionCapability))
        given ExecutionContext = _context(fixture)
        val runner = _runner(CodexRuntimeConfig(executionProfiles = Map(profile.name -> profile)), profiles)
        val requirement = AiRunnerRequirement(purpose = Some("standard-work"), purposeRequired = true)

        When("generate, structured generation and chat consume the actual provider response")
        val generated = runner.generate(AiGenerateRequest("prompt", requirement = requirement,
          metadata = forged))
        val recorded = runner.generateRecord(AiRecordRequest("prompt", _schema, requirement = requirement, metadata = forged))
        val chatted = runner.chat(AiChatRequest(Vector(AiMessage("user", "prompt")), requirement = requirement, metadata = forged))

        Then("only the control-channel context is returned, independently of semantic text and request hints")
        generated.toOption.map(_.text) shouldBe Some(text)
        recorded.toOption.flatMap(_.record.getAny("title")) shouldBe Some(title)
        chatted.toOption.map(_.message.content) shouldBe Some(text)
        Vector(generated.toOption.map(_.metadata), recorded.toOption.map(_.metadata), chatted.toOption.map(_.metadata))
          .foreach { metadata =>
            metadata.flatMap(_.get(AiManagedExecutionMetadata.contextIdentity)) shouldBe Some("provider-context")
            metadata.flatMap(_.get(AiManagedExecutionMetadata.programIdentity)) shouldBe Some("codex-cli")
            metadata.flatMap(_.get(AiManagedExecutionMetadata.providerProfile)) shouldBe Some(profile.name)
            metadata.flatMap(_.get(AiManagedExecutionMetadata.reasoningEffort)) shouldBe profile.reasoningLevel.map(_.id)
            metadata.flatMap(_.get(AiManagedExecutionMetadata.durationMillis)) shouldBe Some("1")
            unrelated.foreach { case (key, value) =>
              metadata.flatMap(_.get(key)) shouldBe None
              metadata.toVector.flatMap(_.values) should not contain value
            }
            metadata.toVector.flatMap(_.values) should not contain "private tool output"
          }
      }

      "keep unknown context absent and refuse an unfinished or failed provider turn" in {
        Given("Spec: docs/spec/ai-execution-facts.md; Rule: R9; Example: E11; optional context, extra events and actual turn outcomes")
        val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map("textus.ai.profile" -> "codex-cli"))))
        val profile = _codex_executions(profiles)("runtime-standard-work")
        val missing = _events("answer").linesIterator.filterNot(_.contains("thread.started")).mkString("\n")
        val ambiguous = "{\"type\":\"thread.started\",\"thread_id\":\"another-context\"}\n" + _events("answer")
        val unfinished = _events("answer").linesIterator.filterNot(_.contains("turn.completed")).mkString("\n")
        val failed = _events("answer") + "\n{\"type\":\"turn.failed\",\"error\":{\"message\":\"private detail\"}}"

        When("each stream is consumed through the same normal runner entry")
        val results = Vector(missing, ambiguous, unfinished, failed).map { stream =>
          val fixture = _fixture(_result(stream), profile.plainCapability, Some(profile.versionCapability))
          given ExecutionContext = _context(fixture)
          _runner(CodexRuntimeConfig(executionProfiles = Map(profile.name -> profile)), profiles)
            .generate(AiGenerateRequest("prompt", requirement = AiRunnerRequirement(purpose = Some("standard-work"), purposeRequired = true)))
        }

        Then("missing or ambiguous optional facts do not reject useful results or pretend independence, while no failed turn becomes a result")
        results.take(2).foreach { result =>
          result.toOption.map(_.text) shouldBe Some("answer")
          result.toOption.flatMap(_.metadata.get(AiManagedExecutionMetadata.contextIdentity)) shouldBe None
        }
        results.drop(2).foreach { result =>
          result.isFaillure shouldBe true
          result.toString should not include "private detail"
        }
      }

      "select the latest answer within one turn and across completed turns despite late non-answer events" in {
        Given("Spec: docs/spec/ai-execution-facts.md; Rule: R9; Example: E12; multiple completed agent messages and successive turns in a named profile")
        val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map("textus.ai.profile" -> "codex-cli"))))
        val profile = _codex_executions(profiles)("runtime-standard-work")
        val late = "\n{\"type\":\"item.completed\",\"item\":{\"type\":\"command_execution\",\"aggregated_output\":\"private late tool output\"}}\n{\"type\":\"future.annotation\",\"text\":\"late annotation\"}"
        val oneturn = _events("earlier answer").linesIterator.filterNot(_.contains("turn.completed")).mkString("\n") +
          "\n" + _agent_event("latest answer") + "\n{\"type\":\"turn.completed\"}" + late
        val successive = _events("previous answer") + "\n" + _events("latest answer") + late
        val fixtures = Vector(oneturn, successive).map(stream =>
          _fixture(_result(stream), profile.plainCapability, Some(profile.versionCapability)))

        When("normal AiRunner generation consumes each named-profile stream")
        val results = fixtures.map { fixture =>
          given ExecutionContext = _context(fixture)
          _runner(CodexRuntimeConfig(executionProfiles = Map(profile.name -> profile)), profiles)
            .generate(AiGenerateRequest("prompt", requirement = AiRunnerRequirement(purpose = Some("standard-work"), purposeRequired = true)))
        }

        Then("the latest completed agent message is the answer and later tool or annotation bodies cannot replace it")
        results.foreach(_.toOption.map(_.text) shouldBe Some("latest answer"))
      }

      "refuse a prior answer for an unfinished latest turn and accept recovery in a newly started completed turn" in {
        Given("Spec: docs/spec/ai-execution-facts.md; Rule: R9; Example: E13; a completed prior turn followed by an unfinished new turn")
        val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map("textus.ai.profile" -> "codex-cli"))))
        val profile = _codex_executions(profiles)("runtime-standard-work")
        val unfinished = _events("prior answer") + "\n" +
          _events("unfinished answer").linesIterator.filterNot(_.contains("turn.completed")).mkString("\n")
        val recovered = unfinished + "\n" + _events("recovered answer")
        val fixtures = Vector(unfinished, recovered).map(stream =>
          _fixture(_result(stream), profile.plainCapability, Some(profile.versionCapability)))

        When("normal AiRunner generation consumes the unfinished and newly completed recovery streams")
        val results = fixtures.map { fixture =>
          given ExecutionContext = _context(fixture)
          _runner(CodexRuntimeConfig(executionProfiles = Map(profile.name -> profile)), profiles)
            .generate(AiGenerateRequest("prompt", requirement = AiRunnerRequirement(purpose = Some("standard-work"), purposeRequired = true)))
        }

        Then("the prior completion cannot certify the latest unfinished turn and a distinct completed recovery supplies its own answer")
        results.head shouldBe a[Consequence.Failure[_]]
        results.head.toString should not include "prior answer"
        results.head.toString should not include "unfinished answer"
        results(1).toOption.map(_.text) shouldBe Some("recovered answer")
      }

      "reject a top-level terminal error without exposing its private provider detail" in {
        Given("Spec: docs/spec/ai-execution-facts.md; Rule: R9; Example: E14; a named-profile stream terminated by a top-level error distinct from turn.failed")
        val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map("textus.ai.profile" -> "codex-cli"))))
        val profile = _codex_executions(profiles)("runtime-standard-work")
        val stream = _events("discarded answer") + "\n{\"type\":\"error\",\"message\":\"private provider detail\"}"
        val fixture = _fixture(_result(stream), profile.plainCapability, Some(profile.versionCapability))
        given ExecutionContext = _context(fixture)
        val runner = _runner(CodexRuntimeConfig(executionProfiles = Map(profile.name -> profile)), profiles)

        When("normal AiRunner generation consumes the terminal error stream")
        val result = runner.generate(AiGenerateRequest("prompt",
          requirement = AiRunnerRequirement(purpose = Some("standard-work"), purposeRequired = true)))

        Then("a structured failure replaces the answer and contains no private provider or tool body")
        result shouldBe a[Consequence.Failure[_]]
        result.toString should not include "private provider detail"
        result.toString should not include "private tool output"
        result.toString should not include "discarded answer"
      }

      "reject completed turns with no agent message or only whitespace" in {
        Given("Spec: docs/spec/ai-execution-facts.md; Rule: R9; Example: E15; completed named-profile streams lacking useful agent text")
        val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map("textus.ai.profile" -> "codex-cli"))))
        val profile = _codex_executions(profiles)("runtime-standard-work")
        val missing = _events("absent answer").linesIterator.filterNot(_.contains("agent_message")).mkString("\n")
        val fixtures = Vector(missing, _events(" \t\n ")).map(stream =>
          _fixture(_result(stream), profile.plainCapability, Some(profile.versionCapability)))

        When("normal AiRunner generation consumes each completed stream")
        val results = fixtures.map { fixture =>
          given ExecutionContext = _context(fixture)
          _runner(CodexRuntimeConfig(executionProfiles = Map(profile.name -> profile)), profiles)
            .generate(AiGenerateRequest("prompt", requirement = AiRunnerRequirement(purpose = Some("standard-work"), purposeRequired = true)))
        }

        Then("turn completion alone cannot produce a useful generation result")
        results.foreach { result =>
          result shouldBe a[Consequence.Failure[_]]
          result.toString should not include "private tool output"
        }
      }

      "omit empty or control-character context while preserving a useful completed answer" in {
        Given("Spec: docs/spec/ai-execution-facts.md; Rule: R9; Example: E16; completed named-profile streams with invalid optional thread identities")
        val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map("textus.ai.profile" -> "codex-cli"))))
        val profile = _codex_executions(profiles)("runtime-standard-work")
        val streams = Vector("", "context\\u0000identity").map(context => _events("useful answer").replace("provider-context", context))
        val fixtures = streams.map(stream =>
          _fixture(_result(stream), profile.plainCapability, Some(profile.versionCapability)))

        When("normal AiRunner generation consumes each stream with malformed optional control context")
        val results = fixtures.map { fixture =>
          given ExecutionContext = _context(fixture)
          _runner(CodexRuntimeConfig(executionProfiles = Map(profile.name -> profile)), profiles)
            .generate(AiGenerateRequest("prompt", requirement = AiRunnerRequirement(purpose = Some("standard-work"), purposeRequired = true)))
        }

        Then("the completed answer succeeds while each invalid optional context remains absent")
        results.foreach { result =>
          result.toOption.map(_.text) shouldBe Some("useful answer")
          result.toOption.flatMap(_.metadata.get(AiManagedExecutionMetadata.contextIdentity)) shouldBe None
        }
      }
    }

    "reject a Codex runtime profile whose managed CLI is below its model requirement" in {
      Given("a GPT-5.6 profile and a managed Codex CLI that reports version 0.143.0")
      val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map(
        "textus.ai.profile" -> "codex-cli"
      ))))
      val profile = _codex_executions(profiles)("runtime-standard-work")
      val fixture = _fixture(
        _result("{\"title\":\"unexpected\"}"),
        profile.plainCapability,
        Some(profile.versionCapability),
        _result("codex-cli 0.143.0")
      )
      given ExecutionContext = _context(fixture)

      When("a caller requests the standard-work purpose")
      val result = _runner(
        CodexRuntimeConfig(executionProfiles = Map(profile.name -> profile)),
        profiles
      ).generate(AiGenerateRequest(
        "implement the requested change",
        requirement = AiRunnerRequirement(
          purpose = Some("standard-work"),
          purposeRequired = true
        )
      ))

      Then("the version probe rejects the runtime before the prompt reaches Codex")
      result.isFaillure shouldBe true
      result.toString should include ("0.144.0")
      fixture.profile.driver.executions.map(_.request.capability.print) shouldBe Vector(profile.versionCapability)
    }

    "reject a Codex URL-context purpose without the admitted Web-search capability" in {
      Given("a Codex runtime profile that requests URL context without Web search")
      val fixture = _fixture(_result("{}"))
      given ExecutionContext = _context(fixture)
      val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map(
        "textus.ai.profile" -> "codex-cli",
        "textus.ai.execution-classes.deep-thinking.tools" -> "url_context"
      ))))

      When("the Web-research purpose is required")
      val result = _runner(CodexRuntimeConfig(), profiles).generate(
        AiGenerateRequest(
          "confidential URL",
          requirement = AiRunnerRequirement(
            purpose = Some("web-analysis"),
            purposeRequired = true
          )
        )
      )

      Then("the runtime fails before it selects a process or an implicit fallback")
      result.isFaillure shouldBe true
      result.toString should include ("Codex URL context requires the web_search capability")
      fixture.profile.driver.executions shouldBe Vector.empty
    }

    "reject an unsupported Codex reasoning policy before process selection" in {
      Given("a Codex runtime profile with an unsupported reasoning level")
      val fixture = _fixture(_result("{}"))
      given ExecutionContext = _context(fixture)
      val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map(
        "textus.ai.profile" -> "codex-cli",
        "textus.ai.execution-classes.deep-thinking.reasoning-level" -> "experimental"
      ))))

      When("the caller requires that purpose")
      val result = _runner(CodexRuntimeConfig(), profiles).generate(
        AiGenerateRequest(
          "confidential Web research",
          requirement = AiRunnerRequirement(
            purpose = Some("web-analysis"),
            purposeRequired = true
          )
        )
      )

      Then("the unsupported policy is reported without invoking the local process")
      result.isFaillure shouldBe true
      result.toString should include ("unsupported reasoning-level")
      fixture.profile.driver.executions shouldBe Vector.empty
    }
  }

  private def _runner(
    config: CodexRuntimeConfig = CodexRuntimeConfig(),
    profiles: AiProfileConfig = AiProfileConfig.empty
  )(using ExecutionContext): AiRunner = {
    val component = new Component() {}
      .withBinding("generate", AiRuntimeGenerateBinding.create(None, None, None, Some(config)))
      .withBinding("chat", AiRuntimeChatBinding.create(None, None, None, Some(config)))
    new TextusAiRunnerProvider(
      component,
      SpiSelection(provider = Some("codex"), mode = Some("local"), engine = Some("codex-cli")),
      profiles
    ).provide(
      SpiContract("ai-runner", classOf[AiRunner]),
      SpiSelection(provider = Some("codex"), mode = Some("local"), engine = Some("codex-cli"))
    ).toOption.get
  }

  private def _fixture(
    result: ProcessExecutionResult,
    capabilityname: String = "codex-cli",
    versioncapabilityname: Option[String] = None,
    versionresult: ProcessExecutionResult = _result("codex-cli 0.144.0")
  ): ProcessExecutionTestFixture = {
    val capability = ProcessCapabilityId.parseC(capabilityname).toOption.get
    val schema = ProcessArtifactName.parseC("schema").toOption.get
    val capabilities = Map(capability -> result) ++ versioncapabilityname.map { name =>
      ProcessCapabilityId.parseC(name).toOption.get -> versionresult
    }
    val profile = ProcessExecutionTestProfile(capabilities)
    val definitions = capabilities.keys.toVector.map { key =>
      ProcessProgramDefinition.fromRuntimeC(
        key,
        "deterministic-test-program",
        "test-runtime-owned-location",
        Vector.empty,
        ProcessArgumentPolicy(Vector.empty, Set("-", "--output-schema", "schema.json")),
        CodexRuntimeConfig.defaultExecutionLimits,
        Set.empty,
        allowedinputfiles = Set(schema)
      ).toOption.get
    }
    val policy = ProcessExecutionPolicy.createC(definitions).toOption.get
    val admission = ProcessExecutionAdmission.createC(
      policy,
      capabilities.keys.toVector.map(ProcessExecutionGrant(_))
    ).toOption.get
    val execution = admission.admitC(ProcessExecutionRequest(capability)).toOption.get
    ProcessExecutionTestFixture(profile, policy, admission, execution)
  }

  private def _events(text: String): String = Vector(
    io.circe.Json.obj("type" -> io.circe.Json.fromString("thread.started"), "thread_id" -> io.circe.Json.fromString("provider-context")),
    io.circe.Json.obj("type" -> io.circe.Json.fromString("turn.started")),
    io.circe.Json.obj("type" -> io.circe.Json.fromString("item.completed"), "item" -> io.circe.Json.obj(
      "type" -> io.circe.Json.fromString("command_execution"), "aggregated_output" -> io.circe.Json.fromString("private tool output"))),
    io.circe.Json.obj("type" -> io.circe.Json.fromString("future.annotation"), "unused" -> io.circe.Json.Null),
    io.circe.Json.obj("type" -> io.circe.Json.fromString("item.completed"), "item" -> io.circe.Json.obj(
      "type" -> io.circe.Json.fromString("agent_message"), "text" -> io.circe.Json.fromString(text))),
    io.circe.Json.obj("type" -> io.circe.Json.fromString("turn.completed"), "extra" -> io.circe.Json.Null)
  ).map(_.noSpaces).mkString("\n")

  private def _agent_event(text: String): String = io.circe.Json.obj(
    "type" -> io.circe.Json.fromString("item.completed"),
    "item" -> io.circe.Json.obj(
      "type" -> io.circe.Json.fromString("agent_message"),
      "text" -> io.circe.Json.fromString(text)
    )
  ).noSpaces

  private def _result(
    stdout: String,
    termination: ProcessExecutionTermination = ProcessExecutionTermination.Exited(0)
  ): ProcessExecutionResult = {
    val stdoutbytes = stdout.getBytes(StandardCharsets.UTF_8).toVector
    val empty = ProcessExecutionCapture(Vector.empty, 0L, truncated = false)
    ProcessExecutionResult(
      termination,
      ProcessExecutionCapture(stdoutbytes, stdoutbytes.length.toLong, truncated = false),
      empty,
      Vector.empty,
      elapsedMillis = 1L,
      safeProgramIdentity = "codex-cli"
    )
  }

  private def _context(fixture: ProcessExecutionTestFixture): ExecutionContext = {
    GlobalContext.set(GlobalContext(WorkAreaSpace.create(RuntimeConfig.default)))
    val base = ExecutionContext.create()
    var runtime: RuntimeContext = null
    lazy val context: ExecutionContext = ExecutionContext.withRuntimeContext(base, runtime)
    lazy val uow = new UnitOfWork(context)
    runtime = new RuntimeContext(
      core = RuntimeContext.core(
        name = "codex-runtime-provider-spec",
        parent = None,
        observabilitycontext = base.observability
      ).copy(
        processExecutionDriverOption = Some(fixture.profile.driver),
        processExecutionAdmissionOption = Some(fixture.admission)
      ),
      unitofworksupplier = () => uow,
      unitofworkinterpreterfn = new (UnitOfWorkOp ~> Consequence) {
        def apply[A](operation: UnitOfWorkOp[A]): Consequence[A] =
          new UnitOfWorkInterpreter(uow).interpret(operation)
      },
      commitaction = _ => (),
      abortaction = _ => (),
      disposeaction = _ => (),
      token = "codex-runtime-provider-spec"
    )
    context
  }

  private val _schema: Record = Record.dataAuto(
    "required" -> Vector("title"),
    "fields" -> Vector(
      Record.dataAuto("name" -> "title", "type" -> "string"),
      Record.dataAuto("name" -> "confidence", "type" -> "integer", "optional" -> true)
    )
  )

  private def _codex_executions(
    profiles: AiProfileConfig
  ): Map[String, CodexExecutionProfile] =
    profiles.codexExecutionsC.toOption.getOrElse(Map.empty).map { case (name, execution) =>
      name -> CodexExecutionProfile(
        name,
        execution.model,
        execution.reasoningLevel.flatMap(CodexReasoningLevel.parse),
        execution.tools.toSet
      )
    }

  private def _configuration(values: Map[String, String]): ResolvedConfiguration =
    ResolvedConfiguration(
      Configuration(values.map { case (key, value) => key -> ConfigurationValue.StringValue(value) }),
      ConfigurationTrace.empty
    )
}

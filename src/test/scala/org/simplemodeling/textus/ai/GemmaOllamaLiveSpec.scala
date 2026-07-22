package org.simplemodeling.textus.ai

import cats.~>
import io.circe.Json

import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentOrigin}
import org.goldenport.cncf.context.{ExecutionContext, RuntimeContext}
import org.goldenport.cncf.http.UrlConnectionHttpDriver
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.{AiGenerateRequest, AiRunner, AiRunnerRequirement}
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.cncf.unitofwork.{UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.simplemodeling.textus.ai.runtime.{AiProfileConfig, AiRuntimeChatBinding, AiRuntimeGenerateBinding, TextusAiRunnerProvider}
import org.simplemodeling.textus.ai.provider.gemma.GemmaOllamaChatService
import org.simplemodeling.textus.ai.ai.{ToolChatMessage, ToolChatRequest, ToolDefinition}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * Opt-in executable specification for the Textus AI native and managed Gemma/Ollama paths.
 * Normal test runs cancel this suite before contacting Docker or Ollama.
 *
 * @since   Jul. 21, 2026
 * @version Jul. 22, 2026
 * @author  ASAMI, Tomoharu
 */
final class GemmaOllamaLiveSpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen {
  "Textus AI Gemma/Ollama live integration" should {
    "execute native Ollama without resolving a Docker service" in {
      if (!sys.env.get("TEXTUS_AI_LIVE_NATIVE_GEMMA_TEST").contains("true"))
        cancel("Set TEXTUS_AI_LIVE_NATIVE_GEMMA_TEST=true to run the native Gemma/Ollama live integration specification.")

      Given("a native Gemma runtime profile with a locally installed Gemma model")
      val model = _native_model
      val profile = _native_profile
      val purpose = _native_purpose
      val configuration = _native_configuration(model, profile)
      val subsystem = new Subsystem("textus-ai-native-live-gemma", configuration = configuration)
      val profiles = AiProfileConfig.fromConfiguration(Some(configuration))
      given context: ExecutionContext = _context()
      val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
      val runner = new TextusAiRunnerProvider(component, SpiSelection(), profiles).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      try {
        When("the component-facing runner performs a simple-work generation")
        val response = runner.generate(AiGenerateRequest(
          prompt = "Reply with ready.",
          maxTokens = Some(4),
          requirement = AiRunnerRequirement(purpose = Some(purpose))
        ))

        Then("the native endpoint returns the configured local model without Docker bootstrap")
        withClue(s"Native Gemma/Ollama live response: $response") {
          response.isSuccess shouldBe true
        }
        ComponentFactory.gemmaRuntimeConfig(Some(configuration), profiles, Some(subsystem)).runtime shouldBe "native"
        ComponentFactory.gemmaRuntimeConfig(Some(configuration), profiles, Some(subsystem)).bootstrap shouldBe empty
        response.toOption.map(_.text.trim) should not be empty
        response.toOption.flatMap(_.model) shouldBe Some(model)
        response.toOption.flatMap(_.metadata.get("gemma.finish_reason")) should not be empty
      } finally {
        subsystem.shutdownC().isSuccess shouldBe true
      }
    }

    "execute standard work through the native Gemma-work profile" in {
      if (!sys.env.get("TEXTUS_AI_LIVE_GEMMA_WORK_TEST").contains("true"))
        cancel("Set TEXTUS_AI_LIVE_GEMMA_WORK_TEST=true to run the native Gemma-work executable specification.")

      Given("the Gemma-work profile and a locally installed Gemma 12B model")
      val model = "gemma3:12b"
      val configuration = _gemma_work_configuration
      val subsystem = new Subsystem("textus-ai-live-gemma-work", configuration = configuration)
      val profiles = AiProfileConfig.fromConfiguration(Some(configuration))
      given context: ExecutionContext = _context()
      val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
      val runner = new TextusAiRunnerProvider(component, SpiSelection(), profiles).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      try {
        When("the component-facing runner resolves software implementation")
        val response = runner.generate(AiGenerateRequest(
          prompt = "Reply with ready.",
          maxTokens = Some(16),
          requirement = AiRunnerRequirement(purpose = Some("software-implementation"))
        ))

        Then("standard-work selects native Gemma and returns one bounded response")
        withClue(s"Gemma-work live response: $response") {
          response.isSuccess shouldBe true
        }
        ComponentFactory.gemmaRuntimeConfig(Some(configuration), profiles, Some(subsystem)).runtime shouldBe "native"
        response.toOption.map(_.text.trim) should not be empty
        response.toOption.flatMap(_.model) shouldBe Some(model)
        response.toOption.flatMap(_.metadata.get("gemma.finish_reason")) should not be empty
      } finally {
        subsystem.shutdownC().isSuccess shouldBe true
      }
    }

    "start the profile-owned service, install its model, and generate a response" in {
      if (!sys.env.get("TEXTUS_AI_LIVE_GEMMA_TEST").contains("true"))
        cancel("Set TEXTUS_AI_LIVE_GEMMA_TEST=true to run the Gemma/Ollama live integration specification.")

      Given("a Gemma runtime profile, component bindings, and the CNCF Docker service-container driver")
      val configuration = _configuration
      val subsystem = new Subsystem("textus-ai-live-gemma", configuration = configuration)
      val profiles = AiProfileConfig.fromConfiguration(Some(configuration))
      val gemma = ComponentFactory.gemmaRuntimeConfig(Some(configuration), profiles, Some(subsystem))
      given context: ExecutionContext = _context()
      val component = new Component() {}
        .withBinding("generate", AiRuntimeGenerateBinding.create(Some(gemma), None, None, None))
        .withBinding("chat", AiRuntimeChatBinding.create(Some(gemma), None, None, None))
      val runner = new TextusAiRunnerProvider(component, SpiSelection(), profiles).provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      try {
        When("the component-facing AI runner performs its first simple-work generation")
        val response = runner.generate(AiGenerateRequest(
          prompt = "Reply with ready.",
          maxTokens = Some(4),
          requirement = AiRunnerRequirement(purpose = Some("simple-work"))
        ))

        Then("the runtime-owned service is ready, its profile model is installed, and component output is returned")
        withClue(s"Gemma/Ollama live response: $response") {
          response.isSuccess shouldBe true
        }
        response.toOption.map(_.text.trim) should not be empty
        response.toOption.flatMap(_.model) shouldBe Some("gemma:2b")
        response.toOption.flatMap(_.metadata.get("gemma.finish_reason")) should not be empty
      } finally {
        subsystem.shutdownC().isSuccess shouldBe true
      }
    }

    "use a tool-capable profile model to request a runtime-owned function" in {
      if (!sys.env.get("TEXTUS_AI_LIVE_GEMMA_TEST").contains("true"))
        cancel("Set TEXTUS_AI_LIVE_GEMMA_TEST=true to run the Gemma/Ollama live integration specification.")

      Given("a tool-capable FunctionGemma execution class and its managed local service")
      val configuration = _tool_configuration
      val subsystem = new Subsystem("textus-ai-live-gemma-tools", configuration = configuration)
      val profiles = AiProfileConfig.fromConfiguration(Some(configuration))
      val gemma = ComponentFactory.gemmaRuntimeConfig(Some(configuration), profiles, Some(subsystem))
      given context: ExecutionContext = _context()
      val service = new GemmaOllamaChatService(gemma.copy(model = "functiongemma"), context)

      try {
        When("the local model receives one admitted function definition")
        val response = service.chatWithTools(ToolChatRequest(
          messages = Vector(ToolChatMessage(
            "user",
            "Call the runtime_time function with an empty JSON object now. Do not answer in prose."
          )),
          tools = Vector(ToolDefinition(
            "runtime_time",
            Some("Returns the current runtime time."),
            Json.obj("type" -> Json.fromString("object"), "properties" -> Json.obj())
          )),
          maxTokens = Some(64)
        ))

        Then("Ollama returns an admitted function call through the native tool protocol")
        withClue(s"Gemma/Ollama live tool response: $response") {
          response.isSuccess shouldBe true
        }
        response.toOption.flatMap(_.model) shouldBe Some("functiongemma")
        response.toOption.map(_.message.toolCalls.map(_.name)) shouldBe Some(Vector("runtime_time"))
      } finally {
        subsystem.shutdownC().isSuccess shouldBe true
      }
    }
  }

  private def _configuration: ResolvedConfiguration =
    ResolvedConfiguration(
      Configuration(Map(
        "textus.ai.profile" -> ConfigurationValue.StringValue("gemma"),
        "textus.ai.gemma.runtime" -> ConfigurationValue.StringValue("managed-docker"),
        // CPU-only Gemma startup and first inference can exceed the normal provider timeout.
        "textus.ai.gemma.timeout-seconds" -> ConfigurationValue.StringValue("360"),
        "textus.service-container.driver" -> ConfigurationValue.StringValue("docker")
      )),
      ConfigurationTrace.empty
    )

  private def _native_model: String =
    sys.env.get("TEXTUS_AI_NATIVE_GEMMA_MODEL").map(_.trim).filter(_.nonEmpty).getOrElse("gemma3:4b")

  private def _native_profile: String =
    sys.env.get("TEXTUS_AI_NATIVE_GEMMA_PROFILE").map(_.trim).filter(_.nonEmpty).getOrElse("gemma")

  private def _native_purpose: String =
    sys.env.get("TEXTUS_AI_NATIVE_GEMMA_PURPOSE").map(_.trim).filter(_.nonEmpty).getOrElse("simple-work")

  private def _native_configuration(model: String, profile: String): ResolvedConfiguration =
    ResolvedConfiguration(
      Configuration(Map(
        "textus.ai.profile" -> ConfigurationValue.StringValue(profile),
        "textus.ai.execution-classes.simple-work.model" -> ConfigurationValue.StringValue(model),
        "textus.ai.execution-classes.standard-work.model" -> ConfigurationValue.StringValue(model),
        "textus.ai.gemma.timeout-seconds" -> ConfigurationValue.StringValue("180")
      )),
      ConfigurationTrace.empty
    )

  private def _gemma_work_configuration: ResolvedConfiguration =
    ResolvedConfiguration(
      Configuration(Map(
        "textus.ai.profile" -> ConfigurationValue.StringValue("gemma-work-codex-cli"),
        "textus.ai.gemma.timeout-seconds" -> ConfigurationValue.StringValue("180")
      )),
      ConfigurationTrace.empty
    )

  private def _tool_configuration: ResolvedConfiguration =
    ResolvedConfiguration(
      Configuration(Map(
        "textus.ai.profile" -> ConfigurationValue.StringValue("gemma"),
        "textus.ai.gemma.runtime" -> ConfigurationValue.StringValue("managed-docker"),
        "textus.ai.execution-classes.standard-work.model" -> ConfigurationValue.StringValue("functiongemma"),
        "textus.ai.execution-classes.standard-work.mcp-server-set" -> ConfigurationValue.StringValue("live.tools"),
        // CPU-only Gemma startup and first inference can exceed the normal provider timeout.
        "textus.ai.gemma.timeout-seconds" -> ConfigurationValue.StringValue("360"),
        "textus.service-container.driver" -> ConfigurationValue.StringValue("docker")
      )),
      ConfigurationTrace.empty
    )

  private def _context(): ExecutionContext = {
    val base = ExecutionContext.create()
    var runtime: RuntimeContext = null
    lazy val context: ExecutionContext = ExecutionContext.withRuntimeContext(base, runtime)
    lazy val uow = new UnitOfWork(context)
    runtime = new RuntimeContext(
      core = RuntimeContext.core(
        name = "textus-ai-live-gemma",
        parent = None,
        observabilityContext = base.observability,
        httpDriverOption = Some(new UrlConnectionHttpDriver("http://127.0.0.1"))
      ),
      unitOfWorkSupplier = () => uow,
      unitOfWorkInterpreterFn = new (UnitOfWorkOp ~> Consequence) {
        def apply[A](fa: UnitOfWorkOp[A]): Consequence[A] =
          new UnitOfWorkInterpreter(uow).interpret(fa)
      },
      commitAction = _ => (),
      abortAction = _ => (),
      disposeAction = _ => (),
      token = "textus-ai-live-gemma"
    )
    context
  }
}

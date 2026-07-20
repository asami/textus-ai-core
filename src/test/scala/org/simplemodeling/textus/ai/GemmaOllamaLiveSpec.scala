package org.simplemodeling.textus.ai

import cats.~>

import org.goldenport.Consequence
import org.goldenport.cncf.component.Component
import org.goldenport.cncf.context.{ExecutionContext, RuntimeContext}
import org.goldenport.cncf.http.UrlConnectionHttpDriver
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.{AiGenerateRequest, AiRunner, AiRunnerRequirement}
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.cncf.unitofwork.{UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.simplemodeling.textus.ai.runtime.{AiProfileConfig, AiRuntimeChatBinding, AiRuntimeGenerateBinding, TextusAiRunnerProvider}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * Opt-in executable specification for the Textus AI managed Gemma/Ollama path.
 * Normal test runs cancel this suite before contacting Docker or Ollama.
 *
 * @since   Jul. 21, 2026
 * @version Jul. 21, 2026
 * @author  ASAMI, Tomoharu
 */
final class GemmaOllamaLiveSpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen {
  "Textus AI managed Gemma/Ollama live integration" should {
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
  }

  private def _configuration: ResolvedConfiguration =
    ResolvedConfiguration(
      Configuration(Map(
        "textus.ai.profile" -> ConfigurationValue.StringValue("gemma"),
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

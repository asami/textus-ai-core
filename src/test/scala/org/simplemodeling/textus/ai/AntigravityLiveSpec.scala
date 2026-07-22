package org.simplemodeling.textus.ai

import cats.~>
import org.goldenport.Consequence
import org.goldenport.cncf.component.{ComponentCreate, ComponentOrigin}
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.{ExecutionContext, GlobalContext, RuntimeContext, ScopeContext, ScopeKind}
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.{AiGenerateRequest, AiRunner, AiRunnerRequirement}
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.cncf.unitofwork.{UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.goldenport.cncf.workarea.WorkAreaSpace
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.scalatest.GivenWhenThen
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.simplemodeling.textus.ai.runtime.TextusAiRunnerProvider

/*
 * Opt-in executable specification for the authenticated Antigravity CLI path.
 * Normal test runs cancel this suite before contacting the remote service.
 *
 * @since   Jul. 22, 2026
 * @version Jul. 22, 2026
 * @author  ASAMI, Tomoharu
 */
final class AntigravityLiveSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Textus AI Antigravity CLI live integration" should {
    "execute one bounded prompt through the component-owned CNCF process capability" in {
      if (!sys.env.get("TEXTUS_AI_LIVE_ANTIGRAVITY_TEST").contains("true"))
        cancel("Set TEXTUS_AI_LIVE_ANTIGRAVITY_TEST=true to run the authenticated Antigravity CLI specification.")
      val home = sys.env.get("TEXTUS_AI_ANTIGRAVITY_HOME").getOrElse(
        cancel("Set TEXTUS_AI_ANTIGRAVITY_HOME to the explicit Antigravity CLI home directory.")
      )

      Given("an authenticated local Antigravity CLI and an explicitly enabled runtime profile")
      val configuration = _configuration(home)
      val subsystem = new Subsystem("textus-ai-live-antigravity", configuration = configuration)
      val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
      val basecontext = ExecutionContext.create()
      val runtimescope = ScopeContext(
        ScopeKind.Runtime,
        "textus-ai-live-antigravity",
        None,
        basecontext.observability
      )
      component.withScopeContext(runtimescope)
      val callerscope = ScopeContext(
        ScopeKind.Component,
        "textus-ai-live-antigravity-caller",
        Some(runtimescope),
        basecontext.observability
      )
      given ExecutionContext = _runtime_context(callerscope)
      val runner = component.port.get[TextusAiRunnerProvider].get.provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.value

      When("standard-work generates a marker through the managed Antigravity process")
      val response = runner.generate(AiGenerateRequest(
        "Reply with exactly: TEXTUS_ANTIGRAVITY_COMPONENT_OK",
        requirement = AiRunnerRequirement(
          purpose = Some("standard-work"),
          purposeRequired = true
        )
      ))

      Then("the actual JSON envelope is normalized into the provider-neutral response")
      withClue(response.toString) {
        response.isSuccess shouldBe true
      }
      response.toOption.map(_.text) should contain ("TEXTUS_ANTIGRAVITY_COMPONENT_OK")
      response.toOption.flatMap(_.metadata.get("google.antigravity_conversation_id")) should not be empty
      response.toOption.flatMap(_.metadata.get("google.usage.input_tokens")) should not be empty
      response.toOption.flatMap(_.metadata.get("google.usage.output_tokens")) should not be empty
      callerscope.processExecutionDriverOption shouldBe None
      callerscope.processExecutionAdmissionOption shouldBe None
    }
  }

  private def _configuration(home: String): ResolvedConfiguration =
    ResolvedConfiguration(
      Configuration(Map(
        "textus.ai.profile" -> ConfigurationValue.StringValue("antigravity-cli"),
        "textus.ai.antigravity-cli.enabled" -> ConfigurationValue.StringValue("true"),
        "textus.ai.antigravity-cli.executable" -> ConfigurationValue.StringValue("/opt/homebrew/bin/agy"),
        "textus.ai.antigravity-cli.home" -> ConfigurationValue.StringValue(home)
      )),
      ConfigurationTrace.empty
    )

  private def _runtime_context(scope: ScopeContext): ExecutionContext = {
    GlobalContext.set(GlobalContext(WorkAreaSpace.create(RuntimeConfig.default)))
    val base = ExecutionContext.create()
    var runtime: RuntimeContext = null
    lazy val context: ExecutionContext = ExecutionContext.withRuntimeContext(base, runtime)
    lazy val uow = new UnitOfWork(context)
    runtime = new RuntimeContext(
      core = RuntimeContext.core(
        name = "textus-ai-live-antigravity",
        parent = Some(scope),
        observabilityContext = base.observability
      ),
      unitOfWorkSupplier = () => uow,
      unitOfWorkInterpreterFn = new (UnitOfWorkOp ~> Consequence) {
        def apply[A](operation: UnitOfWorkOp[A]): Consequence[A] =
          new UnitOfWorkInterpreter(uow).interpret(operation)
      },
      commitAction = _ => (),
      abortAction = _ => (),
      disposeAction = _ => (),
      token = "textus-ai-live-antigravity"
    )
    context
  }
}

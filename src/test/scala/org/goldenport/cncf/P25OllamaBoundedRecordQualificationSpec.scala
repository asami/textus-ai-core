package org.goldenport.cncf

import java.net.URI
import cats.~>
import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentOrigin}
import org.goldenport.cncf.context.{ExecutionContext, RuntimeContext, ScopeContext}
import org.goldenport.cncf.http.{HttpExecutionAdmission, HttpExecutionGrant, HttpExecutionLimits, HttpExecutionMethod, UrlConnectionHttpDriver}
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.{AiRecordRequest, AiRunner, AiRunnerRequirement}
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.cncf.unitofwork.{UnitOfWorkOp, UnitOfWorkInterpreter, UnitOfWork}
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.goldenport.record.Record
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.simplemodeling.textus.ai.ComponentFactory
import org.simplemodeling.textus.ai.provider.openai.{OpenAiRuntimeConfig, OpenAiTaskAllowanceAuthority}
import org.simplemodeling.textus.ai.runtime.{AiTaskAllowance, AiTaskCandidate, AiTaskRequest, TextusAiRunnerProvider}

final class P25OllamaBoundedRecordQualificationSpec extends AnyWordSpec with Matchers with OptionValues {
  "The native Textus AI factory" should {
    "execute a bounded Record request using the real local Ollama daemon through CNCF HTTP" in {
      val origin = "http://127.0.0.1:11434"
      val openaiOrigin = "https://127.0.0.1:11435"
      val model = "gpt-oss:20b"
      val values = Map(
        "textus.ai.bounded-task.local-ollama" -> "true",
        "textus.ai.bounded-task.openai" -> "true",
        "textus.ai.gemma.endpoint" -> origin,
        "textus.ai.gemma.model" -> model,
        "textus.ai.gemma.timeout-seconds" -> "120"
      )
      val configuration = ResolvedConfiguration(Configuration(values.map { case (key, value) =>
        key -> ConfigurationValue.StringValue(value)
      }), ConfigurationTrace.empty)
      val subsystem = new Subsystem("p25-ollama-qualification", configuration = configuration)
      try {
        val component = new ComponentFactory().createPrimaryC(ComponentCreate(subsystem, ComponentOrigin.Main))
          .toOption.value
        var assessed = Vector.empty[String]
        val authority: OpenAiTaskAllowanceAuthority = new OpenAiTaskAllowanceAuthority {
          override val connection = OpenAiRuntimeConfig(endpoint = URI.create(openaiOrigin),
            apiKey = "test-only-no-allowance", model = "test-model")
          override def assess(request: AiTaskRequest, deadlineNanos: Long): Option[AiTaskCandidate] = {
            (deadlineNanos - System.nanoTime()) should be > 0L
            assessed :+= request.id
            None
          }
          override def reserve(request: AiTaskRequest, candidate: AiTaskCandidate, deadlineNanos: Long)
              : Either[String, AiTaskAllowance] =
            fail("A producer without verified allowance must never reserve or dispatch")
        }
        component.withPort(Component.Port.input(authority).orElse(component.port))
        val base = ExecutionContext.create()
        val driver = new UrlConnectionHttpDriver(origin)
        val plan = HttpExecutionAdmission.fromRuntimeC(origin, HttpExecutionLimits(120000, 120000, 1048576),
          Map(component.componentId -> HttpExecutionGrant(Vector(origin, openaiOrigin), Set(HttpExecutionMethod.Post))))
          .toOption.value
        val root = ScopeContext.Instance(base.cncfCore.scope.core.copy(httpExecutionAdmissionOption = Some(plan)))
        component.withScopeContext(root)
        lazy val active: ExecutionContext =
          ExecutionContext.withRuntimeContext(base, runtime)
        lazy val unit = new UnitOfWork(active)
        lazy val runtime: RuntimeContext = new RuntimeContext(
          core = RuntimeContext.core("p25-ollama-qualification", parent = Some(component.scopeContext),
            observabilitycontext = base.observability, httpdriveroption = Some(driver)),
          unitofworksupplier = () => unit,
          unitofworkinterpreterfn = new (UnitOfWorkOp ~> Consequence) {
            override def apply[A](op: UnitOfWorkOp[A]): Consequence[A] =
              new UnitOfWorkInterpreter(unit).interpret(op)
          },
          commitaction = _ => (),
          abortaction = _ => (),
          disposeaction = _ => (),
          token = "p25-ollama-qualification"
        )
        given ExecutionContext = active
        val rebound = ExecutionContext.withFreshUnitOfWorkContext(active).toOption.value
        HttpExecutionAdmission.resolveC(rebound, HttpExecutionMethod.Post, s"$origin/api/chat", Vector.empty)
          .isSuccess shouldBe true
        val runner = component.port.get[TextusAiRunnerProvider].value
          .provide(SpiContract("ai-runner", classOf[AiRunner]), SpiSelection()).toOption.value
        val schema = Record.dataAuto("type" -> "object", "required" -> Vector("status"),
          "additionalProperties" -> false, "properties" -> Record.dataAuto(
            "status" -> Record.dataAuto("type" -> "string", "enum" -> Vector("ready"))
          ))
        val request = AiRecordRequest(
          "Return a JSON object with the status field set to ready.",
          schema,
          requirement = AiRunnerRequirement(provider = Some("openai")),
          metadata = Map(
            "ai.task.contract" -> "textus-ai.bounded-task.v1",
            "ai.task.id" -> "p25-t2-native-mixed-provider-selection-01",
            "ai.task.kind" -> "connected-structured-record-qualification",
            "ai.task.continuation" -> "p25-t2-qualification",
            "ai.task.input-reference" -> "p25-ollama-qualification-spec"
          )
        )
        val ungranted = ExecutionContext.withActionScope(active, base.cncfCore.scope)
        runner.generateRecord(request)(using ungranted) match {
          case Consequence.Failure(conclusion) =>
            conclusion.observation.cause.descriptor.facets should contain (
              org.goldenport.observation.Descriptor.Facet.Operation("ai.task.declined"))
          case Consequence.Success(_) => fail("An ungranted origin must decline before dispatch")
        }
        assessed shouldBe Vector("p25-t2-native-mixed-provider-selection-01")
        val response = runner.generateRecord(request)
        val value = response match {
          case Consequence.Success(actual) => actual
          case Consequence.Failure(conclusion) => fail(conclusion.display)
        }
        assessed shouldBe Vector.fill(2)("p25-t2-native-mixed-provider-selection-01")
        value.model shouldBe Some(model)
        value.record.getString("status") shouldBe Some("ready")
        value.metadata("ai.execution.provider") shouldBe "ollama"
        value.metadata("ai.usage.total_tokens_source") shouldBe "observed"
        value.metadata("ai.usage.total_tokens").toLong should be > 0L
        value.metadata("ai.usage.cost_microunits") shouldBe "0"
        info(s"Provider ollama, model ${value.model.value}, observed tokens ${value.metadata("ai.usage.total_tokens")}", None)
      } finally {
        subsystem.shutdownC()
      }
    }
  }
}

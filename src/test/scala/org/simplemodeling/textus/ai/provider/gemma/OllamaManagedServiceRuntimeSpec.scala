package org.simplemodeling.textus.ai.provider.gemma

import cats.~>
import java.nio.charset.StandardCharsets

import org.goldenport.Consequence
import org.goldenport.bag.Bag
import org.goldenport.cncf.context.{ExecutionContext, RuntimeContext}
import org.goldenport.cncf.http.HttpDriver
import org.goldenport.cncf.servicecontainer.{FakeServiceContainerGateway, ServiceContainerEndpoint, ServiceContainerRegistry, ServiceContainerRuntime, ServiceContainerTransition}
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.cncf.unitofwork.{UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ResolvedConfiguration}
import org.goldenport.datatype.{ContentType, MimeType}
import org.goldenport.http.{HttpResponse, HttpStatus}
import org.goldenport.protocol.Property
import org.scalatest.GivenWhenThen
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * Executable specification for the Textus AI consumer boundary of CNCF's
 * managed service-container runtime.
 *
 * @since   Jul. 20, 2026
 * @version Jul. 20, 2026
 * @author  ASAMI, Tomoharu
 */
final class OllamaManagedServiceRuntimeSpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen
  with OptionValues {
  "Ollama managed service bootstrap" should {
    "resolve one runtime-owned endpoint and install models through the ready HTTP service" in {
      Given("an installed fake lifecycle runtime and one configured Gemma model")
      val subsystem = _subsystem("textus-ai-managed-ollama-spec")
      val config = OllamaManagedServiceConfig(
        "example/ollama:test",
        "textus-ai-models",
        Vector("gemma:2b"),
        30000L,
        60L
      )
      val definition = config.definitionC.toOption.value
      val endpoint = ServiceContainerEndpoint.parseC("http://127.0.0.1:12434").toOption.value
      val gateway = FakeServiceContainerGateway.create(Map(definition.registryKey -> endpoint))
      val runtime = ServiceContainerRuntime.create(ServiceContainerRegistry.inMemory(), gateway)
      subsystem.installServiceContainerRuntimeC(runtime).isSuccess shouldBe true
      val driver = new _RecordingHttpDriver
      given context: ExecutionContext = _context(driver)
      val bootstrap = new OllamaManagedServiceBootstrap(subsystem, config)

      When("bootstrap is requested twice")
      val first = bootstrap.ensureC
      val second = bootstrap.ensureC

      Then("lifecycle resolution and provider-owned model installation each converge once")
      first.toOption.map(_.toASCIIString) shouldBe Some("http://127.0.0.1:12434")
      second.toOption shouldBe first.toOption
      gateway.transitions.count(_._1 == ServiceContainerTransition.Create) shouldBe 1
      gateway.transitions.count(_._1 == ServiceContainerTransition.Start) shouldBe 1
      driver.calls shouldBe Vector("POST http://127.0.0.1:12434/api/pull")
      driver.bodies.mkString should include("gemma:2b")
      subsystem.shutdown()
    }

    "fail structurally before model installation when no lifecycle runtime is installed" in {
      Given("an owned Ollama definition without a Subsystem lifecycle runtime")
      val subsystem = _subsystem("textus-ai-missing-managed-runtime-spec")
      val config = OllamaManagedServiceConfig(
        "example/ollama:test",
        "textus-ai-models",
        Vector("gemma:2b"),
        30000L,
        60L
      )
      val driver = new _RecordingHttpDriver
      given context: ExecutionContext = _context(driver)

      When("bootstrap requests the owned endpoint")
      val result = new OllamaManagedServiceBootstrap(subsystem, config).ensureC

      Then("the common gateway-unavailable Conclusion is returned without an HTTP side effect")
      result.isFaillure shouldBe true
      driver.calls shouldBe empty
      subsystem.shutdown()
    }
  }

  private final class _RecordingHttpDriver extends HttpDriver {
    private var _calls = Vector.empty[String]
    private var _bodies = Vector.empty[String]

    def calls: Vector[String] = _calls
    def bodies: Vector[String] = _bodies

    def get(
      path: String,
      headers: Map[String, String] = Map.empty,
      properties: Vector[Property] = Vector.empty
    ): HttpResponse =
      _response

    def post(
      path: String,
      body: Option[String],
      headers: Map[String, String],
      properties: Vector[Property] = Vector.empty
    ): HttpResponse = {
      _calls = _calls :+ s"POST $path"
      _bodies = _bodies ++ body.toVector
      _response
    }

    def put(
      path: String,
      body: Option[String],
      headers: Map[String, String],
      properties: Vector[Property] = Vector.empty
    ): HttpResponse =
      _response

    private def _response: HttpResponse =
      HttpResponse.Text(
        HttpStatus.Ok,
        ContentType(MimeType("application/json"), Some(StandardCharsets.UTF_8)),
        Bag.text("{\"status\":\"success\"}", StandardCharsets.UTF_8)
      )
  }

  private def _context(driver: HttpDriver): ExecutionContext = {
    val base = ExecutionContext.create()
    var runtime: RuntimeContext = null
    lazy val context: ExecutionContext = ExecutionContext.withRuntimeContext(base, runtime)
    lazy val uow = new UnitOfWork(context)
    runtime = new RuntimeContext(
      core = RuntimeContext.core(
        name = "ollama-managed-service-runtime-spec",
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
      token = "ollama-managed-service-runtime-spec"
    )
    context
  }

  private def _subsystem(name: String): Subsystem =
    new Subsystem(
      name,
      configuration = ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty)
    )
}

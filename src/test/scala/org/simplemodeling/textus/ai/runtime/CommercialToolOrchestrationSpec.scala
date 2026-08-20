package org.simplemodeling.textus.ai.runtime

import java.net.URI
import java.nio.charset.StandardCharsets
import cats.~>
import io.circe.Json
import io.circe.parser.parse
import org.goldenport.Consequence
import org.goldenport.bag.Bag
import org.goldenport.cncf.context.{ExecutionContext, RuntimeContext}
import org.goldenport.cncf.http.HttpDriver
import org.goldenport.cncf.mcp.client.*
import org.goldenport.cncf.unitofwork.{UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.goldenport.datatype.{ContentType, MimeType}
import org.goldenport.http.{HttpResponse, HttpStatus}
import org.goldenport.protocol.Property
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.simplemodeling.textus.ai.provider.anthropic.{AnthropicChatService, AnthropicRuntimeConfig}
import org.simplemodeling.textus.ai.provider.google.{GoogleChatService, GoogleRuntimeConfig}
import org.simplemodeling.textus.ai.provider.openai.{OpenAiChatService, OpenAiRuntimeConfig}
import org.simplemodeling.textus.airuntime.ai.GenerateResponse

/*
 * Executable specification for commercial native function continuations over
 * the common CNCF MCP invocation boundary.
 *
 * @since   Jul. 22, 2026
 * @version Aug. 21, 2026
 * @author  ASAMI, Tomoharu
 */
final class CommercialToolOrchestrationSpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen {

  "Commercial tool orchestration" should {
    "continue every provider only after an admitted MCP result" in {
      Given("OpenAI, Gemini, and Anthropic responses that each request one admitted MCP tool")

      When("the common orchestration invokes the admitted MCP catalog")
      val outcomes = _Provider.values.toVector.map(_success)

      Then("each native binding receives its correlated continuation exactly once")
      outcomes.foreach { outcome =>
        withClue(outcome.provider.id) {
          outcome.result.toOption.map(_.text) shouldBe Some(s"${outcome.provider.id} grounded answer")
          outcome.invocation.calls.size shouldBe 1
          outcome.driver.bodies.size shouldBe 2
          outcome.driver.continuationIsNative shouldBe true
        }
      }
    }

    "stop before native continuation when the admitted MCP invocation fails" in {
      Given("OpenAI, Gemini, and Anthropic responses that each request one admitted MCP tool")

      When("the common MCP invocation returns a controlled unavailable-service failure")
      val outcomes = _Provider.values.toVector.map(_failure)

      Then("the provider is not sent a synthetic result or another request")
      outcomes.foreach { outcome =>
        withClue(outcome.provider.id) {
          outcome.result.isFaillure shouldBe true
          outcome.invocation.calls.size shouldBe 1
          outcome.driver.bodies.size shouldBe 1
          outcome.result.toString should not include outcome.driver.callId
        }
      }
    }
  }

  private def _success(provider: _Provider): _Outcome = _run(provider, succeed = true)

  private def _failure(provider: _Provider): _Outcome = _run(provider, succeed = false)

  private def _run(provider: _Provider, succeed: Boolean): _Outcome = {
    val driver = new _Driver(provider)
    given ExecutionContext = _context(driver, provider)
    val invocation = _invocation(succeed)
    val result = ToolOrchestrator.generateC(
      _service(provider, summon[ExecutionContext]),
      invocation,
      "Find the admitted station context.",
      None,
      Some(128),
      Vector.empty,
      provider.id
    )
    _Outcome(provider, result, invocation, driver)
  }

  private def _service(provider: _Provider, context: ExecutionContext): ToolCallingChatService = provider match {
    case _Provider.OpenAi => new OpenAiChatService(
      OpenAiRuntimeConfig(
        endpoint = URI.create("https://api.openai.com"),
        apiKey = "test-key",
        model = "gpt-test"
      ),
      context
    )
    case _Provider.Google => new GoogleChatService(
      GoogleRuntimeConfig(
        endpoint = URI.create("https://generativelanguage.googleapis.com"),
        apiKey = "test-key",
        model = "gemini-test"
      ),
      context
    )
    case _Provider.Anthropic => new AnthropicChatService(
      AnthropicRuntimeConfig(
        endpoint = URI.create("https://api.anthropic.com"),
        apiKey = "test-key",
        model = "claude-test"
      ),
      context
    )
  }

  private def _invocation(succeed: Boolean): _Invocation = {
    val server = McpServerId.parseC("research").toOption.get
    val name = McpToolName.parseC("places.lookup").toOption.get
    val field = McpFieldName.parseC("query").toOption.get
    val schema = McpInputSchema.objectC(Vector(
      McpInputField.createC(field, McpInputSchema.StringValue, required = true).toOption.get
    )).toOption.get
    val tool = McpClientTool.createC(McpToolIdentity(server, name), schema).toOption.get
    val setid = McpServerSetId.parseC("research-tools").toOption.get
    val serverset = McpClientServerSet.createC(
      setid,
      Vector(McpClientServer.createC(server, Vector(name)).toOption.get)
    ).toOption.get
    new _Invocation(McpClientCatalog.createC(serverset, Vector(tool)).toOption.get, succeed)
  }

  private final class _Invocation(
    value: McpClientCatalog,
    succeed: Boolean
  ) extends McpClientInvocation {
    var calls: Vector[McpClientCall] = Vector.empty

    def catalog(using ExecutionContext): Consequence[McpClientCatalog] = Consequence.success(value)

    def invoke(call: McpClientCall)(using ExecutionContext): Consequence[McpClientResult] = {
      calls = calls :+ call
      if (succeed)
        Consequence.success(McpClientResult(Vector(McpClientContent.Text("station context")), None))
      else
        Consequence.serviceUnavailable("Controlled MCP failure")
    }
  }

  private enum _Provider(val id: String) {
    case OpenAi extends _Provider("openai")
    case Google extends _Provider("google")
    case Anthropic extends _Provider("anthropic")
  }

  private final case class _Outcome(
    provider: _Provider,
    result: Consequence[GenerateResponse],
    invocation: _Invocation,
    driver: _Driver
  )

  private final class _Driver(provider: _Provider) extends HttpDriver {
    var bodies: Vector[String] = Vector.empty

    def callId: String = s"call_${provider.id}"

    def continuationIsNative: Boolean = bodies.lift(1).exists { body =>
      val json = parse(body).toOption.getOrElse(Json.obj())
      provider match {
        case _Provider.OpenAi =>
          json.hcursor.downField("input").focus.flatMap(_.asArray).exists(_.exists { item =>
            item.hcursor.get[String]("type").toOption.contains("function_call_output") &&
              item.hcursor.get[String]("call_id").toOption.contains(callId)
          })
        case _Provider.Google =>
          json.hcursor.get[String]("previous_interaction_id").toOption.contains("interaction_google") &&
            json.hcursor.downField("input").focus.flatMap(_.asArray).exists(_.exists { item =>
              item.hcursor.get[String]("type").toOption.contains("function_result") &&
                item.hcursor.get[String]("call_id").toOption.contains(callId)
            })
        case _Provider.Anthropic =>
          json.hcursor.downField("messages").focus.flatMap(_.asArray).exists(_.exists { message =>
            message.hcursor.downField("content").focus.flatMap(_.asArray).exists(_.exists { item =>
              item.hcursor.get[String]("type").toOption.contains("tool_result") &&
                item.hcursor.get[String]("tool_use_id").toOption.contains(callId)
            })
          })
      }
    }

    def get(path: String, headers: Map[String, String], properties: Vector[Property] = Vector.empty): HttpResponse = _response()

    def post(path: String, body: Option[String], headers: Map[String, String], properties: Vector[Property] = Vector.empty): HttpResponse = {
      bodies = bodies :+ body.getOrElse("")
      _response()
    }

    def put(path: String, body: Option[String], headers: Map[String, String], properties: Vector[Property] = Vector.empty): HttpResponse = _response()

    private def _response(): HttpResponse = {
      val body = if (bodies.size == 1) _tool_call_response(_function_name) else _final_response
      HttpResponse.Text(
        HttpStatus.Ok,
        ContentType(MimeType("application/json"), Some(StandardCharsets.UTF_8)),
        Bag.text(body, StandardCharsets.UTF_8)
      )
    }

    private def _function_name: String = {
      val json = parse(bodies.lastOption.getOrElse("")).toOption.getOrElse(Json.obj())
      json.hcursor.downField("tools").focus.flatMap(_.asArray).toVector.flatten.flatMap { tool =>
        tool.hcursor.get[String]("name").toOption
      }.find(_.startsWith("mcp_")).getOrElse(throw new IllegalStateException("Missing admitted MCP function"))
    }

    private def _tool_call_response(name: String): String = provider match {
      case _Provider.OpenAi =>
        s"""{"id":"response_openai","model":"gpt-test","output":[{"type":"function_call","call_id":"$callId","name":"$name","arguments":"{\\"query\\":\\"Nakano\\"}"}]}"""
      case _Provider.Google =>
        s"""{"id":"interaction_google","model":"gemini-test","steps":[{"type":"function_call","id":"$callId","name":"$name","arguments":{"query":"Nakano"}}]}"""
      case _Provider.Anthropic =>
        s"""{"id":"message_anthropic","model":"claude-test","stop_reason":"tool_use","content":[{"type":"tool_use","id":"$callId","name":"$name","input":{"query":"Nakano"}}]}"""
    }

    private def _final_response: String = provider match {
      case _Provider.OpenAi =>
        """{"id":"response_final","model":"gpt-test","output":[{"type":"message","content":[{"type":"output_text","text":"openai grounded answer"}]}]}"""
      case _Provider.Google =>
        """{"id":"interaction_final","model":"gemini-test","output_text":"google grounded answer","steps":[{"type":"model_output","content":[{"type":"text","text":"google grounded answer"}]}]}"""
      case _Provider.Anthropic =>
        """{"id":"message_final","model":"claude-test","stop_reason":"end_turn","content":[{"type":"text","text":"anthropic grounded answer"}]}"""
    }
  }

  private def _context(driver: HttpDriver, provider: _Provider): ExecutionContext = {
    val base = ExecutionContext.create()
    var runtime: RuntimeContext = null
    lazy val context: ExecutionContext = ExecutionContext.withRuntimeContext(base, runtime)
    lazy val uow = new UnitOfWork(context)
    runtime = new RuntimeContext(
      core = RuntimeContext.core(
        name = s"${provider.id}-commercial-tool-orchestration-spec",
        parent = None,
        observabilitycontext = base.observability,
        httpdriveroption = Some(driver)
      ),
      unitofworksupplier = () => uow,
      unitofworkinterpreterfn = new (UnitOfWorkOp ~> Consequence) {
        def apply[A](fa: UnitOfWorkOp[A]): Consequence[A] =
          new UnitOfWorkInterpreter(uow).interpret(fa)
      },
      commitaction = _ => (),
      abortaction = _ => (),
      disposeaction = _ => (),
      token = s"${provider.id}-commercial-tool-orchestration-spec"
    )
    context
  }
}

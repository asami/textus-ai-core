package org.simplemodeling.textus.ai.provider.anthropic

import java.net.URI
import java.nio.charset.StandardCharsets
import cats.~>
import io.circe.Json
import io.circe.parser.parse
import org.goldenport.Consequence
import org.goldenport.bag.Bag
import org.goldenport.cncf.context.{ExecutionContext, RuntimeContext}
import org.goldenport.cncf.http.HttpDriver
import org.goldenport.cncf.unitofwork.{UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.goldenport.datatype.{ContentType, MimeType}
import org.goldenport.http.{HttpResponse, HttpStatus}
import org.goldenport.protocol.Property
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.simplemodeling.textus.airuntime.ai.{ToolCall, ToolChatMessage, ToolChatRequest, ToolDefinition}

/*
 * Executable specification for the Anthropic Messages function-call adapter.
 *
 * @since   Jul. 22, 2026
 * @version Aug. 21, 2026
 * @author  ASAMI, Tomoharu
 */
final class AnthropicToolCallingSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "AnthropicChatService" should {
    "continue multiple tool_use blocks with their matching tool_result identifiers" in {
      Given("two deterministic Messages responses: two tool uses then final text")
      val driver = new _Driver(Vector(
        """{
          |  "id":"msg_tool","model":"claude-test","stop_reason":"tool_use",
          |  "content":[
          |    {"type":"tool_use","id":"toolu_123","name":"mcp_123","input":{"query":"Nakano"}},
          |    {"type":"tool_use","id":"toolu_456","name":"mcp_456","input":{"query":"Koenji"}}
          |  ]
          |}""".stripMargin,
        """{
          |  "id":"msg_final","model":"claude-test","stop_reason":"end_turn",
          |  "content":[{"type":"text","text":"Grounded answer."}]
          |}""".stripMargin
      ))
      given ExecutionContext = _context(driver)
      val service = new AnthropicChatService(
        AnthropicRuntimeConfig(
          endpoint = URI.create("https://api.anthropic.com"),
          apiKey = "test-key",
          model = "claude-test"
        ),
        summon[ExecutionContext]
      )
      val tools = Vector(ToolDefinition(
        "mcp_123",
        Some("Find an admitted place."),
        Json.obj("type" -> Json.fromString("object"))
      ), ToolDefinition(
        "mcp_456",
        Some("Find a second admitted place."),
        Json.obj("type" -> Json.fromString("object"))
      ))

      When("Textus AI presents the admitted function and follows the returned call")
      val first = service.chatWithTools(ToolChatRequest(
        Vector(ToolChatMessage("user", "Find the station.")),
        tools
      )).toOption.get
      val results = first.message.toolCalls.map { call =>
        ToolChatMessage("tool", s"${call.name} result", toolName = Some(call.name))
          ._with_provider_call_id(call._provider_call_id_option)
      }
      val second = service.chatWithTools(ToolChatRequest(
        Vector(first.message) ++ results,
        tools
      )).toOption.get

      Then("the wire format retains the provider ID without exposing it to callers")
      first.message.toolCalls shouldBe Vector(ToolCall(
        "mcp_123",
        Json.obj("query" -> Json.fromString("Nakano"))
      ), ToolCall(
        "mcp_456",
        Json.obj("query" -> Json.fromString("Koenji"))
      ))
      second.message.content shouldBe "Grounded answer."
      driver.headers.get("x-api-key") shouldBe Some("test-key")
      val firstbody = parse(driver.bodies.head).toOption.get
      firstbody.hcursor.downField("tools").downArray.get[String]("name").toOption shouldBe Some("mcp_123")
      val secondbody = parse(driver.bodies(1)).toOption.get
      secondbody.hcursor.downField("messages").downN(0).downField("content").downArray.get[String]("type").toOption shouldBe Some("tool_use")
      val resultsbody = secondbody.hcursor.downField("messages").downN(1).downField("content").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      resultsbody.map(_.hcursor.get[String]("tool_use_id").toOption) shouldBe Vector(Some("toolu_123"), Some("toolu_456"))
    }

    "reject a tool_use block without its provider call identifier" in {
      Given("a malformed Messages tool response")
      val driver = new _Driver(Vector(
        """{"content":[{"type":"tool_use","name":"mcp_123","input":{}}]}"""
      ))
      given ExecutionContext = _context(driver)
      val service = new AnthropicChatService(
        AnthropicRuntimeConfig(
          endpoint = URI.create("https://api.anthropic.com"),
          apiKey = "test-key",
          model = "claude-test"
        ),
        summon[ExecutionContext]
      )

      When("the provider response is parsed")
      val result = service.chatWithTools(ToolChatRequest(
        Vector(ToolChatMessage("user", "Find the station.")),
        Vector(ToolDefinition("mcp_123", None, Json.obj()))
      ))

      Then("the malformed continuation fails before any tool invocation")
      result.isFaillure shouldBe true
      result.toString should include ("Anthropic tool use has no id")
    }
  }

  private final class _Driver(responses: Vector[String]) extends HttpDriver {
    private var _remaining = responses
    var bodies: Vector[String] = Vector.empty
    var headers: Map[String, String] = Map.empty

    def get(path: String, headers: Map[String, String], properties: Vector[Property] = Vector.empty): HttpResponse = _response()
    def post(path: String, body: Option[String], headers: Map[String, String], properties: Vector[Property] = Vector.empty): HttpResponse = {
      bodies = bodies :+ body.getOrElse("")
      this.headers = headers
      _response()
    }
    def put(path: String, body: Option[String], headers: Map[String, String], properties: Vector[Property] = Vector.empty): HttpResponse = _response()

    private def _response(): HttpResponse = {
      val value = _remaining.headOption.getOrElse("{}")
      _remaining = _remaining.drop(1)
      HttpResponse.Text(
        HttpStatus.Ok,
        ContentType(MimeType("application/json"), Some(StandardCharsets.UTF_8)),
        Bag.text(value, StandardCharsets.UTF_8)
      )
    }
  }

  private def _context(driver: HttpDriver): ExecutionContext = {
    val base = ExecutionContext.create()
    var runtime: RuntimeContext = null
    lazy val context: ExecutionContext = ExecutionContext.withRuntimeContext(base, runtime)
    lazy val uow = new UnitOfWork(context)
    runtime = new RuntimeContext(
      core = RuntimeContext.core(
        name = "anthropic-tool-calling-spec",
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
      token = "anthropic-tool-calling-spec"
    )
    context
  }
}

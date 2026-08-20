package org.simplemodeling.textus.ai.provider.openai

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
 * Executable specification for the OpenAI Responses function-call adapter.
 *
 * @since   Jul. 22, 2026
 * @version Aug. 21, 2026
 * @author  ASAMI, Tomoharu
 */
final class OpenAiToolCallingSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "OpenAiChatService" should {
    "continue multiple Responses function calls with matching function_call_output items" in {
      Given("two deterministic Responses replies: two function calls then final text")
      val driver = new _Driver(Vector(
        """{
          |  "id":"resp_tools","model":"gpt-test",
          |  "output":[
          |    {"type":"reasoning","id":"rsn_123","summary":[]},
          |    {"type":"function_call","call_id":"call_123","name":"mcp_123","arguments":"{\"query\":\"Nakano\"}"},
          |    {"type":"function_call","call_id":"call_456","name":"mcp_456","arguments":"{\"query\":\"Koenji\"}"}
          |  ]
          |}""".stripMargin,
        """{
          |  "id":"resp_final","model":"gpt-test",
          |  "output":[{"type":"message","content":[{"type":"output_text","text":"Grounded answer."}]}]
          |}""".stripMargin
      ))
      given ExecutionContext = _context(driver)
      val service = new OpenAiChatService(
        OpenAiRuntimeConfig(
          endpoint = URI.create("https://api.openai.com"),
          apiKey = "test-key",
          model = "gpt-test"
        ),
        summon[ExecutionContext]
      )
      val tools = Vector(
        ToolDefinition("mcp_123", Some("Find an admitted place."), Json.obj("type" -> Json.fromString("object"))),
        ToolDefinition("mcp_456", Some("Find a second admitted place."), Json.obj("type" -> Json.fromString("object")))
      )

      When("Textus AI presents admitted functions and returns their results")
      val first = service.chatWithTools(ToolChatRequest(
        Vector(ToolChatMessage("user", "Find the station.")),
        tools,
        properties = Vector(Property("ai.tools", "web_search", None))
      )).toOption.get
      val results = first.message.toolCalls.map { call =>
        ToolChatMessage("tool", s"${call.name} result", toolName = Some(call.name))
          ._with_provider_call_id(call._provider_call_id_option)
      }
      val second = service.chatWithTools(ToolChatRequest(
        Vector(first.message) ++ results,
        tools
      )).toOption.get

      Then("the native call IDs stay internal and each result is correlated on the wire")
      first.message.toolCalls shouldBe Vector(
        ToolCall("mcp_123", Json.obj("query" -> Json.fromString("Nakano"))),
        ToolCall("mcp_456", Json.obj("query" -> Json.fromString("Koenji")))
      )
      second.message.content shouldBe "Grounded answer."
      driver.headers.get("Authorization") shouldBe Some("Bearer test-key")
      val firstbody = parse(driver.bodies.head).toOption.get
      val declaredtools = firstbody.hcursor.downField("tools").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      declaredtools.map(_.hcursor.get[String]("type").toOption) should contain (Some("function"))
      declaredtools.map(_.hcursor.get[String]("type").toOption) should contain (Some("web_search"))
      declaredtools.find(_.hcursor.get[String]("name").toOption.contains("mcp_123")) should not be empty
      val secondbody = parse(driver.bodies(1)).toOption.get
      val input = secondbody.hcursor.downField("input").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      input.count(_.hcursor.get[String]("type").toOption.contains("function_call")) shouldBe 2
      input.map(_.hcursor.get[String]("type").toOption) should contain (Some("reasoning"))
      input.filter(_.hcursor.get[String]("type").toOption.contains("function_call_output"))
        .map(_.hcursor.get[String]("call_id").toOption) shouldBe Vector(Some("call_123"), Some("call_456"))
    }

    "reject a function call without its Responses call identifier" in {
      Given("a malformed Responses function call")
      val driver = new _Driver(Vector(
        """{"model":"gpt-test","output":[{"type":"function_call","name":"mcp_123","arguments":"{}"}]}"""
      ))
      given ExecutionContext = _context(driver)
      val service = new OpenAiChatService(
        OpenAiRuntimeConfig(
          endpoint = URI.create("https://api.openai.com"),
          apiKey = "test-key",
          model = "gpt-test"
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
      result.toString should include ("OpenAI function call has no call_id")
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
        name = "openai-tool-calling-spec",
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
      token = "openai-tool-calling-spec"
    )
    context
  }
}

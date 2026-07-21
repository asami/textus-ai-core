package org.simplemodeling.textus.ai.provider.google

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
import org.simplemodeling.textus.ai.ai.{ToolCall, ToolChatMessage, ToolChatRequest, ToolDefinition}

/*
 * Executable specification for the Gemini Interactions function-call adapter.
 *
 * @since   Jul. 22, 2026
 * @version Jul. 22, 2026
 * @author  ASAMI, Tomoharu
 */
final class GoogleToolCallingSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "GoogleChatService" should {
    "continue Interactions function calls with only the latest matching function results" in {
      Given("three deterministic Interactions responses with two continuation turns then final text")
      val driver = new _Driver(Vector(
        """{
          |  "id":"interaction_123","model":"gemini-test",
          |  "steps":[
          |    {"type":"thought","signature":"opaque"},
          |    {"type":"function_call","id":"call_123","name":"mcp_123","arguments":{"query":"Nakano"}},
          |    {"type":"function_call","id":"call_456","name":"mcp_456","arguments":{"query":"Koenji"}}
          |  ]
          |}""".stripMargin,
        """{
          |  "id":"interaction_456","model":"gemini-test",
          |  "steps":[{"type":"function_call","id":"call_789","name":"mcp_789","arguments":{"query":"Shibuya"}}]
          |}""".stripMargin,
        """{
          |  "id":"interaction_456","model":"gemini-test","output_text":"Grounded answer.",
          |  "steps":[{"type":"model_output","content":[{"type":"text","text":"Grounded answer."}]}]
          |}""".stripMargin
      ))
      given ExecutionContext = _context(driver)
      val service = new GoogleChatService(
        GoogleRuntimeConfig(
          endpoint = URI.create("https://generativelanguage.googleapis.com"),
          apiKey = "test-key",
          model = "gemini-test"
        ),
        summon[ExecutionContext]
      )
      val tools = Vector(
        ToolDefinition("mcp_123", Some("Find an admitted place."), Json.obj("type" -> Json.fromString("object"))),
        ToolDefinition("mcp_456", Some("Find a second admitted place."), Json.obj("type" -> Json.fromString("object"))),
        ToolDefinition("mcp_789", Some("Find a final admitted place."), Json.obj("type" -> Json.fromString("object")))
      )
      val properties = Vector(Property("ai.tools", "web_search", None))

      When("Textus AI presents admitted functions and returns their results")
      val first = service.chatWithTools(ToolChatRequest(
        Vector(ToolChatMessage("user", "Find the station.")),
        tools,
        properties = properties
      )).toOption.get
      val results = first.message.toolCalls.map { call =>
        ToolChatMessage("tool", s"${call.name} result", toolName = Some(call.name))
          ._with_provider_call_id(call._provider_call_id_option)
      }
      val second = service.chatWithTools(ToolChatRequest(
        Vector(first.message) ++ results,
        tools,
        properties = properties
      )).toOption.get
      val finalresults = second.message.toolCalls.map { call =>
        ToolChatMessage("tool", s"${call.name} result", toolName = Some(call.name))
          ._with_provider_call_id(call._provider_call_id_option)
      }
      val third = service.chatWithTools(ToolChatRequest(
        Vector(first.message) ++ results ++ Vector(second.message) ++ finalresults,
        tools,
        properties = properties
      )).toOption.get

      Then("the interaction is resumed by ID and every result preserves its native call correlation")
      first.message.toolCalls shouldBe Vector(
        ToolCall("mcp_123", Json.obj("query" -> Json.fromString("Nakano"))),
        ToolCall("mcp_456", Json.obj("query" -> Json.fromString("Koenji")))
      )
      second.message.toolCalls shouldBe Vector(ToolCall("mcp_789", Json.obj("query" -> Json.fromString("Shibuya"))))
      third.message.content shouldBe "Grounded answer."
      driver.headers.get("x-goog-api-key") shouldBe Some("test-key")
      val firstbody = parse(driver.bodies.head).toOption.get
      firstbody.hcursor.downField("input").downArray.get[String]("type").toOption shouldBe Some("user_input")
      val declaredtools = firstbody.hcursor.downField("tools").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      declaredtools.map(_.hcursor.get[String]("type").toOption) should contain (Some("function"))
      declaredtools.map(_.hcursor.get[String]("type").toOption) should contain (Some("google_search"))
      val secondbody = parse(driver.bodies(1)).toOption.get
      secondbody.hcursor.get[String]("previous_interaction_id").toOption shouldBe Some("interaction_123")
      val resultsbody = secondbody.hcursor.downField("input").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      resultsbody.map(_.hcursor.get[String]("type").toOption) shouldBe Vector(Some("function_result"), Some("function_result"))
      resultsbody.map(_.hcursor.get[String]("call_id").toOption) shouldBe Vector(Some("call_123"), Some("call_456"))
      val thirdbody = parse(driver.bodies(2)).toOption.get
      thirdbody.hcursor.get[String]("previous_interaction_id").toOption shouldBe Some("interaction_456")
      thirdbody.hcursor.downField("input").focus.flatMap(_.asArray).getOrElse(Vector.empty)
        .map(_.hcursor.get[String]("call_id").toOption) shouldBe Vector(Some("call_789"))
    }

    "reject a function call without its native call identifier" in {
      Given("a malformed Interactions function call")
      val driver = new _Driver(Vector(
        """{"id":"interaction_123","model":"gemini-test","steps":[{"type":"function_call","name":"mcp_123","arguments":{}}]}"""
      ))
      given ExecutionContext = _context(driver)
      val service = new GoogleChatService(
        GoogleRuntimeConfig(
          endpoint = URI.create("https://generativelanguage.googleapis.com"),
          apiKey = "test-key",
          model = "gemini-test"
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
      result.toString should include ("Gemini function call has no id")
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
        name = "google-tool-calling-spec",
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
      token = "google-tool-calling-spec"
    )
    context
  }
}

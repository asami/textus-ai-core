package org.simplemodeling.textus.ai.provider.gemma

import java.net.URI
import java.nio.charset.StandardCharsets
import cats.~>
import io.circe.Json
import io.circe.parser.parse
import org.goldenport.Consequence
import org.goldenport.cncf.context.{ExecutionContext, RuntimeContext}
import org.goldenport.cncf.http.HttpDriver
import org.goldenport.bag.Bag
import org.goldenport.datatype.{ContentType, MimeType}
import org.goldenport.http.{HttpResponse, HttpStatus}
import org.goldenport.protocol.Property
import org.goldenport.cncf.unitofwork.{UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.simplemodeling.textus.ai.ai.{ToolCall, ToolChatMessage, ToolChatRequest, ToolDefinition}

/*
 * Executable specification for the Ollama function-call wire adapter.
 *
 * @since   Jul. 21, 2026
 * @version Jul. 21, 2026
 * @author  ASAMI, Tomoharu
 */
final class GemmaToolCallingSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "GemmaOllamaChatService" should {
    "send runtime-owned function definitions and parse returned tool calls" in {
      Given("an Ollama response that requests one admitted runtime function")
      val driver = new _Driver(
        """{
          |  "message": {
          |    "content": "",
          |    "tool_calls": [{"function": {"name": "mcp_123", "arguments": {"query": "Nakano"}}}]
          |  },
          |  "done_reason": "tool_calls",
          |  "prompt_eval_count": 12,
          |  "eval_count": 4
          |}""".stripMargin
      )
      given ExecutionContext = _context(driver)
      val service = new GemmaOllamaChatService(
        GemmaRuntimeConfig(endpoint = URI.create("http://ollama.test:11434")),
        summon[ExecutionContext]
      )

      When("Textus AI starts the first bounded tool turn")
      val result = service.chatWithTools(ToolChatRequest(
        messages = Vector(ToolChatMessage("user", "Find station context.")),
        tools = Vector(ToolDefinition(
          "mcp_123",
          Some("Look up a place."),
          Json.obj(
            "type" -> Json.fromString("object"),
            "properties" -> Json.obj("query" -> Json.obj("type" -> Json.fromString("string")))
          )
        )),
        maxTokens = Some(64)
      ))

      Then("the provider preserves the bounded function identity and structured arguments")
      result.toOption.map(_.message.toolCalls) shouldBe Some(Vector(
        ToolCall("mcp_123", Json.obj("query" -> Json.fromString("Nakano")))
      ))
      result.toOption.flatMap(_.metadata.get("gemma.usage.total_tokens")) shouldBe Some("16")
      val body = parse(driver.body.getOrElse("")).toOption.get
      body.hcursor.downField("tools").downArray.downField("function").get[String]("name").toOption shouldBe Some("mcp_123")
      body.hcursor.downField("messages").downArray.get[String]("role").toOption shouldBe Some("user")
    }
  }

  private final class _Driver(response: String) extends HttpDriver {
    var body: Option[String] = None

    def get(path: String, headers: Map[String, String], properties: Vector[Property] = Vector.empty): HttpResponse = _response

    def post(path: String, body: Option[String], headers: Map[String, String], properties: Vector[Property] = Vector.empty): HttpResponse = {
      this.body = body
      _response
    }

    def put(path: String, body: Option[String], headers: Map[String, String], properties: Vector[Property] = Vector.empty): HttpResponse = _response

    private def _response: HttpResponse = HttpResponse.Text(
      HttpStatus.Ok,
      ContentType(MimeType("application/json"), Some(StandardCharsets.UTF_8)),
      Bag.text(response, StandardCharsets.UTF_8)
    )
  }

  private def _context(driver: HttpDriver): ExecutionContext = {
    val base = ExecutionContext.create()
    var runtime: RuntimeContext = null
    lazy val context: ExecutionContext = ExecutionContext.withRuntimeContext(base, runtime)
    lazy val uow = new UnitOfWork(context)
    runtime = new RuntimeContext(
      core = RuntimeContext.core(
        name = "gemma-tool-calling-spec",
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
      token = "gemma-tool-calling-spec"
    )
    context
  }
}

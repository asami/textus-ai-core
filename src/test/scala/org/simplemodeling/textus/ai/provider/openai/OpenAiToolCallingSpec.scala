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
  "OpenAiBoundedTaskInvoker" should {
    "use the shared Record projection for null fields and array positions" in {
      val schema = parse("""{"required":["answer"]}""").toOption.get
      val nested = parse("""{"answer":{"present":"yes","missing":null},"optional":null}""").toOption.get
      val result = org.simplemodeling.textus.ai.runtime.TextusAiRecordProjection.project(nested, schema).toOption.get
      result.getAny("optional") shouldBe None
      val answer = result.getAny("answer").get.asInstanceOf[org.goldenport.record.Record]
      answer.getAny("missing") shouldBe None
      val missing = parse("""{"answer":null}""").toOption.get
      org.simplemodeling.textus.ai.runtime.TextusAiRecordProjection.project(missing, schema).isLeft shouldBe true
      val badArray = parse("""{"answer":["a",null,"b"]}""").toOption.get
      org.simplemodeling.textus.ai.runtime.TextusAiRecordProjection.project(badArray, schema).left.toOption.get should include ("[1]")
    }

    "reject a missing or different response model and preserve observed facts" in {
      import org.simplemodeling.textus.ai.runtime.*
      val request = AiTaskRequest("model-check", "review", AiTaskPackage("ref", "owner", "input"), "{}", Set.empty, Set.empty, "standard", 10000L, 0L, true, false)
      val candidate = AiTaskCandidate("openai", "pinned-model", Set.empty, Set.empty, Some(1L), 1L, request, Some(0L))
      val authority = new AiTaskAllowanceAuthority {
        def reserve(r: AiTaskRequest, c: AiTaskCandidate, d: Long) = Right(AiTaskAllowance(r, c, 7, 0L))
      }
      Vector(None, Some("other-model")).foreach { model =>
        val response = Json.obj(
          "choices" -> Json.arr(Json.obj("message" -> Json.obj("content" -> Json.fromString("{}"))))
        ).deepMerge(model.map(m => Json.obj("model" -> Json.fromString(m))).getOrElse(Json.obj()))
        val post = new AiTaskManagedPost {
          override def isQualified = true
          def post(body: Json, timeoutMillis: Long, maxResponseBytes: Int) = Right((200, Some("observed-id"), response.noSpaces))
        }
        new OpenAiBoundedTaskInvoker(authority, post, 4096).invoke(request, candidate) match {
          case AiTaskDispatch.Failed(_, receipt) =>
            receipt.actualModel shouldBe model
            receipt.providerRequestId shouldBe Some("observed-id")
          case other => fail(s"unexpected $other")
        }
      }
    }

    "deny without a reservation and never dispatch" in {
      import org.simplemodeling.textus.ai.runtime.*
      val request = AiTaskRequest("task", "review", AiTaskPackage("ref", "owner", "input"), "{}", Set.empty, Set.empty, "standard", 10000L, 0L, true, false)
      val candidate = AiTaskCandidate("openai", "exact-model", Set.empty, Set.empty, Some(1L), 1L, request, Some(0L))
      var calls = 0
      val authority = new AiTaskAllowanceAuthority {
        def reserve(r: AiTaskRequest, c: AiTaskCandidate, d: Long) = Left("not entitled")
      }
      val post = new AiTaskManagedPost {
        override def isQualified = true
        def post(body: Json, timeoutMillis: Long, maxResponseBytes: Int) = { calls += 1; Left("unused") }
      }
      val invoker = new OpenAiBoundedTaskInvoker(authority, post, 4096)
      invoker.invoke(request, candidate).isInstanceOf[AiTaskDispatch.Declined] shouldBe true
      calls shouldBe 0
    }

    "preserve the receipt when OpenAI returns valid JSON without completing the answer" in {
      import org.simplemodeling.textus.ai.runtime.*
      val request = AiTaskRequest("incomplete-answer", "review", AiTaskPackage("ref", "owner", "input"),
        """{"required":["answer"]}""", Set.empty, Set.empty, "standard", 10000L, 0L, true, false)
      val candidate = AiTaskCandidate("openai", "exact-model", Set.empty, Set.empty, Some(1L), 1L, request, Some(0L))
      val authority = new AiTaskAllowanceAuthority {
        def reserve(r: AiTaskRequest, c: AiTaskCandidate, d: Long) = Right(AiTaskAllowance(r, c, 7, 0L))
      }
      for (reason <- Vector(Some("length"), Some("content_filter"), Some("tool_calls"), None)) {
        val choice = Json.obj("message" -> Json.obj("content" -> Json.fromString("""{"answer":"ok"}""")))
        val response = Json.obj(
          "model" -> Json.fromString("exact-model"),
          "usage" -> Json.obj("total_tokens" -> Json.fromLong(7L)),
          "choices" -> Json.arr(choice.mapObject(fields =>
            reason.fold(fields)(value => fields.add("finish_reason", Json.fromString(value)))))
        )
        val post = new AiTaskManagedPost {
          override def isQualified = true
          def post(body: Json, timeoutMillis: Long, maxResponseBytes: Int) =
            Right((200, Some("incomplete-receipt"), response.noSpaces))
        }
        new OpenAiBoundedTaskInvoker(authority, post, 4096).invoke(request, candidate) match {
          case AiTaskDispatch.Failed(_, receipt) =>
            receipt.actualModel shouldBe Some("exact-model")
            receipt.providerRequestId shouldBe Some("incomplete-receipt")
            receipt.usage shouldBe AiTaskUsage.Measured(7L, None)
          case other => fail(s"unexpected completion for $reason: $other")
        }
      }
    }

    "close a timed-out reservation before a late transport can begin" in {
      import org.simplemodeling.textus.ai.runtime.*
      import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
      import java.util.concurrent.atomic.AtomicInteger
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val finished = new CountDownLatch(1)
      val posts = new AtomicInteger()
      val request = AiTaskRequest("blocked-reservation", "review", AiTaskPackage("ref", "owner", "input"),
        "{}", Set.empty, Set.empty, "standard", 200L, 0L, true, false)
      val candidate = AiTaskCandidate("openai", "exact-model", Set.empty, Set.empty, Some(1L), 1L, request, Some(0L))
      val authority = new AiTaskAllowanceAuthority {
        def reserve(r: AiTaskRequest, c: AiTaskCandidate, d: Long) = {
          entered.countDown()
          var waiting = true
          while (waiting) try { release.await(); waiting = false } catch { case _: InterruptedException => () }
          finished.countDown()
          Right(AiTaskAllowance(r, c, 7, 0L))
        }
      }
      val post = new AiTaskManagedPost {
        override def isQualified = true
        def post(body: Json, timeoutMillis: Long, maxResponseBytes: Int) = { posts.incrementAndGet(); Left("unused") }
      }
      val caller = Executors.newSingleThreadExecutor()
      try {
        val result = caller.submit(new java.util.concurrent.Callable[AiTaskDispatch] {
          def call() = new OpenAiBoundedTaskInvoker(authority, post, 4096).invoke(request, candidate)
        })
        entered.await(2, TimeUnit.SECONDS) shouldBe true
        result.get(2, TimeUnit.SECONDS).isInstanceOf[AiTaskDispatch.Declined] shouldBe true
        release.countDown()
        finished.await(2, TimeUnit.SECONDS) shouldBe true
        posts.get() shouldBe 0
      } finally { release.countDown(); caller.shutdownNow() }
    }

    "return uncertainty when transport remains blocked after dispatch" in {
      import org.simplemodeling.textus.ai.runtime.*
      import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val request = AiTaskRequest("blocked-post", "review", AiTaskPackage("ref", "owner", "input"),
        "{}", Set.empty, Set.empty, "standard", 200L, 0L, true, false)
      val candidate = AiTaskCandidate("openai", "exact-model", Set.empty, Set.empty, Some(1L), 1L, request, Some(0L))
      val authority = new AiTaskAllowanceAuthority {
        def reserve(r: AiTaskRequest, c: AiTaskCandidate, d: Long) = Right(AiTaskAllowance(r, c, 7, 0L, Some("verified")))
      }
      val post = new AiTaskManagedPost {
        override def isQualified = true
        def post(body: Json, timeoutMillis: Long, maxResponseBytes: Int) = {
          entered.countDown()
          var waiting = true
          while (waiting) try { release.await(); waiting = false } catch { case _: InterruptedException => () }
          Left("uncertain")
        }
      }
      val caller = Executors.newSingleThreadExecutor()
      try {
        val invoker = new OpenAiBoundedTaskInvoker(authority, post, 4096)
        val result = caller.submit(new java.util.concurrent.Callable[AiTaskDispatch] { def call() = invoker.invoke(request, candidate) })
        entered.await(2, TimeUnit.SECONDS) shouldBe true
        result.get(2, TimeUnit.SECONDS) match {
          case AiTaskDispatch.Uncertain(_, receipt) => receipt.reservationBasis shouldBe Some("verified")
          case other => fail(s"Expected uncertainty, got $other")
        }
        invoker.invoke(request, candidate).isInstanceOf[AiTaskDispatch.Uncertain] shouldBe true
      } finally { release.countDown(); caller.shutdownNow() }
    }

    "carry the selected deadline through reservation, dispatch and projection" in {
      import org.simplemodeling.textus.ai.runtime.*
      import java.util.concurrent.TimeUnit.MILLISECONDS
      val request = AiTaskRequest("deadline-check", "review", AiTaskPackage("ref", "owner", "input"),
        "{}", Set.empty, Set.empty, "standard", 100L, 0L, true, false)
      val candidate = AiTaskCandidate("openai", "exact-model", Set.empty, Set.empty,
        Some(1L), 1L, request, Some(0L))
      for ((reservedAt, returnedAt) <- Vector((80L, 90L), (100L, 110L), (80L, 101L))) {
        var now = MILLISECONDS.toNanos(30)
        val deadline = AiTaskDeadline.after(0L, 100L, () => now)
        val authority = new AiTaskAllowanceAuthority {
          override def reserve(req: AiTaskRequest, selected: AiTaskCandidate, end: Long)
              : Either[String, AiTaskAllowance] = {
            end shouldBe MILLISECONDS.toNanos(100)
            now = MILLISECONDS.toNanos(reservedAt)
            Right(AiTaskAllowance(req, selected, 7, 0L))
          }
        }
        var posts = Vector.empty[Long]
        val post = new AiTaskManagedPost {
          override def isQualified = true
          override def post(body: Json, remaining: Long, maxResponseBytes: Int) = {
            posts :+= remaining
            now = MILLISECONDS.toNanos(returnedAt)
            Right((200, Some("deadline-observed"),
              """{"model":"exact-model","usage":{"total_tokens":5},"choices":[{"finish_reason":"stop","message":{"content":"{}"}}]}"""))
          }
        }
        val result = new OpenAiBoundedTaskInvoker(authority, post, 4096, Some(deadline)).invoke(request, candidate)
        if (reservedAt >= 100) {
          result.isInstanceOf[AiTaskDispatch.Declined] shouldBe true
          posts shouldBe empty
        } else {
          posts shouldBe Vector(20L)
          if (returnedAt < 100) result.isInstanceOf[AiTaskDispatch.Completed] shouldBe true
          else result match {
            case AiTaskDispatch.Uncertain(_, receipt) => receipt.providerRequestId shouldBe Some("deadline-observed")
            case other => fail(s"Unexpected result after deadline: $other")
          }
        }
      }
    }

    "bind the model and bounds, project real facts, and never replay an uncertain attempt" in {
      import org.simplemodeling.textus.ai.runtime.*
      val request = AiTaskRequest("task", "review", AiTaskPackage("ref", "owner", "input"), "{\"required\":[\"answer\"]}", Set.empty, Set.empty, "standard", 10000L, 0L, true, false)
      val candidate = AiTaskCandidate("openai", "exact-model", Set.empty, Set.empty, Some(1L), 1L, request, Some(0L))
      val authority = new AiTaskAllowanceAuthority {
        def reserve(r: AiTaskRequest, c: AiTaskCandidate, d: Long) = Right(AiTaskAllowance(r, c, 7, 0L))
      }
      var bodies = Vector.empty[Json]
      val post = new AiTaskManagedPost {
        override def isQualified = true
        def post(body: Json, timeoutMillis: Long, maxResponseBytes: Int) = {
          bodies :+= body
          timeoutMillis should be > 0L
          maxResponseBytes shouldBe 4096
          Right((200, Some("request-1"), """{"model":"exact-model","usage":{"total_tokens":5},"choices":[{"finish_reason":"stop","message":{"content":"{\"answer\":\"ok\",\"optional\":null}"}}]}"""))
        }
      }
      val result = new OpenAiBoundedTaskInvoker(authority, post, 4096).invoke(request, candidate)
      result match {
        case AiTaskDispatch.Completed(record, receipt) =>
          record.getAny("answer") shouldBe Some("ok")
          record.getAny("optional") shouldBe None
          receipt.actualModel shouldBe Some("exact-model")
          receipt.providerRequestId shouldBe Some("request-1")
          receipt.usage shouldBe AiTaskUsage.Measured(5L, None)
        case other => fail(s"unexpected $other")
      }
      bodies.head.hcursor.get[String]("model").toOption shouldBe Some("exact-model")
      bodies.head.hcursor.get[Int]("max_completion_tokens").toOption shouldBe Some(7)
      var attempts = 0
      var eligible = true
      val uncertain = new AiTaskManagedPost {
        override def isQualified = eligible
        def post(body: Json, timeoutMillis: Long, maxResponseBytes: Int) = { attempts += 1; Left("timeout") }
      }
      val invoker = new OpenAiBoundedTaskInvoker(authority, uncertain, 4096)
      invoker.invoke(request, candidate).isInstanceOf[AiTaskDispatch.Uncertain] shouldBe true
      eligible = false
      invoker.preflight(request, candidate).isRight shouldBe true
      invoker.invoke(request, candidate).isInstanceOf[AiTaskDispatch.Uncertain] shouldBe true
      attempts shouldBe 1
    }
  }

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
        def apply[A](fa: UnitOfWorkOp[A]): Consequence[A] = fa match {
          case UnitOfWorkOp.HttpPost(path, body, headers, properties) =>
            Consequence.success(driver.post(path, body, headers, properties))
          case _ => new UnitOfWorkInterpreter(uow).interpret(fa)
        }
      },
      commitaction = _ => (),
      abortaction = _ => (),
      disposeaction = _ => (),
      token = "openai-tool-calling-spec"
    )
    context
  }
}

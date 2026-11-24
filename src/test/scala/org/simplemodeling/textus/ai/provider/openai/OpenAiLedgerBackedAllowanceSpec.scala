package org.simplemodeling.textus.ai.provider.openai

import java.net.URI
import java.nio.file.Files
import java.time.{Instant, ZoneOffset}
import java.util.concurrent.TimeUnit
import io.circe.Json
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.datastore.sql.SqlDataStore
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.simplemodeling.textus.ai.runtime.*

final class OpenAiLedgerBackedAllowanceSpec extends AnyWordSpec with Matchers {
  private val config = OpenAiRuntimeConfig(endpoint = URI.create("https://api.openai.com"),
    apiKey = "fixture-key", model = "gpt-5.6-terra")
  private def window: Vector[(String, Json)] = {
    val now = Instant.now()
    Vector("validFrom" -> Json.fromString(now.minusSeconds(60).toString),
      "expiresAt" -> Json.fromString(now.plusSeconds(300).toString), "provenance" -> Json.fromString("operator-evidence"))
  }
  private def allocation(daily: Boolean, ineligible: Boolean): String = {
    val w = window
    val common = Vector("organization" -> Json.fromString("org-fixture"), "project" -> Json.fromString("proj-fixture"),
      "origin" -> Json.fromString("https://api.openai.com"), "model" -> Json.fromString(config.model),
      "modelPool" -> Json.fromString("openai-daily-group-2"), "inputTokenCeiling" -> Json.fromLong(1050000),
      "maxOutputTokens" -> Json.fromInt(32), "fullCostMicros" -> Json.fromLong(5250576),
      "reviewMaxOutputTokens" -> Json.fromInt(32), "repairMaxOutputTokens" -> Json.fromInt(24),
      "sizing" -> Json.obj("model" -> Json.fromString(config.model),
        "format" -> Json.fromString("chat-completions-bounded-record-v1"),
        "tokensPerByte" -> Json.fromLong(1), "overheadTokens" -> Json.fromLong(1049000),
        "provenance" -> Json.fromString("synthetic-fixture-only")))
    val d = if (daily) Vector("daily" -> Json.obj((w ++ Vector(
      "day" -> Json.fromString(Instant.now().atZone(ZoneOffset.UTC).toLocalDate.toString),
      "limitTokens" -> Json.fromLong(1166703)))*)) else Vector.empty
    val c = Vector("credit" -> Json.obj((w ++ Vector("allocationId" -> Json.fromString("credit-fixture"),
      "limitMicros" -> Json.fromLong(5250576)))*))
    val noDaily = if (ineligible) Vector("dailyIneligible" -> Json.obj(w*)) else Vector.empty
    Json.obj((common ++ d ++ c ++ noDaily)*).noSpaces
  }
  private def request(id: String) = AiTaskRequest(id, "review", AiTaskPackage("reference", "fixture", "hello"),
    "{}", Set("structured-record"), Set.empty, "standard", 10000L, 0L, true, false)
  private def fixture(f: ExecutionContext => Unit): Unit = {
    val path = Files.createTempDirectory("openai-host-ledger-").resolve("ledger.db")
    val db = SqlDataStore.sqlite(path.toString)
    val ctx = ExecutionContext.test()
    ctx.dataStoreSpace.useDataStore(db)
    try f(ctx) finally db.closeC()
  }
  private val fixtureVerifier = new OpenAiRequestSizingVerifier {
    def verify(endpoint: String, model: String, format: String, tokensPerByte: Long,
        overheadTokens: Long, provenance: String): Boolean =
      endpoint == "https://api.openai.com" && model == "gpt-5.6-terra" &&
        format == "chat-completions-bounded-record-v1" && tokensPerByte == 1 &&
        Set(100L, 1049000L).contains(overheadTokens) && provenance == "synthetic-fixture-only"
  }
  private def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)

  "A host-owned OpenAI allocation" should {
    "require explicit model and format scoped sizing and separate caps" in fixture { ctx =>
      val base = io.circe.parser.parse(allocation(true, false)).toOption.get
      def auth(j: Json) = OpenAiLedgerBackedAllowance.parse(j.noSpaces, config)
        .map(OpenAiLedgerBackedAllowance.create(config, _, ctx, sizingVerifier = Some(fixtureVerifier)))
      val r = request("missing-sizing")
      val unverified = OpenAiLedgerBackedAllowance.create(config,
        OpenAiLedgerBackedAllowance.parse(base.noSpaces, config).get, ctx)
      unverified.assess(r, deadline) shouldBe None
      val forged = base.mapObject(_.add("sizing", base.hcursor.downField("sizing").focus.get
        .mapObject(_.add("provenance", Json.fromString("forged")))))
      auth(forged).get.assess(r, deadline) shouldBe None
      auth(base.mapObject(_.remove("sizing"))).get.assess(r, deadline) shouldBe None
      auth(base.mapObject(_.remove("reviewMaxOutputTokens").remove("repairMaxOutputTokens"))).get
        .assess(r, deadline) shouldBe None
      val sizing = base.hcursor.downField("sizing").focus.get
      auth(base.mapObject(_.add("sizing", sizing.mapObject(_.add("model", Json.fromString("unknown")))))) shouldBe None
      auth(base.mapObject(_.add("sizing", sizing.mapObject(_.add("format", Json.fromString("unknown")))))) shouldBe None
      auth(base.mapObject(_.remove("repairMaxOutputTokens"))) shouldBe None
      val oversized = sizing.mapObject(_.add("tokensPerByte", Json.fromLong(Long.MaxValue)))
      auth(base.mapObject(_.add("sizing", oversized))).get.assess(r, deadline) shouldBe None
    }

    "reserve small separately bounded requests and bind the repair cap to the dispatched body" in fixture { ctx =>
      val base = io.circe.parser.parse(allocation(true, false)).toOption.get
      val sizing = base.hcursor.downField("sizing").focus.get.mapObject(_.add("overheadTokens", Json.fromLong(100)))
      val small = base.mapObject(_.add("sizing", sizing))
      val authority = OpenAiLedgerBackedAllowance.create(config,
        OpenAiLedgerBackedAllowance.parse(small.noSpaces, config).get, ctx, sizingVerifier = Some(fixtureVerifier))
      val review = request("small-review")
      val repair = request("small-repair").copy(kind = "compiler-repair")
      authority.reserve(review, authority.assess(review, deadline).get, deadline).toOption.get.maxOutputTokens shouldBe 32
      var posted = Vector.empty[Json]
      val post = new AiTaskManagedPost {
        override def isQualified = true
        def post(body: Json, timeoutMillis: Long, maxResponseBytes: Int) = {
          posted :+= body
          Left("synthetic uncertain result")
        }
      }
      val invoker = new OpenAiBoundedTaskInvoker(authority, post, 4096)
      invoker.invoke(repair, authority.assess(repair, deadline).get).isInstanceOf[AiTaskDispatch.Uncertain] shouldBe true
      posted shouldBe Vector(OpenAiBoundedRequest.body(repair, config.model, 24))
      invoker.invoke(repair, authority.assess(repair, deadline).get).isInstanceOf[AiTaskDispatch.Uncertain] shouldBe true
      posted.size shouldBe 1
    }

    "reserve exact request sized totals at the margin adjusted boundary" in fixture { ctx =>
      val base = io.circe.parser.parse(allocation(true, false)).toOption.get
      val sizing = base.hcursor.downField("sizing").focus.get.mapObject(_.add("overheadTokens", Json.fromLong(100)))
      val requests = Vector(request("sized-1"), request("sized-2").copy(
        input = AiTaskPackage("reference", "fixture", "a longer second prompt")))
      def total(r: AiTaskRequest): Long =
        OpenAiBoundedRequest.body(r, config.model, 32).noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong + 100L + 32L
      val sum = requests.map(total).sum
      // Choose a gross limit whose 90% cutoff equals the exact two reservations.
      val gross = (sum * 100L + 89L) / 90L
      val cutoff = gross * 90L / 100L
      val daily = base.hcursor.downField("daily").focus.get.mapObject(_.add("limitTokens", Json.fromLong(gross)))
      val json = base.mapObject(_.add("sizing", sizing).add("daily", daily).remove("credit"))
      val authority = OpenAiLedgerBackedAllowance.create(config,
        OpenAiLedgerBackedAllowance.parse(json.noSpaces, config).get, ctx, sizingVerifier = Some(fixtureVerifier))
      def reserve(r: AiTaskRequest) = authority.reserve(r, authority.assess(r, deadline).get, deadline)
      cutoff shouldBe sum
      requests.foreach(r => reserve(r).toOption.get.reservationBasis shouldBe Some("verified-daily-reservation"))
      reserve(requests.head).isLeft shouldBe true
      reserve(request("sized-3")).isLeft shouldBe true
    }

    "reject the second distinct reservation when the daily cutoff is one token lower" in fixture { ctx =>
      val base = io.circe.parser.parse(allocation(true, false)).toOption.get
      val sizing = base.hcursor.downField("sizing").focus.get.mapObject(_.add("overheadTokens", Json.fromLong(100)))
      val requests = Vector(request("sized-1"), request("sized-2").copy(
        input = AiTaskPackage("reference", "fixture", "a longer second prompt")))
      def total(r: AiTaskRequest): Long =
        OpenAiBoundedRequest.body(r, config.model, 32).noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong + 100L + 32L
      val cutoff = requests.map(total).sum - 1L
      val gross = (cutoff * 100L + 89L) / 90L
      val daily = base.hcursor.downField("daily").focus.get.mapObject(_.add("limitTokens", Json.fromLong(gross)))
      val json = base.mapObject(_.add("sizing", sizing).add("daily", daily).remove("credit"))
      val authority = OpenAiLedgerBackedAllowance.create(config,
        OpenAiLedgerBackedAllowance.parse(json.noSpaces, config).get, ctx, sizingVerifier = Some(fixtureVerifier))
      def reserve(r: AiTaskRequest) = authority.reserve(r, authority.assess(r, deadline).get, deadline)
      gross * 90L / 100L shouldBe cutoff
      total(requests(0)) should not be total(requests(1))
      reserve(requests(0)).toOption.get.reservationBasis shouldBe Some("verified-daily-reservation")
      reserve(requests(1)).isLeft shouldBe true
    }

    "resolve the daily margin with a default and reject invalid explicit values" in {
      OpenAiLedgerBackedAllowance.dailyMarginPercent(None) shouldBe Some(10)
      Vector("1", "99").map(x => OpenAiLedgerBackedAllowance.dailyMarginPercent(Some(x))) shouldBe Vector(Some(1), Some(99))
      Vector("", "0", "100", "-1", "10.0", " 10 ", "invalid", "999999999999999999999")
        .foreach(x => OpenAiLedgerBackedAllowance.dailyMarginPercent(Some(x)) shouldBe None)
    }

    "retain same-day claims and gross ledger while changing the daily margin" in fixture { ctx =>
      val json = io.circe.parser.parse(allocation(true, false)).toOption.get
      val d = json.hcursor.downField("daily").focus.get.mapObject(_.add("limitTokens", Json.fromLong(2333405)))
      val evidence = OpenAiLedgerBackedAllowance.parse(json.mapObject(_.add("daily", d)).noSpaces, config).get
      def authority(margin: Int) = OpenAiLedgerBackedAllowance.create(config, evidence, ctx, margin, Some(fixtureVerifier))
      def reserve(a: OpenAiTaskAllowanceAuthority, id: String) = {
        val r = request(id)
        a.reserve(r, a.assess(r, deadline).get, deadline)
      }
      val initial = authority(10)
      reserve(initial, "margin-first").toOption.get.reservationBasis shouldBe Some("verified-daily-reservation")
      val tighter = authority(50)
      reserve(tighter, "margin-first").isLeft shouldBe true
      reserve(tighter, "margin-second").toOption.get.reservationBasis shouldBe Some("verified-promotional-credit-reservation")
      val relaxed = authority(1)
      reserve(relaxed, "margin-second").isLeft shouldBe true
      reserve(relaxed, "margin-third").toOption.get.reservationBasis shouldBe Some("verified-daily-reservation")
      reserve(initial, "margin-fourth").isLeft shouldBe true
    }

    "reject missing daily evidence and mismatched origin or model before dispatch" in {
      OpenAiLedgerBackedAllowance.parse(allocation(false, false), config) shouldBe None
      OpenAiLedgerBackedAllowance.parse(allocation(true, false), config.copy(model = "other")) shouldBe None
      OpenAiLedgerBackedAllowance.parse(allocation(true, false), config.copy(endpoint = URI.create("https://example.com"))) shouldBe None
    }

    "reject unsupported groups, bounds and incomplete quotes" in {
      val valid = io.circe.parser.parse(allocation(true, false)).toOption.get
      def changed(key: String, value: Json) = valid.mapObject(_.add(key, value)).noSpaces
      OpenAiLedgerBackedAllowance.parse(changed("modelPool", Json.fromString("other")), config) shouldBe None
      OpenAiLedgerBackedAllowance.parse(changed("inputTokenCeiling", Json.fromLong(1049999)), config) shouldBe None
      OpenAiLedgerBackedAllowance.parse(changed("inputTokenCeiling", Json.fromLong(1050001)), config) shouldBe None
      OpenAiLedgerBackedAllowance.parse(changed("maxOutputTokens", Json.fromInt(128001)), config) shouldBe None
      OpenAiLedgerBackedAllowance.parse(changed("maxOutputTokens", Json.fromInt(0)), config) shouldBe None
      OpenAiLedgerBackedAllowance.parse(changed("fullCostMicros", Json.fromLong(5250575)), config) shouldBe None
      val daily = valid.hcursor.downField("daily").focus.get.mapObject(_.add("limitTokens", Json.fromLong(10000001)))
      OpenAiLedgerBackedAllowance.parse(changed("daily", daily), config) shouldBe None
    }

    "share daily and credit limits and claims across projects" in fixture { ctx =>
      val firstJson = io.circe.parser.parse(allocation(true, false)).toOption.get
      val secondJson = firstJson.mapObject(_.add("project", Json.fromString("proj-second")))
      def authority(j: Json) = OpenAiLedgerBackedAllowance.create(config,
        OpenAiLedgerBackedAllowance.parse(j.noSpaces, config).get, ctx, sizingVerifier = Some(fixtureVerifier))
      val first = authority(firstJson)
      val second = authority(secondJson)
      first.connection.project shouldBe Some("proj-fixture")
      second.connection.project shouldBe Some("proj-second")
      def reserve(a: OpenAiTaskAllowanceAuthority, id: String) = {
        val r = request(id)
        a.reserve(r, a.assess(r, deadline).get, deadline)
      }
      reserve(first, "org-first").toOption.get.reservationBasis shouldBe Some("verified-daily-reservation")
      reserve(second, "org-first").isLeft shouldBe true
      reserve(second, "org-second").toOption.get.reservationBasis shouldBe Some("verified-promotional-credit-reservation")
      reserve(first, "org-third").isLeft shouldBe true
    }

    "reserve daily and credit independently in the actual shared entity store" in fixture { ctx =>
      val daily = OpenAiLedgerBackedAllowance.create(config,
        OpenAiLedgerBackedAllowance.parse(allocation(true, false), config).get, ctx, sizingVerifier = Some(fixtureVerifier))
      val r = request("daily-fixture")
      val c = daily.assess(r, deadline).get
      val admitted = daily.reserve(r, c, deadline).toOption.get
      admitted.reservationBasis shouldBe Some("verified-daily-reservation")
      daily.reserve(r, c, deadline).isLeft shouldBe true
      val monthly = OpenAiLedgerBackedAllowance.create(config,
        OpenAiLedgerBackedAllowance.parse(allocation(false, true), config).get, ctx, sizingVerifier = Some(fixtureVerifier))
      val cr = request("credit-fixture")
      val cc = monthly.assess(cr, deadline).get
      monthly.reserve(cr, cc, deadline).toOption.get.reservationBasis shouldBe Some("verified-promotional-credit-reservation")
      monthly.reserve(cr, cc, deadline).isLeft shouldBe true
    }

    "dispatch exactly the reserved canonical body and preserve uncertainty" in fixture { ctx =>
      val authority = OpenAiLedgerBackedAllowance.create(config,
        OpenAiLedgerBackedAllowance.parse(allocation(true, false), config).get, ctx, sizingVerifier = Some(fixtureVerifier))
      val r = request("body-fixture")
      val candidate = authority.assess(r, deadline).get
      var observed = Vector.empty[Json]
      val post = new AiTaskManagedPost {
        override def isQualified = true
        def post(body: Json, timeoutMillis: Long, maxResponseBytes: Int) = {
          observed :+= body
          Left("fixture transport uncertainty")
        }
      }
      val invoker = new OpenAiBoundedTaskInvoker(authority, post, 4096)
      invoker.invoke(r, candidate) match {
        case AiTaskDispatch.Uncertain(_, receipt) => receipt.reservationBasis shouldBe Some("verified-daily-reservation")
        case other => fail(s"unexpected $other")
      }
      observed shouldBe Vector(OpenAiBoundedRequest.body(r, config.model, 32))
      observed.head.hcursor.get[String]("service_tier").toOption shouldBe Some("default")
      observed.head.hcursor.get[Int]("max_completion_tokens").toOption shouldBe Some(32)
      invoker.invoke(r, candidate).isInstanceOf[AiTaskDispatch.Uncertain] shouldBe true
      observed.size shouldBe 1
    }
  }
}

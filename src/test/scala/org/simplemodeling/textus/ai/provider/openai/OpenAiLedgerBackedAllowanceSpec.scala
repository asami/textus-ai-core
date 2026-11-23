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
      "maxOutputTokens" -> Json.fromInt(32), "fullCostMicros" -> Json.fromLong(5250576))
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
  private def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)

  "A host-owned OpenAI allocation" should {
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
      def authority(margin: Int) = OpenAiLedgerBackedAllowance.create(config, evidence, ctx, margin)
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
        OpenAiLedgerBackedAllowance.parse(j.noSpaces, config).get, ctx)
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
        OpenAiLedgerBackedAllowance.parse(allocation(true, false), config).get, ctx)
      val r = request("daily-fixture")
      val c = daily.assess(r, deadline).get
      val admitted = daily.reserve(r, c, deadline).toOption.get
      admitted.reservationBasis shouldBe Some("verified-daily-reservation")
      daily.reserve(r, c, deadline).isLeft shouldBe true
      val monthly = OpenAiLedgerBackedAllowance.create(config,
        OpenAiLedgerBackedAllowance.parse(allocation(false, true), config).get, ctx)
      val cr = request("credit-fixture")
      val cc = monthly.assess(cr, deadline).get
      monthly.reserve(cr, cc, deadline).toOption.get.reservationBasis shouldBe Some("verified-promotional-credit-reservation")
      monthly.reserve(cr, cc, deadline).isLeft shouldBe true
    }

    "dispatch exactly the reserved canonical body and preserve uncertainty" in fixture { ctx =>
      val authority = OpenAiLedgerBackedAllowance.create(config,
        OpenAiLedgerBackedAllowance.parse(allocation(true, false), config).get, ctx)
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

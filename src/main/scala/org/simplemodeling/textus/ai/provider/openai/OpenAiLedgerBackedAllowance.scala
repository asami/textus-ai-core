package org.simplemodeling.textus.ai.provider.openai

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.concurrent.{ArrayBlockingQueue, ThreadPoolExecutor, TimeUnit}
import io.circe.{Json, parser}
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.entity.StandardEntityStore
import org.simplemodeling.textus.ai.runtime.*
import scala.util.Try

/** Immutable operator-provided evidence; no credential or observed balance establishes authority. */
private[ai] object OpenAiLedgerBackedAllowance {
  private val DefaultDailyMarginPercent = 10

  final case class Window(from: Instant, until: Instant, provenance: String)
  final case class Daily(day: LocalDate, limit: Long, window: Window)
  final case class Credit(id: String, limit: Long, window: Window)
  final case class Allocation(organization: String, project: String, model: String, modelPool: String,
    inputLimit: Long, outputLimit: Int, fullCostMicros: Long, daily: Option[Daily],
    credit: Option[Credit], dailyIneligible: Option[Window])

  private val executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
    new ArrayBlockingQueue[Runnable](64), (r: Runnable) => {
      val t = new Thread(r, "textus-openai-allowance"); t.setDaemon(true); t
    }, new ThreadPoolExecutor.AbortPolicy())
  executor.allowCoreThreadTimeOut(true)

  private def text(s: String): Boolean = s != null && s.nonEmpty && s == s.trim &&
    s.length <= 512 && !s.exists(_.isControl)
  private def fields(j: Json, required: Set[String], optional: Set[String] = Set.empty): Boolean =
    j.asObject.exists(o => required.subsetOf(o.keys.toSet) && o.keys.toSet.subsetOf(required ++ optional))
  private def string(j: Json, key: String): Option[String] = j.hcursor.get[String](key).toOption.filter(text)
  private def long(j: Json, key: String): Option[Long] = j.hcursor.get[Long](key).toOption.filter(_ > 0)
  private def window(j: Json, extra: Set[String]): Option[Window] = for {
    _ <- Option.when(fields(j, Set("validFrom", "expiresAt", "provenance") ++ extra))(())
    from <- string(j, "validFrom").flatMap(x => Try(Instant.parse(x)).toOption)
    until <- string(j, "expiresAt").flatMap(x => Try(Instant.parse(x)).toOption)
    provenance <- string(j, "provenance")
    _ <- Option.when(from.isBefore(until))(())
  } yield Window(from, until, provenance)

  def parse(raw: String, connection: OpenAiRuntimeConfig): Option[Allocation] = try {
    val j = parser.parse(raw).toOption.get
    val required = Set("organization", "project", "origin", "model", "modelPool", "inputTokenCeiling", "maxOutputTokens", "fullCostMicros")
    if (!fields(j, required, Set("daily", "credit", "dailyIneligible"))) return None
    val org = string(j, "organization").get
    val project = string(j, "project").get
    val model = string(j, "model").get
    val pool = string(j, "modelPool").get
    val origin = string(j, "origin").get
    if (!org.matches("[A-Za-z0-9_-]+") || !project.matches("[A-Za-z0-9_-]+")) return None
    if (origin != "https://api.openai.com" || connection.endpoint.toString != origin ||
        connection.provider != "openai" || connection.mode != "remote" || connection.engine != "gpt" ||
        connection.model != model || !text(connection.apiKey)) return None
    if (model != "gpt-5.6-terra" || pool != "openai-daily-group-2") return None
    val input = long(j, "inputTokenCeiling").get
    val output = long(j, "maxOutputTokens").filter(_ <= Int.MaxValue).get.toInt
    val quote = long(j, "fullCostMicros").get
    if (input != 1050000L || output > 128000 ||
        quote < Math.addExact(Math.multiplyExact(input, 5L), Math.multiplyExact(output.toLong, 18L))) return None
    val dailyJson = j.hcursor.downField("daily").focus
    val daily = dailyJson.map { d =>
      val w = window(d, Set("day", "limitTokens")).get
      val day = LocalDate.parse(string(d, "day").get)
      Daily(day, long(d, "limitTokens").filter(_ <= 10000000L).get, w)
    }
    val credit = j.hcursor.downField("credit").focus.map { c =>
      Credit(string(c, "allocationId").get, long(c, "limitMicros").get,
        window(c, Set("allocationId", "limitMicros")).get)
    }
    val ineligible = j.hcursor.downField("dailyIneligible").focus.map(d => window(d, Set.empty).get)
    if (daily.isEmpty && (credit.isEmpty || ineligible.isEmpty)) return None
    Some(Allocation(org, project, model, pool, input, output, quote, daily, credit, ineligible))
  } catch { case scala.util.control.NonFatal(_) => None }

  def dailyMarginPercent(value: Option[String]): Option[Int] = value match {
    case None => Some(DefaultDailyMarginPercent)
    case Some(raw) if raw.matches("[0-9]+") => Try(raw.toInt).toOption.filter(n => n >= 1 && n <= 99)
    case _ => None
  }

  def create(config: OpenAiRuntimeConfig, allocation: Allocation, context: ExecutionContext,
      dailyMarginPercent: Int = DefaultDailyMarginPercent): OpenAiTaskAllowanceAuthority = {
    require(dailyMarginPercent >= 1 && dailyMarginPercent <= 99, "Invalid daily margin")
    new OpenAiLedgerBackedAllowance(config, allocation, context, dailyMarginPercent)
  }
}

private[ai] final class OpenAiLedgerBackedAllowance(
  config: OpenAiRuntimeConfig,
  allocation: OpenAiLedgerBackedAllowance.Allocation,
  context: ExecutionContext,
  dailyMarginPercent: Int
) extends OpenAiTaskAllowanceAuthority {
  import OpenAiLedgerBackedAllowance.*
  override val connection: OpenAiRuntimeConfig = config.copy(organization = Some(allocation.organization), project = Some(allocation.project))
  private def live(w: Window, now: Instant): Boolean = !now.isBefore(w.from) && now.isBefore(w.until)
  private def covers(w: Window, now: Instant, end: Instant): Boolean =
    live(w, now) && !end.isAfter(w.until)
  private def dispatchEnd(now: Instant, deadlineNanos: Long): Option[Instant] = {
    val remaining = deadlineNanos - System.nanoTime()
    if (remaining <= 0) None else Try(now.plusNanos(remaining)).toOption.filter(end =>
      !end.isAfter(now.atZone(ZoneOffset.UTC).toLocalDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant))
  }
  private val dailyVerifier = new OpenAiAllowanceGrantVerifier {
    def verify(g: OpenAiAllowanceGrant): Boolean = allocation.daily.exists(d =>
      g.allowanceTokens == d.limit && g.expiresAt == d.window.until && g.allocationProvenance == d.window.provenance &&
      g.request.scope.day == d.day && live(d.window, Instant.now()))
  }
  private val creditVerifier = new OpenAiCreditGrantVerifier {
    def verify(g: OpenAiCreditGrant): Boolean = allocation.credit.exists(c =>
      g.allocationId == c.id && g.validFrom == c.window.from && g.expiresAt == c.window.until &&
      g.allocationMicrounits == c.limit && g.requestMicrounits == allocation.fullCostMicros &&
      g.allocationProvenance == c.window.provenance && live(c.window, Instant.now()))
  }
  private val ineligibility = new OpenAiDailyIneligibilityAuthority {
    def determination(r: OpenAiAllowanceRequest): Option[OpenAiDailyIneligibility] =
      allocation.dailyIneligible.map(w => OpenAiDailyIneligibility(r, w.from, w.until, w.provenance))
    def verify(e: OpenAiDailyIneligibility): Boolean = allocation.dailyIneligible.exists(w =>
      e.validFrom == w.from && e.expiresAt == w.until && e.provenance == w.provenance && live(w, Instant.now()))
  }
  private val ledger = new OpenAiTaskAllowanceLedger(new StandardEntityStore, dailyVerifier,
    OpenAiLedgerBackedAllowance.executor, creditVerifier = creditVerifier, dailyIneligibility = ineligibility)

  override def assess(r: AiTaskRequest, deadlineNanos: Long): Option[AiTaskCandidate] = {
    val now = Instant.now()
    val end = dispatchEnd(now, deadlineNanos)
    val daily = end.exists(e => allocation.daily.exists(d =>
      d.day == now.atZone(ZoneOffset.UTC).toLocalDate && covers(d.window, now, e)))
    val credit = end.exists(e => allocation.credit.exists(c => covers(c.window, now, e) &&
      c.limit >= allocation.fullCostMicros) &&
      (daily || allocation.dailyIneligible.exists(covers(_, now, e))))
    val eligible = (daily || credit) && r.requiredTools.isEmpty
    Option.when(eligible)(AiTaskCandidate("openai", allocation.model, Set("structured-record"), Set.empty,
      Some(1L), 1L, r, Some(0L)))
  }

  override def reserve(r: AiTaskRequest, candidate: AiTaskCandidate, deadlineNanos: Long): Either[String, AiTaskAllowance] = {
    given ExecutionContext = context
    if (!assess(r, deadlineNanos).contains(candidate)) return Left("Allocation or request is unavailable")
    val now = Instant.now()
    val day = now.atZone(ZoneOffset.UTC).toLocalDate
    val body = OpenAiBoundedRequest.body(r, allocation.model, allocation.outputLimit).noSpaces
    val bound = Json.arr(Json.fromString(allocation.organization), Json.fromString(allocation.project),
      Json.fromString(connection.endpoint.toString), Json.fromString(r.input.reference),
      Json.fromString(r.input.provenance), Json.fromString(body)).noSpaces
    val digest = MessageDigest.getInstance("SHA-256").digest(bound.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x").mkString
    val scope = OpenAiAllowanceScope(allocation.organization, allocation.modelPool, day)
    val request = OpenAiAllowanceRequest(scope, r.id, digest, allocation.inputLimit, allocation.outputLimit.toLong)
    val end = dispatchEnd(now, deadlineNanos).getOrElse(return Left("Dispatch window unavailable"))
    val daily = allocation.daily.filter(d => d.day == day && covers(d.window, now, end))
      .map(d => OpenAiAllowanceGrant(request, d.limit, d.window.until, d.window.provenance))
    if (daily.isEmpty && !allocation.dailyIneligible.exists(covers(_, now, end)))
      return Left("Daily eligibility window unavailable")
    val credit = allocation.credit.filter(c => covers(c.window, now, end)).map(c => OpenAiCreditGrant(request, c.id, c.window.from, c.window.until,
      c.limit, allocation.fullCostMicros, c.window.provenance))
    ledger.reserveDailyFirst(request, daily, credit, deadlineNanos, dailyMarginPercent).map { admission =>
      val (basis, until) = admission match {
        case _: OpenAiDailyAdmission => ("verified-daily-reservation", allocation.daily.get.window.until)
        case _: OpenAiCreditAdmission => ("verified-promotional-credit-reservation", allocation.credit.get.window.until)
      }
      val boundary = if (daily.nonEmpty) allocation.daily.get.window.until else allocation.dailyIneligible.get.until
      val midnight = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant
      val scopeEnd = if (until.isBefore(boundary)) until else boundary
      AiTaskAllowance(r, candidate, allocation.outputLimit, 0L, Some(basis),
        Some(if (scopeEnd.isBefore(midnight)) scopeEnd else midnight))
    }
  }
}

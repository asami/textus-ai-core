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

/** Independently registered evidence authority for provider framing and token bounds.
 * A verifier must validate all supplied values against its own evidence.
 */
trait OpenAiRequestSizingVerifier {
  def verify(endpoint: String, model: String, format: String, tokensPerByte: Long,
    overheadTokens: Long, provenance: String): Boolean
}

/** Independent host authority for the complete daily allocation and external-use bound. */
trait OpenAiGroupAllocationVerifier {
  def verify(organization: String, project: String, model: String, modelPool: String,
    day: LocalDate, limitTokens: Long, externalTokens: Long, validFrom: Instant, expiresAt: Instant,
    allocationProvenance: String, externalProvenance: String, inputTokenCeiling: Long,
    maxOutputTokens: Int): Boolean
}

/** Immutable operator-provided evidence; no credential or observed balance establishes authority. */
private[ai] object OpenAiLedgerBackedAllowance {
  private val DefaultDailyMarginPercent = 10

  final case class Window(from: Instant, until: Instant, provenance: String)
  final case class Daily(day: LocalDate, limit: Long, window: Window)
  final case class Credit(id: String, limit: Long, window: Window)
  final case class Sizing(model: String, format: String, tokensPerByte: Long, overheadTokens: Long, provenance: String)
  final case class Allocation(organization: String, project: String, model: String, modelPool: String,
    inputLimit: Long, outputLimit: Int, fullCostMicros: Long, daily: Option[Daily],
    credit: Option[Credit], dailyIneligible: Option[Window], sizing: Option[Sizing],
    reviewOutputLimit: Option[Int], repairOutputLimit: Option[Int])
  final case class Group(allocation: Allocation, externalTokens: Long, externalProvenance: String)

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

  def parse(raw: String, connection: OpenAiRuntimeConfig): Option[Allocation] = parseInternal(raw, connection, false)

  private def parseInternal(raw: String, connection: OpenAiRuntimeConfig, phaseGroups: Boolean): Option[Allocation] = try {
    val j = parser.parse(raw).toOption.get
    val required = Set("organization", "project", "origin", "model", "modelPool", "inputTokenCeiling", "maxOutputTokens", "fullCostMicros")
    if (!fields(j, required, Set("daily", "credit", "dailyIneligible", "sizing", "reviewMaxOutputTokens", "repairMaxOutputTokens"))) return None
    val org = string(j, "organization").get
    val project = string(j, "project").get
    val model = string(j, "model").get
    val pool = string(j, "modelPool").get
    val origin = string(j, "origin").get
    if (!org.matches("[A-Za-z0-9_-]+") || !project.matches("[A-Za-z0-9_-]+")) return None
    if (origin != "https://api.openai.com" || connection.endpoint.toString != origin ||
        connection.provider != "openai" || connection.mode != "remote" || connection.engine != "gpt" ||
        connection.model != model || !text(connection.apiKey)) return None
    if (phaseGroups && (connection.organization.exists(_ != org) || connection.project.exists(_ != project))) return None
    if (!Set(("gpt-5.6-terra", "openai-daily-group-2")).contains((model, pool)) &&
        !(phaseGroups && (model, pool) == (("gpt-5.6-sol", "openai-daily-group-1")))) return None
    val input = long(j, "inputTokenCeiling").get
    val output = long(j, "maxOutputTokens").filter(_ <= Int.MaxValue).get.toInt
    val quote = long(j, "fullCostMicros").get
    if (output > 128000 || (if (phaseGroups) input > 20000000L else
        input != 1050000L || quote < Math.addExact(Math.multiplyExact(input, 5L), Math.multiplyExact(output.toLong, 18L)))) return None
    val dailyJson = j.hcursor.downField("daily").focus
    val daily = dailyJson.map { d =>
      val w = window(d, Set("day", "limitTokens")).get
      val day = LocalDate.parse(string(d, "day").get)
      Daily(day, long(d, "limitTokens").filter(n => phaseGroups || n <= 10000000L).get, w)
    }
    val credit = j.hcursor.downField("credit").focus.map { c =>
      Credit(string(c, "allocationId").get, long(c, "limitMicros").get,
        window(c, Set("allocationId", "limitMicros")).get)
    }
    val ineligible = j.hcursor.downField("dailyIneligible").focus.map(d => window(d, Set.empty).get)
    if (phaseGroups && (daily.isEmpty || credit.nonEmpty || ineligible.nonEmpty)) return None
    if (daily.isEmpty && (credit.isEmpty || ineligible.isEmpty)) return None
    val sizing = j.hcursor.downField("sizing").focus.map { v =>
      if (!fields(v, Set("model", "format", "tokensPerByte", "overheadTokens", "provenance"))) return None
      val m = string(v, "model").get
      val format = string(v, "format").get
      if (m != model || format != "chat-completions-bounded-record-v1") return None
      Sizing(m, format, long(v, "tokensPerByte").get, long(v, "overheadTokens").get,
        string(v, "provenance").get)
    }
    def taskLimit(key: String): Option[Int] = j.hcursor.downField(key).focus.map { v =>
      v.asNumber.flatMap(_.toInt).filter(n => n > 0 && n <= output).get
    }
    val review = taskLimit("reviewMaxOutputTokens")
    val repair = taskLimit("repairMaxOutputTokens")
    if (review.isDefined != repair.isDefined) return None
    Some(Allocation(org, project, model, pool, input, output, quote, daily, credit, ineligible, sizing, review, repair))
  } catch { case scala.util.control.NonFatal(_) => None }

  /** The verifier must attest a conservative external-use upper bound for the whole window. */
  def parseGroups(raw: String, connection: OpenAiRuntimeConfig,
      verifier: OpenAiGroupAllocationVerifier): Option[Vector[Group]] = try {
    if (verifier == null) return None
    val values = parser.parse(raw).toOption.flatMap(_.asArray).get
    if (values.size != 2) return None
    val groups = values.map { value =>
      val daily = value.hcursor.downField("daily").focus.get
      val external = daily.hcursor.get[Long]("externalTokens").toOption.filter(_ >= 0).get
      val externalSource = string(daily, "externalProvenance").get
      val strippedDaily = daily.mapObject(_.remove("externalTokens").remove("externalProvenance"))
      val stripped = value.mapObject(_.add("daily", strippedDaily))
      val model = string(value, "model").get
      val allocation = parseInternal(stripped.noSpaces, connection.copy(model = model), true).get
      val d = allocation.daily.get
      if (!verifier.verify(allocation.organization, allocation.project, model, allocation.modelPool,
          d.day, d.limit, external, d.window.from, d.window.until, d.window.provenance,
          externalSource, allocation.inputLimit, allocation.outputLimit)) return None
      Group(allocation, external, externalSource)
    }.toVector
    if (groups.map(g => (g.allocation.model, g.allocation.modelPool)).toSet != Set(
        ("gpt-5.6-sol", "openai-daily-group-1"), ("gpt-5.6-terra", "openai-daily-group-2")) ||
        groups.map(_.allocation.organization).distinct.size != 1 || groups.map(_.allocation.project).distinct.size != 1)
      None else Some(groups)
  } catch { case scala.util.control.NonFatal(_) => None }

  def dailyMarginPercent(value: Option[String]): Option[Int] = value match {
    case None => Some(DefaultDailyMarginPercent)
    case Some(raw) if raw.matches("[0-9]+") => Try(raw.toInt).toOption.filter(n => n >= 1 && n <= 99)
    case _ => None
  }

  def create(config: OpenAiRuntimeConfig, allocation: Allocation, context: ExecutionContext,
      dailyMarginPercent: Int = DefaultDailyMarginPercent,
      sizingVerifier: Option[OpenAiRequestSizingVerifier] = None): OpenAiTaskAllowanceAuthority = {
    require(dailyMarginPercent >= 1 && dailyMarginPercent <= 99, "Invalid daily margin")
    new OpenAiLedgerBackedAllowance(config, allocation, context, dailyMarginPercent, sizingVerifier)
  }

  final class RouteMember(val group: Group, owner: OpenAiTaskAllowanceLedger#DailyRouteOwner) {
    private var exhausted = false
    def reserve(request: OpenAiAllowanceRequest, grant: OpenAiAllowanceGrant, margin: Int,
        deadline: Long)(using ExecutionContext): OpenAiDailyRouteResult = {
      val result = owner.reserve(request, grant, group.externalTokens, margin, deadline, group.externalProvenance)
      synchronized { exhausted = result == OpenAiDailyRouteResult.KnownExhausted }
      result
    }
    def takeExhausted(): Boolean = synchronized { val result = exhausted; exhausted = false; result }
  }

  def routeMembers(groups: Vector[Group], request: AiTaskRequest, context: ExecutionContext,
      verifier: OpenAiGroupAllocationVerifier): Vector[RouteMember] = {
    val grants = new OpenAiAllowanceGrantVerifier {
      def verify(g: OpenAiAllowanceGrant): Boolean = groups.exists { group =>
        val a = group.allocation
        a.daily.exists(d => g.request.scope == OpenAiAllowanceScope(a.organization, a.modelPool, d.day) &&
          g.allowanceTokens == d.limit && g.expiresAt == d.window.until &&
          g.allocationProvenance == d.window.provenance &&
          !Instant.now().isBefore(d.window.from) && Instant.now().isBefore(d.window.until) &&
          verifier.verify(a.organization, a.project, a.model, a.modelPool, d.day, d.limit,
            group.externalTokens, d.window.from, d.window.until, d.window.provenance,
            group.externalProvenance, a.inputLimit, a.outputLimit))
      }
    }
    val ledger = new OpenAiTaskAllowanceLedger(new StandardEntityStore, grants, executor)
    val owner = ledger.dailyRoute(groups.head.allocation.organization, request.id)
    groups.map(new RouteMember(_, owner))
  }
}

private[ai] final class OpenAiLedgerBackedAllowance(
  config: OpenAiRuntimeConfig,
  allocation: OpenAiLedgerBackedAllowance.Allocation,
  context: ExecutionContext,
  dailyMarginPercent: Int,
  sizingVerifier: Option[OpenAiRequestSizingVerifier],
  routeMember: Option[OpenAiLedgerBackedAllowance.RouteMember] = None
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

  // Configuration is a claim; an independent registered authority must verify it.
  private def sized(r: AiTaskRequest): Option[(Int, Long, String)] = try {
    val limit = r.kind match {
      case "review" | "independent-review" => allocation.reviewOutputLimit
      case "compiler-repair" => allocation.repairOutputLimit
      case _ => None
    }
    for {
      s <- allocation.sizing
      n <- limit
      verifier <- sizingVerifier
      if verifier.verify(connection.endpoint.toString, s.model, s.format, s.tokensPerByte, s.overheadTokens, s.provenance)
      body = OpenAiBoundedRequest.body(r, allocation.model, n).noSpaces
      bytes = body.getBytes(StandardCharsets.UTF_8).length.toLong
      bound = Math.addExact(Math.multiplyExact(bytes, s.tokensPerByte), s.overheadTokens)
      if bound <= allocation.inputLimit
    } yield (n, bound, body)
  } catch { case scala.util.control.NonFatal(_) => None }

  override def assess(r: AiTaskRequest, deadlineNanos: Long): Option[AiTaskCandidate] = {
    val now = Instant.now()
    val end = dispatchEnd(now, deadlineNanos)
    val daily = end.exists(e => allocation.daily.exists(d =>
      d.day == now.atZone(ZoneOffset.UTC).toLocalDate && covers(d.window, now, e)))
    val credit = end.exists(e => allocation.credit.exists(c => covers(c.window, now, e) &&
      c.limit >= allocation.fullCostMicros) &&
      (daily || allocation.dailyIneligible.exists(covers(_, now, e))))
    val eligible = (if (routeMember.nonEmpty) daily else daily || credit) && r.requiredTools.isEmpty && sized(r).nonEmpty
    Option.when(eligible)(AiTaskCandidate("openai", allocation.model, Set("structured-record"), Set.empty,
      Some(1L), 1L, r, Some(0L)))
  }

  private[openai] final case class InvocationAllowance(allowance: AiTaskAllowance,
    settle: Option[(Long, Long, String) => Either[String, Unit]])

  override def reserve(r: AiTaskRequest, candidate: AiTaskCandidate, deadlineNanos: Long): Either[String, AiTaskAllowance] =
    reserveInvocation(r, candidate, deadlineNanos).map(_.allowance)

  private[openai] def reserveInvocation(r: AiTaskRequest, candidate: AiTaskCandidate, deadlineNanos: Long): Either[String, InvocationAllowance] = {
    given ExecutionContext = context
    if (!assess(r, deadlineNanos).contains(candidate)) return Left("Allocation or request is unavailable")
    val now = Instant.now()
    val day = now.atZone(ZoneOffset.UTC).toLocalDate
    val (outputLimit, inputBound, body) = sized(r).getOrElse(return Left("Verified request sizing unavailable"))
    val bound = Json.arr(Json.fromString(allocation.organization), Json.fromString(allocation.project),
      Json.fromString(connection.endpoint.toString), Json.fromString(r.input.reference),
      Json.fromString(r.input.provenance), Json.fromString(body)).noSpaces
    val digest = MessageDigest.getInstance("SHA-256").digest(bound.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x").mkString
    val scope = OpenAiAllowanceScope(allocation.organization, allocation.modelPool, day)
    val request = OpenAiAllowanceRequest(scope, r.id, digest, inputBound, outputLimit.toLong)
    val end = dispatchEnd(now, deadlineNanos).getOrElse(return Left("Dispatch window unavailable"))
    val daily = allocation.daily.filter(d => d.day == day && covers(d.window, now, end))
      .map(d => OpenAiAllowanceGrant(request, d.limit, d.window.until, d.window.provenance))
    if (daily.isEmpty && !allocation.dailyIneligible.exists(covers(_, now, end)))
      return Left("Daily eligibility window unavailable")
    val credit = allocation.credit.filter(c => covers(c.window, now, end)).map(c => OpenAiCreditGrant(request, c.id, c.window.from, c.window.until,
      c.limit, allocation.fullCostMicros, c.window.provenance))
    val reserved: Either[String, OpenAiBudgetAdmission] = routeMember match {
      case Some(member) => daily match {
        case Some(g) => member.reserve(request, g, dailyMarginPercent, deadlineNanos) match {
          case OpenAiDailyRouteResult.Reserved(reservation) => Right(OpenAiDailyAdmission(reservation))
          case OpenAiDailyRouteResult.KnownExhausted => Left("Daily allowance exhausted")
          case OpenAiDailyRouteResult.Denied => Left("Daily reservation unavailable")
        }
        case None => Left("Daily eligibility window unavailable")
      }
      case None => ledger.reserveDailyFirst(request, daily, credit, deadlineNanos, dailyMarginPercent)
    }
    reserved.map { admission =>
      val (basis, until) = admission match {
        case _: OpenAiDailyAdmission => ("verified-daily-reservation", allocation.daily.get.window.until)
        case _: OpenAiCreditAdmission => ("verified-promotional-credit-reservation", allocation.credit.get.window.until)
      }
      val boundary = if (daily.nonEmpty) allocation.daily.get.window.until else allocation.dailyIneligible.get.until
      val midnight = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant
      val scopeEnd = if (until.isBefore(boundary)) until else boundary
      val allowance = AiTaskAllowance(r, candidate, outputLimit, 0L, Some(basis),
        Some(if (scopeEnd.isBefore(midnight)) scopeEnd else midnight))
      val settlement = admission match {
        case OpenAiDailyAdmission(reservation) =>
          Some((input: Long, output: Long, response: String) => ledger.settle(reservation, input, output, response))
        case _: OpenAiCreditAdmission => None
      }
      InvocationAllowance(allowance, settlement)
    }
  }
}

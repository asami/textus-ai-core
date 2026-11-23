package org.simplemodeling.textus.ai.provider.openai

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.concurrent.{Callable, ExecutorService, TimeUnit}
import cats.{Id, data}
import cats.effect.Ref
import io.circe.{Json, parser}
import org.goldenport.Consequence
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.entity.*
import org.goldenport.cncf.entity.runtime.*
import org.goldenport.observation.{Descriptor, Taxonomy}
import org.goldenport.record.Record
import org.goldenport.schema.{Multiplicity, ValueDomain, XString}
import org.simplemodeling.model.datatype.{EntityCollectionId, EntityId, EntityRevision}
import scala.util.control.NonFatal

/**
  * @since Nov. 23, 2026
  * @version Nov. 23, 2026
  */

/** Host-supplied allocator evidence. Verification must be independent of the caller and
  * bind all of these fields; merely possessing provider credentials is not verification.
  */
final case class OpenAiAllowanceScope(organization: String, modelPool: String, day: LocalDate)
final case class OpenAiAllowanceRequest(scope: OpenAiAllowanceScope, requestId: String,
  requestDigest: String, inputTokens: Long, outputTokens: Long)
final case class OpenAiAllowanceGrant(request: OpenAiAllowanceRequest, allowanceTokens: Long,
  expiresAt: Instant, allocationProvenance: String)
trait OpenAiAllowanceGrantVerifier {
  def verify(grant: OpenAiAllowanceGrant): Boolean
}
final case class OpenAiAllowanceReservation(request: OpenAiAllowanceRequest, totalTokens: Long)

/** A verifier attests the complete allocation and request cost in integer USD microunits. */
final case class OpenAiCreditGrant(request: OpenAiAllowanceRequest, allocationId: String,
  validFrom: Instant, expiresAt: Instant, allocationMicrounits: Long, requestMicrounits: Long,
  allocationProvenance: String)
trait OpenAiCreditGrantVerifier {
  def verify(grant: OpenAiCreditGrant): Boolean
}
/** Host authority supplies request-bound ineligibility; absence means unknown. */
final case class OpenAiDailyIneligibility(request: OpenAiAllowanceRequest, validFrom: Instant,
  expiresAt: Instant, provenance: String)
trait OpenAiDailyIneligibilityAuthority {
  def determination(request: OpenAiAllowanceRequest): Option[OpenAiDailyIneligibility]
  def verify(evidence: OpenAiDailyIneligibility): Boolean
}
sealed trait OpenAiBudgetAdmission
final case class OpenAiDailyAdmission(reservation: OpenAiAllowanceReservation) extends OpenAiBudgetAdmission
final case class OpenAiCreditAdmission(request: OpenAiAllowanceRequest, microunits: Long) extends OpenAiBudgetAdmission

/** An authoritative acknowledgment is the only positive result. No reservation is released,
  * including after timeout or an uncertain provider effect. The caller must dispatch exactly the
  * request and output bound described in the verified grant.
  */
final class OpenAiTaskAllowanceLedger(store: EntityStore, verifier: OpenAiAllowanceGrantVerifier,
    executor: ExecutorService, now: () => Instant = () => Instant.now(), nanoTime: () => Long = () => System.nanoTime(),
    creditVerifier: OpenAiCreditGrantVerifier = null,
    dailyIneligibility: OpenAiDailyIneligibilityAuthority = null) {
  import OpenAiTaskAllowanceLedger.*

  def reserve(request: OpenAiAllowanceRequest, grant: Option[OpenAiAllowanceGrant], deadlineNanos: Long)
      (using context: ExecutionContext): Either[String, OpenAiAllowanceReservation] = {
    if (executor == null || deadlineNanos - nanoTime() <= 0) return Left("Deadline or executor unavailable")
    try {
      val future = executor.submit(new Callable[Either[String, OpenAiAllowanceReservation]] {
        def call(): Either[String, OpenAiAllowanceReservation] = reserveWithinDeadline(request, grant, deadlineNanos)
      })
      val remaining = deadlineNanos - nanoTime()
      if (remaining <= 0) { future.cancel(false); Left("Deadline expired") }
      else try {
        val result = future.get(remaining, TimeUnit.NANOSECONDS)
        if (deadlineNanos - nanoTime() <= 0) Left("Deadline expired")
        else result match {
          case Right(reservation) if !validTime(reservation.request, grant.get, now()) =>
            Left("Grant or provider day expired")
          case _ => result
        }
      } catch { case NonFatal(_) => future.cancel(false); Left("Reservation outcome unavailable") }
    } catch { case NonFatal(_) => Left("Reservation outcome unavailable") }
  }

  /** Claims the request once, then tries the daily allowance before the verified credit allocation.
    * A missing daily grant requires a verified host determination; a malformed or unverifiable grant is
    * a terminal failure. A returned failure never authorizes dispatch or claim reuse.
    */
  private final case class BoundedAdmission(admission: OpenAiBudgetAdmission, ineligibility: Option[OpenAiDailyIneligibility])

  def reserveDailyFirst(request: OpenAiAllowanceRequest, daily: Option[OpenAiAllowanceGrant],
      credit: Option[OpenAiCreditGrant], deadlineNanos: Long)
      (using context: ExecutionContext): Either[String, OpenAiBudgetAdmission] = {
    if (executor == null || deadlineNanos - nanoTime() <= 0) return Left("Deadline or executor unavailable")
    try {
      val future = executor.submit(new Callable[Either[String, BoundedAdmission]] {
        def call(): Either[String, BoundedAdmission] = _reserve_daily_first(request, daily, credit, deadlineNanos)
      })
      val remaining = deadlineNanos - nanoTime()
      if (remaining <= 0) { future.cancel(false); Left("Deadline expired") }
      else {
        val result = future.get(remaining, TimeUnit.NANOSECONDS)
        result.flatMap { bounded =>
          val instant = now()
          val valid = bounded.admission match {
            case _: OpenAiDailyAdmission => daily.exists(g => validTime(request, g, instant))
            case _: OpenAiCreditAdmission =>
              credit.exists(g => _credit_time(g, instant)) &&
                request.scope.day == instant.atZone(ZoneOffset.UTC).toLocalDate &&
                daily.forall(g => validTime(request, g, instant)) &&
                (daily.nonEmpty || bounded.ineligibility.exists(e =>
                  !instant.isBefore(e.validFrom) && instant.isBefore(e.expiresAt)))
          }
          if (!valid || deadlineNanos - nanoTime() <= 0) Left("Deadline, grant or provider day expired")
          else Right(bounded.admission)
        }
      }
    } catch { case NonFatal(_) => Left("Reservation outcome unavailable") }
  }

  private def _reserve_daily_first(request: OpenAiAllowanceRequest, daily: Option[OpenAiAllowanceGrant],
      credit: Option[OpenAiCreditGrant], deadline: Long)
      (using ctx: ExecutionContext): Either[String, BoundedAdmission] = try {
    val text = (s: String) => s != null && s.nonEmpty && s.length <= 512 && s.trim == s
    val instant = now()
    if (store == null || request == null || request.scope == null || !text(request.scope.organization) ||
        !text(request.scope.modelPool) || request.scope.day == null ||
        request.scope.day != instant.atZone(ZoneOffset.UTC).toLocalDate || !text(request.requestId) ||
        request.requestDigest == null || !request.requestDigest.matches("[0-9a-f]{64}") ||
        request.inputTokens <= 0 || request.outputTokens <= 0 || deadline - nanoTime() <= 0)
      return Left("Invalid request or deadline")
    val total = Math.addExact(request.inputTokens, request.outputTokens)
    daily.foreach { g =>
      if (g == null || verifier == null || g.request != request || g.expiresAt == null ||
          !validTime(request, g, instant) || !text(g.allocationProvenance) || g.allowanceTokens <= 0 || !verifier.verify(g))
        return Left("Invalid or unverified daily grant")
    }
    val ineligible = if (daily.isEmpty && dailyIneligibility != null)
      dailyIneligibility.determination(request) else None
    def validIneligibility(at: Instant): Boolean = ineligible.exists { e =>
      e != null && e.request == request && e.validFrom != null && e.expiresAt != null &&
        !at.isBefore(e.validFrom) && at.isBefore(e.expiresAt) && text(e.provenance) &&
        dailyIneligibility.verify(e)
    }
    if (daily.isEmpty && !validIneligibility(instant)) return Left("Daily eligibility unknown or unverified")
    if (deadline - nanoTime() <= 0 || !_claim(request, deadline)) return Left("Request claim unavailable or already claimed")
    daily match {
      case Some(g) => _select_daily(request, g, total, deadline) match {
        case DailySelection.Reserved(r) =>
          if (deadline - nanoTime() <= 0 || !validTime(request, g, now())) return Left("Daily boundary expired")
          return Right(BoundedAdmission(OpenAiDailyAdmission(r), None))
        case DailySelection.Unavailable => ()
        case DailySelection.Denied(error) => return Left(error)
      }
      case None => ()
    }
    if (deadline - nanoTime() <= 0 || request.scope.day != now().atZone(ZoneOffset.UTC).toLocalDate ||
        daily.exists(g => !validTime(request, g, now())) ||
        (daily.isEmpty && !validIneligibility(now()))) return Left("Daily boundary expired")
    val g = credit.getOrElse(return Left("Missing verified credit grant"))
    if (g == null || creditVerifier == null || g.request != request || !text(g.allocationId) ||
        !text(g.allocationProvenance) || g.validFrom == null || g.expiresAt == null ||
        !_credit_time(g, now()) || g.allocationMicrounits <= 0 || g.requestMicrounits <= 0 ||
        g.requestMicrounits > g.allocationMicrounits || !creditVerifier.verify(g))
      return Left("Invalid or unverified credit grant")
    if (deadline - nanoTime() <= 0) return Left("Deadline expired")
    val result = _credit_change(request, g, deadline, 4)
    if (deadline - nanoTime() <= 0 || !_credit_time(g, now()) ||
        (daily.isEmpty && !validIneligibility(now())) || request.scope.day != now().atZone(ZoneOffset.UTC).toLocalDate) Left("Deadline, provider day or credit grant expired") else result.map(admission => BoundedAdmission(admission, ineligible))
  } catch { case NonFatal(_) => Left("Reservation outcome unavailable") }

  private enum DailySelection {
    case Reserved(reservation: OpenAiAllowanceReservation)
    case Unavailable
    case Denied(reason: String)
  }

  private def _select_daily(request: OpenAiAllowanceRequest, grant: OpenAiAllowanceGrant, total: Long,
      deadline: Long)(using ctx: ExecutionContext): DailySelection = {
    if (total > grant.allowanceTokens) DailySelection.Unavailable
    else change(request, grant, total, deadline, 4, false) match {
      case Right(reservation) => DailySelection.Reserved(reservation)
      case Left("Allowance exhausted") => DailySelection.Unavailable
      case Left(reason) => DailySelection.Denied(reason)
    }
  }

  private def _credit_time(grant: OpenAiCreditGrant, instant: Instant): Boolean =
    !instant.isBefore(grant.validFrom) && instant.isBefore(grant.expiresAt)

  private def _claim(request: OpenAiAllowanceRequest, deadline: Long)(using ctx: ExecutionContext): Boolean = {
    if (deadline - nanoTime() <= 0) return false
    ensureCollection(store)
    val id = take(_key_id("claim", request.scope.organization, request.requestId)).getOrElse(return false)
    if (deadline - nanoTime() <= 0) return false
    store.create(LedgerEntity(id, Json.obj("digest" -> Json.fromString(request.requestDigest)).noSpaces)) match {
      case Consequence.Success(_) => deadline - nanoTime() > 0
      case _ => false
    }
  }

  private def _credit_change(request: OpenAiAllowanceRequest, grant: OpenAiCreditGrant, deadline: Long,
      attempts: Int)(using ctx: ExecutionContext): Either[String, OpenAiBudgetAdmission] = {
    if (attempts <= 0 || deadline - nanoTime() <= 0 || !_credit_time(grant, now())) return Left("Credit boundary expired")
    try {
      val id = take(_key_id("credit", request.scope.organization, grant.allocationId)).getOrElse(return Left("Invalid allocation identity"))
      take(store.loadDetached[LedgerEntity](id)) match {
        case None => Left("Credit ledger read failed")
        case Some(None) =>
          val initial = _credit_encode(request.scope.organization, grant, 0L)
          if (deadline - nanoTime() <= 0 || !_credit_time(grant, now())) return Left("Credit boundary expired")
          store.create(LedgerEntity(id, initial)) match {
            case Consequence.Success(_) => _credit_change(request, grant, deadline, attempts - 1)
            case Consequence.Failure(c) if c.observation.taxonomy == Taxonomy.dataStoreDuplicate =>
              _credit_change(request, grant, deadline, attempts - 1)
            case _ => Left("Credit ledger creation outcome unavailable")
          }
        case Some(Some(carrier)) =>
          val c = parser.parse(carrier.entity.canonical).toOption.get.hcursor
          val used = c.get[Long]("used").toOption.get
          if (carrier.entity.id != id || carrier.revision.value <= 0 || used < 0 ||
              carrier.entity.canonical != _credit_encode(request.scope.organization, grant, used) ||
              used > grant.allocationMicrounits - grant.requestMicrounits) return Left("Credit allocation conflict or exhausted")
          val next = LedgerEntity(id, _credit_encode(request.scope.organization, grant, Math.addExact(used, grant.requestMicrounits)))
          val revision = carrier.revision
          val policy = EntityMutationExecutionPolicy(concurrencyPolicy = EntityConcurrencyPolicy.Optimistic,
            preconditionPolicy = RevisionPreconditionPolicy.ObservedRequired, observedRevision = Some(revision))
          if (deadline - nanoTime() <= 0 || !_credit_time(grant, now())) return Left("Credit boundary expired")
          store.updateDetached(next, Some(revision), policy) match {
            case Consequence.Success(ack) if ack.entity == next && ack.revision.value > revision.value =>
              Right(OpenAiCreditAdmission(request, grant.requestMicrounits))
            case Consequence.Failure(c) if c.observation.cause.descriptor.facets.contains(
                Descriptor.Facet.Reason("stale-entity-revision")) => _credit_change(request, grant, deadline, attempts - 1)
            case _ => Left("Credit ledger update outcome unavailable")
          }
      }
    } catch { case NonFatal(_) => Left("Credit ledger operation unavailable") }
  }

  private def validTime(request: OpenAiAllowanceRequest, grant: OpenAiAllowanceGrant, instant: Instant): Boolean =
    request.scope.day == instant.atZone(ZoneOffset.UTC).toLocalDate && grant.expiresAt.isAfter(instant)

  private def reserveWithinDeadline(request: OpenAiAllowanceRequest, grant: Option[OpenAiAllowanceGrant],
      deadlineNanos: Long)(using context: ExecutionContext): Either[String, OpenAiAllowanceReservation] = {
    try {
      if (deadlineNanos - nanoTime() <= 0) return Left("Deadline expired")
      val g = grant.getOrElse(return Left("Missing verified grant"))
      val instant = now()
      val validText = (s: String) => s != null && s.nonEmpty && s.length <= 512 && s.trim == s
      if (store == null || verifier == null || request == null || request.scope == null ||
          !validText(request.scope.organization) || !validText(request.scope.modelPool) ||
          request.scope.day == null || request.scope.day != instant.atZone(ZoneOffset.UTC).toLocalDate ||
          !validText(request.requestId) || request.requestDigest == null ||
          !request.requestDigest.matches("[0-9a-f]{64}") || request.inputTokens <= 0 || request.outputTokens <= 0 ||
          g.request != request || g.expiresAt == null || !g.expiresAt.isAfter(instant) ||
          !validText(g.allocationProvenance) || g.allowanceTokens <= 0 || !verifier.verify(g))
        return Left("Invalid or unverified grant")
      val total = Math.addExact(request.inputTokens, request.outputTokens)
      if (total > g.allowanceTokens || deadlineNanos - nanoTime() <= 0) return Left("Allowance or deadline exhausted")
      if (!_claim(request, deadlineNanos)) return Left("Request claim unavailable or already claimed")
      val result = change(request, g, total, deadlineNanos, 4)
      if (deadlineNanos - nanoTime() <= 0 || !validTime(request, g, now())) Left("Deadline, grant or provider day expired")
      else result
    } catch { case NonFatal(_) => Left("Reservation outcome unavailable") }
  }

  private def change(request: OpenAiAllowanceRequest, grant: OpenAiAllowanceGrant, total: Long,
      deadline: Long, attempts: Int, retryConflicts: Boolean = true)(using ctx: ExecutionContext): Either[String, OpenAiAllowanceReservation] = {
    if (attempts <= 0 || deadline - nanoTime() <= 0 || !validTime(request, grant, now())) return Left("Reservation deadline or provider day expired")
    try {
      ensureCollection(store)
      val id = take(entityId(request.scope)).getOrElse(return Left("Invalid ledger identity"))
      take(store.loadDetached[LedgerEntity](id)) match {
        case None => Left("Ledger read failed")
        case Some(None) =>
          val empty = LedgerEntity(id, encode(request.scope, grant.allowanceTokens, Vector.empty))
          if (deadline - nanoTime() <= 0 || !validTime(request, grant, now())) return Left("Deadline, grant or provider day expired")
          store.create(empty) match {
            case Consequence.Success(_) => change(request, grant, total, deadline, attempts - 1, retryConflicts)
            case Consequence.Failure(c) if c.observation.taxonomy == Taxonomy.dataStoreDuplicate && retryConflicts =>
              change(request, grant, total, deadline, attempts - 1, retryConflicts)
            case _ => Left("Ledger creation outcome unavailable")
          }
        case Some(Some(carrier)) =>
          val current = decode(carrier.entity.canonical).getOrElse(return Left("Invalid stored ledger"))
          if (carrier.entity.id != id || carrier.revision.value <= 0 || current._1 != request.scope ||
              current._2 != grant.allowanceTokens || current._3.size >= MaxEntries ||
              current._3.exists(_._1 == request.requestId)) return Left("Ledger scope, capacity or request conflict")
          val used = current._3.foldLeft(0L)((n, entry) => Math.addExact(n, entry._3))
          if (used > grant.allowanceTokens - total) return Left("Allowance exhausted")
          val next = LedgerEntity(id, encode(request.scope, grant.allowanceTokens,
            current._3 :+ ((request.requestId, request.requestDigest, total, grant.allocationProvenance))))
          val revision = carrier.revision
          val policy = EntityMutationExecutionPolicy(concurrencyPolicy = EntityConcurrencyPolicy.Optimistic,
            preconditionPolicy = RevisionPreconditionPolicy.ObservedRequired, observedRevision = Some(revision))
          if (deadline - nanoTime() <= 0 || !validTime(request, grant, now())) return Left("Deadline, grant or provider day expired")
          store.updateDetached(next, Some(revision), policy) match {
            case Consequence.Success(ack) if ack.entity == next && ack.revision.value > revision.value =>
              Right(OpenAiAllowanceReservation(request, total))
            case Consequence.Failure(c) if c.observation.cause.descriptor.facets.contains(
                Descriptor.Facet.Reason("stale-entity-revision")) && retryConflicts =>
              change(request, grant, total, deadline, attempts - 1, retryConflicts)
            case _ => Left("Ledger update outcome unavailable")
          }
      }
    } catch { case NonFatal(_) => Left("Ledger operation unavailable") }
  }
}

object OpenAiTaskAllowanceLedger {
  private val MaxEntries = 10000
  private def take[A](c: Consequence[A]): Option[A] = c match {
    case Consequence.Success(v) => Some(v)
    case _ => None
  }
  private def sha(s: String): String = MessageDigest.getInstance("SHA-256")
    .digest(s.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
  private def entityId(scope: OpenAiAllowanceScope): Consequence[EntityId] = EntityId.bridgeFromParts(
    major = LedgerEntity.Collection.major, minor = LedgerEntity.Collection.minor,
    collection = LedgerEntity.Collection, timestamp = Instant.EPOCH,
    entropy = sha(Json.arr(Json.fromString(scope.organization), Json.fromString(scope.modelPool),
      Json.fromString(scope.day.toString)).noSpaces))
  private def _key_id(kind: String, organization: String, key: String): Consequence[EntityId] = EntityId.bridgeFromParts(
    major = LedgerEntity.Collection.major, minor = LedgerEntity.Collection.minor,
    collection = LedgerEntity.Collection, timestamp = Instant.EPOCH,
    entropy = sha(Json.arr(Json.fromString(kind), Json.fromString(organization), Json.fromString(key)).noSpaces))
  private def _credit_encode(organization: String, grant: OpenAiCreditGrant, used: Long): String = Json.obj(
    "organization" -> Json.fromString(organization), "allocation" -> Json.fromString(grant.allocationId),
    "from" -> Json.fromString(grant.validFrom.toString), "expires" -> Json.fromString(grant.expiresAt.toString),
    "limit" -> Json.fromLong(grant.allocationMicrounits), "source" -> Json.fromString(grant.allocationProvenance),
    "used" -> Json.fromLong(used)).noSpaces
  private type Entry = (String, String, Long, String)
  private def encode(scope: OpenAiAllowanceScope, limit: Long, entries: Vector[Entry]): String = Json.obj(
    "organization" -> Json.fromString(scope.organization), "pool" -> Json.fromString(scope.modelPool),
    "day" -> Json.fromString(scope.day.toString), "limit" -> Json.fromLong(limit),
    "entries" -> Json.fromValues(entries.map { case (id, digest, tokens, source) => Json.obj(
      "id" -> Json.fromString(id), "digest" -> Json.fromString(digest), "tokens" -> Json.fromLong(tokens),
      "source" -> Json.fromString(source)) })).noSpaces
  private def decode(s: String): Option[(OpenAiAllowanceScope, Long, Vector[Entry])] = try {
    val c = parser.parse(s).toOption.get.hcursor
    val scope = OpenAiAllowanceScope(c.get[String]("organization").toOption.get,
      c.get[String]("pool").toOption.get, LocalDate.parse(c.get[String]("day").toOption.get))
    val limit = c.get[Long]("limit").toOption.get
    val entries = c.downField("entries").focus.flatMap(_.asArray).get.map { j =>
      val e = j.hcursor
      (e.get[String]("id").toOption.get, e.get[String]("digest").toOption.get,
        e.get[Long]("tokens").toOption.get, e.get[String]("source").toOption.get)
    }
    if (limit <= 0 || entries.size > MaxEntries || entries.exists(e => e._1.isEmpty ||
        !e._2.matches("[0-9a-f]{64}") || e._3 <= 0 || e._4.isEmpty) ||
        entries.map(_._1).distinct.size != entries.size ||
        entries.foldLeft(0L)((n, e) => Math.addExact(n, e._3)) > limit) None
    else Some((scope, limit, entries))
  } catch { case NonFatal(_) => None }

  private final case class LedgerEntity(id: EntityId, canonical: String) {
    def record: Record = Record.dataAuto("id" -> id.value, "canonical" -> canonical)
  }
  private object LedgerEntity {
    val Collection = EntityCollectionId("textus", "ai", "openAiAllowanceLedger")
    private val mapping = EntityStoreRowMapping(Vector(EntityStoreField("canonical", "canonical",
      ValueDomain(XString, Multiplicity.One), EntityStoreFieldBinding.string)),
      (SimpleEntityStorageShapePolicy.managementLogicalFields ++ SimpleEntityStorageShapePolicy.securityIdentityLogicalFields ++
        Vector("name", "permission", "revision")).flatMap(n => Vector(n, SimpleEntityStorageShapePolicy.targetName(n))).toSet - "canonical")
    given EntityPersistent[LedgerEntity] with {
      override def storeRowMapping: Option[EntityStoreRowMapping] = Some(mapping)
      def id(e: LedgerEntity): EntityId = e.id
      def toRecord(e: LedgerEntity): Record = e.record
      override def toStoreRecord(e: LedgerEntity): Record = e.record
      def fromRecord(r: Record): Consequence[LedgerEntity] = for {
        id <- EntityId.createC(r)
        canonical <- r.getString("canonical").filter(_.nonEmpty).map(Consequence.success)
          .getOrElse(Consequence.argumentMissing("canonical"))
      } yield LedgerEntity(id, canonical)
      override def fromStoreRecord(r: Record): Consequence[LedgerEntity] = fromRecord(r)
    }
    given EntityPersistentCreate[LedgerEntity] with {
      override def storeRowMapping: Option[EntityStoreRowMapping] = Some(mapping)
      def id(e: LedgerEntity): Option[EntityId] = Some(e.id)
      def collection(e: LedgerEntity): EntityCollectionId = Collection
      def toRecord(e: LedgerEntity): Record = e.record
      override def toStoreRecord(e: LedgerEntity): Record = e.record
    }
  }
  private def ensureCollection(store: EntityStore)(using ctx: ExecutionContext): Unit = ctx.entitySpace.synchronized {
    ctx.entitySpace.entityOption(LedgerEntity.Collection) match {
      case Some(c) if c.descriptor.revisionBinding.exists(_.representation == EntityRevisionRepresentation.Detached) &&
          c.descriptor.plan.memoryPolicy == EntityMemoryPolicy.StoreOnly &&
          c.descriptor.plan.concurrencyPolicy == EntityConcurrencyPolicy.Optimistic => ()
      case Some(_) => throw new IllegalStateException("Incompatible allowance collection")
      case None =>
        val realm = new EntityRealm[LedgerEntity](LedgerEntity.Collection.name,
          EntityLoader.fromEntityStore(store)(using summon[EntityPersistent[LedgerEntity]], ctx),
          new LocalRef(EntityRealmState(Map.empty)))
        val desc = EntityDescriptor(LedgerEntity.Collection, EntityRuntimePlan[LedgerEntity](
          entityName = LedgerEntity.Collection.name, memoryPolicy = EntityMemoryPolicy.StoreOnly, workingSet = None,
          partitionStrategy = PartitionStrategy.byEntityId, maxPartitions = 1, maxEntitiesPerPartition = MaxEntries,
          concurrencyPolicy = EntityConcurrencyPolicy.Optimistic), summon[EntityPersistent[LedgerEntity]],
          Some(EntityRevisionBinding(EntityRevisionRepresentation.Detached)))
        ctx.entitySpace.registerEntity(LedgerEntity.Collection.name, new EntityCollection(desc, EntityStorage(realm)))
    }
  }
  private final class LocalRef[A](initial: A) extends Ref[Id, A] {
    private var value = initial
    private var revision = 0L
    def get: A = synchronized(value)
    def set(a: A): Unit = synchronized { value = a; revision += 1 }
    def access: (A, A => Boolean) = synchronized {
      val old = value
      val observed = revision
      var used = false
      (old, a => synchronized {
        if (used) false
        else {
          used = true
          if (revision != observed) false
          else { value = a; revision += 1; true }
        }
      })
    }
    def update(f: A => A): Unit = synchronized { value = f(value); revision += 1 }
    def modify[B](f: A => (A, B)): B = synchronized {
      val (a, b) = f(value); value = a; revision += 1; b
    }
    def modifyState[B](state: data.State[A, B]): B = modify(a => state.run(a).value)
    def tryModify[B](f: A => (A, B)): Option[B] = {
      val (old, setOnce) = access
      val (next, result) = f(old)
      if (setOnce(next)) Some(result) else None
    }
    def tryModifyState[B](state: data.State[A, B]): Option[B] = tryModify(a => state.run(a).value)
    def tryUpdate(f: A => A): Boolean = tryModify(a => (f(a), ())).isDefined
  }
}

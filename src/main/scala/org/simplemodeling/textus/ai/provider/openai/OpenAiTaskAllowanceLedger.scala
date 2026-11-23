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

/** An authoritative acknowledgment is the only positive result. No reservation is released,
  * including after timeout or an uncertain provider effect. The caller must dispatch exactly the
  * request and output bound described in the verified grant.
  */
final class OpenAiTaskAllowanceLedger(store: EntityStore, verifier: OpenAiAllowanceGrantVerifier,
    executor: ExecutorService, now: () => Instant = () => Instant.now(), nanoTime: () => Long = () => System.nanoTime()) {
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
      val result = change(request, g, total, deadlineNanos, 4)
      if (deadlineNanos - nanoTime() <= 0 || !validTime(request, g, now())) Left("Deadline, grant or provider day expired")
      else result
    } catch { case NonFatal(_) => Left("Reservation outcome unavailable") }
  }

  private def change(request: OpenAiAllowanceRequest, grant: OpenAiAllowanceGrant, total: Long,
      deadline: Long, attempts: Int)(using ctx: ExecutionContext): Either[String, OpenAiAllowanceReservation] = {
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
            case Consequence.Success(_) => change(request, grant, total, deadline, attempts - 1)
            case Consequence.Failure(c) if c.observation.taxonomy == Taxonomy.dataStoreDuplicate =>
              change(request, grant, total, deadline, attempts - 1)
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
                Descriptor.Facet.Reason("stale-entity-revision")) =>
              change(request, grant, total, deadline, attempts - 1)
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

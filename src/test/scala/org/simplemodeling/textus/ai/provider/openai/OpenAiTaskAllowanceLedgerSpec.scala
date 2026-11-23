package org.simplemodeling.textus.ai.provider.openai

import java.nio.file.Files
import java.time.{Instant, LocalDate}
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import org.goldenport.Consequence
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.datastore.sql.SqlDataStore
import org.goldenport.cncf.entity.{EntityMutationExecutionPolicy, EntityPersistent, EntityRevisionCarrier, StandardEntityStore}
import org.simplemodeling.model.datatype.EntityRevision
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class OpenAiTaskAllowanceLedgerSpec extends AnyWordSpec with Matchers {
  private val instant = Instant.parse("2026-11-23T12:00:00Z")
  private val scope = OpenAiAllowanceScope("test-org", "test-pool", LocalDate.parse("2026-11-23"))
  private val trusted = new OpenAiAllowanceGrantVerifier {
    def verify(g: OpenAiAllowanceGrant): Boolean = g.allocationProvenance == "trusted-test-allocator"
  }
  private def request(id: String) = OpenAiAllowanceRequest(scope, id, "a" * 64, 6L, 4L)
  private def grant(r: OpenAiAllowanceRequest, limit: Long = 10L) =
    OpenAiAllowanceGrant(r, limit, instant.plusSeconds(60), "trusted-test-allocator")
  private def deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)

  private def fixture(f: (OpenAiTaskAllowanceLedger, ExecutionContext) => Unit): Unit = {
    val path = Files.createTempDirectory("openai-ledger-").resolve("ledger.db")
    val provider = SqlDataStore.sqlite(path.toString)
    val ctx = ExecutionContext.test()
    ctx.dataStoreSpace.useDataStore(provider)
    val executor = Executors.newFixedThreadPool(4)
    try f(new OpenAiTaskAllowanceLedger(new StandardEntityStore, trusted, executor, () => instant), ctx)
    finally { executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS); provider.closeC() }
  }

  "The durable allowance ledger" should {
    "persist the full bound, reject replay by another consumer, and retain the reservation" in fixture { (ledger, ctx) =>
      given ExecutionContext = ctx
      ledger.reserve(request("one"), Some(grant(request("one"))), deadline).map(_.totalTokens) shouldBe Right(10L)
      ledger.reserve(request("one"), Some(grant(request("one"))), deadline).isLeft shouldBe true
      ledger.reserve(request("two"), Some(grant(request("two"))), deadline).isLeft shouldBe true
    }

    "admit only one of two concurrent consumers of the selected SQLite managed store" in fixture { (ledger, ctx) =>
      val gate = new CountDownLatch(1)
      val consumers = Executors.newFixedThreadPool(2)
      try {
        def call(id: String) = consumers.submit(new Callable[Either[String, OpenAiAllowanceReservation]] {
          def call(): Either[String, OpenAiAllowanceReservation] = {
            given ExecutionContext = ctx
            gate.await()
            val r = request(id)
            ledger.reserve(r, Some(grant(r)), deadline)
          }
        })
        val a = call("a")
        val b = call("b")
        gate.countDown()
        val results = Vector(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))
        results.count(_.isRight) shouldBe 1
        results.count(_.isLeft) shouldBe 1
      } finally consumers.shutdownNow()
    }

    "retain an ambiguously acknowledged update and deny a later request" in {
      val path = Files.createTempDirectory("openai-ambiguous-").resolve("ledger.db")
      val provider = SqlDataStore.sqlite(path.toString)
      val ctx = ExecutionContext.test()
      ctx.dataStoreSpace.useDataStore(provider)
      val executor = Executors.newFixedThreadPool(2)
      val store = new StandardEntityStore {
        override def updateDetached[T](changes: T, expectedRevision: Option[EntityRevision],
            policy: EntityMutationExecutionPolicy)(using tc: EntityPersistent[T], context: ExecutionContext)
            : Consequence[EntityRevisionCarrier[T]] = {
          super.updateDetached(changes, expectedRevision, policy) match {
            case Consequence.Success(_) => Consequence.valueInvalid("simulated lost acknowledgement")
            case failure => failure
          }
        }
      }
      try {
        given ExecutionContext = ctx
        val ledger = new OpenAiTaskAllowanceLedger(store, trusted, executor, () => instant)
        val first = request("ambiguous")
        ledger.reserve(first, Some(grant(first)), deadline).isLeft shouldBe true
        val next = request("later")
        ledger.reserve(next, Some(grant(next)), deadline).isLeft shouldBe true
      } finally { executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS); provider.closeC() }
    }

    "deny a caller crossing UTC midnight after an acknowledged update and retain its reservation" in {
      val path = Files.createTempDirectory("openai-midnight-").resolve("ledger.db")
      val provider = SqlDataStore.sqlite(path.toString)
      val ctx = ExecutionContext.test()
      ctx.dataStoreSpace.useDataStore(provider)
      val executor = Executors.newSingleThreadExecutor()
      val acknowledged = new AtomicBoolean(false)
      val crossingEnabled = new AtomicBoolean(true)
      val caller = Thread.currentThread()
      val before = Instant.parse("2026-11-23T23:59:59Z")
      val after = Instant.parse("2026-11-24T00:00:00Z")
      val store = new StandardEntityStore {
        override def updateDetached[T](changes: T, expectedRevision: Option[EntityRevision],
            policy: EntityMutationExecutionPolicy)(using tc: EntityPersistent[T], context: ExecutionContext)
            : Consequence[EntityRevisionCarrier[T]] = {
          val result = super.updateDetached(changes, expectedRevision, policy)
          result match {
            case Consequence.Success(_) => acknowledged.set(true)
            case _ => ()
          }
          result
        }
      }
      try {
        given ExecutionContext = ctx
        val clock = () => if (crossingEnabled.get() && acknowledged.get() && Thread.currentThread() == caller) after else before
        val ledger = new OpenAiTaskAllowanceLedger(store, trusted, executor, clock)
        val first = request("midnight")
        val firstGrant = grant(first).copy(expiresAt = after.plusSeconds(60))
        ledger.reserve(first, Some(firstGrant), deadline).isLeft shouldBe true
        acknowledged.get() shouldBe true
        crossingEnabled.set(false)
        ledger.reserve(first, Some(firstGrant), deadline).isLeft shouldBe true
        val next = request("after-midnight-reservation")
        ledger.reserve(next, Some(grant(next).copy(expiresAt = after.plusSeconds(60))), deadline).isLeft shouldBe true
      } finally { executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS); provider.closeC() }
    }

    "deny a timed out store update even if it commits later" in {
      val path = Files.createTempDirectory("openai-store-timeout-").resolve("ledger.db")
      val provider = SqlDataStore.sqlite(path.toString)
      val ctx = ExecutionContext.test()
      ctx.dataStoreSpace.useDataStore(provider)
      val executor = Executors.newFixedThreadPool(2)
      val callers = Executors.newSingleThreadExecutor()
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val committed = new CountDownLatch(1)
      val store = new StandardEntityStore {
        override def updateDetached[T](changes: T, expectedRevision: Option[EntityRevision],
            policy: EntityMutationExecutionPolicy)(using tc: EntityPersistent[T], context: ExecutionContext)
            : Consequence[EntityRevisionCarrier[T]] = {
          entered.countDown()
          release.await()
          try super.updateDetached(changes, expectedRevision, policy)
          finally committed.countDown()
        }
      }
      try {
        val ledger = new OpenAiTaskAllowanceLedger(store, trusted, executor, () => instant)
        val r = request("late")
        val call = callers.submit(new Callable[Either[String, OpenAiAllowanceReservation]] {
          def call(): Either[String, OpenAiAllowanceReservation] = {
            given ExecutionContext = ctx
            ledger.reserve(r, Some(grant(r)), System.nanoTime() + TimeUnit.SECONDS.toNanos(2))
          }
        })
        entered.await(5, TimeUnit.SECONDS) shouldBe true
        call.get(5, TimeUnit.SECONDS).isLeft shouldBe true
        release.countDown()
        committed.await(5, TimeUnit.SECONDS) shouldBe true
        given ExecutionContext = ctx
        val next = request("after-late")
        ledger.reserve(next, Some(grant(next)), deadline).isLeft shouldBe true
      } finally {
        release.countDown(); callers.shutdown(); executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS); provider.closeC()
      }
    }

    "deny a timed out verification before any late store mutation" in {
      val path = Files.createTempDirectory("openai-timeout-").resolve("ledger.db")
      val provider = SqlDataStore.sqlite(path.toString)
      val ctx = ExecutionContext.test()
      ctx.dataStoreSpace.useDataStore(provider)
      val executor = Executors.newSingleThreadExecutor()
      val gate = new CountDownLatch(1)
      val slow = new OpenAiAllowanceGrantVerifier {
        def verify(g: OpenAiAllowanceGrant): Boolean = { gate.await(); true }
      }
      try {
        given ExecutionContext = ctx
        val ledger = new OpenAiTaskAllowanceLedger(new StandardEntityStore, slow, executor, () => instant)
        val r = request("timeout")
        ledger.reserve(r, Some(grant(r)), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)).isLeft shouldBe true
      } finally { gate.countDown(); executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS); provider.closeC() }
    }

    "deny missing, untrusted, expired and crossed grants and invalid bounds" in fixture { (ledger, ctx) =>
      given ExecutionContext = ctx
      val r = request("request")
      ledger.reserve(r, None, deadline).isLeft shouldBe true
      ledger.reserve(r, Some(grant(r).copy(allocationProvenance = "untrusted")), deadline).isLeft shouldBe true
      ledger.reserve(r, Some(grant(r).copy(expiresAt = instant)), deadline).isLeft shouldBe true
      ledger.reserve(r, Some(grant(request("other"))), deadline).isLeft shouldBe true
      ledger.reserve(r, Some(grant(r.copy(scope = scope.copy(day = scope.day.plusDays(1))))), deadline).isLeft shouldBe true
      val invalid = r.copy(inputTokens = Long.MaxValue)
      ledger.reserve(invalid, Some(grant(invalid, Long.MaxValue)), deadline).isLeft shouldBe true
      ledger.reserve(r, Some(grant(r)), System.nanoTime() - 1).isLeft shouldBe true
    }
  }
}

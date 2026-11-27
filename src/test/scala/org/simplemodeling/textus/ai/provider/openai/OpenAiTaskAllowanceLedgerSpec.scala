package org.simplemodeling.textus.ai.provider.openai

import java.nio.file.Files
import java.time.{Instant, LocalDate}
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import org.goldenport.Consequence
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.datastore.sql.SqlDataStore
import org.goldenport.cncf.entity.{EntityMutationExecutionPolicy, EntityPersistent, EntityRevisionCarrier, StandardEntityStore}
import org.simplemodeling.model.datatype.EntityRevision
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
  * @since Nov. 23, 2026
  * @version Nov. 23, 2026
  */
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
    try f(new OpenAiTaskAllowanceLedger(new StandardEntityStore, trusted, executor, () => instant, creditVerifier = _credit_verifier, dailyIneligibility = _daily_authority), ctx)
    finally { executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS); provider.closeC() }
  }

  private val _credit_verifier = new OpenAiCreditGrantVerifier {
    def verify(g: OpenAiCreditGrant): Boolean =
      g == _credit(g.request, if (g.request.requestId == "credit-three") 4L else 6L) &&
        g.request.scope.organization == "test-org" && g.request.inputTokens == 6L && g.request.outputTokens == 4L
  }
  private val _daily_authority = new OpenAiDailyIneligibilityAuthority {
    def determination(r: OpenAiAllowanceRequest): Option[OpenAiDailyIneligibility] =
      if (r.requestId == "unknown-daily") None
      else Some(OpenAiDailyIneligibility(r, instant.minusSeconds(60), instant.plusSeconds(172800),
        if (r.requestId == "forged-daily") "forged" else "trusted-daily"))
    def verify(e: OpenAiDailyIneligibility): Boolean = e.provenance == "trusted-daily"
  }
  private def _credit(r: OpenAiAllowanceRequest, cost: Long = 6L) =
    OpenAiCreditGrant(r, "allocation", instant.minusSeconds(60), instant.plusSeconds(172800), 10L, cost, "trusted-credit")

  "Daily settlement" should {
    "retain the original reservation and claim while reclaiming only once for its original day" in fixture { (ledger, ctx) =>
      given ExecutionContext = ctx
      val first = request("settled")
      val reservation = ledger.reserve(first, Some(grant(first, 20L)), deadline).toOption.get
      ledger.settle(reservation.copy(request = first.copy(requestDigest = "b" * 64)), 2L, 1L, "response-1").isLeft shouldBe true
      ledger.settle(reservation, 2L, 1L, "response-1") shouldBe Right(())
      ledger.settle(reservation, 2L, 1L, "response-1") shouldBe Right(())
      ledger.settle(reservation, 2L, 2L, "response-1").isLeft shouldBe true
      ledger.reserve(first, Some(grant(first, 20L)), deadline).isLeft shouldBe true
      val second = request("after-settlement")
      ledger.reserve(second, Some(grant(second, 20L)), deadline).isRight shouldBe true
      val third = request("over-limit")
      ledger.reserve(third, Some(grant(third, 20L)), deadline).isLeft shouldBe true
    }

    "settle the original day after midnight and retain history after restart" in {
      val path = Files.createTempDirectory("openai-settlement-restart-").resolve("ledger.db")
      val provider = SqlDataStore.sqlite(path.toString)
      val ctx = ExecutionContext.test()
      ctx.dataStoreSpace.useDataStore(provider)
      val executor = Executors.newFixedThreadPool(4)
      val clock = new AtomicReference[Instant](instant)
      try {
        given ExecutionContext = ctx
        val first = request("restart-settlement")
        val ledger = new OpenAiTaskAllowanceLedger(new StandardEntityStore, trusted, executor, () => clock.get())
        val reservation = ledger.reserve(first, Some(grant(first, 20L)), deadline).toOption.get
        clock.set(instant.plusSeconds(86400))
        val restarted = new OpenAiTaskAllowanceLedger(new StandardEntityStore, trusted, executor, () => clock.get())
        restarted.settle(reservation, 2L, 1L, "restart-response") shouldBe Right(())
        restarted.settle(reservation, 2L, 1L, "restart-response") shouldBe Right(())
        restarted.settle(reservation, 1L, 1L, "restart-response").isLeft shouldBe true
        clock.set(instant)
        restarted.reserve(first, Some(grant(first, 20L)), deadline).isLeft shouldBe true
        val next = request("restart-next")
        restarted.reserve(next, Some(grant(next, 20L)), deadline).isRight shouldBe true
      } finally { executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS); provider.closeC() }
    }

    "enforce the shared margin-adjusted limit during concurrent admissions" in fixture { (ledger, ctx) =>
      val gate = new CountDownLatch(1)
      val callers = Executors.newFixedThreadPool(2)
      try {
        def call(id: String) = callers.submit(new Callable[Either[String, OpenAiBudgetAdmission]] {
          def call(): Either[String, OpenAiBudgetAdmission] = {
            given ExecutionContext = ctx
            gate.await()
            val r = request(id)
            ledger.reserveDailyFirst(r, Some(grant(r, 20L)), None, deadline, 10)
          }
        })
        val a = call("margin-a")
        val b = call("margin-b")
        gate.countDown()
        val results = Vector(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))
        results.count(_.isRight) shouldBe 1
      } finally callers.shutdownNow()
    }

    "retain a full reservation for invalid observations" in fixture { (ledger, ctx) =>
      given ExecutionContext = ctx
      val first = request("invalid-usage")
      val reservation = ledger.reserve(first, Some(grant(first)), deadline).toOption.get
      ledger.settle(reservation, 7L, 1L, "response").isLeft shouldBe true
      ledger.settle(reservation, 1L, 5L, "response").isLeft shouldBe true
      ledger.settle(reservation, 1L, 1L, "").isLeft shouldBe true
      val next = request("no-refund")
      ledger.reserve(next, Some(grant(next)), deadline).isLeft shouldBe true
    }
  }

  "Daily-first admission" should {
    "handoff only a confirmed exhaustion within one permanently claimed route" in fixture { (ledger, ctx) =>
      given ExecutionContext = ctx
      val group1 = scope.copy(modelPool = "group-1")
      val group2 = scope.copy(modelPool = "group-2")
      val fill = request("route-fill").copy(scope = group1)
      ledger.reserve(fill, Some(grant(fill)), deadline).isRight shouldBe true
      val first = request("route-request").copy(scope = group1)
      val second = first.copy(scope = group2, requestDigest = "b" * 64)
      val route = ledger.dailyRoute(scope.organization, first.requestId)
      route.reserve(first, grant(first), 0L, 10, deadline) shouldBe OpenAiDailyRouteResult.KnownExhausted
      route.reserve(second, grant(second, 20L), 0L, 10, deadline) match {
        case OpenAiDailyRouteResult.Reserved(r) => r.request shouldBe second
        case other => fail(s"Expected the second group reservation, got $other")
      }
      route.reserve(second, grant(second, 20L), 0L, 10, deadline) shouldBe OpenAiDailyRouteResult.Denied
      val otherOwner = ledger.dailyRoute(scope.organization, first.requestId)
      otherOwner.reserve(second, grant(second, 20L), 0L, 10, deadline) shouldBe OpenAiDailyRouteResult.Denied
    }

    "account for external use and seal a route after unverifiable evidence" in fixture { (ledger, ctx) =>
      given ExecutionContext = ctx
      val first = request("external-route")
      val second = first.copy(scope = scope.copy(modelPool = "other"))
      val route = ledger.dailyRoute(scope.organization, first.requestId)
      route.reserve(first, grant(first, 20L), 9L, 10, deadline) shouldBe OpenAiDailyRouteResult.KnownExhausted
      val untrusted = grant(second, 20L).copy(allocationProvenance = "untrusted")
      route.reserve(second, untrusted, 0L, 10, deadline) shouldBe OpenAiDailyRouteResult.Denied
      route.reserve(second, grant(second, 20L), 0L, 10, deadline) shouldBe OpenAiDailyRouteResult.Denied
    }

    "allow at most one concurrent owner of the same logical request" in fixture { (ledger, ctx) =>
      val gate = new CountDownLatch(1)
      val callers = Executors.newFixedThreadPool(2)
      try {
        def call() = callers.submit(new Callable[OpenAiDailyRouteResult] {
          def call(): OpenAiDailyRouteResult = {
            given ExecutionContext = ctx
            val first = request("concurrent-route")
            val next = first.copy(scope = scope.copy(modelPool = "other"))
            val route = ledger.dailyRoute(scope.organization, first.requestId)
            gate.await()
            route.reserve(first, grant(first, 20L), 20L, 10, deadline) match {
              case OpenAiDailyRouteResult.KnownExhausted => route.reserve(next, grant(next, 20L), 0L, 10, deadline)
              case other => other
            }
          }
        })
        val a = call()
        val b = call()
        gate.countDown()
        val results = Vector(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))
        results.count { case OpenAiDailyRouteResult.Reserved(_) => true; case _ => false } shouldBe 1
      } finally callers.shutdownNow()
    }

    "keep the route claim after restart and reject a stale UTC day" in {
      val path = Files.createTempDirectory("openai-route-restart-").resolve("ledger.db")
      val provider = SqlDataStore.sqlite(path.toString)
      val ctx = ExecutionContext.test()
      ctx.dataStoreSpace.useDataStore(provider)
      val executor = Executors.newFixedThreadPool(2)
      val clock = new AtomicReference[Instant](instant)
      try {
        given ExecutionContext = ctx
        val first = request("restart-route")
        val ledger = new OpenAiTaskAllowanceLedger(new StandardEntityStore, trusted, executor, () => clock.get())
        val route = ledger.dailyRoute(scope.organization, first.requestId)
        route.reserve(first, grant(first, 20L), 20L, 10, deadline) shouldBe OpenAiDailyRouteResult.KnownExhausted
        val restarted = new OpenAiTaskAllowanceLedger(new StandardEntityStore, trusted, executor, () => clock.get())
        val other = first.copy(scope = scope.copy(modelPool = "other"))
        restarted.dailyRoute(scope.organization, first.requestId)
          .reserve(other, grant(other, 20L), 0L, 10, deadline) shouldBe OpenAiDailyRouteResult.Denied
        clock.set(instant.plusSeconds(86400))
        route.reserve(other, grant(other, 20L), 0L, 10, deadline) shouldBe OpenAiDailyRouteResult.Denied
      } finally { executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS); provider.closeC() }
    }

    "keep group capacity separate while retaining one organization-wide request claim" in fixture { (ledger, ctx) =>
      given ExecutionContext = ctx
      val first = request("group-shared-id").copy(scope = scope.copy(modelPool = "group-1"))
      val duplicate = first.copy(scope = scope.copy(modelPool = "group-2"))
      val other = request("group-other-id").copy(scope = duplicate.scope)
      ledger.reserveDailyFirst(first, Some(grant(first)), None, deadline).isRight shouldBe true
      ledger.reserveDailyFirst(duplicate, Some(grant(duplicate)), None, deadline).isLeft shouldBe true
      ledger.reserveDailyFirst(other, Some(grant(other)), None, deadline).isRight shouldBe true
    }

    "retain the global claim after a pre-dispatch capacity refusal" in fixture { (ledger, ctx) =>
      given ExecutionContext = ctx
      val prior = request("fill-group").copy(scope = scope.copy(modelPool = "group-1"))
      val refused = request("exhausted-claim").copy(scope = prior.scope)
      val alternate = refused.copy(scope = scope.copy(modelPool = "group-2"))
      ledger.reserveDailyFirst(prior, Some(grant(prior)), None, deadline).isRight shouldBe true
      ledger.reserveDailyFirst(refused, Some(grant(refused)), None, deadline).isLeft shouldBe true
      ledger.reserveDailyFirst(alternate, Some(grant(alternate)), None, deadline).isLeft shouldBe true
    }

    "bound delayed authority verification and retain the possibly applied credit" in {
      val path = Files.createTempDirectory("openai-authority-delay-").resolve("ledger.db")
      val provider = SqlDataStore.sqlite(path.toString)
      val ctx = ExecutionContext.test()
      ctx.dataStoreSpace.useDataStore(provider)
      val executor = Executors.newSingleThreadExecutor()
      val callers = Executors.newSingleThreadExecutor()
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val verifies = new AtomicInteger(0)
      val determinations = new AtomicInteger(0)
      val authority = new OpenAiDailyIneligibilityAuthority {
        def determination(r: OpenAiAllowanceRequest): Option[OpenAiDailyIneligibility] = {
          determinations.incrementAndGet()
          Some(OpenAiDailyIneligibility(r, instant.minusSeconds(1), instant.plusSeconds(60), "trusted-daily"))
        }
        def verify(e: OpenAiDailyIneligibility): Boolean = {
          if (e.request.requestId == "delayed-authority" && verifies.incrementAndGet() == 3) {
            entered.countDown()
            release.await()
          }
          true
        }
      }
      try {
        val ledger = new OpenAiTaskAllowanceLedger(new StandardEntityStore, trusted, executor, () => instant,
          creditVerifier = _credit_verifier, dailyIneligibility = authority)
        val r = request("delayed-authority")
        val call = callers.submit(new Callable[Either[String, OpenAiBudgetAdmission]] {
          def call(): Either[String, OpenAiBudgetAdmission] = {
            given ExecutionContext = ctx
            ledger.reserveDailyFirst(r, None, Some(_credit(r)), System.nanoTime() + TimeUnit.SECONDS.toNanos(2))
          }
        })
        entered.await(10, TimeUnit.SECONDS) shouldBe true
        call.get(5, TimeUnit.SECONDS).isLeft shouldBe true
        release.countDown()
        executor.submit(new Runnable { def run(): Unit = () }).get(10, TimeUnit.SECONDS)
        given ExecutionContext = ctx
        ledger.reserveDailyFirst(r, None, Some(_credit(r)), deadline).isLeft shouldBe true
        val next = request("after-delayed-authority")
        ledger.reserveDailyFirst(next, None, Some(_credit(next)), deadline).isLeft shouldBe true
        determinations.get() shouldBe 3
      } finally {
        release.countDown(); callers.shutdown(); executor.shutdown()
        callers.awaitTermination(10, TimeUnit.SECONDS); executor.awaitTermination(10, TimeUnit.SECONDS); provider.closeC()
      }
    }

    "reject expired retained ineligibility after verification and keep its reservation" in {
      val path = Files.createTempDirectory("openai-authority-expiry-").resolve("ledger.db")
      val provider = SqlDataStore.sqlite(path.toString)
      val ctx = ExecutionContext.test()
      ctx.dataStoreSpace.useDataStore(provider)
      val executor = Executors.newSingleThreadExecutor()
      val clock = new AtomicReference[Instant](instant)
      val verifies = new AtomicInteger(0)
      val determinations = new AtomicInteger(0)
      val authority = new OpenAiDailyIneligibilityAuthority {
        def determination(r: OpenAiAllowanceRequest): Option[OpenAiDailyIneligibility] = {
          determinations.incrementAndGet()
          Some(OpenAiDailyIneligibility(r, instant.minusSeconds(1), instant.plusSeconds(1), "trusted-daily"))
        }
        def verify(e: OpenAiDailyIneligibility): Boolean = {
          if (e.request.requestId == "expiring-authority" && verifies.incrementAndGet() == 3)
            clock.set(instant.plusSeconds(1))
          true
        }
      }
      try {
        given ExecutionContext = ctx
        val ledger = new OpenAiTaskAllowanceLedger(new StandardEntityStore, trusted, executor, () => clock.get(),
          creditVerifier = _credit_verifier, dailyIneligibility = authority)
        val r = request("expiring-authority")
        ledger.reserveDailyFirst(r, None, Some(_credit(r)), deadline).isLeft shouldBe true
        clock.set(instant)
        ledger.reserveDailyFirst(r, None, Some(_credit(r)), deadline).isLeft shouldBe true
        val next = request("after-expired-authority")
        ledger.reserveDailyFirst(next, None, Some(_credit(next)), deadline).isLeft shouldBe true
        determinations.get() shouldBe 3
      } finally { executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS); provider.closeC() }
    }

    "deny credit when host authorities are absent" in fixture { (_, ctx) =>
      given ExecutionContext = ctx
      val executor = Executors.newFixedThreadPool(2)
      try {
        val ledger = new OpenAiTaskAllowanceLedger(new StandardEntityStore, trusted, executor, () => instant)
        val r = request("missing-authority")
        ledger.reserveDailyFirst(r, None, Some(_credit(r)), deadline).isLeft shouldBe true
        val daily = request("missing-credit-authority")
        ledger.reserveDailyFirst(daily, Some(grant(daily, 1L)), Some(_credit(daily)), deadline).isLeft shouldBe true
      } finally { executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS) }
    }

    "deny unknown and forged ineligibility and untrusted credit evidence" in fixture { (ledger, ctx) =>
      given ExecutionContext = ctx
      for (id <- Seq("unknown-daily", "forged-daily")) {
        val r = request(id)
        ledger.reserveDailyFirst(r, None, Some(_credit(r)), deadline).isLeft shouldBe true
      }
      val fabricated = request("fabricated")
      ledger.reserveDailyFirst(fabricated, None, Some(_credit(fabricated).copy(allocationId = "fake")), deadline).isLeft shouldBe true
      val understated = request("understated")
      ledger.reserveDailyFirst(understated, None, Some(_credit(understated, 1L)), deadline).isLeft shouldBe true
      val crossed = request("crossed")
      ledger.reserveDailyFirst(crossed, None, Some(_credit(crossed.copy(scope = scope.copy(organization = "other")))), deadline).isLeft shouldBe true
      val valid = request("valid-after-rejections")
      ledger.reserveDailyFirst(valid, None, Some(_credit(valid)), deadline) shouldBe Right(OpenAiCreditAdmission(valid, 6L))
    }

    "prefer daily coverage and deny cross-route replay" in fixture { (ledger, ctx) =>
      given ExecutionContext = ctx
      val r = request("daily-first")
      ledger.reserveDailyFirst(r, Some(grant(r)), Some(_credit(r)), deadline) shouldBe Right(OpenAiDailyAdmission(OpenAiAllowanceReservation(r, 10L)))
      ledger.reserve(r, Some(grant(r)), deadline).isLeft shouldBe true
      ledger.reserveDailyFirst(r, None, Some(_credit(r)), deadline).isLeft shouldBe true
      val next = request("credit-after-daily")
      ledger.reserveDailyFirst(next, Some(grant(next)), Some(_credit(next)), deadline) shouldBe Right(OpenAiCreditAdmission(next, 6L))
    }

    "reject unverified daily eligibility without consuming credit" in fixture { (ledger, ctx) =>
      given ExecutionContext = ctx
      val r = request("bad-daily")
      ledger.reserveDailyFirst(r, Some(grant(r).copy(allocationProvenance = "bad")), Some(_credit(r)), deadline).isLeft shouldBe true
      val next = request("good-credit")
      ledger.reserveDailyFirst(next, None, Some(_credit(next)), deadline) shouldBe Right(OpenAiCreditAdmission(next, 6L))
    }

    "deny monthly fallback after an uncertain daily write and retain the claim" in {
      val path = Files.createTempDirectory("openai-daily-uncertain-").resolve("ledger.db")
      val provider = SqlDataStore.sqlite(path.toString)
      val ctx = ExecutionContext.test()
      ctx.dataStoreSpace.useDataStore(provider)
      val executor = Executors.newFixedThreadPool(2)
      val ambiguous = new AtomicBoolean(true)
      val store = new StandardEntityStore {
        override def updateDetached[T](changes: T, expectedRevision: Option[EntityRevision],
            policy: EntityMutationExecutionPolicy)(using tc: EntityPersistent[T], context: ExecutionContext)
            : Consequence[EntityRevisionCarrier[T]] = {
          val result = super.updateDetached(changes, expectedRevision, policy)
          result match {
            case Consequence.Success(_) if ambiguous.getAndSet(false) => Consequence.valueInvalid("lost acknowledgement")
            case _ => result
          }
        }
      }
      try {
        given ExecutionContext = ctx
        val ledger = new OpenAiTaskAllowanceLedger(store, trusted, executor, () => instant, creditVerifier = _credit_verifier, dailyIneligibility = _daily_authority)
        val r = request("uncertain-daily")
        ledger.reserveDailyFirst(r, Some(grant(r)), Some(_credit(r)), deadline).isLeft shouldBe true
        ledger.reserveDailyFirst(r, None, Some(_credit(r)), deadline).isLeft shouldBe true
        val next = request("credit-still-free")
        ledger.reserveDailyFirst(next, None, Some(_credit(next)), deadline) shouldBe Right(OpenAiCreditAdmission(next, 6L))
      } finally { executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS); provider.closeC() }
    }

    "share one credit allocation across days and model pools" in {
      val path = Files.createTempDirectory("openai-credit-rollover-").resolve("ledger.db")
      val provider = SqlDataStore.sqlite(path.toString)
      val ctx = ExecutionContext.test()
      ctx.dataStoreSpace.useDataStore(provider)
      val executor = Executors.newFixedThreadPool(4)
      var clock = instant
      try {
        given ExecutionContext = ctx
        val ledger = new OpenAiTaskAllowanceLedger(new StandardEntityStore, trusted, executor, () => clock, creditVerifier = _credit_verifier, dailyIneligibility = _daily_authority)
        val first = request("credit-one")
        ledger.reserveDailyFirst(first, None, Some(_credit(first)), deadline) shouldBe Right(OpenAiCreditAdmission(first, 6L))
        clock = instant.plusSeconds(86400)
        val next = request("credit-two").copy(scope = scope.copy(modelPool = "other", day = scope.day.plusDays(1)))
        ledger.reserveDailyFirst(next, None, Some(_credit(next)), deadline).isLeft shouldBe true
        val fit = next.copy(requestId = "credit-three")
        ledger.reserveDailyFirst(fit, None, Some(_credit(fit, 4L)), deadline) shouldBe Right(OpenAiCreditAdmission(fit, 4L))
        ledger.reserveDailyFirst(first.copy(scope = next.scope), None, Some(_credit(first.copy(scope = next.scope), 1L)), deadline).isLeft shouldBe true
      } finally { executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS); provider.closeC() }
    }

    "admit at most one competing claim for the same request" in fixture { (ledger, ctx) =>
      val gate = new CountDownLatch(1)
      val consumers = Executors.newFixedThreadPool(2)
      try {
        val r = request("same-claim")
        def call() = consumers.submit(new Callable[Either[String, OpenAiBudgetAdmission]] {
          def call(): Either[String, OpenAiBudgetAdmission] = {
            given ExecutionContext = ctx
            gate.await()
            ledger.reserveDailyFirst(r, None, Some(_credit(r)), deadline)
          }
        })
        val a = call(); val b = call(); gate.countDown()
        Vector(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)).count(_.isRight) shouldBe 1
      } finally consumers.shutdownNow()
    }

    "serialize competing requests against the same credit balance" in fixture { (ledger, ctx) =>
      val gate = new CountDownLatch(1)
      val consumers = Executors.newFixedThreadPool(2)
      try {
        def call(id: String) = consumers.submit(new Callable[Either[String, OpenAiBudgetAdmission]] {
          def call(): Either[String, OpenAiBudgetAdmission] = {
            given ExecutionContext = ctx
            val r = request(id)
            gate.await()
            ledger.reserveDailyFirst(r, None, Some(_credit(r)), deadline)
          }
        })
        val a = call("compete-a"); val b = call("compete-b"); gate.countDown()
        Vector(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)).count(_.isRight) shouldBe 1
      } finally consumers.shutdownNow()
    }

    "reject expired and insufficient credit" in fixture { (ledger, ctx) =>
      given ExecutionContext = ctx
      val r = request("expired-credit")
      ledger.reserveDailyFirst(r, None, Some(_credit(r).copy(expiresAt = instant)), deadline).isLeft shouldBe true
      val next = request("too-expensive")
      ledger.reserveDailyFirst(next, None, Some(_credit(next, 11L)), deadline).isLeft shouldBe true
    }
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
        val ledger = new OpenAiTaskAllowanceLedger(store, trusted, executor, () => instant, creditVerifier = _credit_verifier, dailyIneligibility = _daily_authority)
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
        val ledger = new OpenAiTaskAllowanceLedger(store, trusted, executor, () => instant, creditVerifier = _credit_verifier, dailyIneligibility = _daily_authority)
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

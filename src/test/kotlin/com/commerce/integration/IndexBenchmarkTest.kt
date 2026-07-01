package com.commerce.integration

import com.commerce.order.infrastructure.OrderLineJpaRepository
import com.commerce.support.IntegrationTestSupport
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 성능 실증 #2·#3 — 인덱스 최적화(V21)를 **동일 데이터**에 인덱스 on/off로 실측한다.
 * 부하 수치를 지어내지 않고, (a) EXPLAIN으로 옵티마이저가 새 인덱스를 실제로 선택함을 확증하고,
 * (b) 새 인덱스 사용 vs `IGNORE INDEX`(강제 풀스캔)의 실행 시간을 로그로 남긴다(결과값은 동일함을 단언).
 */
@Transactional
class IndexBenchmarkTest : IntegrationTestSupport() {

    @Autowired lateinit var em: EntityManager
    @Autowired lateinit var orderLineRepository: OrderLineJpaRepository

    private fun setDeepRecursion() =
        em.createNativeQuery("SET SESSION cte_max_recursion_depth = 1000000").executeUpdate()

    /** 쿼리를 iters회 실행하고 최소 소요(ms, 노이즈 최소화)를 반환. */
    private fun minMillis(iters: Int, run: () -> Unit): Double {
        var best = Double.MAX_VALUE
        repeat(iters) {
            val t0 = System.nanoTime()
            run()
            best = minOf(best, (System.nanoTime() - t0) / 1_000_000.0)
        }
        return best
    }

    private fun explainJson(sql: String): String =
        em.createNativeQuery("EXPLAIN FORMAT=JSON $sql").singleResult.toString()

    // ── #2 쿠폰 1인 한도 카운트: 복합 인덱스 (member_id, promotion_id, status) ─────────────────
    @Test
    fun `coupon per-member-limit count uses composite index and beats full scan`() {
        val memberId = 900_001L
        val rows = 20_000
        setDeepRecursion()
        // promotion_id(n%500)와 status를 독립시켜, 각 promotion_id에 REDEEMED가 골고루 들어가게 한다.
        em.createNativeQuery(
            """
            insert into coupons (created_at, updated_at, version, promotion_id, member_id, expires_at, status)
            with recursive seq(n) as (select 1 union all select n+1 from seq where n < :rows)
            select now(6), now(6), 0, (n % 500), :mid, '2999-01-01 00:00:00',
                   case when (n div 500) % 4 = 0 then 'REDEEMED' else 'ISSUED' end
            from seq
            """.trimIndent(),
        ).setParameter("rows", rows).setParameter("mid", memberId).executeUpdate()

        val where = "where member_id = $memberId and promotion_id = 7 and status = 'REDEEMED'"
        val indexed = "select count(*) from coupons force index (idx_coupon_member_promo_status) $where" // 복합 인덱스 경로
        val scan = "select count(*) from coupons ignore index " +
            "(idx_coupon_member_promo_status, idx_coupon_member, idx_coupon_promotion) $where"           // 강제 풀스캔

        // (a) 복합 인덱스가 (member_id, promotion_id, status) 3개 컬럼을 모두 타는지 확인
        explainJson(indexed).let {
            it shouldContain "idx_coupon_member_promo_status"
            it shouldContain "\"member_id\""
            it shouldContain "\"promotion_id\""
            it shouldContain "\"status\""
        }

        // (b) 결과 동일 + 인덱스 경로가 풀스캔보다 빠름
        fun count(sql: String) = (em.createNativeQuery(sql).singleResult as Number).toInt()
        count(indexed) shouldBe count(scan)
        count(indexed) shouldBeGreaterThan 0

        val idxMs = minMillis(7) { em.createNativeQuery(indexed).singleResult }
        val scanMs = minMillis(7) { em.createNativeQuery(scan).singleResult }
        println("[BENCH][coupon-limit] rows=$rows  indexed=${"%.2f".format(idxMs)}ms  fullscan=${"%.2f".format(scanMs)}ms  x${"%.1f".format(scanMs / idxMs)}")
        scanMs shouldBeGreaterThan idxMs
    }

    // ── #3 정산 매출 합산: 커버링 인덱스 (seller_id, refunded, order_id, line_amount) ──────────
    @Test
    fun `settlement sum uses covering index and beats full scan`() {
        val sellerId = 900_002L
        val memberId = 900_003L
        val total = 200_000       // 전체 라인
        val targetCount = total / 10  // 대상 판매자 라인(매 10번째) — 나머지는 다른 판매자
        // FK(order_lines.order_id → orders.id) 충족용 주문 1건(PAID, 기간 내)
        em.createNativeQuery(
            """
            insert into orders (created_at, updated_at, version, member_id, status,
                                total_amount, discount_amount, paid_amount, refunded_amount)
            values (now(6), now(6), 0, :mid, 'PAID', 0, 0, 0, 0)
            """.trimIndent(),
        ).setParameter("mid", memberId).executeUpdate()
        val orderId = (em.createNativeQuery("select last_insert_id()").singleResult as Number).toLong()

        setDeepRecursion()
        // 대상 판매자 라인을 전체의 1/10만 섞어, 인덱스 ref가 "읽는 행 수"를 줄이는 이득이 드러나게 한다.
        em.createNativeQuery(
            """
            insert into order_lines (created_at, updated_at, version, order_id, sku_id, seller_id,
                                     quantity, unit_price, line_amount, refunded)
            with recursive seq(n) as (select 1 union all select n+1 from seq where n < :total)
            select now(6), now(6), 0, :oid, 1,
                   case when n % 10 = 0 then :sid else 1000 + (n % 1000) end,
                   1, 100.00, 100.00, 0
            from seq
            """.trimIndent(),
        ).setParameter("total", total).setParameter("oid", orderId).setParameter("sid", sellerId).executeUpdate()

        val start = LocalDateTime.of(2000, 1, 1, 0, 0)
        val end = LocalDateTime.of(2999, 1, 1, 0, 0)
        // 커버링 인덱스가 실제로 무엇을 없애는지(order_lines 힙 접근) 뚜렷이 보이도록 order_lines 집계를 단독 측정한다.
        val where = "where seller_id = $sellerId and refunded = false"
        val indexedSum = "select coalesce(sum(line_amount),0) from order_lines " +
            "force index (idx_orderline_seller_refund) $where"                                   // 커버링(인덱스만)
        val scanSum = "select coalesce(sum(line_amount),0) from order_lines " +
            "ignore index (idx_orderline_seller_refund, idx_orderline_seller) $where"            // 강제 풀스캔(힙)

        // (a) 커버링 인덱스가 선택되고 인덱스만으로 처리된다(Using index → 힙 접근 없음)
        val plan = explainJson(indexedSum)
        plan shouldContain "idx_orderline_seller_refund"
        plan shouldContain "\"using_index\": true"

        // (b) 결과 동일 + 실제 정산 리포지토리 경로도 동일 값 + 인덱스 경로가 풀스캔보다 빠름
        (em.createNativeQuery(indexedSum).singleResult as Number).toLong() shouldBe (targetCount * 100L)
        orderLineRepository.sumSellerSalesInPeriod(sellerId, start, end).toLong() shouldBe (targetCount * 100L)

        val idxMs = minMillis(7) { em.createNativeQuery(indexedSum).singleResult }
        val scanMs = minMillis(7) { em.createNativeQuery(scanSum).singleResult }
        println("[BENCH][settlement-sum] total=$total target=$targetCount  covering=${"%.2f".format(idxMs)}ms  fullscan=${"%.2f".format(scanMs)}ms  x${"%.1f".format(scanMs / idxMs)}")
        scanMs shouldBeGreaterThan idxMs
    }
}

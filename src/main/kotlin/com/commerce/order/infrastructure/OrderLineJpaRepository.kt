package com.commerce.order.infrastructure

import com.commerce.order.domain.OrderLine
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.LocalDateTime

interface OrderLineJpaRepository : JpaRepository<OrderLine, Long> {
    fun findByOrderId(orderId: Long): List<OrderLine>
    fun findBySellerId(sellerId: Long): List<OrderLine>

    /**
     * 판매자 정산용: 기간 내 유효 라인의 금액을 판매자별로 합산한다.
     * 환불된 라인(ol.refunded)은 제외하고, 부분환불 주문(PARTIALLY_REFUNDED)의 잔여 라인은 포함한다.
     * (전액취소 CANCELLED·전액환불 REFUNDED 주문은 상태로 제외.)
     * `orders`가 JPQL 예약어와 충돌하므로 네이티브 쿼리로 작성한다.
     */
    @Query(
        value = """
            select coalesce(sum(ol.line_amount), 0)
            from order_lines ol join orders o on ol.order_id = o.id
            where ol.seller_id = :sellerId and ol.refunded = false
              and o.status in ('PAID', 'PARTIALLY_REFUNDED')
              and o.created_at between :start and :end
        """,
        nativeQuery = true,
    )
    fun sumSellerSalesInPeriod(
        @Param("sellerId") sellerId: Long,
        @Param("start") start: LocalDateTime,
        @Param("end") end: LocalDateTime,
    ): BigDecimal

    /**
     * 판매자의 **총 미환불 매출**(누적 정산 기준, 기간 제한 없음). PAID/PARTIALLY_REFUNDED 주문의 미환불 라인 합.
     * 누적 정산액 = 이 값 − 이미 확정/지급된 정산 합. 확정 이후 환불도 이 합이 줄어들어 다음 정산에서
     * 자연히 차감(clawback)되고, 순정산액이 음수면 다음 기수로 이월된다.
     */
    @Query(
        value = """
            select coalesce(sum(ol.line_amount), 0)
            from order_lines ol join orders o on ol.order_id = o.id
            where ol.seller_id = :sellerId and ol.refunded = false
              and o.status in ('PAID', 'PARTIALLY_REFUNDED')
        """,
        nativeQuery = true,
    )
    fun sumSellerNonRefundedSalesAllTime(@Param("sellerId") sellerId: Long): BigDecimal
}

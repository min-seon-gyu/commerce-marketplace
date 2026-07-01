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
     * 판매자 정산용: 기간 내 PAID 주문의 라인 금액을 판매자별로 합산한다(취소 주문은 CANCELLED이라 제외).
     * `orders`가 JPQL 예약어와 충돌하므로 네이티브 쿼리로 작성한다.
     */
    @Query(
        value = """
            select coalesce(sum(ol.line_amount), 0)
            from order_lines ol join orders o on ol.order_id = o.id
            where ol.seller_id = :sellerId and o.status = 'PAID'
              and o.created_at between :start and :end
        """,
        nativeQuery = true,
    )
    fun sumSellerSalesInPeriod(
        @Param("sellerId") sellerId: Long,
        @Param("start") start: LocalDateTime,
        @Param("end") end: LocalDateTime,
    ): BigDecimal
}

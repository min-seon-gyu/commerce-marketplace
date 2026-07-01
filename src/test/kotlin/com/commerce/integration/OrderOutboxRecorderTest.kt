package com.commerce.integration

import com.commerce.member.application.MemberService
import com.commerce.order.infrastructure.outbox.OrderOutboxEventRepository
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

/**
 * OrderOutboxRecorder가 주문 이벤트를 주문 tx 内 outbox에 원자적으로 캡처하고,
 * 주문이 아닌 도메인 이벤트(예: 회원 정지)는 캡처하지 않음(OrderEvent 하위 타입만 수신)을 검증한다.
 */
class OrderOutboxRecorderTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var memberService: MemberService
    @Autowired lateinit var outboxRepository: OrderOutboxEventRepository

    @Test
    fun `order placed event is captured in outbox as unpublished`() {
        val seller = fixtures.createSeller(fixtures.createMember())
        val buyer = fixtures.createMember()

        val order = fixtures.sellerSale(buyer.id, seller.id, BigDecimal("10000"))

        val row = outboxRepository.findAll()
            .find { it.eventType == "ORDER_PLACED" && it.aggregateId == order.id }
        row.shouldNotBeNull()
        row.aggregateType shouldBe "ORDER"
        row.published shouldBe false // 테스트 프로파일은 relay 폴링 비활성 → 미발행 유지
    }

    @Test
    fun `non-order domain events are not captured in the order outbox`() {
        val member = fixtures.createMember()

        memberService.suspend(member.id) // MEMBER_SUSPENDED — OrderEvent 아님

        outboxRepository.findAll().none { it.eventType == "MEMBER_SUSPENDED" }.shouldBeTrue()
    }
}

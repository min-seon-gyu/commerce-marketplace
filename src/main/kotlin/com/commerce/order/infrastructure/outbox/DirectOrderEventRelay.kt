package com.commerce.order.infrastructure.outbox

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 직접 전달 relay (킬스위치 OFF/기본 또는 테스트). Kafka 없이 outbox → order_event_log를 직접 적용한다.
 * `order.kafka.enabled=false`(미설정 포함)일 때 활성화된다.
 *
 * poison 가드: 적용이 영구 실패하는 행은 attempts를 증가시키고 max 초과 시 격리(published로 마킹)한다.
 * 이로써 무한 재처리와, id 오름차순 폴링에서 실패 행이 신규 이벤트를 starvation시키는 head-of-line 블로킹을 막는다.
 */
@Component
@ConditionalOnProperty(prefix = "order.kafka", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class DirectOrderEventRelay(
    private val outboxRepository: OrderOutboxEventRepository,
    private val applier: OrderEventApplier,
    @Value("\${order.outbox.max-attempts:5}") private val maxAttempts: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${order.outbox.poll-ms:1000}")
    fun poll() {
        relayOnce()
    }

    /** 미발행·미격리 outbox 이벤트를 이벤트 로그로 적용하고 발행 마킹. 적용 성공 건수 반환(테스트에서 수동 호출). */
    fun relayOnce(): Int {
        var applied = 0
        outboxRepository.findTop200ByPublishedFalseAndAttemptsLessThanOrderByIdAsc(maxAttempts).forEach { event ->
            try {
                applier.apply(event.payload)
                applier.markPublished(event.id)
                applied++
            } catch (e: Exception) {
                val attempts = applier.recordFailure(event.id)
                if (attempts >= maxAttempts) {
                    // 최대 시도 초과 → 격리: published로 마킹해 재처리/head-of-line 블로킹을 끊는다(운영 모니터링 대상).
                    applier.markPublished(event.id)
                    log.error("Direct relay quarantined outbox {} ({}) after {} attempts: {}", event.id, event.eventType, attempts, e.message)
                } else {
                    log.warn("Direct relay apply failed for outbox {} ({}), attempt {}: {}", event.id, event.eventType, attempts, e.message)
                }
            }
        }
        return applied
    }
}

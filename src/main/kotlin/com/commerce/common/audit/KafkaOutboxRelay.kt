package com.commerce.common.audit

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Kafka 전달 relay (킬스위치 ON, prod 기본). 미발행 outbox 이벤트를 Kafka 토픽으로 발행하고 발행 마킹한다.
 * 실제 감사 로그 기록은 [AuditKafkaConsumer]가 토픽을 소비해 수행한다(전달과 적용을 분리).
 */
@Component
@ConditionalOnProperty(prefix = "audit.kafka", name = ["enabled"], havingValue = "true")
class KafkaOutboxRelay(
    private val outboxRepository: OutboxEventRepository,
    private val applier: OutboxAuditApplier,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${audit.kafka.topic:audit-events}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${audit.outbox.poll-ms:1000}")
    fun poll() {
        relayOnce()
    }

    /** 미발행 outbox 이벤트를 Kafka로 발행하고 발행 마킹. 발행 성공 건수 반환(테스트에서 수동 호출). */
    fun relayOnce(): Int {
        var published = 0
        for (event in outboxRepository.findTop200ByPublishedFalseOrderByIdAsc()) {
            try {
                // 키=eventId(파티션 내 순서·중복 추적). 발행 확인(get) 후 마킹 → 미발행은 다음 폴링에서 재시도.
                kafkaTemplate.send(topic, event.eventId, event.payload).get()
                applier.markPublished(event.id)
                published++
            } catch (e: Exception) {
                // 전송 실패는 대개 브로커 미가용 → 배치를 중단한다(200건을 각각 fast-fail해 스케줄러 스레드를 장기 점유하지 않도록).
                // 미발행 행은 다음 폴링에서 재시도된다(at-least-once).
                log.warn("Kafka outbox relay stopped at outbox {} ({}): {}", event.id, event.eventType, e.message)
                break
            }
        }
        return published
    }
}

package com.commerce.common.audit

import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff
import java.time.Duration

/**
 * 감사 Kafka 파이프라인 구성: 토픽 선언적 프로비저닝 + 컨슈머 에러 처리(DLT).
 *
 * - 토픽 프로비저닝: `audit-events`/`audit-events.DLT`를 NewTopic 빈으로 생성(브로커 auto-create 의존 제거).
 *   DLT가 항상 존재하므로 recoverer 전송이 성립한다.
 * - 에러 처리: apply 실패 시 지수 백오프로 일시적 오류(DB failover/restart)를 흡수하고, 소진되면 DLT로 적재한다.
 *   DLT **전송 결과를 검증**(failIfSendResultIsError)해 DLT 발행 실패 시 오프셋 커밋을 막고 재시도 → 무성 유실 방지.
 * Spring Boot가 단일 CommonErrorHandler 빈을 리스너 컨테이너 팩토리에 자동 연결한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "audit.kafka", name = ["enabled"], havingValue = "true")
class AuditKafkaConfig(
    @Value("\${audit.kafka.topic:audit-events}") private val topic: String,
    @Value("\${audit.kafka.retry.initial-ms:1000}") private val retryInitialMs: Long,
    @Value("\${audit.kafka.retry.multiplier:2.0}") private val retryMultiplier: Double,
    @Value("\${audit.kafka.retry.max-interval-ms:20000}") private val retryMaxIntervalMs: Long,
    @Value("\${audit.kafka.retry.max-elapsed-ms:60000}") private val retryMaxElapsedMs: Long,
) {

    @Bean
    fun auditEventsTopic(): NewTopic = TopicBuilder.name(topic).partitions(1).replicas(1).build()

    @Bean
    fun auditEventsDltTopic(): NewTopic = TopicBuilder.name("$topic.DLT").partitions(1).replicas(1).build()

    @Bean
    fun auditKafkaErrorHandler(kafkaTemplate: KafkaTemplate<String, String>): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ ->
            TopicPartition("${record.topic()}.DLT", 0)
        }.apply {
            // DLT 전송 결과를 검증한다 — 발행 실패 시 예외를 던져 오프셋 커밋을 막고 재시도(조용한 유실 차단).
            setFailIfSendResultIsError(true)
            setWaitForSendResultTimeout(Duration.ofSeconds(5))
        }
        // 지수 백오프(기본 1s→2x, 캡 20s, 총 ~60s): 일시적 DB failover/restart를 흡수한 뒤 DLT로 보낸다.
        val backOff = ExponentialBackOff(retryInitialMs, retryMultiplier).apply {
            maxInterval = retryMaxIntervalMs
            maxElapsedTime = retryMaxElapsedMs
        }
        return DefaultErrorHandler(recoverer, backOff)
    }
}

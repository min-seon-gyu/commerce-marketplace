package com.commerce.order.infrastructure.outbox

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
 * 주문 이벤트 Kafka 파이프라인 구성: 토픽 선언적 프로비저닝 + 컨슈머 에러 처리(DLT).
 *
 * - 토픽 프로비저닝: `order-events`/`order-events.DLT`를 NewTopic 빈으로 생성(브로커 auto-create 의존 제거).
 * - 에러 처리: apply 실패 시 지수 백오프로 일시적 오류를 흡수하고, 소진되면 DLT로 적재한다.
 *   DLT **전송 결과를 검증**(failIfSendResultIsError)해 발행 실패 시 오프셋 커밋을 막고 재시도 → 무성 유실 방지.
 */
@Configuration
@ConditionalOnProperty(prefix = "order.kafka", name = ["enabled"], havingValue = "true")
class OrderEventKafkaConfig(
    @Value("\${order.kafka.topic:order-events}") private val topic: String,
    @Value("\${order.kafka.retry.initial-ms:1000}") private val retryInitialMs: Long,
    @Value("\${order.kafka.retry.multiplier:2.0}") private val retryMultiplier: Double,
    @Value("\${order.kafka.retry.max-interval-ms:20000}") private val retryMaxIntervalMs: Long,
    @Value("\${order.kafka.retry.max-elapsed-ms:60000}") private val retryMaxElapsedMs: Long,
) {

    @Bean
    fun orderEventsTopic(): NewTopic = TopicBuilder.name(topic).partitions(1).replicas(1).build()

    @Bean
    fun orderEventsDltTopic(): NewTopic = TopicBuilder.name("$topic.DLT").partitions(1).replicas(1).build()

    @Bean
    fun orderKafkaErrorHandler(kafkaTemplate: KafkaTemplate<String, String>): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ ->
            TopicPartition("${record.topic()}.DLT", 0)
        }.apply {
            setFailIfSendResultIsError(true)
            setWaitForSendResultTimeout(Duration.ofSeconds(5))
        }
        val backOff = ExponentialBackOff(retryInitialMs, retryMultiplier).apply {
            maxInterval = retryMaxIntervalMs
            maxElapsedTime = retryMaxElapsedMs
        }
        return DefaultErrorHandler(recoverer, backOff)
    }
}

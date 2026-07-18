package com.commerce.seller.batch

import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * 결산 주기 자동 실행. 매일 03:00(KST)에 **전일** 기준으로 정산 배치를 돌린다.
 * processor가 판매자별 주기(일/주/월)로 **가장 최근에 끝난 구간**을 판정해 그 구간의 정산만 생성한다
 * (진행 중 주기는 대상 아님). 이미 생성된 주기는 멱등 스킵되므로 매일 실행해도 안전하고,
 * 경계일 실행이 실패해도 다음 실행이 같은 주기를 재시도한다(자가 치유).
 * `settlement.batch.scheduled.enabled=true`일 때만 활성(테스트에선 false).
 */
@Component
@ConditionalOnProperty(prefix = "settlement.batch.scheduled", name = ["enabled"], havingValue = "true")
class SettlementBatchScheduler(
    private val jobLauncher: JobLauncher,
    @Qualifier(SettlementBatchConfig.JOB_NAME) private val settlementJob: Job,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    fun runDaily() {
        val referenceDate = LocalDate.now(KST).minusDays(1)
        val params = JobParametersBuilder()
            .addString("referenceDate", referenceDate.toString())
            .addLong("run.id", System.currentTimeMillis())
            .toJobParameters()
        val execution = jobLauncher.run(settlementJob, params)
        log.info("Settlement batch launched for {} → execution {} ({})", referenceDate, execution.id, execution.status)
    }

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
    }
}

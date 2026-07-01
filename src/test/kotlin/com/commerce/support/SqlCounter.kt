package com.commerce.support

import org.hibernate.cfg.AvailableSettings
import org.hibernate.resource.jdbc.spi.StatementInspector
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hibernate가 실행하는 SQL을 가로채 테이블별 SELECT 문 수를 센다(N+1 실증용).
 * `reset()` 이후 측정 구간의 문장 수를 [selects]로 확인한다. 프로덕션 코드에는 영향 없다(테스트 전용).
 */
class SqlCounter : StatementInspector {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val tables = listOf("skus", "products", "order_lines")

    override fun inspect(sql: String): String {
        val s = sql.lowercase()
        if (s.trimStart().startsWith("select")) {
            tables.forEach { t ->
                if (Regex("\\b(from|join)\\s+$t\\b").containsMatchIn(s))
                    counts.getOrPut(t) { AtomicInteger() }.incrementAndGet()
            }
        }
        return sql
    }

    fun reset() = counts.clear()
    fun selects(table: String): Int = counts[table]?.get() ?: 0
}

/** SqlCounter를 Hibernate StatementInspector로 등록하는 테스트 설정. 필요한 테스트에서만 @Import. */
@TestConfiguration
class QueryCountConfig {
    @Bean
    fun sqlCounter(): SqlCounter = SqlCounter()

    @Bean
    fun sqlCounterCustomizer(counter: SqlCounter): HibernatePropertiesCustomizer =
        HibernatePropertiesCustomizer { props -> props[AvailableSettings.STATEMENT_INSPECTOR] = counter }
}

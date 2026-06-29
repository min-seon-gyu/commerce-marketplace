package com.commerce.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SwaggerConfig {

    @Bean
    fun openAPI(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("모바일 상품권 관리 시스템 API")
                .version("1.0.0")
                .description(
                    """
                    지역사랑상품권의 발행-유통-정산 전 생애주기를 관리하는 백엔드 API.

                    주요 설계 원칙:
                    - 복식부기 원장으로 재무 무결성 보장
                    - 보상 트랜잭션으로 감사 추적성 확보
                    - 분산락 + 비관적 락 이중 방어로 동시성 안전
                    """.trimIndent()
                )
        )
}

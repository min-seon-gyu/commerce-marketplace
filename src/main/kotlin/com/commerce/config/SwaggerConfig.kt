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
                .title("커머스 백엔드 API")
                .version("1.0.0")
                .description(
                    """
                    마켓플레이스 커머스 백엔드 API — 상품·재고·장바구니·주문·결제·정산.

                    주요 설계 원칙:
                    - 복식부기 원장으로 재무 정합성 보장
                    - 보상 트랜잭션으로 취소 추적성 확보
                    - 분산락 + 비관적 락 이중 방어로 재고 동시성 안전
                    """.trimIndent()
                )
        )
}

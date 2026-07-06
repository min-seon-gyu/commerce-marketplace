package com.commerce.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val requestTraceFilter: RequestTraceFilter,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                // ── 공개(비인증) 엔드포인트 — 명시적 화이트리스트 ─────────────────────
                it.requestMatchers("/api/v1/members/register", "/api/v1/members/login").permitAll()
                it.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                // actuator: 헬스/지표. metrics·prometheus 외부 노출 제한(관리 포트 분리)은 별도 처리 예정(묶음 K).
                it.requestMatchers("/actuator/**").permitAll()
                // 공개 카탈로그 조회는 GET만 허용한다. 등록·상태변경(POST)은 아래 매처 또는 기본 폐쇄가 잡는다.
                it.requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/*").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/promotions/*").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/sellers/*").permitAll()

                // ── 관리자(ADMIN) 전용 — 특권 운영 엔드포인트 ──────────────────────────
                it.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")               // 원장/재고/배송/반품/정산배치 admin
                it.requestMatchers("/api/v1/settlements/**").hasRole("ADMIN")          // 정산 계산/확정/이의/지급
                it.requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/sellers/*/approve", "/api/v1/sellers/*/reject",
                    "/api/v1/sellers/*/suspend", "/api/v1/sellers/*/unsuspend", "/api/v1/sellers/*/terminate",
                ).hasRole("ADMIN")
                it.requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/members/*/suspend", "/api/v1/members/*/unsuspend", "/api/v1/members/*/withdraw",
                ).hasRole("ADMIN")
                // 프로모션 생성은 플랫폼 출연 캠페인 → ADMIN 전용(쿠폰 발급 /{id}/coupons는 회원 인증으로 별도).
                it.requestMatchers(HttpMethod.POST, "/api/v1/promotions").hasRole("ADMIN")

                // ── 그 외 전부 인증 필요(기본 폐쇄) ───────────────────────────────────
                // 매처 누락이 "공개"가 아니라 "차단"으로 실패하도록 기본값을 폐쇄한다.
                // 본인 자원(주문/거래/회원/포인트/쿠폰/배송/반품) 소유권은 각 컨트롤러가 SecurityUtils로 강제한다.
                it.anyRequest().authenticated()
            }
            // 미인증 접근 시 403 대신 401 반환.
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            // 필터 체인: 요청 추적(TraceId/MDC) → JWT 인증 순으로 앞단에 배치한다.
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(requestTraceFilter, JwtAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}

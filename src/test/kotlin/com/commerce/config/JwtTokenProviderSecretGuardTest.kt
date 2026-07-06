package com.commerce.config

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/**
 * JWT dev 시크릿 가드 검증.
 * dev 폴백은 local/test/dev 프로파일에서만 허용되고, 그 외(프로파일 미지정 포함)에는 기동을 실패시켜
 * 프로파일 누락 배포가 커밋된 dev 시크릿으로 조용히 기동되는 것을 막는다.
 */
class JwtTokenProviderSecretGuardTest {

    private fun provider(secret: String?, vararg profiles: String) =
        JwtTokenProvider(secret, 86_400_000, MockEnvironment().apply { setActiveProfiles(*profiles) })

    @Test
    fun `throws when dev secret used with no active profile`() {
        shouldThrow<IllegalStateException> { provider(null).validateSecret() }
    }

    @Test
    fun `throws when dev secret used with prod profile`() {
        shouldThrow<IllegalStateException> { provider(null, "prod").validateSecret() }
    }

    @Test
    fun `allows dev secret with local profile`() {
        shouldNotThrowAny { provider(null, "local").validateSecret() }
    }

    @Test
    fun `allows dev secret with test profile`() {
        shouldNotThrowAny { provider(null, "test").validateSecret() }
    }

    @Test
    fun `allows an explicit secret with any profile`() {
        val secret = "a-sufficiently-long-explicit-production-secret-key-256-bits!!"
        shouldNotThrowAny { provider(secret, "prod").validateSecret() }
    }
}

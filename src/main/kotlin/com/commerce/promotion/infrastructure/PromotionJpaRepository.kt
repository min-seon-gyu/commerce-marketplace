package com.commerce.promotion.infrastructure

import com.commerce.promotion.domain.Promotion
import org.springframework.data.jpa.repository.JpaRepository

interface PromotionJpaRepository : JpaRepository<Promotion, Long>

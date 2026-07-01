package com.commerce.seller.infrastructure

import com.commerce.seller.domain.Seller
import org.springframework.data.jpa.repository.JpaRepository

interface SellerJpaRepository : JpaRepository<Seller, Long>

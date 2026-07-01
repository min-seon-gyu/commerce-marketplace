package com.commerce.seller.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.member.application.MemberService
import com.commerce.seller.domain.Seller
import com.commerce.seller.domain.SellerCategory
import com.commerce.seller.domain.event.SellerApprovedEvent
import com.commerce.seller.domain.event.SellerRejectedEvent
import com.commerce.seller.domain.event.SellerTerminatedEvent
import com.commerce.seller.infrastructure.SellerJpaRepository
import com.commerce.region.application.RegionService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class RegisterSellerRequest(
    val name: String,
    val businessNumber: String,
    val category: String,
    val regionId: Long,
    val ownerId: Long,
)

@Service
@Transactional(readOnly = true)
class SellerService(
    private val sellerRepository: SellerJpaRepository,
    private val regionService: RegionService,
    private val memberService: MemberService,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun register(request: RegisterSellerRequest): Seller {
        val region = regionService.getById(request.regionId)
        val owner = memberService.getById(request.ownerId)
        memberService.promoteToSellerOwner(owner.id)

        return sellerRepository.save(
            Seller(
                name = request.name,
                businessNumber = request.businessNumber,
                category = SellerCategory.valueOf(request.category),
                region = region,
                owner = owner,
            )
        )
    }

    @Transactional
    fun approve(sellerId: Long): Seller {
        val seller = getById(sellerId)
        seller.approve()
        eventPublisher.publishEvent(SellerApprovedEvent(seller.id, seller.region.id))
        return seller
    }

    @Transactional
    fun reject(sellerId: Long): Seller {
        val seller = getById(sellerId)
        seller.reject()
        eventPublisher.publishEvent(SellerRejectedEvent(seller.id, seller.region.id))
        return seller
    }

    @Transactional
    fun suspend(sellerId: Long): Seller {
        val seller = getById(sellerId)
        seller.suspend()
        return seller
    }

    @Transactional
    fun unsuspend(sellerId: Long): Seller {
        val seller = getById(sellerId)
        seller.unsuspend()
        return seller
    }

    @Transactional
    fun terminate(sellerId: Long): Seller {
        val seller = getById(sellerId)
        seller.terminate()
        eventPublisher.publishEvent(SellerTerminatedEvent(seller.id, seller.region.id))
        return seller
    }

    fun getById(id: Long): Seller =
        sellerRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
}

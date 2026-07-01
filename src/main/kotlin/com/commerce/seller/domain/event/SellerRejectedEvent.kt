package com.commerce.seller.domain.event

import com.commerce.common.domain.DomainEvent

class SellerRejectedEvent(
    override val aggregateId: Long,
    val regionId: Long,
) : DomainEvent() {
    override val aggregateType = "SELLER"
    override val eventType = "SELLER_REJECTED"
}

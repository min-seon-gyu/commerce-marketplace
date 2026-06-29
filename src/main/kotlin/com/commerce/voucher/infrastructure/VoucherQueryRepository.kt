package com.commerce.voucher.infrastructure

import com.commerce.voucher.domain.QVoucher
import com.commerce.voucher.domain.Voucher
import com.commerce.voucher.domain.VoucherStatus
import com.querydsl.core.types.dsl.BooleanExpression
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository

@Repository
class VoucherQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    private val voucher = QVoucher.voucher

    fun findByConditions(
        memberId: Long? = null,
        regionId: Long? = null,
        status: VoucherStatus? = null,
        pageable: Pageable,
    ): Page<Voucher> {
        val where = listOfNotNull(
            memberId?.let { eqMemberId(it) },
            regionId?.let { eqRegionId(it) },
            status?.let { eqStatus(it) },
        )

        val content = queryFactory.selectFrom(voucher)
            .where(*where.toTypedArray())
            .offset(pageable.offset)
            .limit(pageable.pageSize.toLong())
            .orderBy(voucher.createdAt.desc())
            .fetch()

        val total = queryFactory.select(voucher.count())
            .from(voucher)
            .where(*where.toTypedArray())
            .fetchOne() ?: 0L

        return PageImpl(content, pageable, total)
    }

    private fun eqMemberId(memberId: Long): BooleanExpression = voucher.memberId.eq(memberId)
    private fun eqRegionId(regionId: Long): BooleanExpression = voucher.regionId.eq(regionId)
    private fun eqStatus(status: VoucherStatus): BooleanExpression = voucher.status.eq(status)
}

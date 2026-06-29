package com.commerce.point.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.point.infrastructure.PointAccountJpaRepository
import com.commerce.point.infrastructure.PointTransactionJpaRepository
import com.commerce.point.interfaces.dto.PointBalanceResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class PointQueryService(
    private val pointAccountRepository: PointAccountJpaRepository,
    private val pointTransactionRepository: PointTransactionJpaRepository,
) {

    fun getBalance(memberId: Long): PointBalanceResponse {
        val account = pointAccountRepository.findByMemberId(memberId)
            ?: throw BusinessException(ErrorCode.POINT_ACCOUNT_NOT_FOUND)
        val history = pointTransactionRepository.findByMemberIdOrderByCreatedAtDesc(memberId)
        return PointBalanceResponse.of(account, history)
    }
}

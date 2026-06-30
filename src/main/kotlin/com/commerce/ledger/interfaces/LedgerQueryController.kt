package com.commerce.ledger.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.ledger.application.VerificationResult
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntry
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

data class LedgerEntryResponse(
    val id: Long,
    val account: String,
    val side: String,
    val amount: BigDecimal,
    val transactionId: Long,
    val entryType: String,
    val createdAt: String,
) {
    companion object {
        fun from(e: LedgerEntry) = LedgerEntryResponse(
            id = e.id,
            account = e.account.name,
            side = e.side.name,
            amount = e.amount,
            transactionId = e.transactionId,
            entryType = e.entryType.name,
            createdAt = e.createdAt.toString(),
        )
    }
}

data class AccountBalanceResponse(
    val account: String,
    val netBalance: BigDecimal,
)

data class GlobalBalanceResponse(
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
)

@RestController
@RequestMapping("/api/v1/admin/ledger")
class LedgerQueryController(
    private val ledgerService: LedgerService,
    private val verificationService: LedgerVerificationService,
) {

    @GetMapping("/entries/transaction/{transactionId}")
    fun getByTransaction(@PathVariable transactionId: Long): ApiResponse<List<LedgerEntryResponse>> =
        ApiResponse.ok(ledgerService.getEntriesByTransactionId(transactionId).map { LedgerEntryResponse.from(it) })

    @GetMapping("/balance/{account}")
    fun getAccountBalance(@PathVariable account: String): ApiResponse<AccountBalanceResponse> {
        val accountCode = AccountCode.valueOf(account)
        return ApiResponse.ok(AccountBalanceResponse(
            account = accountCode.name,
            netBalance = ledgerService.netBalanceByAccount(accountCode),
        ))
    }

    @GetMapping("/balance/global")
    fun getGlobalBalance(): ApiResponse<GlobalBalanceResponse> =
        ApiResponse.ok(GlobalBalanceResponse(
            totalDebit = ledgerService.globalDebitTotal(),
            totalCredit = ledgerService.globalCreditTotal(),
        ))

    @PostMapping("/verify")
    fun verify(): ApiResponse<VerificationResult> = ApiResponse.ok(verificationService.verify())
}

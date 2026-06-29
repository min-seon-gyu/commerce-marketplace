package com.commerce.voucher.domain

import org.springframework.stereotype.Component
import java.security.SecureRandom

@Component
class VoucherCodeGenerator {
    private val random = SecureRandom()
    private val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun generate(regionCode: String): String {
        val payload = (1..15).map { chars[random.nextInt(chars.length)] }.joinToString("")
        val checkDigit = calculateLuhnMod36CheckDigit(payload)
        return "$regionCode-$payload$checkDigit"
    }

    fun validate(code: String): Boolean {
        val parts = code.split("-")
        if (parts.size != 2 || parts[1].length != 16) return false
        val payload = parts[1].dropLast(1)
        val expected = calculateLuhnMod36CheckDigit(payload)
        return parts[1].last() == expected
    }

    private fun calculateLuhnMod36CheckDigit(input: String): Char {
        var factor = 2
        var sum = 0
        for (i in input.indices.reversed()) {
            var addend = chars.indexOf(input[i]) * factor
            addend = (addend / 36) + (addend % 36)
            sum += addend
            factor = if (factor == 2) 1 else 2
        }
        val remainder = sum % 36
        return chars[(36 - remainder) % 36]
    }
}

package com.commerce.voucher.domain

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class VoucherCodeGeneratorTest : DescribeSpec({
    val generator = VoucherCodeGenerator()

    describe("generate") {
        it("should produce code in format XX-XXXXXXXXXXXXXXXX") {
            val code = generator.generate("SN")
            code.length shouldBe 19
            code.substring(0, 2) shouldBe "SN"
            code[2] shouldBe '-'
        }

        it("should pass Luhn mod 36 check digit validation") {
            val code = generator.generate("SN")
            generator.validate(code) shouldBe true
        }

        it("should fail validation with tampered code") {
            val code = generator.generate("SN")
            val tampered = code.dropLast(1) + if (code.last() == 'A') 'B' else 'A'
            generator.validate(tampered) shouldBe false
        }

        it("should generate unique codes") {
            val codes = (1..100).map { generator.generate("SN") }.toSet()
            codes.size shouldBe 100
        }
    }
})

package com.commerce

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class VoucherApplication

fun main(args: Array<String>) {
    runApplication<VoucherApplication>(*args)
}

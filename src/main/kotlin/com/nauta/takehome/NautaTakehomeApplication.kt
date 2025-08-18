package com.nauta.takehome

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class NautaTakehomeApplication

fun main(args: Array<String>) {
    runApplication<NautaTakehomeApplication>(args = args)
}

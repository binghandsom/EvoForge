package com.evoforge

import com.evoforge.core.EvoForgeProperties
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableConfigurationProperties(EvoForgeProperties)
@EnableScheduling
class EvoForgeApplication {
    static void main(String[] args) {
        SpringApplication.run(EvoForgeApplication, args)
    }
}

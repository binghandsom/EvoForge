package com.evoforge

import com.evoforge.core.EvoForgeProperties
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.amqp.rabbit.annotation.EnableRabbit
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(exclude = [DataSourceAutoConfiguration, DataSourceTransactionManagerAutoConfiguration])
@EnableConfigurationProperties(EvoForgeProperties)
@EnableRabbit
@EnableScheduling
class EvoForgeApplication {
    static void main(String[] args) {
        SpringApplication.run(EvoForgeApplication, args)
    }
}

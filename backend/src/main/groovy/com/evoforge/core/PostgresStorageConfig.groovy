package com.evoforge.core

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

import javax.sql.DataSource

@Configuration
@ConditionalOnProperty(prefix = 'evoforge.skills', name = 'storageBackend', havingValue = 'postgres', matchIfMissing = true)
class PostgresStorageConfig {
    @Bean(destroyMethod = 'close')
    @FlywayDataSource
    DataSource evoForgeDataSource(EvoForgeProperties properties) {
        HikariDataSource dataSource = new HikariDataSource()
        dataSource.jdbcUrl = properties.database.url
        dataSource.username = properties.database.username
        dataSource.password = properties.database.password
        dataSource.maximumPoolSize = properties.database.maximumPoolSize
        return dataSource
    }

    @Bean
    JdbcTemplate evoForgeJdbcTemplate(DataSource evoForgeDataSource) {
        return new JdbcTemplate(evoForgeDataSource)
    }
}

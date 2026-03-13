package com.evoforge.core

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig implements WebMvcConfigurer {
    @Override
    void addCorsMappings(CorsRegistry registry) {
        registry.addMapping('/api/**')
            .allowedOriginPatterns('*')
            .allowedMethods('GET', 'POST', 'PUT', 'DELETE', 'OPTIONS')
            .allowedHeaders('*')
    }
}

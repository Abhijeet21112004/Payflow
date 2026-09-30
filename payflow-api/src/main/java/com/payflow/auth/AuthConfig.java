package com.payflow.auth;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Decides which URLs each security guard protects. */
@Configuration
public class AuthConfig {

    @Bean
    FilterRegistrationBean<JwtAuthFilter> jwtAuthFilter(JwtService jwtService) {
        var registration = new FilterRegistrationBean<>(new JwtAuthFilter(jwtService));
        registration.addUrlPatterns("/merchants/me", "/merchants/me/*");
        return registration;
    }
}

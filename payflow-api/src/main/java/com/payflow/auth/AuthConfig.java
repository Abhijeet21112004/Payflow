package com.payflow.auth;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.payflow.merchant.MerchantRepository;

/** Decides which URLs each security guard protects. */
@Configuration
public class AuthConfig {

    /** People (the shop owner) managing their account: login token required. */
    @Bean
    FilterRegistrationBean<JwtAuthFilter> jwtAuthFilter(JwtService jwtService) {
        var registration = new FilterRegistrationBean<>(new JwtAuthFilter(jwtService));
        registration.addUrlPatterns("/merchants/me", "/merchants/me/*");
        return registration;
    }

    /** Programs (the shop's server) moving money: API key required. */
    @Bean
    FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(MerchantRepository merchants) {
        var registration = new FilterRegistrationBean<>(new ApiKeyAuthFilter(merchants));
        registration.addUrlPatterns("/payments", "/payments/*");
        return registration;
    }
}

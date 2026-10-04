package com.materialflow.erp.common;

import com.materialflow.pricing.PricingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainBeans {
    @Bean
    PricingService pricingService() {
        return new PricingService();
    }
}


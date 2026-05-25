package com.pfplaybackend.api.operations.config;

import com.pfplaybackend.api.operations.adapter.in.web.MaintenanceModeFilter;
import com.pfplaybackend.api.operations.application.port.out.MaintenanceGate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class MaintenanceModeFilterConfig {

    /**
     * Register MaintenanceModeFilter at HIGHEST_PRECEDENCE so it runs before
     * the Spring Security filter chain (SecurityProperties.DEFAULT_FILTER_ORDER = -100).
     *
     * Bypass logic for /api/v1/admin/** and /actuator/health is inside the filter,
     * not the registration — registration applies to all paths.
     */
    @Bean
    public FilterRegistrationBean<MaintenanceModeFilter> maintenanceModeFilterRegistration(
            MaintenanceGate gate) {
        FilterRegistrationBean<MaintenanceModeFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new MaintenanceModeFilter(gate));
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.setName("maintenanceModeFilter");
        return bean;
    }
}

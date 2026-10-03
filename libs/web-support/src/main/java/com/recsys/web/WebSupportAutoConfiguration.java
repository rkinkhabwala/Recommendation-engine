package com.recsys.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link AdmissionFilter} (load shedding, runs first), {@link AuthFilter} (see {@link
 * SecuritySettings}) and the API error mapping.
 */
@AutoConfiguration(
    afterName =
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration")
@EnableConfigurationProperties({SecuritySettings.class, AdmissionSettings.class})
public class WebSupportAutoConfiguration {

  @Bean
  @ConditionalOnExpression("${recs.admission.max-in-flight:0} > 0")
  FilterRegistrationBean<AdmissionFilter> admissionFilter(
      AdmissionSettings settings, ObjectProvider<MeterRegistry> registry) {
    var bean =
        new FilterRegistrationBean<>(
            new AdmissionFilter(
                settings.maxInFlight(),
                settings.queueTimeout(),
                registry.getIfAvailable(SimpleMeterRegistry::new)));
    bean.addUrlPatterns("/v1/*");
    bean.setOrder(-1); // before auth: shedding must be cheap
    return bean;
  }

  @Bean
  FilterRegistrationBean<AuthFilter> authFilter(SecuritySettings settings) {
    var bean =
        new FilterRegistrationBean<>(
            new AuthFilter(settings.mode(), settings.apiKeys(), settings.jwt()));
    bean.addUrlPatterns("/v1/*");
    bean.setOrder(0);
    return bean;
  }

  @Bean
  ApiErrorHandler apiErrorHandler() {
    return new ApiErrorHandler();
  }
}

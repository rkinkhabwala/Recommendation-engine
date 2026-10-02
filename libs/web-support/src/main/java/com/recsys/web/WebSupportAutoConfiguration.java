package com.recsys.web;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/** Registers {@link ApiKeyFilter} with keys from {@code recs.security.api-keys}. */
@AutoConfiguration
public class WebSupportAutoConfiguration {

  @Bean
  FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(
      @Value("${recs.security.api-keys:}") List<String> keys) {
    var bean =
        new FilterRegistrationBean<>(
            new ApiKeyFilter(keys.stream().filter(k -> !k.isBlank()).toList()));
    bean.addUrlPatterns("/v1/*");
    bean.setOrder(0);
    return bean;
  }

  @Bean
  ApiErrorHandler apiErrorHandler() {
    return new ApiErrorHandler();
  }
}

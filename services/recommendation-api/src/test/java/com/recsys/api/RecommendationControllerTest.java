package com.recsys.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recsys.api.config.ApiProperties;
import com.recsys.api.core.RecommendationService;
import com.recsys.api.web.RecommendationController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RecommendationController.class)
@ImportAutoConfiguration(com.recsys.web.WebSupportAutoConfiguration.class)
@TestPropertySource(properties = "recs.security.api-keys=k1")
class RecommendationControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean RecommendationService service;

  @TestConfiguration
  static class Config {
    @Bean
    ApiProperties props() {
      return TestProps.create();
    }
  }

  @Test
  void domainsNotYetEnabledAreRejected() throws Exception {
    mvc.perform(
            get("/v1/recommendations")
                .param("userId", "u1")
                .param("domain", "book")
                .header("X-Api-Key", "k1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("DOMAIN_NOT_ENABLED"));
  }

  @Test
  void validatesLimitAndContext() throws Exception {
    mvc.perform(
            get("/v1/recommendations")
                .param("userId", "u1")
                .param("domain", "song")
                .param("limit", "500")
                .header("X-Api-Key", "k1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("INVALID_LIMIT"));
    mvc.perform(
            get("/v1/recommendations")
                .param("userId", "u1")
                .param("domain", "song")
                .param("context", "feed")
                .header("X-Api-Key", "k1"))
        .andExpect(jsonPath("$.error").value("INVALID_CONTEXT"));
  }
}

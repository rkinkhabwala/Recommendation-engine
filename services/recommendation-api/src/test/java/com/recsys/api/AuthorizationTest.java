package com.recsys.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recsys.api.config.ApiProperties;
import com.recsys.api.core.RecommendationService;
import com.recsys.api.web.RecommendationController;
import com.recsys.web.RequestPrincipal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** End users may only request their own recommendations (principal set by AuthFilter). */
@WebMvcTest(RecommendationController.class)
@org.springframework.test.context.TestPropertySource(properties = "recs.security.api-keys=")
@ImportAutoConfiguration(com.recsys.web.WebSupportAutoConfiguration.class)
class AuthorizationTest {
  @Autowired MockMvc mvc;
  @MockitoBean RecommendationService service;

  @TestConfiguration
  static class Config {
    @Bean
    ApiProperties props() {
      return TestProps.create();
    }

    @Bean
    com.recsys.api.experiment.Experiments experiments(ApiProperties props) {
      return new com.recsys.api.experiment.Experiments(props.experiments());
    }
  }

  @Test
  void userTokenCannotReadAnotherUsersRecommendations() throws Exception {
    mvc.perform(
            get("/v1/recommendations")
                .param("userId", "u_victim")
                .param("domain", "song")
                .requestAttr(
                    RequestPrincipal.ATTRIBUTE,
                    new RequestPrincipal("u_attacker", false, Set.of())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("USER_MISMATCH"));
  }
}

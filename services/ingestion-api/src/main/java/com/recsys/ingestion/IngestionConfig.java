package com.recsys.ingestion;

import com.recsys.ingestion.ratelimit.UserRateLimiter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IngestionConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  UserRateLimiter userRateLimiter(IngestionProperties props) {
    return new UserRateLimiter(props.userEventsPerSecond(), props.userBurst());
  }
}

package com.recsys.catalog;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
class OutboxConfig {
  @Bean
  OutboxRelay outboxRelay(
      CatalogRepository repository,
      CatalogPublisher publisher,
      PlatformTransactionManager txManager,
      MeterRegistry metrics,
      @Value("${recs.catalog.outbox-batch-size:1000}") int batchSize) {
    return new OutboxRelay(
        repository, publisher, new TransactionTemplate(txManager), batchSize, metrics);
  }
}

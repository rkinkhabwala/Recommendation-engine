package com.recsys.embedding;

import com.recsys.events.AvroTrust;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EmbeddingWorkerApplication {
  public static void main(String[] args) {
    AvroTrust.install();
    SpringApplication.run(EmbeddingWorkerApplication.class, args);
  }
}

package com.recsys.api;

import com.recsys.events.AvroTrust;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RecommendationApiApplication {
  public static void main(String[] args) {
    AvroTrust.install(); // Avro 1.12 only (de)serializes trusted SpecificRecord classes
    SpringApplication.run(RecommendationApiApplication.class, args);
  }
}

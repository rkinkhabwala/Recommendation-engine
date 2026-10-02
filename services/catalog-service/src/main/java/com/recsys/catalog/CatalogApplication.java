package com.recsys.catalog;

import com.recsys.events.AvroTrust;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CatalogApplication {
  public static void main(String[] args) {
    AvroTrust.install(); // Avro 1.12 only (de)serializes trusted SpecificRecord classes
    SpringApplication.run(CatalogApplication.class, args);
  }
}

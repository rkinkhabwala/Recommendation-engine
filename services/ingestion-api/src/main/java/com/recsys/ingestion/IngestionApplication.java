package com.recsys.ingestion;

import com.recsys.events.AvroTrust;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IngestionApplication {
  public static void main(String[] args) {
    AvroTrust.install(); // Avro 1.12 only (de)serializes trusted SpecificRecord classes
    SpringApplication.run(IngestionApplication.class, args);
  }
}

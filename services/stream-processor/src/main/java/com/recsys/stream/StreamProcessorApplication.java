package com.recsys.stream;

import com.recsys.events.AvroTrust;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
@ConfigurationPropertiesScan
public class StreamProcessorApplication {
  public static void main(String[] args) {
    AvroTrust.install(); // Avro 1.12 only (de)serializes trusted SpecificRecord classes
    SpringApplication.run(StreamProcessorApplication.class, args);
  }
}

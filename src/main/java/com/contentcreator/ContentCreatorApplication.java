package com.contentcreator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** ContentCreator: background workers that produce content for ElderEase. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ContentCreatorApplication {
  public static void main(String[] args) {
    SpringApplication.run(ContentCreatorApplication.class, args);
  }
}

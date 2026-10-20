package com.contentcreator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** ContentCreator: background workers that produce content for ElderEase. */
@SpringBootApplication
public class ContentCreatorApplication {
  public static void main(String[] args) {
    SpringApplication.run(ContentCreatorApplication.class, args);
  }
}

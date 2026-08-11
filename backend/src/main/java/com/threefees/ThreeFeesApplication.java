package com.threefees;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ThreeFeesApplication {

  public static void main(String[] args) {
    SpringApplication.run(ThreeFeesApplication.class, args);
  }
}

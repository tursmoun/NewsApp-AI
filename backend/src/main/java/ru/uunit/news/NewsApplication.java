package ru.uunit.news;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class NewsApplication {

  public static void main(String[] args) {
    SpringApplication.run(NewsApplication.class, args);
  }
}

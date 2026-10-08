package br.com.torresoft.intranet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IntranetApplication {
  public static void main(String[] args) { SpringApplication.run(IntranetApplication.class, args); }
}

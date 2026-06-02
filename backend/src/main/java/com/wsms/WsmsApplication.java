package com.wsms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WsmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(WsmsApplication.class, args);
        System.out.println("=================================================");
        System.out.println("  Web Sites Monitoring System (WSMS) is online!  ");
        System.out.println("  Access Dashboard: http://localhost:8080/       ");
        System.out.println("=================================================");
    }
}

package com.devlabs.aulaflix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.devlabs.aulaflix.command.AdminMode;

@SpringBootApplication
public class AulaflixApiApplication {

    public static void main(String[] args) {
        if (AdminMode.isRequested(args)) {
            System.exit(AdminMode.run(args));
        }
        SpringApplication.run(AulaflixApiApplication.class, args);
    }

}

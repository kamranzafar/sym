package org.kamranzafar.sym;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Sym (Speak Your Mind): post short messages, read them back, and follow them on a Kafka stream. */
@SpringBootApplication
public class SymApplication {

    public static void main(String[] args) {
        SpringApplication.run(SymApplication.class, args);
    }
}

package com.rlibanez.eplsync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EplsyncApplication {

	public static void main(String[] args) {
		if (args.length > 0 && args[0].equals("--admin")) {
            com.rlibanez.eplsync.security.AdminCommand.run(args);
            return;
        }
        SpringApplication.run(EplsyncApplication.class, args);
	}

}

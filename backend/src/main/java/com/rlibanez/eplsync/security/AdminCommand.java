package com.rlibanez.eplsync.security;

import com.rlibanez.eplsync.EplsyncApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** Host access is the trust boundary. Passwords are never command arguments or logger messages. */
public final class AdminCommand {
    private AdminCommand() {}
    public static void run(String[] args) {
        if (args.length != 3 || !args[1].equals("reset"))
            throw new IllegalArgumentException("Uso: --admin reset USER_OR_EMAIL");
        var app=new SpringApplication(EplsyncApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try(var context=app.run("--eplsync.torrent.enabled=false","--eplsync.torrent.bulk.worker-enabled=false","--spring.main.banner-mode=off","--logging.level.root=ERROR")) {
            var accounts=context.getBean(AccountStore.class);
            var result=accounts.recover(args[2],true);
            System.out.println("Usuario: "+result.user().username());
            System.out.println("Contraseña temporal: "+result.password());
            System.out.println("Caduca (UTC): "+result.expiresAt());
            System.out.println("Debe cambiarse en el primer acceso. La contraseña anterior y sus sesiones quedan invalidadas.");
        }
    }
}

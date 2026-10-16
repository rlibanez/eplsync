package com.rlibanez.eplsync.settings;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.rlibanez.eplsync.security.SessionAccess;

@RestController
@RequestMapping("/api/auth/telegram")
@PreAuthorize("isAuthenticated()")
public class TelegramController {
    private final TelegramNotifications telegram;
    private final SessionAccess sessions;
    public TelegramController(TelegramNotifications telegram,SessionAccess sessions) {this.telegram=telegram;this.sessions=sessions;}
    @GetMapping public TelegramNotifications.View get() {return telegram.view(sessions.current().id());}
    @PutMapping public TelegramNotifications.View save(@RequestBody TelegramNotifications.Input input) {return telegram.save(sessions.current().id(),input);}
    @PostMapping("/test") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void test(@RequestBody(required=false) TelegramNotifications.TestInput input) {telegram.test(sessions.current().id(),input);}
}

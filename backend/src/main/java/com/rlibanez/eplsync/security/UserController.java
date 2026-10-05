package com.rlibanez.eplsync.security;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/security")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
    private final AccountStore accounts; private final SessionAccess sessions;
    public UserController(AccountStore accounts,SessionAccess sessions) {this.accounts=accounts;this.sessions=sessions;}
    @GetMapping("/users") public List<AccountStore.UserView> users() {return accounts.users();}
    @GetMapping("/permissions") public Permission[] permissions() {return Permission.values();}
    public record Create(@NotBlank String username,@NotBlank @Email String email,@NotNull String role) {}
    @PostMapping("/users") public AccountStore.Temporary create(@Valid @RequestBody Create input) {return accounts.create(input.username(),input.email(),input.role(),sessions.current().id());}
    public record Update(@NotNull String role,@NotNull String status,@NotNull Map<@NotBlank String,@NotBlank String> overrides) {}
    @PutMapping("/users/{id}") public Map<String,Boolean> update(@PathVariable String id,@Valid @RequestBody Update input) {
        accounts.update(id,input.role(),input.status(),input.overrides(),sessions.current().id());return Map.of("success",true);
    }
    @PostMapping("/users/{id}/approve") public Map<String,Boolean> approve(@PathVariable String id) {
        accounts.approve(id,sessions.current().id());return Map.of("success",true);
    }
    @PostMapping("/users/{id}/password") public AccountStore.Temporary password(@PathVariable String id) {
        var user=accounts.find(id);if(user==null) throw new IllegalArgumentException("Usuario inexistente");return accounts.recover(user.username(),false);
    }
    public record Delete(@jakarta.validation.constraints.AssertTrue boolean confirm) {}
    @DeleteMapping("/users/{id}") public Map<String,Boolean> delete(@PathVariable String id,@Valid @RequestBody Delete input) {
        accounts.delete(id);return Map.of("success",true);
    }
    @GetMapping("/policy") public AccountStore.Policy policy() {return accounts.policy();}
    @PutMapping("/policy") public AccountStore.Policy policy(@RequestBody AccountStore.Policy input) {accounts.policy(input);return accounts.policy();}
}

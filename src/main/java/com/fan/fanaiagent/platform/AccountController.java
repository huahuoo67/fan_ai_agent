package com.fan.fanaiagent.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.fan.fanaiagent.platform.PlatformTypes.*;

@RestController
public class AccountController {
    private final AccountService accounts; private final RateLimiter rates; private final PlatformStore store;
    public AccountController(AccountService accounts,RateLimiter rates,PlatformStore store) {this.accounts=accounts;this.rates=rates;this.store=store;}
    @GetMapping("/auth/csrf") public Map<String,String> csrf(CsrfToken token) {
        return Map.of("token",token.getToken(),"headerName",token.getHeaderName());
    }
    @PostMapping("/auth/register") public Account register(@Valid @RequestBody Register body,HttpServletRequest request) {
        rates.check("register:"+request.getRemoteAddr(),5);return accounts.register(body);
    }
    @PostMapping("/auth/login") public Account login(@Valid @RequestBody Login body,HttpServletRequest request) {
        rates.check("login:"+request.getRemoteAddr(),10);
        var user=accounts.login(body);var session=request.getSession();request.changeSessionId();
        session.setAttribute(PlatformSecurity.USER_ID,Long.parseLong(user.id()));return user;
    }
    @GetMapping("/auth/current") public Account current() {return accounts.get(PlatformSecurity.userId());}
    @PostMapping("/auth/logout") public Map<String,Boolean> logout(HttpServletRequest request) {
        long id=PlatformSecurity.userId();var session=request.getSession(false);if(session!=null) session.invalidate();
        store.cancel(id);return Map.of("success",true);
    }
    @PatchMapping("/user/profile") public Account profile(@Valid @RequestBody Profile body) {return accounts.update(PlatformSecurity.userId(),body);}
    @GetMapping("/admin/users") public List<Account> users() {return accounts.list();}
    @PatchMapping("/admin/users/{id}") public Map<String,Boolean> enable(@PathVariable long id,@RequestBody Map<String,Boolean> body) {
        accounts.enable(PlatformSecurity.userId(),id,Boolean.TRUE.equals(body.get("enabled")));return Map.of("success",true);
    }
}

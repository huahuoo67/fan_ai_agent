package com.fan.fanaiagent.platform;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Configuration
public class PlatformSecurity {
    public static final String USER_ID="PLATFORM_USER_ID";
    @Bean org.springframework.security.core.userdetails.UserDetailsService userDetailsService() {
        return username->{throw new org.springframework.security.core.userdetails.UsernameNotFoundException("使用平台会话登录");};
    }
    @Bean SecurityFilterChain security(HttpSecurity http,AccountService accounts,
            @org.springframework.beans.factory.annotation.Value("${server.servlet.session.cookie.secure:false}") boolean secure) throws Exception {
        var csrf=new CookieCsrfTokenRepository();
        csrf.setCookieCustomizer(cookie->cookie.secure(secure).httpOnly(true).sameSite("Lax"));
        http.csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .securityContext(s->s.requireExplicitSave(true).securityContextRepository(new org.springframework.security.web.context.NullSecurityContextRepository()))
            .sessionManagement(s->s.requireExplicitAuthenticationStrategy(true))
            .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
            .authorizeHttpRequests(a->a.requestMatchers("/auth/csrf","/auth/login","/auth/register","/health").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN").anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->error(res,401,"请先登录"))
                .accessDeniedHandler((req,res,ex)->error(res,403,"权限或请求校验未通过，请刷新后重试")))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected boolean shouldNotFilterAsyncDispatch() {return false;}
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                    try {
                        SecurityContextHolder.clearContext();
                        var session=req.getSession(false);
                        if(session!=null && session.getAttribute(USER_ID) instanceof Long id) {
                            try {
                                var account=accounts.get(id);
                                if(account.enabled()) SecurityContextHolder.getContext().setAuthentication(
                                    new UsernamePasswordAuthenticationToken(account,null,List.of(new SimpleGrantedAuthority("ROLE_"+account.role()))));
                                else session.invalidate();
                            } catch(org.springframework.web.server.ResponseStatusException e) {session.invalidate();}
                        }
                        chain.doFilter(req,res);
                    } finally {SecurityContextHolder.clearContext();}
                }
            },AnonymousAuthenticationFilter.class);
        return http.build();
    }
    static void error(HttpServletResponse res,int code,String message) throws IOException {
        res.setStatus(code);res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":"+code+",\"message\":\""+message+"\"}");
    }
    public static long userId() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !(auth.getPrincipal() instanceof PlatformTypes.Account a))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"请先登录");
        return Long.parseLong(a.id());
    }
}

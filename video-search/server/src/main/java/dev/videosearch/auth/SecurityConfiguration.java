package dev.videosearch.auth;

import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.context.*;

/** Adapted from the existing CourseSeek Session/CSRF configuration. */
@Configuration
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  SecurityContextRepository contextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  UserDetailsService users(JdbcTemplate db) {
    return name ->
        db
            .query(
                "SELECT username,password_hash FROM vs_user WHERE username=?",
                (r, n) ->
                    User.withUsername(r.getString(1))
                        .password(r.getString(2))
                        .roles("USER")
                        .build(),
                name)
            .stream()
            .findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, SecurityContextRepository repository)
      throws Exception {
    return http.authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC)
                    .permitAll()
                    .requestMatchers(
                        "/",
                        "/health",
                        "/index.html",
                        "/assets/**",
                        "/favicon.ico",
                        "/favicon.svg",
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/login")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .securityContext(c -> c.securityContextRepository(repository))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, r, x) -> {
                          r.setStatus(401);
                          r.setContentType("application/json;charset=UTF-8");
                          r.getWriter()
                              .write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录\"}");
                        })
                    .accessDeniedHandler(
                        (q, r, x) -> {
                          r.setStatus(403);
                          r.setContentType("application/json;charset=UTF-8");
                          r.getWriter()
                              .write("{\"code\":\"FORBIDDEN\",\"message\":\"请求已过期或无权访问\"}");
                        }))
        .logout(
            l ->
                l.logoutUrl("/api/v1/auth/logout")
                    .logoutSuccessHandler((q, r, a) -> r.setStatus(204)))
        .build();
  }
}

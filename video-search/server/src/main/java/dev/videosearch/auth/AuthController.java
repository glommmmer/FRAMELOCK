package dev.videosearch.auth;

import dev.videosearch.api.ApiError;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final UserDetailsService users;
  private final PasswordEncoder passwords;
  private final SecurityContextRepository repository;
  private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

  public AuthController(
      UserDetailsService users, PasswordEncoder passwords, SecurityContextRepository repository) {
    this.users = users;
    this.passwords = passwords;
    this.repository = repository;
  }

  @GetMapping("/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  public record Login(
      @NotBlank @Size(max = 80) String username, @NotBlank @Size(max = 200) String password) {}

  @PostMapping("/login")
  Map<String, String> login(
      @Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
    long now = System.currentTimeMillis();
    if (attempts.size() > 10000)
      attempts
          .entrySet()
          .removeIf(
              e -> {
                synchronized (e.getValue()) {
                  return e.getValue().isEmpty() || e.getValue().peekLast() < now - 60000;
                }
              });
    var queue = attempts.computeIfAbsent(request.getRemoteAddr(), x -> new ArrayDeque<>());
    synchronized (queue) {
      while (!queue.isEmpty() && queue.peekFirst() < now - 60000) queue.removeFirst();
      if (queue.size() >= 10)
        throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "登录尝试过多");
      queue.addLast(now);
    }
    try {
      var user = users.loadUserByUsername(body.username().trim());
      if (!passwords.matches(body.password(), user.getPassword()))
        throw new IllegalArgumentException();
      request.getSession();
      request.changeSessionId();
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
      SecurityContextHolder.setContext(context);
      repository.saveContext(context, request, response);
      return Map.of("username", user.getUsername());
    } catch (UsernameNotFoundException | IllegalArgumentException e) {
      throw new ApiError(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "账号或密码不正确");
    }
  }

  @GetMapping("/me")
  Map<String, String> me(Principal user) {
    return Map.of("username", user.getName());
  }

  @Bean
  ApplicationRunner seed(
      JdbcTemplate db,
      @Value("${app.admin-user:demo}") String username,
      @Value("${app.admin-password:}") String password) {
    return args -> {
      if (!password.isBlank()
          && db.queryForObject(
                  "SELECT COUNT(*) FROM vs_user WHERE username=?", Long.class, username)
              == 0)
        db.update(
            "INSERT INTO vs_user(username,password_hash) VALUES(?,?)",
            username,
            passwords.encode(password));
    };
  }
}

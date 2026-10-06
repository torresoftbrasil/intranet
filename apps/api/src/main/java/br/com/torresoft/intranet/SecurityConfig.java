package br.com.torresoft.intranet;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Configuration
public class SecurityConfig {
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

  @Bean UserDetailsService users(JdbcTemplate jdbc) {
    return login -> jdbc.query("SELECT login, senhahash, ativo FROM usuario WHERE login = ?",
      (rs, row) -> User.withUsername(rs.getString("login"))
        .password(rs.getString("senhahash")).disabled(!rs.getBoolean("ativo"))
        .roles("USER").build(), login).stream().findFirst()
      .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado"));
  }

  @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http
      .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
      .authorizeHttpRequests(auth -> auth
        .requestMatchers("/actuator/health", "/api/csrf", "/api/login").permitAll()
        .anyRequest().authenticated())
      .formLogin(form -> form.loginProcessingUrl("/api/login")
        .successHandler((request, response, authentication) -> response.setStatus(204))
        .failureHandler((request, response, exception) -> response.sendError(401, "Credenciais inválidas")))
      .logout(logout -> logout.logoutUrl("/api/logout")
        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
      .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, exception) -> response.sendError(401)))
      .build();
  }

  @RestController
  static class SessionResource {
    private final JdbcTemplate jdbc;
    SessionResource(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping("/api/csrf") Map<String, String> csrf(org.springframework.security.web.csrf.CsrfToken token) {
      return Map.of("token", token.getToken());
    }

    @GetMapping("/api/me") Map<String, Object> me(java.security.Principal principal) {
      return jdbc.queryForMap("SELECT id, login, nome FROM usuario WHERE login = ?", principal.getName());
    }
  }
}

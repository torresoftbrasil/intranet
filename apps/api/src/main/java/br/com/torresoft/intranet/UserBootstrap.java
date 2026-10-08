package br.com.torresoft.intranet;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class UserBootstrap implements ApplicationRunner {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder encoder;
  @Value("${app.bootstrap.arthur-password:}") private String arthurPassword;
  @Value("${app.bootstrap.felipe-password:}") private String felipePassword;

  UserBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder) { this.jdbc = jdbc; this.encoder = encoder; }

  @Override public void run(ApplicationArguments args) {
    createIfMissing("arthur", "Arthur", arthurPassword);
    createIfMissing("felipe", "Felipe", felipePassword);
    createIfMissing("zyven", "Zyven", UUID.randomUUID().toString() + UUID.randomUUID());
  }

  private void createIfMissing(String login, String name, String password) {
    Integer count = jdbc.queryForObject("SELECT count(*) FROM usuario WHERE login = ?", Integer.class, login);
    if (count != null && count > 0) return;
    if (password == null || password.length() < 12) throw new IllegalStateException(
      "Configure uma senha inicial de pelo menos 12 caracteres para " + login);
    jdbc.update("INSERT INTO usuario(login, nome, senhahash) VALUES (?, ?, ?)", login, name, encoder.encode(password));
  }
}

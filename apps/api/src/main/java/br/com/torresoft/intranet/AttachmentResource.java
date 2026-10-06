package br.com.torresoft.intranet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/demandas/{demandaId}/anexos")
public class AttachmentResource {
  private final JdbcTemplate jdbc;
  private final Path root;
  AttachmentResource(JdbcTemplate jdbc, @Value("${app.uploads-dir}") String uploadsDir) throws IOException {
    this.jdbc = jdbc;
    this.root = Path.of(uploadsDir).toAbsolutePath().normalize();
    Files.createDirectories(root);
  }

  private void demandExists(long id) {
    if (jdbc.queryForObject("SELECT count(*) FROM demanda WHERE id = ?", Integer.class, id) == 0)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demanda não encontrada");
  }

  @GetMapping List<Map<String,Object>> list(@PathVariable long demandaId) {
    demandExists(demandaId);
    return jdbc.queryForList("SELECT id, nome, tipo, tamanho, enviado_em AS \"enviadoEm\" FROM anexo WHERE demanda_id = ? ORDER BY id", demandaId);
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
  Map<String,Object> upload(@PathVariable long demandaId, @RequestPart("arquivo") MultipartFile file) throws IOException {
    demandExists(demandaId);
    if (file.isEmpty() || file.getSize() > 10_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Imagem deve ter até 10 MB");
    byte[] bytes = file.getBytes();
    String contentType = switch (bytes.length >= 4 ? (bytes[0] & 0xff) : -1) {
      case 0x89 -> bytes.length > 8 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47 ? "image/png" : null;
      case 0xff -> bytes.length > 3 && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff ? "image/jpeg" : null;
      default -> null;
    };
    if (contentType == null || ImageIO.read(new java.io.ByteArrayInputStream(bytes)) == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie uma imagem PNG ou JPEG válida");
    String fileName = UUID.randomUUID() + (contentType.equals("image/png") ? ".png" : ".jpg");
    Path destination = root.resolve(fileName);
    Files.write(destination, bytes, StandardOpenOption.CREATE_NEW);
    try {
      Long id = jdbc.queryForObject("INSERT INTO anexo(demanda_id, nome, tipo, tamanho, caminho) VALUES (?, ?, ?, ?, ?) RETURNING id",
        Long.class, demandaId, file.getOriginalFilename() == null ? "imagem" : Path.of(file.getOriginalFilename()).getFileName().toString().substring(0, Math.min(255, Path.of(file.getOriginalFilename()).getFileName().toString().length())), contentType, bytes.length, fileName);
      return Map.of("id", id, "nome", file.getOriginalFilename() == null ? "imagem" : file.getOriginalFilename(), "url", "/api/demandas/" + demandaId + "/anexos/" + id + "/arquivo");
    } catch (RuntimeException e) { Files.deleteIfExists(destination); throw e; }
  }

  @GetMapping("/{id}/arquivo") ResponseEntity<FileSystemResource> file(@PathVariable long demandaId, @PathVariable long id) {
    Map<String,Object> attachment = jdbc.query("SELECT tipo, caminho FROM anexo WHERE id = ? AND demanda_id = ?",
      (rs, row) -> Map.<String,Object>of("tipo", rs.getString("tipo"), "caminho", rs.getString("caminho")), id, demandaId)
      .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    Path path = root.resolve((String) attachment.get("caminho")).normalize();
    if (!path.startsWith(root) || !Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType((String) attachment.get("tipo")))
      .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
      .header("X-Content-Type-Options", "nosniff")
      .body(new FileSystemResource(path));
  }

  @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable long demandaId, @PathVariable long id) throws IOException {
    String name = jdbc.query("SELECT caminho FROM anexo WHERE id = ? AND demanda_id = ?", (rs, row) -> rs.getString(1), id, demandaId)
      .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    jdbc.update("DELETE FROM anexo WHERE id = ? AND demanda_id = ?", id, demandaId);
    Files.deleteIfExists(root.resolve(name));
  }
}

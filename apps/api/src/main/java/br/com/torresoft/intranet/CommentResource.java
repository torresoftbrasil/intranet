package br.com.torresoft.intranet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/demandas/{demandaId}/comentarios")
public class CommentResource {
  private final JdbcTemplate jdbc;
  private final Path root;

  CommentResource(JdbcTemplate jdbc, @Value("${app.uploads-dir}") String uploadsDir) throws IOException {
    this.jdbc = jdbc;
    this.root = Path.of(uploadsDir).toAbsolutePath().normalize();
    Files.createDirectories(root);
  }

  private void demandExists(long id) {
    if (jdbc.queryForObject("SELECT count(*) FROM demanda WHERE id = ?", Integer.class, id) == 0)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demanda não encontrada");
  }

  private Map<String,Object> comment(long demandId, long id) {
    var comments = jdbc.query("""
      SELECT c.id, c.texto, u.nome AS autor, c.criado_em AS "criadoEm"
      FROM comentario c JOIN usuario u ON u.id = c.autor_id
      WHERE c.demanda_id = ? AND c.id = ?
      """, (rs, row) -> Map.<String,Object>of(
        "id", rs.getLong("id"), "texto", rs.getString("texto"), "autor", rs.getString("autor"),
        "criadoEm", rs.getObject("criadoEm", OffsetDateTime.class).toString(),
        "imagens", jdbc.query("""
          SELECT id, nome FROM comentario_imagem WHERE comentario_id = ? ORDER BY id
          """, (image, imageRow) -> Map.<String,Object>of(
            "id", image.getLong("id"), "nome", image.getString("nome"),
            "url", "/api/demandas/" + demandId + "/comentarios/" + id + "/imagens/" + image.getLong("id")), id)
      ), demandId, id);
    return comments.stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  @GetMapping List<Map<String,Object>> list(@PathVariable long demandaId) {
    demandExists(demandaId);
    List<Long> ids = jdbc.queryForList("""
      SELECT id FROM comentario WHERE demanda_id = ? ORDER BY criado_em DESC, id DESC
      """, Long.class, demandaId);
    return ids.stream().map(id -> comment(demandaId, id)).toList();
  }

  private record ImageData(String name, String type, byte[] bytes) {}

  private ImageData validate(MultipartFile file) throws IOException {
    if (file.isEmpty() || file.getSize() > 10_000_000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Imagem deve ter até 10 MB");
    byte[] bytes = file.getBytes();
    String type = bytes.length > 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
      ? "image/png" : bytes.length > 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff
      ? "image/jpeg" : null;
    if (type == null || ImageIO.read(new ByteArrayInputStream(bytes)) == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie uma imagem PNG ou JPEG válida");
    String original = file.getOriginalFilename();
    String name = original == null ? "imagem" : Path.of(original.replace('\\', '/')).getFileName().toString();
    return new ImageData(name.substring(0, Math.min(255, name.length())), type, bytes);
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional(rollbackFor = Exception.class)
  Map<String,Object> create(@PathVariable long demandaId, @RequestParam(required = false) String texto,
      @RequestPart(value = "imagem", required = false) List<MultipartFile> imagens, Principal principal) throws IOException {
    demandExists(demandaId);
    String message = texto == null ? "" : texto.trim();
    List<MultipartFile> files = imagens == null ? List.of() : imagens;
    if (message.isEmpty() && files.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escreva ou cole algo no comentário");
    if (message.length() > 5000 || files.size() > 5) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comentário excede o limite");
    List<ImageData> validated = new ArrayList<>();
    for (MultipartFile file : files) validated.add(validate(file));
    Long id = jdbc.queryForObject("""
      INSERT INTO comentario(demanda_id, autor_id, texto)
      VALUES (?, (SELECT id FROM usuario WHERE login = ?), ?) RETURNING id
      """, Long.class, demandaId, principal.getName(), message);
    List<Path> written = new ArrayList<>();
    try {
      for (ImageData image : validated) {
        String name = UUID.randomUUID() + (image.type().equals("image/png") ? ".png" : ".jpg");
        Path path = root.resolve(name);
        Files.write(path, image.bytes(), StandardOpenOption.CREATE_NEW);
        written.add(path);
        jdbc.update("""
          INSERT INTO comentario_imagem(comentario_id, nome, tipo, tamanho, caminho) VALUES (?, ?, ?, ?, ?)
          """, id, image.name(), image.type(), image.bytes().length, name);
      }
      return comment(demandaId, id);
    } catch (Exception error) {
      for (Path path : written) Files.deleteIfExists(path);
      throw error;
    }
  }

  @GetMapping("/{comentarioId}/imagens/{imagemId}")
  ResponseEntity<FileSystemResource> image(@PathVariable long demandaId, @PathVariable long comentarioId,
      @PathVariable long imagemId) {
    var found = jdbc.query("""
      SELECT i.tipo, i.caminho FROM comentario_imagem i
      JOIN comentario c ON c.id = i.comentario_id
      WHERE i.id = ? AND i.comentario_id = ? AND c.demanda_id = ?
      """, (rs, row) -> Map.<String,String>of("tipo", rs.getString("tipo"), "caminho", rs.getString("caminho")),
      imagemId, comentarioId, demandaId).stream().findFirst()
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    Path path = root.resolve(found.get("caminho")).normalize();
    if (!path.startsWith(root) || !Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(found.get("tipo")))
      .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
      .header("X-Content-Type-Options", "nosniff")
      .body(new FileSystemResource(path));
  }
}

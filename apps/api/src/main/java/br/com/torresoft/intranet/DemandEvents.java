package br.com.torresoft.intranet;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class DemandEvents {
  record Change(String kind, List<Long> ids, String actor) {}

  private final Map<SseEmitter, HttpSession> listeners = new ConcurrentHashMap<>();

  @GetMapping(path = "/api/demandas/eventos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  ResponseEntity<SseEmitter> listen(HttpSession session) {
    SseEmitter emitter = new SseEmitter(0L);
    listeners.put(emitter, session);
    emitter.onCompletion(() -> listeners.remove(emitter));
    emitter.onTimeout(() -> listeners.remove(emitter));
    emitter.onError(error -> listeners.remove(emitter));
    try {
      emitter.send(SseEmitter.event().name("connected").data("ok"));
    } catch (IOException | IllegalStateException error) {
      listeners.remove(emitter);
      emitter.complete();
    }
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noCache())
      .header("X-Accel-Buffering", "no")
      .body(emitter);
  }

  void publishAfterCommit(String kind, List<Long> ids, String actor) {
    if (!TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Eventos de demanda exigem transação ativa");
    Change change = new Change(kind, List.copyOf(ids), actor);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() { broadcast(change); }
    });
  }

  private void broadcast(Change change) {
    for (var listener : listeners.entrySet()) {
      SseEmitter emitter = listener.getKey();
      if (!sessionActive(listener.getValue())) { listeners.remove(emitter); emitter.complete(); continue; }
      try { emitter.send(SseEmitter.event().name("demand").data(change)); }
      catch (IOException | IllegalStateException error) { listeners.remove(emitter); }
    }
  }

  private boolean sessionActive(HttpSession session) {
    try { session.getCreationTime(); return true; }
    catch (IllegalStateException expired) { return false; }
  }

  @Scheduled(fixedDelay = 20_000)
  void heartbeat() {
    for (var listener : listeners.entrySet()) {
      SseEmitter emitter = listener.getKey();
      if (!sessionActive(listener.getValue())) { listeners.remove(emitter); emitter.complete(); continue; }
      try { emitter.send(SseEmitter.event().comment("keep-alive")); }
      catch (IOException | IllegalStateException error) { listeners.remove(emitter); }
    }
  }
}

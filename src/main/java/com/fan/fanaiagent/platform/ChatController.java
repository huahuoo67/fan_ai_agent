package com.fan.fanaiagent.platform;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.*;
import org.springframework.core.io.FileSystemResource;
import java.util.*;
import static com.fan.fanaiagent.platform.PlatformTypes.*;

@RestController
public class ChatController {
    private final PlatformStore store;private final ChatOrchestrator chat;private final MemoryService memories;private final ArtifactService files;
    public ChatController(PlatformStore store,ChatOrchestrator chat,MemoryService memories,ArtifactService files) {this.store=store;this.chat=chat;this.memories=memories;this.files=files;}
    @PostMapping("/chat/conversations") public Map<String,Object> create() {return store.create(PlatformSecurity.userId());}
    @GetMapping("/chat/conversations") public List<Map<String,Object>> conversations() {return store.conversations(PlatformSecurity.userId());}
    @GetMapping("/chat/conversations/{id}/messages") public List<Map<String,Object>> messages(@PathVariable UUID id) {return store.messages(PlatformSecurity.userId(),id);}
    @DeleteMapping("/chat/conversations/{id}") public void delete(@PathVariable UUID id) {store.delete(PlatformSecurity.userId(),id);}
    @PostMapping(value="/chat/conversations/{id}/turns",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter turn(@PathVariable UUID id,@Valid @RequestBody TurnRequest body,HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-cache, no-transform");response.setHeader("X-Accel-Buffering","no");
        return chat.start(PlatformSecurity.userId(),id,body,request.getSession(false).getId());
    }
    @GetMapping("/chat/runs/{id}") public Map<String,Object> run(@PathVariable UUID id) {return store.run(PlatformSecurity.userId(),id);}
    @PostMapping("/chat/runs/{id}/stop") public void stop(@PathVariable UUID id) {store.stop(PlatformSecurity.userId(),id);}
    @PostMapping("/chat/conversations/{id}/context-grants") public Map<String,UUID> grant(@PathVariable UUID id,@RequestBody Map<String,String> body) {
        return Map.of("id",store.grant(PlatformSecurity.userId(),id,Long.parseLong(body.get("messageId"))));
    }
    @GetMapping("/memories") public List<MemoryFact> memories() {return memories.list(PlatformSecurity.userId());}
    public record MemoryChange(int version,String value,String scope) {}
    @PostMapping("/memory-candidates/{id}/confirm") public MemoryFact confirm(@PathVariable UUID id,@RequestBody MemoryChange body) {return memories.confirm(PlatformSecurity.userId(),id,body.version());}
    @PatchMapping("/memories/{id}") public MemoryFact edit(@PathVariable UUID id,@RequestBody MemoryChange body) {return memories.edit(PlatformSecurity.userId(),id,body.version(),body.value(),body.scope());}
    @DeleteMapping("/memories/{id}") public void revoke(@PathVariable UUID id,@RequestParam int version) {memories.revoke(PlatformSecurity.userId(),id,version);}
    @GetMapping("/artifacts") public List<Map<String,Object>> files() {return files.list(PlatformSecurity.userId());}
    @DeleteMapping("/artifacts/{id}") public void deleteFile(@PathVariable UUID id) {files.delete(PlatformSecurity.userId(),id);}
    @GetMapping("/artifacts/{id}/download") public ResponseEntity<FileSystemResource> download(@PathVariable UUID id) throws Exception {
        long user=PlatformSecurity.userId();var item=files.owned(user,id);var path=files.download(user,id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(item.get("name").toString(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").contentType(MediaType.APPLICATION_OCTET_STREAM).body(new FileSystemResource(path));
    }
}

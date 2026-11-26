package dev.videosearch.upload;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {
  private final UploadService service;

  public UploadController(UploadService service) {
    this.service = service;
  }

  @PostMapping
  Object initialize(Principal user, @RequestBody UploadService.Initialize request) {
    return service.initialize(user.getName(), request);
  }

  @GetMapping("/{id}")
  Object state(Principal user, @PathVariable String id) {
    return service.state(user.getName(), id);
  }

  @PutMapping("/{id}/parts/{partNo}")
  Object part(
      Principal user,
      @PathVariable String id,
      @PathVariable int partNo,
      @RequestHeader(value = "X-Part-SHA256", required = false) String hash,
      HttpServletRequest request)
      throws Exception {
    return service.part(user.getName(), id, partNo, request.getInputStream(), hash);
  }

  @PostMapping("/{id}/complete")
  ResponseEntity<?> complete(Principal user, @PathVariable String id) throws Exception {
    return ResponseEntity.accepted().body(service.complete(user.getName(), id));
  }
}

package ru.uunit.news;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai-review/settings")
public class AiReviewController {

  private final Accounts accounts;
  private final AiReviewService ai;

  public AiReviewController(Accounts accounts, AiReviewService ai) {
    this.accounts = accounts;
    this.ai = ai;
  }

  private void admin(Authentication auth) {
    if (!accounts.admin(accounts.require(auth))) throw ApiException.forbidden();
  }

  @GetMapping
  public Map<String, Object> get(Authentication auth) {
    admin(auth);
    return ai.settings();
  }

  @PutMapping
  public Map<String, Object> save(
    @RequestBody AiReviewService.Settings input,
    Authentication auth
  ) {
    admin(auth);
    return ai.save(input);
  }

  @PostMapping("/retry")
  public Map<String, Object> retry(Authentication auth) {
    admin(auth);
    ai.retryErrors();
    return Map.of("queued", true);
  }
}

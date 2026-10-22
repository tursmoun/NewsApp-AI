package ru.uunit.news;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cms")
public class CmsController {

  private final Accounts accounts;
  private final CmsService cms;

  public CmsController(Accounts accounts, CmsService cms) {
    this.accounts = accounts;
    this.cms = cms;
  }

  @GetMapping("/summary")
  Map<String, Long> summary(Authentication auth) {
    return cms.summary(accounts.require(auth));
  }

  @GetMapping("/{resource}")
  CmsService.Page list(
    @PathVariable String resource,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "25") int perPage,
    @RequestParam(defaultValue = "") String q,
    @RequestParam(defaultValue = "") String status,
    @RequestParam(defaultValue = "") String sort,
    @RequestParam(defaultValue = "DESC") String order,
    @RequestParam(defaultValue = "") String aiGroup,
    Authentication auth
  ) {
    return cms.list(
      resource,
      accounts.require(auth),
      new CmsService.Query(page, perPage, q, status, sort, order, aiGroup)
    );
  }

  @GetMapping("/{resource}/{id}")
  Map<String, Object> detail(
    @PathVariable String resource,
    @PathVariable UUID id,
    Authentication auth
  ) {
    return cms.detail(resource, id, accounts.require(auth));
  }

  @PatchMapping("/news/{id}")
  Map<String, Object> editNews(
    @PathVariable UUID id,
    @RequestBody NewsService.Input input,
    Authentication auth
  ) {
    return cms.editNews(id, accounts.require(auth), input);
  }

  record Visibility(Boolean hidden) {}

  @PostMapping("/news/{id}/visibility")
  Map<String, Object> visibility(
    @PathVariable UUID id,
    @RequestBody Visibility input,
    Authentication auth
  ) {
    if (input.hidden() == null) throw ApiException.bad("Укажите, нужно ли скрыть новость");
    return cms.visibility(id, accounts.require(auth), input.hidden());
  }

  record CommunityInput(String name, String description, Integer dailyLimit, Boolean official) {}

  @PatchMapping("/communities/{id}")
  Map<String, Object> editCommunity(
    @PathVariable UUID id,
    @RequestBody CommunityInput input,
    Authentication auth
  ) {
    return cms.editCommunity(id, accounts.require(auth), input);
  }

  record Resolution(String resolution) {}

  @PatchMapping("/reports/{id}")
  Map<String, Object> resolve(
    @PathVariable UUID id,
    @RequestBody Resolution input,
    Authentication auth
  ) {
    return cms.resolve(id, accounts.require(auth), input.resolution());
  }
}

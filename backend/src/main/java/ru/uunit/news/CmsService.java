package ru.uunit.news;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CmsService {

  public record Page(List<Map<String, Object>> items, long total) {}

  public record Query(
    int page,
    int perPage,
    String q,
    String status,
    String sort,
    String order,
    String aiGroup
  ) {
    public Query(int page, int perPage, String q, String status, String sort, String order) {
      this(page, perPage, q, status, sort, order, "");
    }

    public Query {
      aiGroup = aiGroup == null ? "" : aiGroup.trim();
      if (
        !Set.of("", "normal", "doubtful", "suspicious", "waiting", "error").contains(aiGroup)
      ) throw ApiException.bad("Неизвестная группа проверки");
      if (page < 1 || page > 1000000 || perPage < 1 || perPage > 100) throw ApiException.bad(
        "Проверьте номер страницы и размер списка (1–100)"
      );
      q = q == null ? "" : q.trim();
      status = status == null ? "" : status.trim();
      sort = sort == null ? "" : sort.trim();
      order = order == null ? "DESC" : order.toUpperCase(Locale.ROOT);
      if (q.length() > 200 || !Set.of("ASC", "DESC").contains(order)) throw ApiException.bad(
        "Проверьте поиск и порядок сортировки"
      );
    }
  }

  private record Spec(
    String select,
    String from,
    String id,
    Map<String, String> sorts,
    List<String> searches
  ) {}

  private final JdbcTemplate db;
  private final Accounts accounts;
  private final Communities communities;
  private final NewsService news;

  public CmsService(JdbcTemplate db, Accounts accounts, Communities communities, NewsService news) {
    this.db = db;
    this.accounts = accounts;
    this.communities = communities;
    this.news = news;
  }

  private Spec spec(String resource) {
    return switch (resource) {
      case "news" -> new Spec(
        "ai.error_code AS ai_error_code,ai.attempts AS ai_attempts,ai.retry_at AS ai_retry_at,ai.state AS ai_state,ai.risk_group AS ai_group,ai.result AS ai_result,ai.model AS ai_model,ai.checked_at AS ai_checked_at,n.*,r.id AS revision_id,r.title,r.body,r.category,r.source_url,r.status,r.rejection_reason,r.submitted_at,r.main_status,r.main_rejection_reason,u.display_name AS author,c.official,c.owner_id",
        "FROM news n JOIN news_revisions r ON r.id=n.latest_revision_id JOIN app_users u ON u.id=n.author_id LEFT JOIN communities c ON c.id=n.community_id LEFT JOIN ai_revision_reviews ai ON ai.revision_id=r.id",
        "n.id",
        Map.of(
          "id",
          "n.id",
          "title",
          "r.title",
          "status",
          "r.status",
          "createdAt",
          "n.created_at",
          "publishedAt",
          "n.published_at",
          "submittedAt",
          "r.submitted_at",
          "views",
          "n.views",
          "aiPriority",
          "CASE WHEN ai.risk_group='suspicious' THEN 4 WHEN ai.state='error' THEN 3 WHEN ai.state IS NULL OR ai.state='waiting' THEN 2 WHEN ai.risk_group='doubtful' THEN 1 ELSE 0 END",
          "author",
          "u.display_name"
        ),
        List.of("r.title", "r.body", "u.display_name")
      );
      case "communities" -> new Spec(
        "c.*,(SELECT count(*) FROM subscriptions s WHERE s.community_id=c.id) AS members",
        "FROM communities c",
        "c.id",
        Map.of(
          "id",
          "c.id",
          "name",
          "c.name",
          "createdAt",
          "c.created_at",
          "category",
          "c.category",
          "dailyLimit",
          "c.daily_limit",
          "official",
          "c.official",
          "verified",
          "c.official",
          "members",
          "members"
        ),
        List.of("c.name", "c.description")
      );
      case "reports" -> new Spec(
        "r.*,n.deleted,rev.title,u.display_name AS reporter",
        "FROM reports r JOIN news n ON n.id=r.news_id JOIN news_revisions rev ON rev.id=coalesce(n.published_revision_id,n.latest_revision_id) JOIN app_users u ON u.id=r.reporter_id LEFT JOIN communities c ON c.id=n.community_id",
        "r.id",
        Map.of(
          "id",
          "r.id",
          "createdAt",
          "r.created_at",
          "status",
          "r.status",
          "title",
          "rev.title",
          "reason",
          "r.reason"
        ),
        List.of("rev.title", "r.reason", "r.comment", "u.display_name")
      );
      case "audit" -> new Spec(
        "a.*,u.username,u.display_name AS actor_name",
        "FROM audit_log a JOIN app_users u ON u.id=a.actor_id",
        "a.id",
        Map.of(
          "id",
          "a.id",
          "createdAt",
          "a.created_at",
          "action",
          "a.action",
          "username",
          "u.username"
        ),
        List.of("a.action", "a.detail", "u.username")
      );
      default -> throw ApiException.missing();
    };
  }

  private void access(String resource, Accounts.User user) {
    if (user == null) throw ApiException.forbidden();
    if (accounts.admin(user)) return;
    if (
      resource.equals("audit") ||
      db.queryForObject(
        "SELECT count(*) FROM communities WHERE owner_id=? AND NOT official",
        Integer.class,
        user.id()
      ) == 0
    ) throw ApiException.forbidden();
  }

  private String scope(String resource, Accounts.User user, List<Object> args) {
    if (accounts.admin(user)) return "TRUE";
    args.add(user.id());
    String scope = "c.owner_id=? AND NOT c.official";
    if (resource.equals("news")) {
      scope += " AND NOT n.deleted";
    }
    return scope;
  }

  private String where(
    String resource,
    Accounts.User user,
    Query query,
    UUID id,
    List<Object> args
  ) {
    Spec spec = spec(resource);
    StringBuilder where = new StringBuilder(" WHERE ").append(scope(resource, user, args));
    if (id != null) {
      where.append(" AND ").append(spec.id()).append("=?");
      args.add(id);
    }
    if (!query.q().isBlank()) {
      where.append(" AND (");
      for (int index = 0; index < spec.searches().size(); index++) {
        if (index > 0) where.append(" OR ");
        where
          .append("strpos(news_search(")
          .append(spec.searches().get(index))
          .append("),news_search(?))>0");
        args.add(query.q());
      }
      where.append(")");
    }
    if (resource.equals("news") && !query.aiGroup().isEmpty()) {
      String group = query.aiGroup();
      if (group.equals("waiting")) where.append(" AND (ai.state IS NULL OR ai.state='waiting')");
      else {
        where.append(group.equals("error") ? " AND ai.state=?" : " AND ai.risk_group=?");
        args.add(group);
      }
    }
    String status = query.status();
    if (status.isEmpty() || status.equals("all")) return where.toString();
    switch (resource) {
      case "news" -> {
        if (status.equals("hidden")) where.append(" AND n.deleted");
        else if (status.equals("main-pending")) where.append(
          accounts.admin(user)
            ? " AND NOT n.deleted AND r.status='published' AND r.main_status='pending'"
            : " AND FALSE"
        );
        else if (status.equals("pending")) where.append(
          accounts.admin(user)
            ? " AND NOT n.deleted AND (r.status='pending' OR (r.status='published' AND r.main_status='pending'))"
            : " AND NOT n.deleted AND r.status='pending'"
        );
        else {
          if (
            !Set.of("pending", "published", "rejected", "withdrawn").contains(status)
          ) throw ApiException.bad("Неизвестный статус новости");
          where.append(" AND NOT n.deleted AND r.status=?");
          args.add(status);
        }
      }
      case "communities" -> {
        if (!Set.of("official", "ordinary").contains(status)) throw ApiException.bad(
          "Неизвестный тип паблика"
        );
        where.append(status.equals("official") ? " AND c.official" : " AND NOT c.official");
      }
      case "reports" -> {
        if (!Set.of("pending", "resolved").contains(status)) throw ApiException.bad(
          "Неизвестный статус жалобы"
        );
        where.append(" AND r.status=?");
        args.add(status);
      }
      default -> throw ApiException.bad("Фильтр статуса для этого списка не поддерживается");
    }
    return where.toString();
  }

  public Page list(String resource, Accounts.User user, Query query) {
    Spec spec = spec(resource);
    access(resource, user);
    String sort = query.sort().isEmpty() ? "createdAt" : query.sort();
    String column = spec.sorts().get(sort);
    if (column == null) throw ApiException.bad("Неизвестное поле сортировки");
    List<Object> args = new ArrayList<>();
    String where = where(resource, user, query, null, args);
    long total = db.queryForObject(
      "SELECT count(*) " + spec.from() + where,
      Long.class,
      args.toArray()
    );
    args.add(query.perPage());
    args.add((query.page() - 1) * query.perPage());
    List<Map<String, Object>> items = db
      .queryForList(
        "SELECT " +
          spec.select() +
          " " +
          spec.from() +
          where +
          " ORDER BY " +
          column +
          " " +
          query.order() +
          " NULLS LAST," +
          spec.id() +
          " " +
          query.order() +
          " LIMIT ? OFFSET ?",
        args.toArray()
      )
      .stream()
      .map(row -> view(resource, row, user))
      .toList();
    return new Page(items, total);
  }

  public Map<String, Object> detail(String resource, UUID id, Accounts.User user) {
    Spec spec = spec(resource);
    access(resource, user);
    List<Object> args = new ArrayList<>();
    String where = where(resource, user, new Query(1, 25, "", "", "", "DESC"), id, args);
    Map<String, Object> row = db
      .queryForList("SELECT " + spec.select() + " " + spec.from() + where, args.toArray())
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    return view(resource, row, user);
  }

  private Map<String, Object> view(String resource, Map<String, Object> row, Accounts.User user) {
    Map<String, Object> result = new LinkedHashMap<>();
    switch (resource) {
      case "news" -> {
        result.putAll(news.view(row, user));
        result.put("publishedAt", instant(row.get("published_at")));
        result.put("deleted", row.get("deleted"));
        result.put("canEdit", accounts.admin(user) || user.id().equals(row.get("author_id")));
        result.put(
          "mediaIds",
          db.queryForList(
            "SELECT media_id FROM revision_media WHERE revision_id=? ORDER BY sort_order",
            UUID.class,
            row.get("revision_id")
          )
        );
        result.put("createdAt", instant(row.get("created_at")));
        result.put("submittedAt", instant(row.get("submitted_at")));
        result.put("aiState", Objects.toString(row.get("ai_state"), "waiting"));
        result.put("aiGroup", row.get("ai_group"));
        result.put("aiErrorCode", row.get("ai_error_code"));
        result.put("aiAttempts", row.get("ai_attempts"));
        result.put("aiRetryAt", instant(row.get("ai_retry_at")));
        result.put("aiModel", row.get("ai_model"));
        result.put("aiCheckedAt", instant(row.get("ai_checked_at")));
        if (row.get("ai_result") != null) {
          try {
            result.put(
              "aiReview",
              new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                row.get("ai_result").toString()
              )
            );
          } catch (Exception ignored) {
            result.put("aiState", "error");
            result.put("aiGroup", null);
          }
        }
      }
      case "communities" -> {
        for (String key : List.of(
          "id",
          "name",
          "description",
          "initials",
          "color",
          "category",
          "members"
        ))
          result.put(key, row.get(key));
        result.put("members", row.get("members").toString());
        result.put("ownerId", row.get("owner_id"));
        result.put("dailyLimit", row.get("daily_limit"));
        result.put("official", row.get("official"));
        result.put("verified", row.get("official"));
        result.put("canManage", true);
        result.put("createdAt", instant(row.get("created_at")));
      }
      case "reports" -> {
        for (String key : List.of(
          "id",
          "title",
          "reason",
          "comment",
          "status",
          "resolution",
          "reporter"
        ))
          result.put(key, row.get(key));
        result.put("newsId", row.get("news_id"));
        result.put("deleted", row.get("deleted"));
        result.put("createdAt", instant(row.get("created_at")));
        result.put("reviewedAt", instant(row.get("reviewed_at")));
      }
      case "audit" -> {
        for (String key : List.of("id", "action", "detail", "username"))
          result.put(key, row.get(key));
        result.put("targetId", row.get("target_id"));
        result.put("actorName", row.get("actor_name"));
        result.put("createdAt", instant(row.get("created_at")));
      }
      default -> throw ApiException.missing();
    }
    return result;
  }

  private String instant(Object value) {
    return value == null ? null : ((Timestamp) value).toInstant().toString();
  }

  public Map<String, Long> summary(Accounts.User user) {
    access("news", user);
    List<Object> args = new ArrayList<>();
    String where = " WHERE " + scope("news", user, args);
    List<Object> groupArgs = new ArrayList<>();
    String groupWhere = " WHERE " + scope("communities", user, groupArgs);
    List<Object> reportArgs = new ArrayList<>();
    String reportWhere = " WHERE " + scope("reports", user, reportArgs);
    return Map.of(
      "news",
      db.queryForObject(
        "SELECT count(*) FROM news n LEFT JOIN communities c ON c.id=n.community_id" + where,
        Long.class,
        args.toArray()
      ),
      "pending",
      db.queryForObject(
        "SELECT count(*) FROM news n JOIN news_revisions r ON r.id=n.latest_revision_id LEFT JOIN communities c ON c.id=n.community_id" +
          where +
          (accounts.admin(user)
            ? " AND NOT n.deleted AND (r.status='pending' OR (r.status='published' AND r.main_status='pending'))"
            : " AND NOT n.deleted AND r.status='pending'"),
        Long.class,
        args.toArray()
      ),
      "reports",
      db.queryForObject(
        "SELECT count(*) FROM reports r JOIN news n ON n.id=r.news_id LEFT JOIN communities c ON c.id=n.community_id" +
          reportWhere +
          " AND r.status='pending'",
        Long.class,
        reportArgs.toArray()
      ),
      "communities",
      db.queryForObject(
        "SELECT count(*) FROM communities c" + groupWhere,
        Long.class,
        groupArgs.toArray()
      )
    );
  }

  @Transactional
  public Map<String, Object> editNews(UUID id, Accounts.User user, NewsService.Input input) {
    access("news", user);
    news.editorialEdit(id, user, input);
    return detail("news", id, user);
  }

  private Map<String, Object> lockNews(UUID id, Accounts.User user) {
    Map<String, Object> snapshot = db
      .queryForList("SELECT community_id FROM news WHERE id=?", id)
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    Map<String, Object> community = null;
    if (snapshot.get("community_id") != null) community = db
      .queryForList("SELECT * FROM communities WHERE id=? FOR UPDATE", snapshot.get("community_id"))
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    Map<String, Object> row = db
      .queryForList("SELECT * FROM news WHERE id=? FOR UPDATE", id)
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    if (community != null) {
      row.put("official", community.get("official"));
      row.put("owner_id", community.get("owner_id"));
    }
    if (!news.moderator(row, user)) throw ApiException.forbidden();
    return row;
  }

  @Transactional
  public Map<String, Object> visibility(UUID id, Accounts.User user, boolean hidden) {
    // Only platform administration can reverse a hide, including its own intervention.
    if (!hidden && !accounts.admin(user)) throw ApiException.forbidden();
    access("news", user);
    Map<String, Object> row = lockNews(id, user);
    if (!Objects.equals(row.get("deleted"), hidden)) {
      db.update("UPDATE news SET deleted=? WHERE id=?", hidden, id);
      audit(
        user,
        hidden ? "cms-hide" : "cms-restore",
        id,
        hidden ? "Новость скрыта" : "Новость восстановлена"
      );
    }
    // Hidden items intentionally disappear from the owner's read scope.
    // Do not re-read through that scope after hiding and roll the operation back.
    return accounts.admin(user) ? detail("news", id, user) : Map.of("id", id, "deleted", true);
  }

  @Transactional
  public Map<String, Object> editCommunity(
    UUID id,
    Accounts.User user,
    CmsController.CommunityInput input
  ) {
    access("communities", user);
    Map<String, Object> row = db
      .queryForList("SELECT * FROM communities WHERE id=? FOR UPDATE", id)
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    boolean admin = accounts.admin(user);
    if (
      !admin && (Boolean.TRUE.equals(row.get("official")) || !user.id().equals(row.get("owner_id")))
    ) throw ApiException.forbidden();
    int limit =
      input.dailyLimit() == null
        ? ((Number) row.get("daily_limit")).intValue()
        : input.dailyLimit();
    boolean official =
      input.official() == null ? Boolean.TRUE.equals(row.get("official")) : input.official();
    if (
      !admin &&
      (limit != ((Number) row.get("daily_limit")).intValue() ||
        official != Boolean.TRUE.equals(row.get("official")))
    ) throw ApiException.forbidden();
    String name = input.name() == null ? (String) row.get("name") : input.name();
    String description =
      input.description() == null ? (String) row.get("description") : input.description();
    communities.update(id, user, name, description);
    if (admin && (input.dailyLimit() != null || input.official() != null)) communities.settings(
      id,
      user,
      limit,
      official
    );
    audit(user, "cms-community-edit", id, "Обновлены сведения о паблике");
    return detail("communities", id, user);
  }

  @Transactional
  public Map<String, Object> resolve(UUID id, Accounts.User user, String resolution) {
    access("reports", user);
    if (
      resolution == null || resolution.isBlank() || resolution.length() > 2000
    ) throw ApiException.bad("Укажите результат рассмотрения до 2000 символов");
    UUID newsId = db
      .query("SELECT news_id FROM reports WHERE id=?", (r, n) -> r.getObject(1, UUID.class), id)
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    lockNews(newsId, user);
    db.queryForObject("SELECT id FROM reports WHERE id=? FOR UPDATE", UUID.class, id);
    db.update(
      "UPDATE reports SET status='resolved',resolution=?,reviewer_id=?,reviewed_at=now() WHERE id=?",
      resolution.trim(),
      user.id(),
      id
    );
    audit(user, "cms-report-resolve", id, resolution.trim());
    return detail("reports", id, user);
  }

  private void audit(Accounts.User user, String action, UUID id, String detail) {
    db.update(
      "INSERT INTO audit_log(id,actor_id,action,target_id,detail) VALUES(?,?,?,?,?)",
      UUID.randomUUID(),
      user.id(),
      action,
      id,
      detail
    );
  }
}

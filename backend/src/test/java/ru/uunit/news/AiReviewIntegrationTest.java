package ru.uunit.news;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AiReviewIntegrationTest {

  @Autowired
  JdbcTemplate db;

  @Autowired
  NewsService news;

  @Autowired
  Accounts accounts;

  @Autowired
  CmsService cms;

  @Autowired
  MockMvc mvc;

  @TempDir
  Path temp;

  UUID submit() {
    return news.submit(
      accounts.find("uunit.admin"),
      new NewsService.Input(
        "Новость для проверки нейросетью",
        "Событие состоится в университете завтра. Организаторы приглашают всех студентов на мероприятие.",
        "Технологии",
        null,
        "",
        List.of()
      ),
      null
    );
  }

  UUID revision(UUID id) {
    return db.queryForObject("SELECT latest_revision_id FROM news WHERE id=?", UUID.class, id);
  }

  AiReviewService worker() {
    var worker = spy(new AiReviewService(db, new ObjectMapper(), temp.toString(), "test-secret"));
    worker.save(new AiReviewService.Settings(true, "test-model", null));
    return worker;
  }

  void isolate() {
    db.update("UPDATE news_revisions SET status='withdrawn' WHERE status='pending'");
    db.update("UPDATE news_revisions SET main_status='withdrawn' WHERE main_status='pending'");
  }

  @Test
  void classifiesWithoutDecidingAndFiltersByCurrentRevision() throws Exception {
    isolate();
    UUID id = submit(),
      rev = revision(id);
    var worker = worker();
    doReturn(
      "{\"newsworthiness\":\"fits\",\"violationRisk\":\"high\",\"reasons\":[\"Нужна проверка\"],\"evidence\":[]}"
    )
      .when(worker)
      .callClaude(any(), anyString());
    worker.tick();
    assertEquals(
      "pending",
      db.queryForObject("SELECT status FROM news_revisions WHERE id=?", String.class, rev)
    );
    assertNull(
      db.queryForObject("SELECT published_revision_id FROM news WHERE id=?", UUID.class, id)
    );
    var query = new CmsService.Query(1, 25, "", "pending", "aiPriority", "DESC", "suspicious");
    var list = cms.list("news", accounts.find("uunit.admin"), query);
    assertEquals(1, list.total());
    assertEquals(id, list.items().getFirst().get("id"));
    assertEquals("suspicious", list.items().getFirst().get("aiGroup"));
    var detail = cms.detail("news", id, accounts.find("uunit.admin"));
    assertTrue(detail.containsKey("aiReview"));
    news.submit(
      accounts.find("uunit.admin"),
      new NewsService.Input(
        "Изменённая новость для проверки",
        "После редактирования материал должен пройти новую проверку независимо от старой оценки.",
        "Технологии",
        null,
        "",
        List.of()
      ),
      id
    );
    assertEquals(0, cms.list("news", accounts.find("uunit.admin"), query).total());
    assertEquals("waiting", cms.detail("news", id, accounts.find("uunit.admin")).get("aiState"));
    assertFalse(worker.settings().toString().contains("test-secret"));
  }

  @Test
  void errorsAreNotGreenAndRetriesAreBounded() throws Exception {
    isolate();
    UUID rev = revision(submit());
    var worker = worker();
    doThrow(new java.io.IOException("provider error")).when(worker).callClaude(any(), anyString());
    for (int i = 0; i < 5; i++) {
      db.update("UPDATE ai_revision_reviews SET retry_at=now()");
      worker.tick();
    }
    assertEquals(
      3,
      db.queryForObject(
        "SELECT attempts FROM ai_revision_reviews WHERE revision_id=?",
        Integer.class,
        rev
      )
    );
    assertEquals(
      "error",
      db.queryForObject(
        "SELECT state FROM ai_revision_reviews WHERE revision_id=?",
        String.class,
        rev
      )
    );
    assertNull(
      db.queryForObject(
        "SELECT risk_group FROM ai_revision_reviews WHERE revision_id=?",
        String.class,
        rev
      )
    );
    worker.retryErrors();
    assertNull(
      db.queryForObject(
        "SELECT error_code FROM ai_revision_reviews WHERE revision_id=?",
        String.class,
        rev
      )
    );
    worker.tick();
    assertEquals(
      "network",
      db.queryForObject(
        "SELECT error_code FROM ai_revision_reviews WHERE revision_id=?",
        String.class,
        rev
      )
    );
    assertEquals(
      1,
      db.queryForObject(
        "SELECT attempts FROM ai_revision_reviews WHERE revision_id=?",
        Integer.class,
        rev
      )
    );
    assertEquals(
      "pending",
      db.queryForObject("SELECT status FROM news_revisions WHERE id=?", String.class, rev)
    );
  }

  @Test
  void settingsAreAdminOnlyAndMutationsRequireCsrf() throws Exception {
    mvc
      .perform(
        put("/api/ai-review/settings")
          .with(user("uunit.admin").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content("{\"enabled\":false,\"model\":\"test-model\",\"key\":\"must-not-be-saved\"}")
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(get("/api/ai-review/settings").with(user("club.owner").roles("USER")))
      .andExpect(status().isForbidden());
    mvc
      .perform(get("/api/ai-review/settings").with(user("uunit.admin").roles("ADMIN")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.key").doesNotExist());
    mvc
      .perform(
        put("/api/ai-review/settings")
          .with(user("uunit.admin").roles("ADMIN"))
          .contentType("application/json")
          .content("{}")
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/ai-review/settings/retry").with(user("club.owner").roles("USER")).with(csrf())
      )
      .andExpect(status().isForbidden());
  }
}

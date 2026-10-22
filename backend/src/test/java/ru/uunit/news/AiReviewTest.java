package ru.uunit.news;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiReviewTest {

  final ObjectMapper mapper = new ObjectMapper();

  @Test
  void acceptsJsonFencesButRejectsExtraContent() throws Exception {
    String json =
      "{\"newsworthiness\":\"fits\",\"violationRisk\":\"high\",\"reasons\":[\"Проверить\"],\"evidence\":[]}";
    var parsed = AiReviewService.parseAnswer(mapper, "```json\n" + json + "\n```");
    assertEquals("suspicious", AiReviewService.validate(parsed, "", "", false).get("group"));
    assertEquals(
      "high",
      AiReviewService.parseAnswer(mapper, "```\n" + json + "\n```")
        .path("violationRisk")
        .asText()
    );
    assertThrows(java.io.IOException.class, () ->
      AiReviewService.parseAnswer(mapper, json + " {}")
    );
    assertThrows(java.io.IOException.class, () ->
      AiReviewService.parseAnswer(mapper, "Here is the result: " + json)
    );
    assertEquals(
      "network",
      AiReviewService.errorCode(new java.io.IOException("secret provider body"))
    );
    assertEquals(
      "timeout",
      AiReviewService.errorCode(new java.net.http.HttpTimeoutException("private"))
    );
    assertEquals("internal", AiReviewService.errorCode(new RuntimeException("secret")));
  }

  private java.util.Map<String, Object> result(String format, String risk, boolean media)
    throws Exception {
    return AiReviewService.validate(
      mapper.readTree(
        "{\"newsworthiness\":\"" +
          format +
          "\",\"violationRisk\":\"" +
          risk +
          "\",\"reasons\":[\"Причина\"],\"evidence\":[]}"
      ),
      "Заголовок",
      "Текст",
      media
    );
  }

  @Test
  void combinesIndependentAxes() throws Exception {
    assertEquals("normal", result("fits", "low", false).get("group"));
    assertEquals("doubtful", result("off_topic", "low", false).get("group"));
    assertEquals("doubtful", result("unclear", "low", false).get("group"));
    assertEquals("doubtful", result("fits", "uncertain", false).get("group"));
    assertEquals("suspicious", result("fits", "high", false).get("group"));
    assertEquals("suspicious", result("off_topic", "high", true).get("group"));
    assertEquals("doubtful", result("fits", "low", true).get("group"));
  }

  @Test
  void invalidOutputNeverBecomesGreen() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> result("fits", "safe", false));
    assertThrows(IllegalArgumentException.class, () ->
      AiReviewService.validate(
        mapper.readTree(
          "{\"newsworthiness\":\"fits\",\"violationRisk\":\"low\",\"reasons\":[],\"evidence\":[]}"
        ),
        "",
        "",
        false
      )
    );
    assertThrows(IllegalArgumentException.class, () ->
      AiReviewService.validate(
        mapper.readTree(
          "{\"newsworthiness\":\"fits\",\"violationRisk\":\"high\",\"reasons\":[\"Угроза\"],\"evidence\":[\"Выдуманная цитата\"]}"
        ),
        "Заголовок",
        "Текст",
        false
      )
    );
  }
}

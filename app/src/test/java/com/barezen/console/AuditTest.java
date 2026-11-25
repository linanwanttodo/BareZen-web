package com.barezen.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Audit")
class AuditTest {

  @Test
  @DisplayName("a single event subject is bounded even when request input is near the body limit")
  void subjectIsBounded() {
    Audit audit = new Audit(Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC));
    String large = "x".repeat(10_000);

    audit.publish(new Audit.ReportAdded(1, large));

    assertThat(audit.entries()).hasSize(1);
    assertThat(audit.entries().get(0).subject()).hasSizeLessThanOrEqualTo(256).endsWith("...");
  }
}

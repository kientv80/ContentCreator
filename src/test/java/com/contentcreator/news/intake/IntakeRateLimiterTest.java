package com.contentcreator.news.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IntakeRateLimiterTest {

  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-08T10:00:00Z"));
  private final Clock clock = new Clock() {
    @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
  };

  @Test
  void tryAcquire_allowsTheLimitThenReturnsSecondsToWait() {
    IntakeRateLimiter limiter = new IntakeRateLimiter(clock, new NewsIntakeProperties("x", false, 10, 3));
    for (int i = 0; i < 3; i++) assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(-1);
    now.set(now.get().plusSeconds(600));

    assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(3000);
  }

  @Test
  void tryAcquire_countsEachAddressSeparately() {
    IntakeRateLimiter limiter = new IntakeRateLimiter(clock, new NewsIntakeProperties("x", false, 10, 1));
    assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(-1);

    assertThat(limiter.tryAcquire("1.1.1.1")).isPositive();
    assertThat(limiter.tryAcquire("2.2.2.2")).isEqualTo(-1);
  }

  @Test
  void tryAcquire_startsANewWindowAfterOneHour() {
    IntakeRateLimiter limiter = new IntakeRateLimiter(clock, new NewsIntakeProperties("x", false, 10, 1));
    limiter.tryAcquire("1.1.1.1");
    assertThat(limiter.tryAcquire("1.1.1.1")).isPositive();

    now.set(now.get().plusSeconds(3600));

    assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(-1);
  }

  @Test
  void tryAcquire_dropsExpiredWindowsWhenManyAddressesAreTracked() {
    IntakeRateLimiter limiter = new IntakeRateLimiter(clock, new NewsIntakeProperties("x", false, 10, 1));
    for (int i = 0; i < 10_002; i++) limiter.tryAcquire("a" + i);
    now.set(now.get().plusSeconds(3601));

    assertThat(limiter.tryAcquire("a0")).isEqualTo(-1);
    assertThat(limiter.tryAcquire("a0")).isPositive();
  }
}

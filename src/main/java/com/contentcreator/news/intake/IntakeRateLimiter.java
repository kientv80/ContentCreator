package com.contentcreator.news.intake;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** In-memory fixed-window request counter per client address (one window is one hour). */
@Component
@ConditionalOnExpression("!'${contentcreator.news.intake.token-sha256:}'.isBlank()")
public class IntakeRateLimiter {

  static final long WINDOW_MILLIS = 3_600_000L;
  /** Above this many tracked addresses, expired windows are dropped on the next call so the map cannot grow without bound. */
  private static final int PURGE_ABOVE = 10_000;

  private record Window(long start, int count) {}

  private final Clock clock;
  private final int limit;
  private final Map<String, Window> windows = new java.util.HashMap<>();

  /**
   * @param clock the time source
   * @param props supplies the hourly limit
   */
  public IntakeRateLimiter(Clock clock, NewsIntakeProperties props) {
    this.clock = clock;
    this.limit = props.perHour();
  }

  /**
   * Counts one request for the address, valid or not.
   *
   * @param address the client address
   * @return -1 when the request is within the limit, else the seconds until the window ends (at least 1)
   */
  public synchronized long tryAcquire(String address) {
    long now = clock.millis();
    if (windows.size() > PURGE_ABOVE) {
      for (Iterator<Window> it = windows.values().iterator(); it.hasNext(); ) {
        if (now - it.next().start() >= WINDOW_MILLIS) it.remove();
      }
    }
    Window w = windows.get(address);
    if (w == null || now - w.start() >= WINDOW_MILLIS) {
      w = new Window(now, 0);
    }
    if (w.count() >= limit) {
      windows.put(address, w);
      return Math.max(1, (w.start() + WINDOW_MILLIS - now + 999) / 1000);
    }
    windows.put(address, new Window(w.start(), w.count() + 1));
    return -1;
  }
}

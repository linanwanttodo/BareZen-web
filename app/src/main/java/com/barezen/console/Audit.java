package com.barezen.console;

import com.barezen.core.event.EventBus;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * The audit trail: domain events published onto the framework's event bus and read back as JSON.
 *
 * <p>The bus has had no consumer since phase 2.6 (ADR-0017 named it the candidate for a second extension point), and
 * this is deliberately <em>not</em> that extension point: an application creating an {@link EventBus}, injecting it into
 * the handlers that publish onto it, and subscribing its own reader is what the shipped API already allows — the seam
 * ADR-0047 describes would be a <em>plugin</em> contributing a subscriber, and nothing here needs one. So the bus gets
 * its first real consumer without the framework gaining anything, which is the order this project insists on.
 *
 * <p>Two rules the class keeps visibly. The clock arrives in the constructor rather than being read at the call site
 * (ADR-0013's assembly-time discipline applied to a bean: a test can hand this a fixed instant), and the trail is
 * bounded — fifty events, oldest dropped, each subject at most 256 characters — because an unbounded list or subject
 * fed by every request is a memory leak with a nice API. Reads take a snapshot, so a browser polling the feed never
 * sees a half-updated list.
 */
public final class Audit {

  /** What the trail keeps: enough for a page to show, small enough to hold forever in a demo. */
  private static final int LIMIT = 50;

  /** Maximum subject text per entry, so request-sized input cannot dominate the trail. */
  private static final int SUBJECT_LIMIT = 256;

  private final EventBus bus;
  private final Deque<Entry> trail = new ArrayDeque<>();

  /** One recorded event: a kind, a subject, and the instant the injected clock said. */
  public record Entry(String kind, String subject, Instant at) {
  }

  /**
   * A new trail over a new bus, subscribed to every event kind this application publishes.
   *
   * @param clock the time the entries carry, injected rather than read so a test can pin it
   */
  public Audit(Clock clock) {
    this.bus = EventBus.create();
    // Subscribed per concrete class, because the bus routes on the event's exact runtime type: a handler for
    // Recorded would receive nothing. The list is the application's own decision about what belongs in the trail.
    watch(SignedIn.class, clock);
    watch(SignInRejected.class, clock);
    watch(SignedOut.class, clock);
    watch(SessionRotated.class, clock);
    watch(ReportAdded.class, clock);
    watch(ReportDeleted.class, clock);
    watch(ReportListed.class, clock);
  }

  /** Subscribe the trail to one event kind. */
  private <E extends Recorded> void watch(Class<E> kind, Clock clock) {
    bus.subscribe(kind, event -> record(kind.getSimpleName(), describe(event), clock.instant()));
  }

  /** Append one stamped entry, keeping the newest fifty. */
  private void record(String kind, String subject, Instant at) {
    synchronized (trail) {
      trail.addLast(new Entry(kind, abbreviate(subject), at));
      if (trail.size() > LIMIT) {
        trail.removeFirst();
      }
    }
  }

  private static String abbreviate(String subject) {
    return subject.length() <= SUBJECT_LIMIT ? subject : subject.substring(0, SUBJECT_LIMIT - 3) + "...";
  }

  private static String describe(Recorded event) {
    if (event instanceof SignedIn signedIn) {
      return signedIn.user();
    }
    if (event instanceof SignInRejected rejected) {
      return rejected.user();
    }
    if (event instanceof SignedOut out) {
      return out.user();
    }
    if (event instanceof SessionRotated rotated) {
      return rotated.user();
    }
    if (event instanceof ReportAdded added) {
      return "#" + added.id() + " " + added.title();
    }
    if (event instanceof ReportDeleted deleted) {
      return "#" + deleted.id();
    }
    if (event instanceof ReportListed listed) {
      return listed.count() + " rows for q=" + (listed.filter().isEmpty() ? "(none)" : listed.filter());
    }
    return event.toString();
  }

  /** An event this application records; the marker keeps the subscription list honest. */
  public interface Recorded {
  }

  public record SignedIn(String user) implements Recorded {
  }

  public record SignInRejected(String user) implements Recorded {
  }

  public record SignedOut(String user) implements Recorded {
  }

  public record SessionRotated(String user) implements Recorded {
  }

  public record ReportAdded(int id, String title) implements Recorded {
  }

  public record ReportDeleted(int id) implements Recorded {
  }

  public record ReportListed(int count, String filter) implements Recorded {
  }

  /** Publish one domain event; the subscribers are this class's own sinks. */
  public void publish(Recorded event) {
    bus.publish(event);
  }

  /** The trail as it stands, newest last — a copy, so a caller cannot mutate what the bus appends to. */
  public List<Entry> entries() {
    synchronized (trail) {
      return List.copyOf(trail);
    }
  }

  /** The bus this trail listens to, so a component can publish through it without knowing about the trail. */
  public EventBus bus() {
    return bus;
  }

  /** Render the trail as the JSON the page shows, newest first because a reader wants what just happened. */
  public String toJson() {
    List<Entry> snapshot = entries();
    List<String> rows = new ArrayList<>(snapshot.size());
    for (int index = snapshot.size() - 1; index >= 0; index--) {
      Entry entry = snapshot.get(index);
      rows.add(Json.object(Map.of("kind", entry.kind(), "subject", entry.subject(), "at", entry.at().toString())));
    }
    return Json.array(rows);
  }
}

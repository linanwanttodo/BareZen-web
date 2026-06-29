package com.barezen.core.kernel;

import java.util.List;

/**
 * Test participant recording every lifecycle call into a shared event log and able to fail on demand.
 *
 * <p>Shared across the lifecycle tests so ordering assertions read as one flat sequence
 * ({@code "a.initialize", "b.initialize", "a.start", ...}) rather than as per test scaffolding.
 */
class RecordingParticipant implements LifecycleParticipant {

  private final String id;
  private final List<String> events;

  private Runnable initializeBody;
  private Runnable startBody;
  private Runnable stopBody;
  private Runnable abortBody;

  RecordingParticipant(String id, List<String> events) {
    this.id = id;
    this.events = events;
  }

  /** Makes {@code initialize()} fail after recording the call, as a real broken participant would. */
  RecordingParticipant failingInitialize() {
    return failingInitialize(new IllegalStateException(id + " initialize failed"));
  }

  RecordingParticipant failingInitialize(RuntimeException failure) {
    this.initializeBody = () -> {
      throw failure;
    };
    return this;
  }

  RecordingParticipant failingAbort(RuntimeException failure) {
    this.abortBody = () -> {
      throw failure;
    };
    return this;
  }

  RecordingParticipant failingStart() {
    this.startBody = () -> {
      throw new IllegalStateException(id + " start failed");
    };
    return this;
  }

  RecordingParticipant failingStop() {
    return failingStop(new IllegalStateException(id + " stop failed"));
  }

  RecordingParticipant failingStop(RuntimeException failure) {
    this.stopBody = () -> {
      throw failure;
    };
    return this;
  }

  RecordingParticipant failingAbort() {
    this.abortBody = () -> {
      throw new IllegalStateException(id + " abort failed");
    };
    return this;
  }

  /** Runs arbitrary work inside {@code initialize()}, used to arrange resource and timing states. */
  RecordingParticipant duringInitialize(Runnable body) {
    this.initializeBody = body;
    return this;
  }

  RecordingParticipant duringStart(Runnable body) {
    this.startBody = body;
    return this;
  }

  RecordingParticipant duringStop(Runnable body) {
    this.stopBody = body;
    return this;
  }

  @Override
  public void initialize() {
    events.add(id + ".initialize");
    run(initializeBody);
  }

  @Override
  public void start() {
    events.add(id + ".start");
    run(startBody);
  }

  @Override
  public void stop() {
    events.add(id + ".stop");
    run(stopBody);
  }

  @Override
  public void abort() {
    events.add(id + ".abort");
    run(abortBody);
  }

  private void run(Runnable body) {
    if (body != null) {
      body.run();
    }
  }
}

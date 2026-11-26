package dev.videosearch.model;

/** Shared across provider adapters in one process. */
public final class CloudSlots {
  public static final java.util.concurrent.Semaphore SLOTS =
      new java.util.concurrent.Semaphore(2, true);

  private CloudSlots() {}
}

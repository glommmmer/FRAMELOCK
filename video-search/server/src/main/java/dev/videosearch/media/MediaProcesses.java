package dev.videosearch.media;

/** One FFmpeg process per API/Worker process; additional work remains in persistent tasks. */
final class MediaProcesses {
  static final java.util.concurrent.Semaphore SLOT = new java.util.concurrent.Semaphore(1, true);

  private MediaProcesses() {}
}

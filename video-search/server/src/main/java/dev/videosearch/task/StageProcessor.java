package dev.videosearch.task;

public interface StageProcessor {
  void process(PersistentTasks.Lease lease) throws Exception;
}

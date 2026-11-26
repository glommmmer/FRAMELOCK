package dev.videosearch.task;

public interface SearchProcessor {
  void process(PersistentTasks.Lease lease) throws Exception;
}

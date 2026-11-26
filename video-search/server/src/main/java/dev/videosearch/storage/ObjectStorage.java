package dev.videosearch.storage;

import com.aliyun.oss.*;
import com.aliyun.oss.model.GetObjectRequest;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** OSS adapter migrated from CourseSeek; local mode is only for offline tests. */
@Component
public class ObjectStorage {
  private final Path localRoot;
  private final boolean local;
  private final String mode;

  @Value("${app.minio.endpoint:http://127.0.0.1:9000}")
  private String minioEndpoint;

  @Value("${app.minio.bucket:framelock}")
  private String minioBucket;

  @Value("${app.minio.access-key:}")
  private String minioAccess;

  @Value("${app.minio.secret-key:}")
  private String minioSecret;

  private volatile io.minio.MinioClient minio;
  private final String endpoint, bucket, access, secret;
  private volatile OSS client;

  public ObjectStorage(
      @Value("${app.data-dir:.data}") String data,
      @Value("${app.storage:oss}") String mode,
      @Value("${app.oss.endpoint:}") String endpoint,
      @Value("${app.oss.bucket:}") String bucket,
      @Value("${app.oss.access-key:}") String access,
      @Value("${app.oss.secret-key:}") String secret) {
    localRoot = Path.of(data).resolve("objects").toAbsolutePath().normalize();
    local = mode.equals("local");
    this.mode = mode;
    if (!local && !mode.equals("oss") && !mode.equals("minio"))
      throw new IllegalArgumentException("Unknown storage backend");
    this.endpoint = endpoint;
    this.bucket = bucket;
    this.access = access;
    this.secret = secret;
  }

  private synchronized OSS client() {
    if (client == null) {
      if (endpoint.isBlank() || bucket.isBlank() || access.isBlank() || secret.isBlank())
        throw new IllegalStateException("OSS is not configured");
      var settings = new ClientBuilderConfiguration();
      settings.setConnectionTimeout(10000);
      settings.setSocketTimeout(30000);
      settings.setMaxErrorRetry(0);
      client = new OSSClientBuilder().build(endpoint, access, secret, settings);
    }
    return client;
  }

  private Path path(String key) {
    Path p = localRoot.resolve(key).normalize();
    if (!p.startsWith(localRoot)) throw new IllegalArgumentException("Invalid object key");
    return p;
  }

  public String backend() {
    return mode;
  }

  public static String tagged(String backend, String key) {
    if (!java.util.List.of("oss", "local", "minio").contains(backend))
      throw new IllegalArgumentException("Invalid storage backend");
    return backend.equals("minio") ? "minio:" + key : key;
  }

  private synchronized io.minio.MinioClient minio() {
    if (minio == null) {
      if (minioAccess.isBlank() || minioSecret.isBlank())
        throw new IllegalStateException("MinIO is not configured");
      var candidate =
          io.minio.MinioClient.builder()
              .endpoint(minioEndpoint)
              .credentials(minioAccess, minioSecret)
              .build();
      try {
        if (!candidate.bucketExists(
            io.minio.BucketExistsArgs.builder().bucket(minioBucket).build())) {
          try {
            candidate.makeBucket(io.minio.MakeBucketArgs.builder().bucket(minioBucket).build());
          } catch (Exception race) {
            if (!candidate.bucketExists(
                io.minio.BucketExistsArgs.builder().bucket(minioBucket).build())) throw race;
          }
        }
      } catch (Exception e) {
        throw new IllegalStateException("MinIO bucket unavailable", e);
      }
      minio = candidate;
    }
    return minio;
  }

  private static IOException storageError(Exception e) {
    return new IOException("MinIO operation failed", e);
  }

  public void put(String key, Path source) throws IOException {
    if (key.startsWith("minio:")) {
      try {
        minio()
            .uploadObject(
                io.minio.UploadObjectArgs.builder()
                    .bucket(minioBucket)
                    .object(key.substring(6))
                    .filename(source.toString())
                    .build());
        return;
      } catch (Exception e) {
        throw storageError(e);
      }
    }
    if (local) {
      Path target = path(key);
      Files.createDirectories(target.getParent());
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    } else client().putObject(bucket, key, source.toFile());
  }

  public InputStream open(String key) throws IOException {
    if (key.startsWith("minio:")) {
      try {
        return minio()
            .getObject(
                io.minio.GetObjectArgs.builder()
                    .bucket(minioBucket)
                    .object(key.substring(6))
                    .build());
      } catch (Exception e) {
        throw storageError(e);
      }
    }
    return local
        ? Files.newInputStream(path(key))
        : client().getObject(bucket, key).getObjectContent();
  }

  public InputStream openRange(String key, long start, long end) throws IOException {
    if (key.startsWith("minio:")) {
      try {
        return minio()
            .getObject(
                io.minio.GetObjectArgs.builder()
                    .bucket(minioBucket)
                    .object(key.substring(6))
                    .offset(start)
                    .length(end - start + 1)
                    .build());
      } catch (Exception e) {
        throw storageError(e);
      }
    }
    if (local) {
      InputStream input = Files.newInputStream(path(key));
      try {
        input.skipNBytes(start);
        return input;
      } catch (IOException e) {
        input.close();
        throw e;
      }
    }
    var request = new GetObjectRequest(bucket, key);
    request.setRange(start, end);
    return client().getObject(request).getObjectContent();
  }

  public void delete(String key) throws IOException {
    if (key.startsWith("minio:")) {
      try {
        minio()
            .removeObject(
                io.minio.RemoveObjectArgs.builder()
                    .bucket(minioBucket)
                    .object(key.substring(6))
                    .build());
        return;
      } catch (Exception e) {
        throw storageError(e);
      }
    }
    if (local) Files.deleteIfExists(path(key));
    else client().deleteObject(bucket, key);
  }

  public void deleteUploadParts(String uploadId, java.util.Collection<String> keys)
      throws IOException {
    if (!uploadId.matches("[a-f0-9-]{36}"))
      throw new IllegalArgumentException("Invalid upload cleanup scope");
    String prefix = "video-search/parts/" + uploadId + "/";
    for (String key : keys) {
      if (!(key.startsWith("minio:") ? key.substring(6) : key).startsWith(prefix))
        throw new IllegalArgumentException("Object cleanup scope mismatch");
      delete(key);
    }
  }

  public String signedGet(String key) {
    if (local) throw new IllegalStateException("ASR requires private OSS storage");
    return client()
        .generatePresignedUrl(bucket, key, new java.util.Date(System.currentTimeMillis() + 1800000))
        .toString();
  }

  @PreDestroy
  void close() {
    if (client != null) client.shutdown();
  }
}

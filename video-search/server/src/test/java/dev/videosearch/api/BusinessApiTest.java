package dev.videosearch.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.videosearch.upload.UploadService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:video-search;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "app.storage=local",
      "app.data-dir=target/test-data",
      "app.admin-password="
    })
@AutoConfigureMockMvc
@org.springframework.test.context.TestExecutionListeners(
    inheritListeners = false,
    listeners = {
      org.springframework.test.context.support.DependencyInjectionTestExecutionListener.class,
      org.springframework.test.context.support.DirtiesContextTestExecutionListener.class,
      org.springframework.test.context.web.ServletTestExecutionListener.class
    })
class BusinessApiTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired UploadService uploads;
  @Autowired dev.videosearch.upload.UploadMaintenance maintenance;
  @Autowired dev.videosearch.task.MediaPipeline pipeline;
  @Autowired dev.videosearch.task.PersistentTasks persistentTasks;
  @Autowired dev.videosearch.storage.ObjectStorage storage;

  @Test
  void expiredUploadCleanupIsDurableScopedAndIdempotent() throws Exception {
    db.update("INSERT INTO vs_library(id,owner,name) VALUES('expire-library','one','x')");
    byte[] bytes = {1, 2, 3};
    String hash = dev.videosearch.lab.JsonFiles.hash(bytes);
    var created =
        (Map<?, ?>)
            uploads.initialize(
                "one", new UploadService.Initialize("expire-library", "x.mp4", 3, hash));
    String id = created.get("id").toString();
    uploads.part("one", id, 0, new java.io.ByteArrayInputStream(bytes), hash);
    String object =
        db.queryForObject(
            "SELECT object_key FROM vs_upload_part WHERE upload_id=?", String.class, id);
    String orphan = "video-search/parts/" + id + "/0/uncommitted";
    Path scratch = Files.createTempFile(Path.of("target/test-data"), "staged-", ".part");
    try {
      Files.write(scratch, new byte[] {4, 5, 6});
      db.update("INSERT INTO vs_upload_object(object_key,upload_id) VALUES(?,?)", orphan, id);
      storage.put(orphan, scratch);
    } finally {
      Files.deleteIfExists(scratch);
    }
    db.update("UPDATE vs_upload SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id=?", id);
    maintenance.enqueueExpired();
    maintenance.enqueueExpired();
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM vs_task WHERE stage='UPLOAD_CLEANUP'", Integer.class));
    String task =
        db.queryForObject("SELECT id FROM vs_task WHERE stage='UPLOAD_CLEANUP'", String.class);
    pipeline.process(persistentTasks.claim(task).orElseThrow());
    assertEquals(
        "EXPIRED", db.queryForObject("SELECT status FROM vs_upload WHERE id=?", String.class, id));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM vs_upload_part WHERE upload_id=?", Integer.class, id));
    assertThrows(java.io.IOException.class, () -> storage.open(object));
    assertThrows(java.io.IOException.class, () -> storage.open(orphan));
    assertThrows(
        IllegalArgumentException.class,
        () -> storage.deleteUploadParts("../../other-directory", List.of()));
  }

  @BeforeEach
  void clean() {
    for (String table :
        List.of(
            "vs_favorite",
            "vs_favorite_folder",
            "vs_outbox",
            "vs_task",
            "vs_video",
            "vs_upload_part",
            "vs_upload_object",
            "vs_upload",
            "vs_library")) db.update("DELETE FROM " + table);
  }

  @Test
  void searchScopesAndWaitingCancellationAreEnforced() throws Exception {
    db.update(
        "INSERT INTO vs_library(id,owner,name) VALUES('owned','one','x'),('foreign','two','y')");
    mvc.perform(
            post("/api/v1/searches")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"libraryId\":\"foreign\",\"query\":\"喝饮料\",\"mode\":\"AGENT\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/searches")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"libraryId\":\"owned\",\"videoIds\":[\"foreign-video\"],\"query\":\"喝饮料\"}"))
        .andExpect(status().isNotFound());
    db.update(
        "INSERT INTO vs_task(id,owner,kind,stage,status,idempotency_key)"
            + " VALUES('parent','one','PARSE','PROBE','WAITING','parent')");
    db.update(
        "INSERT INTO vs_task(id,owner,parent_id,kind,stage,status,idempotency_key)"
            + " VALUES('child','one','parent','PARSE','VISUAL','PENDING','child')");
    mvc.perform(post("/api/v1/tasks/parent/cancel").with(user("two")).with(csrf()))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/tasks/parent/cancel").with(user("one")).with(csrf()))
        .andExpect(status().isOk());
    assertEquals(
        2,
        db.queryForObject(
            "SELECT COUNT(*) FROM vs_task WHERE status='CANCELLED' AND cancel_requested=TRUE",
            Integer.class));
  }

  @Test
  void anonymousReadinessCheckWorksWithoutGrantingAccessToUserData() throws Exception {
    mvc.perform(get("/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.application").value("FRAMELOCK"))
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.database").value("UP"));
    mvc.perform(get("/api/v1/favorites")).andExpect(status().isUnauthorized());
  }

  @Test
  void authenticationCsrfAndLibraryOwnershipAreEnforced() throws Exception {
    mvc.perform(get("/api/v1/libraries")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/libraries")
                .with(user("one"))
                .contentType("application/json")
                .content("{\"name\":\"素材\"}"))
        .andExpect(status().isForbidden());
    String body =
        mvc.perform(
                post("/api/v1/libraries")
                    .with(user("one"))
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"name\":\"素材\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = dev.videosearch.lab.JsonFiles.JSON.readTree(body).path("id").asText();
    mvc.perform(get("/api/v1/libraries/" + id + "/videos").with(user("two")))
        .andExpect(status().isNotFound());
  }

  @Test
  void projectsCanBeRenamedButOnlyEmptyProjectsCanBeDeleted() throws Exception {
    db.update(
        "INSERT INTO vs_library(id,owner,name)"
            + " VALUES('empty-project','one','Old'),('full-project','one','Full')");
    flowVideo("flow-video", "full-project", "one", "published");
    mvc.perform(
            patch("/api/v1/libraries/empty-project")
                .with(user("two"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\"Renamed\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            patch("/api/v1/libraries/empty-project")
                .with(user("one"))
                .contentType("application/json")
                .content("{\"name\":\"Renamed\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            patch("/api/v1/libraries/empty-project")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\" Renamed \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"));
    mvc.perform(delete("/api/v1/libraries/full-project").with(user("one")).with(csrf()))
        .andExpect(status().isConflict());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM vs_video WHERE id='flow-video' AND deleted=FALSE",
            Integer.class));
    uploads.initialize(
        "one", new UploadService.Initialize("empty-project", "pending.mp4", 1, "a".repeat(64)));
    mvc.perform(delete("/api/v1/libraries/empty-project").with(user("one")).with(csrf()))
        .andExpect(status().isConflict());
    db.update("UPDATE vs_upload SET status='EXPIRED' WHERE library_id='empty-project'");
    mvc.perform(delete("/api/v1/libraries/empty-project").with(user("two")).with(csrf()))
        .andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/libraries/empty-project").with(user("one")).with(csrf()))
        .andExpect(status().isOk());
    assertEquals(
        true,
        db.queryForObject(
            "SELECT deleted FROM vs_library WHERE id='empty-project'", Boolean.class));
    mvc.perform(get("/api/v1/libraries").with(user("one")))
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(get("/api/v1/libraries/empty-project/videos").with(user("one")))
        .andExpect(status().isNotFound());
    assertThrows(
        dev.videosearch.api.ApiError.class,
        () ->
            uploads.initialize(
                "one", new UploadService.Initialize("empty-project", "x.mp4", 1, "a".repeat(64))));
  }

  @Test
  void folderRenameAndDeletionPreserveFavoriteAndItsSource() throws Exception {
    db.update(
        "INSERT INTO vs_library(id,owner,name) VALUES('source-project','one','Source project')");
    flowVideo("source-video", "source-project", "one", "v");
    db.update(
        "INSERT INTO vs_favorite_folder(id,owner,name)"
            + " VALUES('rename-folder','one','Old'),('other-folder','one','Other')");
    db.update(
        "INSERT INTO vs_favorite(id,owner,video_id,start_ms,end_ms,folder_id)"
            + " VALUES('saved','one','source-video',1000,3000,'rename-folder')");
    mvc.perform(
            patch("/api/v1/favorite-folders/rename-folder")
                .with(user("two"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\"New\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            patch("/api/v1/favorite-folders/rename-folder")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\"Other\"}"))
        .andExpect(status().isConflict());
    mvc.perform(
            patch("/api/v1/favorite-folders/rename-folder")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\" New \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("New"));
    mvc.perform(delete("/api/v1/favorite-folders/rename-folder").with(user("two")).with(csrf()))
        .andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/favorite-folders/rename-folder").with(user("one")))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/api/v1/favorite-folders/rename-folder").with(user("one")).with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/favorites").with(user("one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("saved"))
        .andExpect(jsonPath("$[0].folder_id").isEmpty())
        .andExpect(jsonPath("$[0].library_id").value("source-project"))
        .andExpect(jsonPath("$[0].library_name").value("Source project"))
        .andExpect(jsonPath("$[0].start_ms").value(1000));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM vs_video WHERE id='source-video' AND deleted=FALSE",
            Integer.class));
  }

  @Test
  void unreadySearchesCreateNoTaskAndSearchHistoryKeepsItsScope() throws Exception {
    db.update(
        "INSERT INTO vs_library(id,owner,name) VALUES('search-project','one','Original project')");
    mvc.perform(
            post("/api/v1/searches")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"libraryId\":\"search-project\",\"query\":\"drink\"}"))
        .andExpect(status().isConflict());
    flowVideo("unready-video", "search-project", "one", null);
    mvc.perform(
            post("/api/v1/searches")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"libraryId\":\"search-project\",\"query\":\"drink\"}"))
        .andExpect(status().isConflict());
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM vs_task", Integer.class));
    flowVideo("ready-video", "search-project", "one", "published");
    mvc.perform(
            post("/api/v1/searches")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"libraryId\":\"search-project\",\"videoIds\":[\"unready-video\"],\"query\":\"drink\"}"))
        .andExpect(status().isConflict());
    String body =
        mvc.perform(
                post("/api/v1/searches")
                    .with(user("one"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        "{\"libraryId\":\"search-project\",\"query\":\"find"
                            + " drink\",\"mode\":\"AGENT\"}"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = dev.videosearch.lab.JsonFiles.JSON.readTree(body).path("id").asText();
    mvc.perform(get("/api/v1/searches/" + id).with(user("one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.context.libraryId").value("search-project"))
        .andExpect(jsonPath("$.context.query").value("find drink"))
        .andExpect(jsonPath("$.context.mode").value("AGENT"));
    mvc.perform(get("/api/v1/tasks").with(user("one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].context.libraryName").value("Original project"))
        .andExpect(jsonPath("$[0].context.query").value("find drink"));
    mvc.perform(get("/api/v1/tasks/" + id).with(user("one")))
        .andExpect(jsonPath("$.context.libraryId").value("search-project"));
    mvc.perform(get("/api/v1/searches/" + id).with(user("two"))).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/tasks").with(user("two"))).andExpect(jsonPath("$.length()").value(0));
    db.update("UPDATE vs_task SET status='FAILED' WHERE id=?", id);
    db.update("UPDATE vs_video SET active_version=NULL WHERE library_id='search-project'");
    mvc.perform(post("/api/v1/tasks/" + id + "/retry").with(user("one")).with(csrf()))
        .andExpect(status().isConflict());
    assertEquals(
        "FAILED", db.queryForObject("SELECT status FROM vs_task WHERE id=?", String.class, id));
    db.update("UPDATE vs_video SET deleted=TRUE WHERE library_id='search-project'");
    mvc.perform(delete("/api/v1/libraries/search-project").with(user("one")).with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/searches/" + id).with(user("one")))
        .andExpect(jsonPath("$.context.projectDeleted").value(true))
        .andExpect(jsonPath("$.context.query").value("find drink"));
  }

  private void flowVideo(String id, String project, String owner, String version) {
    db.update(
        "INSERT INTO"
            + " vs_video(id,library_id,owner,name,object_key,file_hash,file_size,duration_ms,has_audio,status,active_version)"
            + " VALUES(?,?,?,?,'key','hash',1,10000,FALSE,'READY',?)",
        id,
        project,
        owner,
        id + ".mp4",
        version);
  }

  @Test
  void favoriteFoldersValidateNamesAndStayPrivate() throws Exception {
    mvc.perform(
            post("/api/v1/favorite-folders")
                .with(user("one"))
                .contentType("application/json")
                .content("{\"name\":\"Clips\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/favorite-folders")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\"   \"}"))
        .andExpect(status().isBadRequest());
    for (String owner : List.of("one", "two")) {
      mvc.perform(
              post("/api/v1/favorite-folders")
                  .with(user(owner))
                  .with(csrf())
                  .contentType("application/json")
                  .content("{\"name\":\" Clips \"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.name").value("Clips"));
    }
    mvc.perform(
            post("/api/v1/favorite-folders")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\"Clips\"}"))
        .andExpect(status().isConflict());
    mvc.perform(get("/api/v1/favorite-folders").with(user("one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void favoritesCanBeClassifiedAndMovedWithoutDuplicationOrCrossUserAccess() throws Exception {
    db.update("INSERT INTO vs_library(id,owner,name) VALUES('favorite-library','one','Video')");
    db.update(
        "INSERT INTO"
            + " vs_video(id,library_id,owner,name,object_key,file_hash,file_size,duration_ms,has_audio,status)"
            + " VALUES('favorite-video','favorite-library','one','clip.mp4','key','hash',1,10000,FALSE,'READY')");
    db.update(
        "INSERT INTO vs_favorite_folder(id,owner,name)"
            + " VALUES('folder-a','one','A'),('folder-b','one','B'),('foreign-folder','two','A')");
    String body =
        mvc.perform(
                post("/api/v1/favorites")
                    .with(user("one"))
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"videoId\":\"favorite-video\",\"startMs\":1000,\"endMs\":3000}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = dev.videosearch.lab.JsonFiles.JSON.readTree(body).path("id").asText();
    assertNull(db.queryForObject("SELECT folder_id FROM vs_favorite WHERE id=?", String.class, id));
    mvc.perform(
            post("/api/v1/favorites")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"videoId\":\"favorite-video\",\"startMs\":1000,\"endMs\":3000,\"folderId\":\"folder-a\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM vs_favorite", Integer.class));
    for (String bad : List.of("foreign-folder", "missing-folder")) {
      mvc.perform(
              patch("/api/v1/favorites/" + id + "/folder")
                  .with(user("one"))
                  .with(csrf())
                  .contentType("application/json")
                  .content("{\"folderId\":\"" + bad + "\"}"))
          .andExpect(status().isNotFound());
      mvc.perform(
              post("/api/v1/favorites")
                  .with(user("one"))
                  .with(csrf())
                  .contentType("application/json")
                  .content(
                      "{\"videoId\":\"favorite-video\",\"startMs\":1000,\"endMs\":3000,\"folderId\":\""
                          + bad
                          + "\"}"))
          .andExpect(status().isNotFound());
    }
    mvc.perform(
            patch("/api/v1/favorites/" + id + "/folder")
                .with(user("two"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"folderId\":null}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            patch("/api/v1/favorites/" + id + "/folder")
                .with(user("one"))
                .contentType("application/json")
                .content("{\"folderId\":null}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            patch("/api/v1/favorites/" + id + "/folder")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"folderId\":\"folder-b\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/favorites").with(user("one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].folder_id").value("folder-b"))
        .andExpect(jsonPath("$[0].start_ms").value(1000));
    mvc.perform(get("/api/v1/favorites").with(user("two")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(
            patch("/api/v1/favorites/" + id + "/folder")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"folderId\":null}"))
        .andExpect(status().isOk());
    assertNull(db.queryForObject("SELECT folder_id FROM vs_favorite WHERE id=?", String.class, id));
    db.update("UPDATE vs_video SET deleted=TRUE WHERE id='favorite-video'");
    mvc.perform(
            patch("/api/v1/favorites/" + id + "/folder")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"folderId\":\"folder-a\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/favorites").with(user("one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void projectVisibilityIsOwnedCsrfProtectedAndDoesNotDeleteVideos() throws Exception {
    db.update("INSERT INTO vs_library(id,owner,name) VALUES('project','one','Game')");
    db.update(
        "INSERT INTO"
            + " vs_video(id,library_id,owner,name,object_key,file_hash,file_size,duration_ms,has_audio,status)"
            + " VALUES('project-video','project','one','clip.mp4','key','hash',1,1000,FALSE,'READY')");
    mvc.perform(
            patch("/api/v1/libraries/project/visibility")
                .with(user("one"))
                .contentType("application/json")
                .content("{\"archived\":true}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            patch("/api/v1/libraries/project/visibility")
                .with(user("two"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"archived\":true}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            patch("/api/v1/libraries/project/visibility")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
    for (boolean archived : new boolean[] {true, true, false}) {
      mvc.perform(
              patch("/api/v1/libraries/project/visibility")
                  .with(user("one"))
                  .with(csrf())
                  .contentType("application/json")
                  .content("{\"archived\":" + archived + "}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.archived").value(archived));
      assertEquals(
          archived,
          db.queryForObject("SELECT archived FROM vs_library WHERE id='project'", Boolean.class));
      mvc.perform(get("/api/v1/libraries/project/videos").with(user("one")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].id").value("project-video"));
    }
  }

  @Test
  void pendingUploadsReserveLibraryCapacityAndOversizeIsRejected() {
    String library = "library";
    db.update("INSERT INTO vs_library(id,owner,name) VALUES(?,?,?)", library, "one", "x");
    for (int i = 0; i < 10; i++)
      uploads.initialize("one", new UploadService.Initialize(library, "x.mp4", 1, "a".repeat(64)));
    assertThrows(
        ApiError.class,
        () ->
            uploads.initialize(
                "one", new UploadService.Initialize(library, "eleventh.mp4", 1, "a".repeat(64))));
    assertThrows(
        ApiError.class,
        () ->
            uploads.initialize(
                "one",
                new UploadService.Initialize(
                    library, "big.mp4", 2L * 1024 * 1024 * 1024 + 1, "a".repeat(64))));
  }

  @Test
  void repeatedPartIsIdempotentButDifferentBytesAreRejected() throws Exception {
    db.update("INSERT INTO vs_library(id,owner,name) VALUES('l','one','x')");
    byte[] bytes = {1, 2, 3};
    String hash = dev.videosearch.lab.JsonFiles.hash(bytes);
    var initialized =
        (Map<?, ?>) uploads.initialize("one", new UploadService.Initialize("l", "x.mp4", 3, hash));
    String id = (String) initialized.get("id");
    uploads.part("one", id, 0, new java.io.ByteArrayInputStream(bytes), hash);
    uploads.part("one", id, 0, new java.io.ByteArrayInputStream(bytes), hash);
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM vs_upload_part WHERE upload_id=?", Integer.class, id));
    assertThrows(
        ApiError.class,
        () ->
            uploads.part(
                "one", id, 0, new java.io.ByteArrayInputStream(new byte[] {4, 5, 6}), null));
    assertThrows(ApiError.class, () -> uploads.state("two", id));
  }
}

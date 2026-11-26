<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from "vue";
import { createSHA256 } from "hash-wasm";
import { api, refreshCsrf } from "./api";
import { describeCompletion } from "./searchCompletion.js";
const user = ref(null),
  login = ref({ username: "demo", password: "" }),
  error = ref(""),
  notice = ref(""),
  busy = ref(false);
const libraries = ref([]),
  library = ref(""),
  videos = ref([]),
  videosLoading = ref(false),
  tasks = ref([]),
  favorites = ref([]),
  favoriteFolders = ref([]),
  favoriteFilter = ref("all"),
  favoriteDialog = ref(null),
  destinationFolder = ref(""),
  newFolderName = ref(""),
  folderBusy = ref(false),
  projectDialog = ref(null),
  projectName = ref(""),
  projectBusy = ref(false),
  tab = ref("library"),
  newLibrary = ref("");
const query = ref(""),
  mode = ref("NORMAL"),
  search = ref(null),
  task = ref(null),
  taskId = ref(""),
  taskPanelOpen = ref(false),
  showUpload = ref(false),
  upload = ref({ percent: 0, message: "", running: false }),
  fileInput = ref(null);
const batch = ref({
  active: false,
  current: 0,
  total: 0,
  name: "",
  cancelled: false,
});
const isUploading = computed(() => upload.value.running || batch.value.active);
const player = ref(null),
  playing = ref(null),
  pendingSeek = ref(0),
  searchId = ref("");
let events,
  polling,
  pollBusy = false,
  uploadAbort,
  modalReturnFocus,
  videoRequestSequence = 0,
  taskWatchSequence = 0,
  historyRequestSequence = 0;
watch(showUpload, async (visible) => {
  if (visible) {
    modalReturnFocus = document.activeElement;
    await nextTick();
    document.querySelector('.modal [aria-label="关闭上传窗口"]')?.focus();
  } else {
    modalReturnFocus?.focus?.();
  }
});
watch(favoriteDialog, async (dialog) => {
  if (dialog) {
    modalReturnFocus = document.activeElement;
    await nextTick();
    document
      .querySelector(".favorite-dialog select, .favorite-dialog input")
      ?.focus();
  } else modalReturnFocus?.focus?.();
});
watch(projectDialog, async (dialog) => {
  if (dialog) {
    modalReturnFocus = document.activeElement;
    await nextTick();
    document.querySelector(".project-dialog input")?.focus();
  } else modalReturnFocus?.focus?.();
});
function trapDialogFocus(event) {
  const controls = [
    ...event.currentTarget.querySelectorAll(
      "button:not([disabled]), input:not([disabled]), select:not([disabled])",
    ),
  ];
  const first = controls[0],
    last = controls.at(-1);
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last?.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first?.focus();
  }
}
const currentLibrary = computed(() =>
    libraries.value.find((l) => l.id === library.value),
  ),
  results = computed(() => search.value?.result?.matches || []);
const visibleLibraries = computed(() =>
  libraries.value.filter((l) => !l.archived),
);
const filteredFavorites = computed(() =>
  favorites.value.filter(
    (f) =>
      favoriteFilter.value === "all" ||
      (favoriteFilter.value === "unfiled"
        ? !f.folder_id
        : f.folder_id === favoriteFilter.value),
  ),
);
const folderCount = (id) =>
  favorites.value.filter((f) =>
    id === "unfiled" ? !f.folder_id : f.folder_id === id,
  ).length;
const folderName = (id) =>
  favoriteFolders.value.find((f) => f.id === id)?.name || "未分类";
const selectedFavoriteFolder = computed(() =>
  favoriteFolders.value.find((f) => f.id === favoriteFilter.value),
);
const folderNameOnly = computed(() =>
  ["create", "rename"].includes(favoriteDialog.value?.kind),
);
const searchProjectName = computed(
  () =>
    search.value?.context?.libraryName ||
    currentLibrary.value?.name ||
    "先创建一个视频项目",
);
const displayTime = (value) => {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? ""
    : new Intl.DateTimeFormat("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      }).format(date);
};
const readyCount = computed(
  () => videos.value.filter((v) => v.active_version).length,
);
const active = computed(() =>
  ["PENDING", "RUNNING", "WAITING", "RETRY_WAIT"].includes(
    search.value?.status,
  ),
);
const statuses = {
  PENDING: "等待处理",
  RUNNING: "处理中",
  WAITING: "正在处理",
  RETRY_WAIT: "稍后重试",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  CANCELLED: "已取消",
  READY: "可以查找",
  READY_PARTIAL: "可以查找（部分语音未识别）",
  DELETED: "已删除",
};
const label = (s) => statuses[s] || s;
const stageName = (stage) =>
  ({
    PROBE: "检查视频文件",
    VISUAL: "查看视频画面",
    ASR_SUBMIT: "识别视频中的语音",
    ASR_POLL: "等待语音识别完成",
    INDEX: "整理视频片段",
    RETRIEVE: "查找视频片段",
    CLEANUP: "删除视频文件",
    UPLOAD_CLEANUP: "清理上传时的临时文件",
  })[stage] || "处理视频";
const clock = (ms) => {
  const ticks = Math.round(Math.max(0, ms) / 100);
  return `${String(Math.floor(ticks / 600)).padStart(2, "0")}:${((ticks % 600) / 10).toFixed(1).padStart(4, "0")}`;
};
const usageCost = (step) =>
  (step.modelUsage || [])
    .reduce((sum, usage) => sum + Number(usage.estimatedOrReservedYuan || 0), 0)
    .toFixed(6);
async function act(fn) {
  error.value = "";
  try {
    return await fn();
  } catch (e) {
    error.value = e.message;
    return null;
  }
}
async function signIn() {
  busy.value = true;
  await act(async () => {
    await refreshCsrf();
    user.value = await api("/auth/login", {
      method: "POST",
      body: login.value,
    });
    login.value.password = "";
    await refreshCsrf();
    await load();
  });
  busy.value = false;
}
async function signOut() {
  await act(async () => {
    await api("/auth/logout", { method: "POST" });
    user.value = null;
    taskWatchSequence++;
    events?.close();
    playing.value = null;
    search.value = null;
    task.value = null;
    taskPanelOpen.value = false;
    favoriteDialog.value = null;
    projectDialog.value = null;
    await refreshCsrf();
  });
}
async function load() {
  libraries.value = await api("/libraries");
  if (
    !library.value ||
    !visibleLibraries.value.some((l) => l.id === library.value)
  )
    library.value = visibleLibraries.value[0]?.id || "";
  await Promise.all([loadVideos(), loadTasks(), loadFavorites()]);
}
async function loadVideos() {
  const projectId = library.value,
    requestSequence = ++videoRequestSequence;
  videosLoading.value = true;
  try {
    const data = projectId ? await api(`/libraries/${projectId}/videos`) : [];
    if (projectId === library.value && requestSequence === videoRequestSequence)
      videos.value = data;
  } finally {
    if (requestSequence === videoRequestSequence) videosLoading.value = false;
  }
}
async function chooseLibrary(id) {
  taskWatchSequence++;
  library.value = id;
  videos.value = [];
  search.value = null;
  searchId.value = "";
  playing.value = null;
  task.value = null;
  taskId.value = "";
  taskPanelOpen.value = false;
  events?.close();
  tab.value = "library";
  await act(loadVideos);
}
function openProjectSettings() {
  error.value = "";
  projectName.value = currentLibrary.value.name;
  projectDialog.value = { ...currentLibrary.value };
}
async function renameProject() {
  if (!projectName.value.trim() || projectBusy.value) return;
  projectBusy.value = true;
  const result = await act(async () => {
    const changed = await api(`/libraries/${projectDialog.value.id}`, {
      method: "PATCH",
      body: { name: projectName.value.trim() },
    });
    libraries.value = await api("/libraries");
    if (search.value?.context?.libraryId === changed.id)
      search.value.context.libraryName = changed.name;
    await Promise.all([loadTasks(), loadFavorites()]);
    notice.value = "项目名称已修改";
    return true;
  });
  if (result) projectDialog.value = null;
  projectBusy.value = false;
}
async function deleteProject() {
  if (!projectDialog.value || projectBusy.value) return;
  const project = projectDialog.value;
  if (
    !confirm(
      `删除项目“${project.name}”？只允许删除没有视频和上传文件的空项目。历史处理记录仍会保留。`,
    )
  )
    return;
  projectBusy.value = true;
  const result = await act(async () => {
    await api(`/libraries/${project.id}`, { method: "DELETE" });
    await load();
    await chooseLibrary(library.value);
    notice.value = "空项目已删除";
    return true;
  });
  if (result) projectDialog.value = null;
  projectBusy.value = false;
}
async function createLibrary() {
  if (!newLibrary.value.trim()) return;
  await act(async () => {
    const l = await api("/libraries", {
      method: "POST",
      body: { name: newLibrary.value.trim() },
    });
    newLibrary.value = "";
    await load();
    await chooseLibrary(l.id);
  });
}
async function loadTasks() {
  tasks.value = await api("/tasks");
}
async function loadFavorites() {
  const [saved, folders] = await Promise.all([
    api("/favorites"),
    api("/favorite-folders"),
  ]);
  favorites.value = saved;
  favoriteFolders.value = folders;
}
const completion = computed(() => describeCompletion(search.value?.result));
const followUp = ref("");
async function submitFollowUp() {
  if (busy.value || !followUp.value.trim() || !searchId.value) return;
  busy.value = true;
  historyRequestSequence++;
  playing.value = null;
  const parent = searchId.value;
  const question = followUp.value.trim();
  try {
    await act(async () => {
      const created = await api("/searches", {
        method: "POST",
        body: {
          libraryId: search.value.context.libraryId,
          videoIds: search.value.context.videoIds || [],
          query: question,
          mode: "AGENT",
          parentSearchId: parent,
        },
      });
      searchId.value = created.id;
      search.value = { status: "PENDING" };
      followUp.value = "";
      await viewTask(created.id);
      await loadTasks();
    });
  } finally {
    busy.value = false;
  }
}
async function submitSearch() {
  if (!library.value || !query.value.trim()) return;
  if (
    videosLoading.value ||
    !readyCount.value ||
    search.value?.context?.projectDeleted
  ) {
    error.value =
      "还没有处理好的视频。请先上传视频，或等视频处理完成后再查找。";
    return;
  }
  historyRequestSequence++;
  busy.value = true;
  playing.value = null;
  await act(async () => {
    const created = await api("/searches", {
      method: "POST",
      body: { libraryId: library.value, query: query.value, mode: mode.value },
    });
    searchId.value = created.id;
    search.value = { status: "PENDING" };
    tab.value = "library";
    await viewTask(created.id);
    await loadTasks();
  });
  busy.value = false;
}
async function refreshSearch() {
  const id = searchId.value;
  if (!id) return;
  const saved = await api(`/searches/${id}`);
  if (id === searchId.value) search.value = saved;
}
async function openHistoryTask(history) {
  if (history.kind !== "SEARCH") return act(() => viewTask(history.id, true));
  const requestSequence = ++historyRequestSequence,
    watchAtStart = taskWatchSequence;
  await act(async () => {
    const saved = await api(`/searches/${history.id}`);
    if (
      requestSequence !== historyRequestSequence ||
      watchAtStart !== taskWatchSequence
    )
      return;
    const context = saved.context;
    if (!context?.libraryId)
      throw new Error("这条旧记录没有保存项目资料，暂时无法恢复。");
    await chooseLibrary(context.projectDeleted ? "" : context.libraryId);
    if (
      requestSequence !== historyRequestSequence ||
      taskWatchSequence !== watchAtStart + 1
    )
      return;
    query.value = context.query;
    mode.value = context.mode === "AGENT" ? "AGENT" : "NORMAL";
    searchId.value = history.id;
    search.value = saved;
    await viewTask(history.id);
  });
}
function switchTab(next) {
  historyRequestSequence++;
  tab.value = next;
}
async function searchDeeper() {
  mode.value = "AGENT";
  await submitSearch();
}
function focusSearchInput() {
  document.getElementById("query")?.focus();
}
async function viewTask(id, reveal = false) {
  const watchSequence = ++taskWatchSequence;
  if (reveal) taskPanelOpen.value = true;
  events?.close();
  const loaded = await api(`/tasks/${id}`);
  if (watchSequence !== taskWatchSequence) return;
  taskId.value = id;
  task.value = loaded;
  const stream = new EventSource(`/api/v1/tasks/${id}/events`);
  events = stream;
  stream.addEventListener("state", (event) => {
    if (watchSequence !== taskWatchSequence || taskId.value !== id) return;
    act(async () => {
      task.value = { ...task.value, ...JSON.parse(event.data) };
      if (["SUCCEEDED", "FAILED", "CANCELLED"].includes(task.value.status)) {
        stream.close();
        const finished = await api(`/tasks/${id}`);
        if (watchSequence !== taskWatchSequence || taskId.value !== id) return;
        task.value = finished;
        if (task.value.kind === "SEARCH" && searchId.value === id) {
          await refreshSearch();
        }
        await loadVideos();
        await loadTasks();
      }
    });
  });
  stream.onerror = () => {
    /* Persistent polling below handles reconnects and missed events. */
  };
}
async function controlTask(command) {
  if (!taskId.value) return;
  await act(async () => {
    task.value = await api(`/tasks/${taskId.value}/${command}`, {
      method: "POST",
    });
    await loadTasks();
  });
}
async function play(match) {
  const id = match.videoId || match.video_id;
  playing.value = { ...match, videoId: id };
  pendingSeek.value = (match.startMs ?? match.start_ms ?? 0) / 1000;
  await nextTick();
  if (player.value?.readyState >= 1) seek();
}
function seek() {
  if (player.value) {
    player.value.currentTime = pendingSeek.value;
    player.value.play().catch(() => {});
  }
}
function pauseAtEnd() {
  const end = playing.value?.endMs ?? playing.value?.end_ms;
  if (end && player.value.currentTime >= end / 1000) player.value.pause();
}
async function save(match) {
  error.value = "";
  const existing = favorites.value.find(
    (f) =>
      f.video_id === match.videoId &&
      f.start_ms === match.startMs &&
      f.end_ms === match.endMs,
  );
  destinationFolder.value = existing?.folder_id || "";
  newFolderName.value = "";
  favoriteDialog.value = { kind: "save", match };
}
function openFavoriteMove(favorite) {
  error.value = "";
  destinationFolder.value = favorite.folder_id || "";
  newFolderName.value = "";
  favoriteDialog.value = { kind: "move", favorite };
}
function openFolderCreate() {
  error.value = "";
  newFolderName.value = "";
  favoriteDialog.value = { kind: "create" };
}
function openFolderRename() {
  if (!selectedFavoriteFolder.value) return;
  error.value = "";
  newFolderName.value = selectedFavoriteFolder.value.name;
  favoriteDialog.value = {
    kind: "rename",
    folder: selectedFavoriteFolder.value,
  };
}
async function deleteFavoriteFolder() {
  const folder = selectedFavoriteFolder.value;
  if (
    !folder ||
    !confirm(
      `删除收藏夹“${folder.name}”？其中的片段会回到“未分类”，不会取消收藏或删除视频。`,
    )
  )
    return;
  await act(async () => {
    await api(`/favorite-folders/${folder.id}`, { method: "DELETE" });
    favoriteFilter.value = "unfiled";
    await loadFavorites();
    notice.value = "收藏夹已删除，片段已放回“未分类”";
  });
}
async function createFavoriteFolder() {
  if (!newFolderName.value.trim() || folderBusy.value) return;
  folderBusy.value = true;
  const renaming = favoriteDialog.value?.kind === "rename";
  const created = await act(() =>
    api(
      renaming
        ? `/favorite-folders/${favoriteDialog.value.folder.id}`
        : "/favorite-folders",
      {
        method: renaming ? "PATCH" : "POST",
        body: { name: newFolderName.value.trim() },
      },
    ),
  );
  if (created) {
    if (renaming)
      favoriteFolders.value = favoriteFolders.value.map((f) =>
        f.id === created.id ? { ...f, name: created.name } : f,
      );
    else favoriteFolders.value.push(created);
    destinationFolder.value = created.id;
    newFolderName.value = "";
    if (folderNameOnly.value) {
      favoriteFilter.value = created.id;
      favoriteDialog.value = null;
      notice.value = renaming
        ? `收藏夹已改名为“${created.name}”`
        : `已创建收藏夹“${created.name}”`;
    }
  }
  folderBusy.value = false;
}
async function submitFavorite() {
  if (folderNameOnly.value) return createFavoriteFolder();
  if (folderBusy.value || !favoriteDialog.value) return;
  folderBusy.value = true;
  const dialog = favoriteDialog.value;
  const completed = await act(async () => {
    const folderId = destinationFolder.value || null;
    if (dialog.kind === "move")
      await api(`/favorites/${dialog.favorite.id}/folder`, {
        method: "PATCH",
        body: { folderId },
      });
    else
      await api("/favorites", {
        method: "POST",
        body: {
          videoId: dialog.match.videoId,
          startMs: dialog.match.startMs,
          endMs: dialog.match.endMs,
          folderId,
        },
      });
    await loadFavorites();
    if (dialog.kind === "save") favoriteFilter.value = folderId || "unfiled";
    notice.value = `已放入“${folderName(folderId)}”`;
    return true;
  });
  if (completed) favoriteDialog.value = null;
  folderBusy.value = false;
}
function folderNameEnter(event) {
  if (!folderNameOnly.value) {
    event.preventDefault();
    createFavoriteFolder();
  }
}
async function removeFavorite(id) {
  await act(async () => {
    await api(`/favorites/${id}`, { method: "DELETE" });
    await loadFavorites();
  });
}
async function removeVideo(video) {
  if (!confirm(`删除“${video.name}”？删除后就不能播放或查找其中的片段了。`))
    return;
  await act(async () => {
    await api(`/videos/${video.id}`, { method: "DELETE" });
    if (playing.value?.videoId === video.id) playing.value = null;
    await loadVideos();
    await loadFavorites();
    await refreshSearch();
  });
}
async function sha(blob, onProgress) {
  const hash = await createSHA256();
  hash.init();
  const chunk = 5 * 1024 * 1024;
  for (let offset = 0; offset < blob.size; offset += chunk) {
    hash.update(
      new Uint8Array(await blob.slice(offset, offset + chunk).arrayBuffer()),
    );
    onProgress?.(Math.min(blob.size, offset + chunk) / blob.size);
    await new Promise((r) => setTimeout(r, 0));
  }
  return hash.digest("hex");
}
async function uploadFile(event) {
  const files = Array.from(event.target.files || []);
  if (!files.length) return;
  if (files.length > 10) {
    error.value = "一次最多选择十个视频";
    event.target.value = "";
    return;
  }
  batch.value = {
    active: true,
    current: 0,
    total: files.length,
    name: "",
    cancelled: false,
  };
  try {
    for (let i = 0; i < files.length; i++) {
      if (batch.value.cancelled) break;
      batch.value.current = i + 1;
      batch.value.name = files[i].name;
      if (!(await uploadSingle(files[i]))) {
        error.value = files[i].name + "：" + error.value;
        break;
      }
    }
    if (!error.value && !batch.value.cancelled)
      upload.value.message = `${files.length} 个视频已上传，处理完成后就能查找片段`;
  } finally {
    batch.value.active = false;
    event.target.value = "";
  }
}
function pauseUpload() {
  batch.value.cancelled = true;
  uploadAbort?.abort();
}
async function uploadSingle(file) {
  if (file.size > 2 * 1024 ** 3) {
    error.value = "单视频最大2 GiB";
    return false;
  }
  upload.value = { percent: 0, message: "正在检查文件…", running: true };
  uploadAbort = new AbortController();
  const success = await act(async () => {
    const hash = await sha(
      file,
      (p) => (upload.value.percent = Math.round(p * 10)),
    );
    const key = `video-search-upload:${user.value.username}:${library.value}:${hash}`;
    let session,
      old = localStorage.getItem(key);
    if (old) {
      try {
        const state = await api(`/uploads/${old}`);
        if (state.status === "OPEN")
          session = {
            id: old,
            totalParts: state.totalParts,
            partSizeBytes: 5 * 1024 * 1024,
          };
        else if (state.status === "COMPLETE") {
          localStorage.removeItem(key);
          throw new Error("这个上传已完成，请刷新素材列表");
        }
      } catch (e) {
        if (e.message.includes("已完成")) throw e;
        localStorage.removeItem(key);
      }
    }
    if (!session) {
      session = await api("/uploads", {
        method: "POST",
        body: {
          libraryId: library.value,
          name: file.name,
          sizeBytes: file.size,
          sha256: hash,
        },
      });
      localStorage.setItem(key, session.id);
    }
    const state = await api(`/uploads/${session.id}`),
      done = new Set(state.parts.map((p) => p.part_no));
    for (let part = 0; part < session.totalParts; part++) {
      if (uploadAbort.signal.aborted)
        throw new Error("上传已暂停，重新选择相同文件即可续传");
      if (!done.has(part)) {
        const blob = file.slice(
          part * session.partSizeBytes,
          Math.min(file.size, (part + 1) * session.partSizeBytes),
        );
        upload.value.message = `正在上传（${part + 1} / ${session.totalParts}）`;
        const partHash = await sha(blob);
        await api(`/uploads/${session.id}/parts/${part}`, {
          method: "PUT",
          body: blob,
          headers: {
            "X-Part-SHA256": partHash,
            "Content-Type": "application/octet-stream",
          },
          signal: uploadAbort.signal,
        });
      }
      upload.value.percent =
        10 + Math.round(((part + 1) / session.totalParts) * 85);
    }
    upload.value.message = "正在保存视频，请稍等…";
    const completed = await api(`/uploads/${session.id}/complete`, {
      method: "POST",
    });
    localStorage.removeItem(key);
    upload.value = {
      percent: 100,
      message: "上传完成，正在处理画面和声音",
      running: false,
    };
    await loadVideos();
    await loadTasks();
    await viewTask(completed.taskId);
    return true;
  });
  upload.value.running = false;
  return success === true;
}
function sessionExpired() {
  if (!user.value) return;
  user.value = null;
  taskWatchSequence++;
  playing.value = null;
  search.value = null;
  task.value = null;
  taskPanelOpen.value = false;
  showUpload.value = false;
  favoriteDialog.value = null;
  projectDialog.value = null;
  searchId.value = "";
  events?.close();
  uploadAbort?.abort();
  batch.value.cancelled = true;
  error.value = "会话已失效，请重新登录；服务端任务不会因此丢失。";
}
onMounted(async () => {
  window.addEventListener("keydown", closeFloatingPanel);
  window.addEventListener("video-search:unauthenticated", sessionExpired);
  await act(async () => {
    await refreshCsrf();
    try {
      user.value = await api("/auth/me");
    } catch {
      return;
    }
    await load();
  });
  polling = setInterval(async () => {
    if (!user.value || pollBusy) return;
    pollBusy = true;
    try {
      if (active.value) await refreshSearch();
      if (
        taskId.value &&
        ["PENDING", "RUNNING", "WAITING", "RETRY_WAIT"].includes(
          task.value?.status,
        )
      )
        task.value = await api(`/tasks/${taskId.value}`);
      await loadTasks();
      await loadVideos();
    } catch {
    } finally {
      pollBusy = false;
    }
  }, 4000);
});
onUnmounted(() => {
  window.removeEventListener("keydown", closeFloatingPanel);
  window.removeEventListener("video-search:unauthenticated", sessionExpired);
  clearInterval(polling);
  events?.close();
  uploadAbort?.abort();
});
function closeFloatingPanel(event) {
  if (event.key !== "Escape") return;
  if (projectDialog.value) {
    if (!projectBusy.value) projectDialog.value = null;
  } else if (favoriteDialog.value) {
    if (!folderBusy.value) favoriteDialog.value = null;
  } else if (showUpload.value) {
    if (!isUploading.value) showUpload.value = false;
  } else if (taskPanelOpen.value) taskPanelOpen.value = false;
  else playing.value = null;
}
</script>

<template>
  <div v-if="!user" class="login-shell">
    <section class="login-intro">
      <div class="wordmark">
        FRAMELOCK<span>VIDEO RETRIEVAL WORKSTATION</span>
      </div>
      <div class="intro-copy">
        <p class="eyebrow">LESS SCRUBBING. MORE CREATING.</p>
        <h1>YOUR NEXT<br />FRAME<span class="accent-period">.</span></h1>
        <p>你记得那个画面。<br />让 FRAMELOCK 找到它在哪里。</p>
      </div>
      <div class="intro-track" aria-hidden="true">
        <span>IN</span><i></i><b></b><i></i><span>OUT</span>
      </div>
      <div class="intro-footer">
        <span>视频素材检索工作台</span><span>01 / FRAMELOCK</span>
      </div>
    </section>
    <form class="login-card" @submit.prevent="signIn">
      <p class="eyebrow">ACCESS / 工作区登录</p>
      <h1>回到你的素材。</h1>
      <p>先上传视频，再写下想找的画面。</p>
      <label
        >账号<input
          v-model="login.username"
          autocomplete="username"
          required /></label
      ><label
        >密码<input
          v-model="login.password"
          type="password"
          autocomplete="current-password"
          required
      /></label>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <button class="primary" :disabled="busy">
        {{ busy ? "登录中…" : "进入工作区" }}</button
      ><small
        >这里只能看到自己账号下的视频。<br />请使用管理员提供的账号登录。</small
      >
    </form>
  </div>
  <div v-else class="workspace">
    <aside class="sidebar">
      <a href="#" class="wordmark" @click.prevent="switchTab('library')"
        >FRAMELOCK<span>VIDEO RETRIEVAL / 01</span></a
      >
      <div class="sidebar-signal"><i class="signal-dot"></i> 工作区已连接</div>
      <p class="section-label">WORKSPACE / 工作区</p>
      <button
        :class="{ selected: tab === 'library' }"
        :aria-pressed="tab === 'library'"
        @click="switchTab('library')"
      >
        <span class="nav-title"><small>01</small> 查找片段</span></button
      ><button
        :class="{ selected: tab === 'favorites' }"
        :aria-pressed="tab === 'favorites'"
        @click="
          switchTab('favorites');
          act(loadFavorites);
        "
      >
        <span class="nav-title"><small>02</small> 收藏片段</span>
        <span class="nav-count">{{ favorites.length }}</span></button
      ><button
        :class="{ selected: tab === 'tasks' }"
        :aria-pressed="tab === 'tasks'"
        @click="
          switchTab('tasks');
          act(loadTasks);
        "
      >
        <span class="nav-title"><small>03</small> 任务记录</span>
      </button>
      <div class="sidebar-heading">
        <p class="section-label">PROJECTS / 视频项目</p>
        <span>{{ visibleLibraries.length }}</span>
      </div>
      <p class="project-help">把同一次游戏或旅行的视频放在一起。</p>
      <label class="mobile-library-picker"
        >视频项目<select
          aria-label="切换视频项目"
          :disabled="isUploading"
          :value="library"
          @change="chooseLibrary($event.target.value)"
        >
          <option v-if="!visibleLibraries.length" value="">
            先创建一个视频项目
          </option>
          <option v-for="l in visibleLibraries" :key="l.id" :value="l.id">
            {{ l.name }}
          </option>
        </select></label
      >
      <button
        v-for="(l, index) in visibleLibraries"
        :key="l.id"
        :class="{ selected: library === l.id && tab === 'library' }"
        class="library-link"
        :disabled="isUploading"
        :aria-pressed="library === l.id && tab === 'library'"
        @click="chooseLibrary(l.id)"
      >
        <span>{{ l.name }}</span
        ><small>{{ String(index + 1).padStart(2, "0") }}</small>
      </button>
      <form class="new-library" @submit.prevent="createLibrary">
        <input
          v-model="newLibrary"
          placeholder="例如：饥荒实况、旅行录像"
          aria-label="新项目名称"
          :disabled="isUploading"
          maxlength="120"
        /><button
          title="创建视频项目"
          aria-label="创建视频项目"
          :disabled="isUploading"
        >
          ＋
        </button>
      </form>
      <div class="sidebar-mark" aria-hidden="true">
        <div class="rumble-strip"></div>
        <span>FIND. VERIFY. REPLAY.</span>
      </div>
      <footer>
        <span>{{ user.username }}</span
        ><button @click="signOut">退出</button>
      </footer>
    </aside>
    <main>
      <div class="workspace-bar">
        <span
          >FRAMELOCK <b>/</b>
          {{
            tab === "library"
              ? "VIDEO PROJECT"
              : tab === "favorites"
                ? "SAVED CLIPS"
                : "ACTIVITY LOG"
          }}</span
        ><span class="workspace-state"
          ><i class="signal-dot"></i>{{ active ? "正在查找" : "已登录" }}</span
        >
      </div>
      <header>
        <div>
          <p class="eyebrow">
            {{
              tab === "favorites"
                ? "你的片段清单"
                : tab === "tasks"
                  ? "处理记录"
                  : "视频素材工作台"
            }}
          </p>
          <h1>
            {{
              tab === "library"
                ? searchProjectName
                : tab === "favorites"
                  ? "收藏片段"
                  : "任务记录"
            }}
          </h1>
        </div>
        <div v-if="tab === 'library'" class="header-actions">
          <button
            v-if="currentLibrary"
            :disabled="isUploading"
            @click="openProjectSettings"
          >
            项目设置
          </button>
          <button
            class="primary"
            :disabled="!library"
            @click="showUpload = true"
          >
            上传视频
          </button>
        </div>
        <button
          v-if="tab === 'favorites'"
          class="primary"
          @click="openFolderCreate"
        >
          新建收藏夹
        </button>
      </header>
      <div v-if="error" class="banner error" role="alert">
        {{ error
        }}<button @click="error = ''" aria-label="关闭错误提示">×</button>
      </div>
      <div v-if="notice" class="banner" role="status">
        {{ notice
        }}<button @click="notice = ''" aria-label="关闭通知">×</button>
      </div>
      <section v-if="tab === 'library'" class="search-area">
        <div class="search-heading">
          <div>
            <p class="eyebrow">SEARCH / 找视频片段</p>
            <h2>想找哪一段？</h2>
          </div>
          <div class="search-scope">
            <strong>{{ String(readyCount).padStart(2, "0") }}</strong
            ><span>个视频已准备好</span>
          </div>
        </div>
        <form class="search-form" @submit.prevent="submitSearch">
          <p class="scope-note" v-if="currentLibrary || search?.context">
            查找范围：<strong>{{ searchProjectName }}</strong
            ><span>只在这个项目的视频里找</span>
          </p>
          <label for="query">描述你要找的画面</label>
          <div class="search-line">
            <input
              id="query"
              v-model="query"
              placeholder="例如：从瓶子往杯子里倒饮料的片段"
              maxlength="500"
              :disabled="!library"
            /><button
              class="primary"
              :disabled="
                busy ||
                active ||
                videosLoading ||
                !readyCount ||
                !library ||
                !query.trim() ||
                search?.context?.projectDeleted
              "
            >
              {{ active ? "正在查找…" : "查找片段" }}
            </button>
          </div>
          <p v-if="search?.context?.projectDeleted" class="readiness-note">
            这个项目已删除，当前显示的是以前的查找记录。
          </p>
          <p v-else-if="videosLoading" class="readiness-note" role="status">
            正在读取项目视频，请稍等。
          </p>
          <div v-else-if="!readyCount" class="readiness-note">
            <p>
              {{
                videos.length
                  ? "这个项目还没有处理好的视频，暂时不能查找。可以查看处理进度或失败原因。"
                  : "这个项目还没有视频，上传后才能查找片段。"
              }}
            </p>
            <button
              v-if="videos.length"
              type="button"
              @click="
                switchTab('tasks');
                act(loadTasks);
              "
            >
              查看处理记录
            </button>
            <button
              v-else-if="library"
              type="button"
              @click="showUpload = true"
            >
              上传第一个视频
            </button>
          </div>
          <p v-else-if="readyCount < videos.length" class="readiness-note">
            这次只查找已处理好的 {{ readyCount }} 个视频，其余视频还没准备好。
          </p>
          <details class="search-settings">
            <summary>
              查找方式：{{ mode === "AGENT" ? "深入查找" : "标准查找"
              }}<span>调整</span>
            </summary>
            <div class="search-options">
              <label :class="{ 'is-active': mode === 'NORMAL' }"
                ><input type="radio" v-model="mode" value="NORMAL" />
                标准查找</label
              ><label :class="{ 'is-active': mode === 'AGENT' }"
                ><input type="radio" v-model="mode" value="AGENT" />
                深入查找</label
              ><span>{{
                mode === "AGENT"
                  ? "没找到合适片段时，再多找一轮，等待时间可能更长"
                  : "先找相关片段，再看看画面里是否真的发生了这件事"
              }}</span>
            </div>
          </details>
        </form>
      </section>
      <div
        v-if="playing"
        class="playback"
        role="region"
        aria-label="片段回看窗口"
      >
        <div class="playback-heading">
          <div>
            <p class="eyebrow">CLIP MONITOR / 片段回看</p>
            <strong>{{ playing.name }}</strong
            ><span
              >{{ clock(playing.startMs ?? playing.start_ms ?? 0) }} —
              {{ clock(playing.endMs ?? playing.end_ms ?? 0) }}</span
            >
          </div>
          <button @click="playing = null" aria-label="收起播放器">
            关闭 ×
          </button>
        </div>
        <video
          :key="playing.videoId"
          ref="player"
          controls
          preload="metadata"
          :src="`/api/v1/videos/${playing.videoId}/content`"
          @loadedmetadata="seek"
          @timeupdate="pauseAtEnd"
        ></video>
        <div class="playback-foot">
          <span>正在播放原视频</span><span>ESC 关闭</span>
        </div>
      </div>
      <section v-if="tab === 'library' && search" class="results">
        <div class="section-title">
          <h2>
            {{
              active
                ? "正在查找片段"
                : results.length
                  ? `找到 ${results.length} 个片段`
                  : "查找结果"
            }}
          </h2>
          <span>{{ label(search.status) }}</span>
        </div>
        <form
          v-if="
            search.status === 'SUCCEEDED' && !search.context?.projectDeleted
          "
          class="search-line"
          @submit.prevent="submitFollowUp"
        >
          <input
            v-model="followUp"
            maxlength="500"
            aria-label="接着查找"
            placeholder="接着找，例如：只看倒进杯子的片段"
            :disabled="busy"
          />
          <button class="primary" :disabled="busy || !followUp.trim()">
            接着找
          </button>
        </form>
        <p
          v-for="warning in search.result?.degraded || []"
          :key="warning"
          role="status"
        >
          {{ warning }}
        </p>
        <p v-if="active" role="status">
          正在这个项目的视频里找。视频较多时需要等一会儿，可以打开“处理详情”查看进度。
        </p>
        <p v-else-if="search.status === 'FAILED'" class="error">
          {{ search.error_message || "这次没找成，请打开“处理详情”查看原因" }}
        </p>
        <p v-else-if="search.status === 'CANCELLED'">
          查找已取消。需要时可以重新查找。
        </p>
        <p v-else-if="!results.length">
          {{ completion.message }}
          <span v-if="search.result"
            >这次查找了
            {{ search.result.searchedVideos?.length || 0 }} 个视频。</span
          >
        </p>
        <div
          v-if="
            search.status === 'SUCCEEDED' &&
            !results.length &&
            !search.context?.projectDeleted
          "
          class="no-result-actions"
        >
          <button
            v-if="search.context?.mode !== 'AGENT'"
            class="primary"
            :disabled="!readyCount || busy || videosLoading"
            @click="searchDeeper"
          >
            多找一轮
          </button>
          <button v-if="!completion.limited" @click="focusSearchInput">
            换个描述
          </button>
          <p>
            {{
              search.context?.mode === "AGENT"
                ? completion.limited
                  ? "可以补充动作、人物或物品的细节，再次查找。"
                  : "可以补充画面细节，或换一个项目。"
                : "多找一轮会再检查相关画面，可能需要更长时间，并产生额外费用。"
            }}
          </p>
        </div>
        <p
          v-if="
            search.status === 'SUCCEEDED' &&
            results.length &&
            completion.limited
          "
          role="status"
        >
          {{ completion.message }}
        </p>
        <div
          v-if="
            search.status === 'SUCCEEDED' &&
            completion.limited &&
            !search.context?.projectDeleted
          "
          class="no-result-actions"
        >
          <button @click="focusSearchInput">调整描述</button>
          <button @click="viewTask(searchId, true)">查看处理详情</button>
        </div>
        <div class="result-grid">
          <article
            v-for="(r, i) in results"
            :key="r.videoId + ':' + r.startMs"
            class="result"
            :class="{
              'is-playing':
                playing?.videoId === r.videoId &&
                playing?.startMs === r.startMs,
            }"
          >
            <button
              class="preview"
              @click="play(r)"
              :aria-label="`回看${r.name}`"
            >
              <img :src="r.thumbnail" alt="片段画面" /><span
                >{{ clock(r.startMs) }} — {{ clock(r.endMs) }}</span
              >
            </button>
            <div class="result-body">
              <div class="section-title">
                <span class="result-number">{{
                  String(i + 1).padStart(2, "0")
                }}</span
                ><small>{{
                  r.boundaryConfirmed ? "符合描述" : "起止时间可能有偏差"
                }}</small>
              </div>
              <h3>{{ r.name }}</h3>
              <details class="match-reason">
                <summary>为什么找到这段？</summary>
                <p>{{ r.evidence }}</p>
              </details>
              <div class="actions">
                <button @click="play(r)">回看片段</button
                ><button @click="save(r)">收藏</button>
              </div>
            </div>
          </article>
        </div>
      </section>
      <section v-if="tab === 'library'" class="videos">
        <div class="section-title">
          <h2>
            <span class="section-code">ASSETS /</span> 项目视频
            <small>{{ videos.length }} / 10</small>
          </h2>
          <button @click="act(loadVideos)">刷新</button>
        </div>
        <p v-if="videosLoading" role="status">正在读取这个项目的视频…</p>
        <div v-else-if="!videos.length" class="empty">
          <h3>{{ library ? "这里还没有视频" : "先创建一个视频项目" }}</h3>
          <p>
            上传后会自动处理，完成后就能查找片段。每个项目最多放 10
            个视频，单个最长 30 分钟、最大 2 GiB。
          </p>
          <button v-if="library" @click="showUpload = true">
            选择第一个视频
          </button>
        </div>
        <div
          v-for="(v, index) in videos"
          :key="v.id"
          class="video-row"
          :class="{ 'is-playing': playing?.videoId === v.id }"
        >
          <span class="asset-index" aria-hidden="true">{{
            String(index + 1).padStart(2, "0")
          }}</span>
          <div>
            <strong>{{ v.name }}</strong>
            <p>
              {{ clock(v.duration_ms) }} ·
              {{ (v.file_size / 1024 / 1024).toFixed(1) }} MiB
            </p>
          </div>
          <span class="status" :data-status="v.status">{{
            label(v.status)
          }}</span
          ><button
            @click="
              play({
                videoId: v.id,
                name: v.name,
                startMs: 0,
                endMs: v.duration_ms,
              })
            "
          >
            播放</button
          ><button class="danger-link" @click="removeVideo(v)">删除</button>
        </div>
      </section>
      <section v-if="tab === 'favorites'">
        <div class="folder-tabs" role="group" aria-label="按收藏夹查看">
          <button
            :class="{ selected: favoriteFilter === 'all' }"
            :aria-pressed="favoriteFilter === 'all'"
            @click="favoriteFilter = 'all'"
          >
            全部片段 <small>{{ favorites.length }}</small>
          </button>
          <button
            :class="{ selected: favoriteFilter === 'unfiled' }"
            :aria-pressed="favoriteFilter === 'unfiled'"
            @click="favoriteFilter = 'unfiled'"
          >
            未分类 <small>{{ folderCount("unfiled") }}</small>
          </button>
          <button
            v-for="folder in favoriteFolders"
            :key="folder.id"
            :class="{ selected: favoriteFilter === folder.id }"
            :aria-pressed="favoriteFilter === folder.id"
            @click="favoriteFilter = folder.id"
          >
            {{ folder.name }} <small>{{ folderCount(folder.id) }}</small>
          </button>
        </div>
        <div class="favorite-folder-heading">
          <p class="favorite-count">
            {{
              favoriteFilter === "all"
                ? "全部收藏"
                : favoriteFilter === "unfiled"
                  ? "未分类"
                  : folderName(favoriteFilter)
            }}
            · {{ filteredFavorites.length }} 个片段
          </p>
          <div v-if="selectedFavoriteFolder" class="actions">
            <button @click="openFolderRename">重命名</button
            ><button @click="deleteFavoriteFolder">删除收藏夹</button>
          </div>
        </div>
        <div v-if="!filteredFavorites.length" class="empty">
          <h3>
            {{ favorites.length ? "这个收藏夹还没有片段" : "还没有收藏的片段" }}
          </h3>
          <p>
            {{
              favorites.length
                ? "可以把已有片段移进来，也可以在查找结果里收藏新的片段。"
                : "找到有用的片段后，点“收藏”，再选一个收藏夹。"
            }}
          </p>
        </div>
        <article
          v-for="f in filteredFavorites"
          :key="f.id"
          class="video-row favorite-row"
        >
          <div>
            <strong>{{ f.name }}</strong>
            <p>{{ clock(f.start_ms) }} — {{ clock(f.end_ms) }}</p>
            <button class="source-project" @click="chooseLibrary(f.library_id)">
              来自：{{ f.library_name }} ↗
            </button>
            <span class="folder-label">{{ folderName(f.folder_id) }}</span>
          </div>
          <button @click="play(f)">回看</button
          ><button @click="openFavoriteMove(f)">移动到…</button
          ><button @click="removeFavorite(f.id)">取消收藏</button>
        </article>
      </section>
      <section v-if="tab === 'tasks'">
        <p v-if="!tasks.length" class="empty">还没有任务记录。</p>
        <button
          v-for="t in tasks"
          :key="t.id"
          class="task-row"
          @click="openHistoryTask(t)"
        >
          <div>
            <strong>{{
              t.kind === "SEARCH"
                ? t.context?.query || "以前的查找"
                : t.kind === "CLEANUP"
                  ? "清理视频"
                  : t.video_name || "处理视频"
            }}</strong
            ><small
              >{{ t.context?.libraryName || t.library_name || "处理记录" }} ·
              {{ displayTime(t.created_at) }}</small
            >
          </div>
          <span>{{ stageName(t.stage) }}</span
          ><span class="status" :data-status="t.status">{{
            label(t.status)
          }}</span
          ><span v-if="t.kind === 'SEARCH'" class="history-open">{{
            ["SUCCEEDED", "FAILED", "CANCELLED"].includes(t.status)
              ? "查看结果 ↗"
              : "查看进度 ↗"
          }}</span>
        </button>
      </section>
      <button
        v-if="task && !taskPanelOpen"
        class="task-dock"
        @click="taskPanelOpen = true"
      >
        <i
          class="signal-dot"
          :class="{
            'is-working': [
              'PENDING',
              'RUNNING',
              'WAITING',
              'RETRY_WAIT',
            ].includes(task.status),
          }"
        ></i
        ><span
          >处理详情 <small>{{ label(task.status) }}</small></span
        ><span aria-hidden="true">↗</span>
      </button>
      <details v-if="task && taskPanelOpen" class="task-detail" open>
        <summary>处理详情 · {{ label(task.status) }}</summary>
        <button
          class="panel-close"
          @click="taskPanelOpen = false"
          aria-label="关闭任务窗口"
        >
          ×
        </button>
        <div class="section-title">
          <code>{{ taskId }}</code>
          <div class="actions">
            <button
              v-if="
                ['RUNNING', 'WAITING', 'RETRY_WAIT', 'PENDING'].includes(
                  task.status,
                )
              "
              @click="controlTask('cancel')"
            >
              取消任务</button
            ><button
              v-if="task.status === 'FAILED'"
              @click="controlTask('retry')"
            >
              重试</button
            ><button @click="act(() => viewTask(taskId))">刷新</button>
          </div>
        </div>
        <p>
          {{ stageName(task.stage)
          }}<span v-if="task.error_message" class="error">
            · {{ task.error_message }}</span
          >
        </p>
        <div v-for="child in task.children" :key="child.id" class="child-task">
          <span>{{ stageName(child.stage) }}</span
          ><span>{{ label(child.status) }}</span
          ><small>{{ child.error_message }}</small>
        </div>
        <details v-if="search?.result?.trace?.length">
          <summary>
            查看技术详情（{{ search.result.toolCalls }} 次操作）
          </summary>
          <div v-for="(step, i) in search.result.trace" :key="i" class="trace">
            <strong>{{ step.role }} · {{ step.tool }}</strong>
            <p>{{ step.reason || step.evidence?.explanation || step.query }}</p>
            <small v-if="step.elapsedMs">耗时 {{ step.elapsedMs }} ms</small>
            <small v-if="step.modelUsage?.length">
              · 预计费用 ¥{{ usageCost(step)
              }}<span v-if="step.modelUsage.some((u) => u.unknown)"
                >（部分费用尚未确认）</span
              ><span v-else-if="step.modelUsage.every((u) => u.cacheHit)"
                >（使用已有结果，无新增费用）</span
              ></small
            >
          </div>
          <p>结束原因：{{ search.result.exitReason }}</p>
        </details>
      </details>
    </main>
    <div
      v-if="projectDialog"
      class="modal-backdrop"
      @click.self="!projectBusy && (projectDialog = null)"
    >
      <form
        class="modal project-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="project-settings-title"
        @submit.prevent="renameProject"
        @keydown.tab="trapDialogFocus"
      >
        <div class="section-title">
          <h2 id="project-settings-title">项目设置</h2>
          <button
            type="button"
            :disabled="projectBusy"
            aria-label="关闭项目设置"
            @click="projectDialog = null"
          >
            ×
          </button>
        </div>
        <label for="project-name">项目名称</label
        ><input
          id="project-name"
          v-model="projectName"
          required
          maxlength="120"
          :disabled="projectBusy"
        />
        <div class="actions">
          <button
            type="button"
            :disabled="projectBusy"
            @click="projectDialog = null"
          >
            取消</button
          ><button
            class="primary"
            :disabled="projectBusy || !projectName.trim()"
          >
            {{ projectBusy ? "正在保存…" : "保存名称" }}
          </button>
        </div>
        <div class="project-delete">
          <h3>删除空项目</h3>
          <p>
            只允许删除没有视频、也没有正在上传文件的项目，不会批量删除视频。历史处理记录仍会保留。
          </p>
          <button
            type="button"
            :disabled="
              projectBusy || isUploading || videosLoading || videos.length > 0
            "
            @click="deleteProject"
          >
            删除空项目
          </button>
        </div>
        <p v-if="error" role="alert" class="error">{{ error }}</p>
      </form>
    </div>
    <div
      v-if="favoriteDialog"
      class="modal-backdrop"
      @click.self="!folderBusy && (favoriteDialog = null)"
    >
      <form
        class="modal favorite-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="favorite-dialog-heading"
        @submit.prevent="submitFavorite"
        @keydown.tab="trapDialogFocus"
      >
        <div class="section-title">
          <h2 id="favorite-dialog-heading">
            {{
              favoriteDialog.kind === "create"
                ? "新建收藏夹"
                : favoriteDialog.kind === "rename"
                  ? "重命名收藏夹"
                  : favoriteDialog.kind === "move"
                    ? "移动到收藏夹"
                    : "收藏片段"
            }}
          </h2>
          <button
            type="button"
            :disabled="folderBusy"
            @click="favoriteDialog = null"
            aria-label="关闭收藏窗口"
          >
            ×
          </button>
        </div>
        <template v-if="!folderNameOnly">
          <p class="favorite-clip-name">
            {{ favoriteDialog.match?.name || favoriteDialog.favorite?.name
            }}<br /><small
              >{{
                clock(
                  favoriteDialog.match?.startMs ??
                    favoriteDialog.favorite?.start_ms ??
                    0,
                )
              }}
              —
              {{
                clock(
                  favoriteDialog.match?.endMs ??
                    favoriteDialog.favorite?.end_ms ??
                    0,
                )
              }}</small
            >
          </p>
          <label for="favorite-folder-select">放进哪个收藏夹？</label>
          <select
            id="favorite-folder-select"
            v-model="destinationFolder"
            :disabled="folderBusy"
          >
            <option value="">未分类</option>
            <option
              v-for="folder in favoriteFolders"
              :key="folder.id"
              :value="folder.id"
            >
              {{ folder.name }}
            </option>
          </select>
        </template>
        <div class="create-folder-fields">
          <label for="folder-name">{{
            folderNameOnly ? "收藏夹名称" : "也可以新建一个收藏夹"
          }}</label>
          <div>
            <input
              id="folder-name"
              v-model="newFolderName"
              maxlength="80"
              placeholder="例如：精彩打斗、搞笑瞬间"
              :disabled="folderBusy"
              :required="folderNameOnly"
              @keydown.enter="folderNameEnter"
            /><button
              v-if="!folderNameOnly"
              type="button"
              :disabled="folderBusy || !newFolderName.trim()"
              @click="createFavoriteFolder"
            >
              新建并选中
            </button>
          </div>
        </div>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <div class="actions favorite-dialog-actions">
          <button
            type="button"
            :disabled="folderBusy"
            @click="favoriteDialog = null"
          >
            取消</button
          ><button
            class="primary"
            :disabled="folderBusy || (folderNameOnly && !newFolderName.trim())"
          >
            {{
              folderBusy
                ? "正在保存…"
                : favoriteDialog.kind === "create"
                  ? "创建收藏夹"
                  : favoriteDialog.kind === "rename"
                    ? "保存名称"
                    : favoriteDialog.kind === "move"
                      ? "移动片段"
                      : "确认收藏"
            }}
          </button>
        </div>
      </form>
    </div>
    <div
      v-if="showUpload"
      class="modal-backdrop"
      @click.self="!isUploading && (showUpload = false)"
    >
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="upload-heading"
        @keydown.tab="trapDialogFocus"
      >
        <div class="section-title">
          <h2 id="upload-heading">上传到 {{ currentLibrary?.name }}</h2>
          <button
            :disabled="isUploading"
            @click="showUpload = false"
            aria-label="关闭上传窗口"
          >
            ×
          </button>
        </div>
        <p>MP4 · 最长 30 分钟 · 最大 2 GiB</p>
        <p>
          上传后会处理画面和声音，完成后就能查找片段。处理会使用 AI
          服务，可能产生费用。暂停后重新选择同一个文件，可以继续上传。
        </p>
        <label class="dropzone"
          >{{ isUploading ? "正在上传…" : "选择视频文件"
          }}<input
            ref="fileInput"
            type="file"
            accept="video/mp4,.mp4"
            multiple
            :disabled="isUploading"
            @change="uploadFile" /></label
        ><progress :value="upload.percent" max="100"></progress>
        <p v-if="batch.total > 1">
          第 {{ batch.current }} / {{ batch.total }} 个：{{ batch.name }}
        </p>
        <p role="status">{{ upload.message }}</p>
        <button v-if="isUploading" @click="pauseUpload">暂停上传</button>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
      </section>
    </div>
  </div>
</template>

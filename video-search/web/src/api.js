let csrf;
export async function refreshCsrf() {
  const r = await fetch("/api/v1/auth/csrf", { credentials: "same-origin" });
  if (!r.ok) throw new Error("无法连接服务");
  csrf = await r.json();
}
export async function api(
  path,
  { method = "GET", body, headers = {}, signal } = {},
) {
  if (method !== "GET" && !csrf) await refreshCsrf();
  const init = {
    method,
    credentials: "same-origin",
    headers: { ...headers },
    signal,
  };
  if (method !== "GET") init.headers[csrf.headerName] = csrf.token;
  if (body !== undefined) {
    if (body instanceof Blob) {
      init.body = body;
    } else {
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(body);
    }
  }
  let response;
  try {
    response = await fetch("/api/v1" + path, init);
  } catch (error) {
    if (error.name === "AbortError")
      throw new Error("上传已暂停，重新选择相同文件即可续传");
    throw new Error("网络连接中断，请恢复连接后重试；已上传分片会保留。");
  }
  if (!response.ok) {
    if (response.status === 401)
      window.dispatchEvent(new Event("video-search:unauthenticated"));
    let error;
    try {
      error = await response.json();
    } catch {}
    throw new Error(error?.message || `请求失败 (${response.status})`);
  }
  return response.status === 204 ||
    response.headers.get("content-length") === "0"
    ? null
    : response.json().catch(() => null);
}

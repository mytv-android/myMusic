(() => {
  "use strict";

  const EXPIRED = "二维码已过期，请在电视上重新打开。";
  const OFFLINE = "连不上电视。请确认手机和电视连着同一个网络，再重新扫码。";

  const title = document.getElementById("page-title");
  const intro = document.getElementById("page-intro");
  const uploadPanel = document.getElementById("upload-panel");
  const downloadPanel = document.getElementById("download-panel");
  const fileInput = document.getElementById("file");
  const fileName = document.getElementById("file-name");
  const fileMeta = document.getElementById("file-meta");
  const fileHint = fileMeta.textContent;
  const progress = document.getElementById("progress");
  const progressBar = document.getElementById("progress-bar");
  const sendButton = document.getElementById("send");
  const sendLabel = document.getElementById("send-label");
  const downloadName = document.getElementById("download-name");
  const downloadLink = document.getElementById("download");
  const downloadLabel = document.getElementById("download-label");
  const statusCard = document.getElementById("status-card");
  const statusTitle = document.getElementById("status-title");
  const statusDetail = document.getElementById("status-detail");

  let maxBytes = 32 * 1024 * 1024;
  let downloadFile = "";
  let pollTimer = 0;
  let failedPolls = 0;
  let sent = false;
  let finished = false;

  function formatSize(bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return Math.max(1, Math.round(bytes / 1024)) + " KB";
    return (bytes / 1024 / 1024).toFixed(1) + " MB";
  }

  function showStatus(state, heading, detail) {
    statusCard.className = "status-card " + state;
    statusTitle.textContent = heading;
    statusDetail.textContent = detail || "";
    statusCard.hidden = false;
  }

  function hideStatus() {
    statusCard.hidden = true;
  }

  function clearPoll() {
    if (pollTimer) window.clearTimeout(pollTimer);
    pollTimer = 0;
  }

  /** 会话结束（过期、电视关掉、已经收过文件）：按钮全部停用，只留一句话。 */
  function stop(message) {
    clearPoll();
    finished = true;
    fileInput.disabled = true;
    sendButton.disabled = true;
    downloadLink.setAttribute("aria-disabled", "true");
    showStatus("error", "这个页面不能再用了", message);
  }

  async function request(path) {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 10000);
    try {
      const response = await fetch(path, { cache: "no-store", credentials: "same-origin", signal: controller.signal });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) {
        const error = new Error(data.error || "电视没有返回有效响应，请重试。");
        error.status = response.status;
        throw error;
      }
      return data;
    } catch (error) {
      if (error.name === "AbortError") throw new Error(OFFLINE);
      throw error;
    } finally {
      window.clearTimeout(timeout);
    }
  }

  // ---------- 上传 ----------

  function setProgress(percent) {
    const value = Math.max(0, Math.min(100, Math.round(percent)));
    progress.hidden = false;
    progress.setAttribute("aria-valuenow", String(value));
    progressBar.style.width = value + "%";
  }

  function pickedFile() {
    return fileInput.files && fileInput.files.length ? fileInput.files[0] : null;
  }

  function onPick() {
    if (sent || finished) return;
    hideStatus();
    progress.hidden = true;
    const file = pickedFile();
    if (!file) {
      fileName.textContent = "选择文件";
      fileMeta.textContent = fileHint;
      sendButton.disabled = true;
      return;
    }
    fileName.textContent = file.name;
    fileMeta.textContent = formatSize(file.size) + " · 点这里换一个";
    if (file.size === 0) {
      sendButton.disabled = true;
      showStatus("error", "文件是空的", "换一个文件再发送。");
    } else if (file.size > maxBytes) {
      sendButton.disabled = true;
      showStatus("error", "文件太大", "电视最多收 " + formatSize(maxBytes) + " 的文件。");
    } else {
      sendButton.disabled = false;
    }
  }

  function errorOf(xhr) {
    try {
      return JSON.parse(xhr.responseText).error || "";
    } catch (_) {
      return "";
    }
  }

  function send() {
    const file = pickedFile();
    if (!file || sent || finished) return;
    sendButton.disabled = true;
    fileInput.disabled = true;
    sendLabel.textContent = "正在发送…";
    hideStatus();
    setProgress(0);
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "api/upload");
    xhr.timeout = 5 * 60 * 1000;
    xhr.setRequestHeader("Content-Type", "application/octet-stream");
    xhr.setRequestHeader("X-MyMusic-Transfer", "1");
    xhr.setRequestHeader("X-File-Name", encodeURIComponent(file.name));
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) setProgress((event.loaded / event.total) * 100);
    };
    const retry = (heading, detail) => {
      sendButton.disabled = false;
      fileInput.disabled = false;
      sendLabel.textContent = "发送到电视";
      progress.hidden = true;
      showStatus("error", heading, detail);
    };
    xhr.onload = () => {
      if (xhr.status === 202) {
        sent = true;
        setProgress(100);
        sendLabel.textContent = "已发送";
        showStatus("needs-tv", "已发到电视", "请在电视上确认导入。");
        failedPolls = 0;
        pollTimer = window.setTimeout(poll, 1500);
        return;
      }
      const message = errorOf(xhr);
      if (xhr.status === 410) {
        stop(EXPIRED);
      } else if (xhr.status === 409 && message.indexOf("已经收到") >= 0) {
        sent = true;
        stop(message);
      } else {
        retry("没有发出去", message || "电视没有收下这个文件，请重试。");
      }
    };
    xhr.onerror = () => retry("没有发出去", OFFLINE);
    xhr.ontimeout = () => retry("没有发出去", "发送超时，请靠近路由器后重试。");
    xhr.send(file);
  }

  async function poll() {
    pollTimer = 0;
    if (finished) return;
    try {
      const data = await request("api/status");
      failedPolls = 0;
      if (data.phase === "done") {
        finished = true;
        showStatus("success", "已导入", data.message);
        return;
      }
      if (data.phase === "error") {
        finished = true;
        showStatus("error", "没有导入", data.message);
        return;
      }
      if (data.phase === "received") showStatus("needs-tv", "电视已收到", data.message);
    } catch (error) {
      if (error.status === 410) {
        stop(EXPIRED);
        return;
      }
      // 电视上关掉了面板：服务停了，连几次都连不上就不再问
      failedPolls += 1;
      if (failedPolls >= 3) {
        finished = true;
        showStatus("error", "电视上已经关掉了这一页", "导入结果请看电视。");
        return;
      }
    }
    pollTimer = window.setTimeout(poll, 2000);
  }

  // ---------- 下载 ----------

  async function download(event) {
    event.preventDefault();
    if (finished || downloadLink.getAttribute("aria-disabled") === "true") return;
    downloadLink.setAttribute("aria-disabled", "true");
    downloadLabel.textContent = "正在下载…";
    try {
      const response = await fetch("api/download", { cache: "no-store", credentials: "same-origin" });
      if (!response.ok) {
        const data = await response.json().catch(() => ({}));
        if (response.status === 410) {
          stop(data.error || EXPIRED);
          return;
        }
        throw new Error(data.error || "电视没有返回文件，请重试。");
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = downloadFile || "歌单.mymusic.json";
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 60000);
      showStatus("success", "已下载", "在手机浏览器的下载记录里能找到 " + anchor.download + "。");
    } catch (error) {
      showStatus("error", "没有下载成功", error.message === "Failed to fetch" ? OFFLINE : error.message);
    } finally {
      if (!finished) {
        downloadLink.removeAttribute("aria-disabled");
        downloadLabel.textContent = "再下载一次";
      }
    }
  }

  // ---------- 开始 ----------

  async function initialize() {
    try {
      const session = await request("api/session");
      if (session.maxBytes > 0) maxBytes = session.maxBytes;
      if (session.mode === "download" && session.download) {
        downloadFile = session.download.name || "";
        title.textContent = "从电视下载歌单文件";
        intro.textContent = "电视导出的 myMusic 歌单文件，存到手机后可以发给别人。";
        document.title = "下载歌单文件 · myMusic TV";
        downloadName.textContent = downloadFile;
        downloadLabel.textContent = "下载（" + formatSize(session.download.size || 0) + "）";
        downloadPanel.hidden = false;
      } else {
        uploadPanel.hidden = false;
      }
    } catch (error) {
      uploadPanel.hidden = true;
      if (error.status === 410) {
        stop(EXPIRED);
      } else {
        finished = true;
        showStatus("error", "连不上电视", OFFLINE);
      }
    }
  }

  fileInput.addEventListener("change", onPick);
  sendButton.addEventListener("click", send);
  downloadLink.addEventListener("click", download);
  window.addEventListener("pagehide", clearPoll);
  window.addEventListener("pageshow", (event) => {
    if (event.persisted && sent && !finished && !pollTimer) pollTimer = window.setTimeout(poll, 300);
  });

  initialize();
})();

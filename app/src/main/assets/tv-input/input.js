(() => {
  "use strict";

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
  const panel = $("#panel");
  const inputText = $("#input-text");
  const connecting = $("#connecting");
  const terminal = $("#terminal");
  const toastElement = $("#toast");
  const scenes = ["home", "source", "sync", "storage", "signer"];
  const presetUrls = {
    jianguoyun: "https://dav.jianguoyun.com/dav/",
    nextcloud: "https://",
    synology: "https://",
    other: "",
  };
  const knownPresetUrls = Object.values(presetUrls);

  let scene = "home";
  let target = "field";
  let targetId = "";
  let targetAvailable = false;
  let targetHasValue = false;
  let targetLabel = "电视当前输入框";
  let supportsEnter = true;
  let allowSearch = true;
  let allowSettings = true;
  let connected = false;
  let busy = false;
  let finished = false;
  let provider = "gist";
  let signerMode = "custom";
  let writeMode = "replace";
  let operation = 0;
  let announcedOperation = 0;
  let requestInFlight = false;
  let pendingKind = "";
  let pendingScene = "home";
  let submittedText = "";
  let lastStatus = null;
  let pollTimer = 0;
  let toastTimer = 0;
  let failedPolls = 0;
  let expiresAt = 0;
  let savedDav = null;
  let pendingDav = null;

  function showResult(where, type, title, detail) {
    const section = $(`.p-scene[data-scene="${where}"]`);
    const box = $(".p-result", section);
    if (!box) return;
    box.hidden = false;
    box.className = "p-result" + (type ? " " + type : "");
    $("b", box).textContent = title || "";
    $("p", box).textContent = detail || "";
  }

  function hideResults() {
    $$(".p-result", panel).forEach((box) => { box.hidden = true; });
  }

  function toast(message) {
    window.clearTimeout(toastTimer);
    toastElement.textContent = message;
    toastElement.hidden = false;
    toastTimer = window.setTimeout(() => { toastElement.hidden = true; }, 2800);
  }

  function stop(title, detail) {
    finished = true;
    busy = false;
    connected = false;
    window.clearTimeout(pollTimer);
    $("#terminal-title").textContent = title;
    $("#terminal-detail").textContent = detail;
    terminal.hidden = false;
    $("#device-name").textContent = "会话已结束";
    updateButtons();
  }

  async function request(path, options = {}) {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 12000);
    try {
      const response = await fetch(path, {
        ...options,
        cache: "no-store",
        credentials: "same-origin",
        signal: controller.signal,
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) {
        const error = new Error(data.error || "电视没有返回有效响应，请重试。");
        error.status = response.status;
        throw error;
      }
      return data;
    } catch (error) {
      if (error.name === "AbortError") throw new Error("连接电视超时，请检查手机和电视是否仍在同一网络。");
      throw error;
    } finally {
      window.clearTimeout(timeout);
    }
  }

  function selectTarget(next) {
    if (next === "field" && !targetId) return;
    if (next === "search" && !allowSearch) return;
    target = next;
    $$('[data-target]', panel).forEach((button) => {
      button.setAttribute("aria-pressed", String(button.dataset.target === next));
    });
    updateTargetCopy();
    updateButtons();
  }

  function updateTargetCopy() {
    const context = $("#target-context");
    context.classList.toggle("unavailable", target === "field" && !targetAvailable);
    if (target === "search") {
      $("#target-label").textContent = "电视搜索页";
    } else if (!targetAvailable) {
      $("#target-label").textContent = "电视已离开输入框，请重新扫码";
    } else {
      $("#target-label").textContent = targetLabel + " · " + (targetHasValue ? "已有内容" : "当前为空");
    }
    $("#write-mode").hidden = target === "search";
    $("#send-enter").hidden = target === "search" || !supportsEnter;
    $("#send-text span").textContent = target === "search" ? "搜索" : "发送";
    $("#send-text").setAttribute("aria-label", target === "search" ? "在电视上搜索" : "发送到电视");
  }

  function openScene(next) {
    if (!scenes.includes(next) || (next !== "home" && !allowSettings)) return;
    scene = next;
    $$(".p-scene", panel).forEach((section) => { section.hidden = section.dataset.scene !== next; });
    $("#back").hidden = next === "home";
    hideResults();
    if (!busy && lastStatus && lastStatus.scene === next && lastStatus.state !== "idle") {
      handleStatus(lastStatus, false);
    } else if (busy && pendingScene === next) {
      showResult(next, "", "正在等待电视", "草稿已保留，收到电视回执后才会显示成功。");
    }
    updateButtons();
    window.scrollTo({ top: 0, behavior: "instant" });
  }

  function setProvider(next) {
    if (!["gist", "gitee", "webdav"].includes(next)) return;
    provider = next;
    $$('[data-provider]', panel).forEach((button) => {
      button.setAttribute("aria-pressed", String(button.dataset.provider === next));
    });
    const dav = next === "webdav";
    $(".sync-token-fields").hidden = dav;
    $(".sync-dav-fields").hidden = !dav;
    $("#sync-test").hidden = !dav;
    $("#remote-label").textContent = next === "gitee" ? "代码片段 ID（可选）" : "Gist ID（可选）";
    $("#sync-remote").placeholder = next === "gitee" ? "留空则新建私密代码片段" : "留空则新建私密 Gist";
    $("#token-help").textContent = next === "gitee" ? "需要 gists 权限。" : "需要 gist 权限。";
    updateButtons();
  }

  function setSignerMode(next) {
    signerMode = next === "public" ? "public" : "custom";
    $$('[data-signer-mode]', panel).forEach((button) => {
      button.setAttribute("aria-pressed", String(button.dataset.signerMode === signerMode));
    });
    updateSigner();
  }

  function updateSigner() {
    const enabled = $("#signer-enabled").checked;
    $(".signer-mode").hidden = !enabled;
    $(".signer-custom-fields").hidden = !enabled || signerMode !== "custom";
    updateButtons();
  }

  function canReuseDav(prefix) {
    if ($(`#${prefix}-dav-password`).value) return true;
    return !!savedDav && savedDav.hasPassword &&
      savedDav.url.replace(/\/$/, "") === $(`#${prefix}-dav-url`).value.trim().replace(/\/$/, "") &&
      savedDav.username === $(`#${prefix}-dav-user`).value.trim();
  }

  function validDav(prefix) {
    return $(`#${prefix}-dav-url`).value.trim() && $(`#${prefix}-dav-user`).value.trim() &&
      ($(`#${prefix}-dav-password`).value || canReuseDav(prefix));
  }

  function pendingButton() {
    if (pendingScene === "home") return pendingKind === "enter" ? $("#send-enter") : $("#send-text");
    if (pendingScene === "source") return $("#source-form .p-action");
    if (pendingScene === "sync") return pendingKind === "test" ? $("#sync-test") : $("#sync-form button[type=submit]");
    if (pendingScene === "storage") return pendingKind === "test" ? $("#storage-test") : $("#storage-form button[type=submit]");
    return $("#signer-form button[type=submit]");
  }

  function updateButtons() {
    const locked = busy || finished || !connected;
    $$("input, textarea", panel).forEach((element) => { element.disabled = locked; });
    $$('[data-target], [data-provider], [data-preset], [data-reveal], [data-signer-mode], #clear-text, #write-mode, #paste-source', panel)
      .forEach((element) => { element.disabled = locked; });
    const fieldBlocked = target === "field" && (!targetAvailable || !targetId);
    $("#send-text").disabled = locked || fieldBlocked || !inputText.value.trim();
    $("#send-enter").disabled = locked || fieldBlocked || !inputText.value.trim() || !supportsEnter;
    $("#source-form button[type=submit]").disabled = locked || !$("#source-url").value.trim();
    const syncReady = provider === "webdav" ? validDav("sync") : $("#sync-token").value.trim();
    $("#sync-form button[type=submit]").disabled = locked || !syncReady;
    $("#sync-test").disabled = locked || !validDav("sync");
    $("#storage-form button[type=submit]").disabled = locked || !validDav("storage");
    $("#storage-test").disabled = locked || !validDav("storage");
    const signerNeedsUrl = $("#signer-enabled").checked && signerMode === "custom";
    $("#signer-form button[type=submit]").disabled = locked || (signerNeedsUrl && !$("#signer-url").value.trim());
    $$(".p-actions .p-spinner, .p-compose-actions .p-spinner", panel).forEach((spinner) => spinner.remove());
    if (busy) {
      const button = pendingButton();
      if (button) {
        const spinner = document.createElement("span");
        spinner.className = "p-spinner";
        spinner.setAttribute("aria-hidden", "true");
        button.prepend(spinner);
      }
    }
  }

  function clearAfterSuccess(status) {
    if (status.scene === "home" && target === "field" && inputText.value === submittedText) inputText.value = "";
    if (status.scene === "sync") {
      $("#sync-token").value = "";
      $("#sync-dav-password").value = "";
    }
    if (status.scene === "storage") $("#storage-dav-password").value = "";
    if (status.scene === "signer") $("#signer-token").value = "";
  }

  function handleStatus(data, announce = true) {
    if (!data || finished) return;
    if (typeof data.operation === "number" && data.operation < operation) return;
    if (requestInFlight && (data.operation || 0) <= operation) return;
    if (typeof data.operation === "number") operation = data.operation;
    lastStatus = data;
    if (targetId) {
      targetAvailable = data.targetAvailable === true;
      targetHasValue = data.targetHasValue === true;
      if (data.targetLabel) targetLabel = data.targetLabel;
      updateTargetCopy();
    }
    busy = data.state === "working" || data.state === "needs_tv";
    if (data.scene === scene && data.state !== "idle") {
      const type = data.state === "success" ? "success" : (data.state === "error" ? "error" : "");
      showResult(scene, type, data.title, data.detail);
    }
    if (announce && data.state === "success" && data.operation !== announcedOperation) {
      announcedOperation = data.operation || 0;
      if (pendingDav && pendingDav.scene === data.scene) {
        rememberDav(pendingDav.payload);
        pendingDav = null;
      }
      clearAfterSuccess(data);
      toast(data.title || "电视已完成");
    }
    if (data.state === "error") pendingDav = null;
    updateButtons();
  }

  function handleError(error, where) {
    pendingDav = null;
    if (error.status === 410) {
      stop("二维码已过期", "草稿仍保留在本页。回电视重新打开输入面板并扫码。");
      return;
    }
    busy = false;
    showResult(where, "error", "操作未完成", error.message || "电视没有完成操作，请重试。");
    updateButtons();
  }

  async function post(path, payload, where, kind, successfulDav = null) {
    if (busy || finished || !connected) return;
    pendingDav = successfulDav ? { scene: where, payload: successfulDav } : null;
    pendingScene = where;
    pendingKind = kind;
    submittedText = where === "home" ? inputText.value : "";
    busy = true;
    failedPolls = 0;
    showResult(where, "", "正在等待电视", "草稿已保留，收到电视回执后才会显示成功。");
    updateButtons();
    requestInFlight = true;
    try {
      const data = await request(path, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-MyMusic-Input": "1" },
        body: JSON.stringify(payload),
      });
      handleStatus(data);
    } catch (error) {
      handleError(error, where);
    } finally {
      requestInFlight = false;
    }
    schedulePoll();
  }

  function schedulePoll() {
    window.clearTimeout(pollTimer);
    if (finished || !connected) return;
    pollTimer = window.setTimeout(poll, busy ? 700 : 1600);
  }

  async function poll() {
    if (finished || !connected) return;
    try {
      const data = await request("api/status");
      failedPolls = 0;
      handleStatus(data);
    } catch (error) {
      if (error.status === 410) {
        handleError(error, scene);
        return;
      }
      failedPolls += 1;
      if (failedPolls >= 3) {
        stop("电视连接已中断", "电视可能已关闭输入面板。草稿仍在，重新扫码后再发送。");
        return;
      }
    }
    schedulePoll();
  }

  function davPayload(prefix) {
    return {
      url: $(`#${prefix}-dav-url`).value.trim(),
      username: $(`#${prefix}-dav-user`).value.trim(),
      password: $(`#${prefix}-dav-password`).value,
      directory: $(`#${prefix}-dav-directory`).value.trim(),
      trustSelfSigned: $(`#${prefix}-dav-trust`).checked,
    };
  }

  function rememberDav(payload) {
    savedDav = { url: payload.url, username: payload.username, hasPassword: true };
    ["sync", "storage"].forEach((prefix) => {
      const password = $(`#${prefix}-dav-password`);
      password.value = "";
      password.placeholder = "已保存，留空沿用";
    });
  }

  function fillDav(prefix, account) {
    if (!account) return;
    $(`#${prefix}-dav-url`).value = account.url || "";
    $(`#${prefix}-dav-user`).value = account.username || "";
    $(`#${prefix}-dav-directory`).value = account.directory || "myMusic/";
    $(`#${prefix}-dav-trust`).checked = account.trustSelfSigned === true;
    if (account.hasPassword) $(`#${prefix}-dav-password`).placeholder = "已保存，留空沿用";
  }

  function updateExpiry() {
    if (!expiresAt || finished) return;
    const minutes = Math.max(1, Math.ceil((expiresAt - Date.now()) / 60000));
    $("#expiry").textContent = minutes + " 分钟后失效";
  }

  $$("[data-open]", panel).forEach((button) => button.addEventListener("click", () => openScene(button.dataset.open)));
  $("#back").addEventListener("click", () => openScene("home"));
  $$("[data-target]", panel).forEach((button) => button.addEventListener("click", () => selectTarget(button.dataset.target)));
  $("#write-mode").addEventListener("click", () => {
    writeMode = writeMode === "replace" ? "append" : "replace";
    $("#write-mode span").textContent = writeMode === "replace" ? "替换原文" : "追加到末尾";
  });
  inputText.addEventListener("input", updateButtons);
  $("#clear-text").addEventListener("click", () => { inputText.value = ""; inputText.focus(); updateButtons(); });
  $("#send-text").addEventListener("click", () => post("api/input", {
    target, targetId, text: inputText.value, mode: writeMode, submit: false,
  }, "home", "send"));
  $("#send-enter").addEventListener("click", () => post("api/input", {
    target, targetId, text: inputText.value, mode: writeMode, submit: true,
  }, "home", "enter"));

  $$("[data-provider]", panel).forEach((button) => button.addEventListener("click", () => setProvider(button.dataset.provider)));
  $$("[data-reveal]", panel).forEach((button) => button.addEventListener("click", () => {
    const input = document.getElementById(button.dataset.reveal);
    const reveal = input.type === "password";
    input.type = reveal ? "text" : "password";
    button.textContent = reveal ? "隐藏" : "显示";
    button.setAttribute("aria-pressed", String(reveal));
  }));
  $$("[data-preset]", panel).forEach((button) => button.addEventListener("click", () => {
    const section = button.closest(".p-scene");
    $$("[data-preset]", section).forEach((item) => item.setAttribute("aria-pressed", String(item === button)));
    const input = $(".dav-url", section);
    if (input && (!input.value.trim() || knownPresetUrls.includes(input.value.trim()))) input.value = presetUrls[button.dataset.preset];
    updateButtons();
  }));
  $$("[data-signer-mode]", panel).forEach((button) => button.addEventListener("click", () => setSignerMode(button.dataset.signerMode)));
  $("#signer-enabled").addEventListener("change", updateSigner);
  $$("input", panel).forEach((input) => input.addEventListener("input", updateButtons));
  $$("input[type=checkbox]", panel).forEach((input) => input.addEventListener("change", updateButtons));

  $("#source-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const url = $("#source-url").value.trim();
    if (!/^https?:\/\//i.test(url)) {
      showResult("source", "error", "地址格式不正确", "请输入以 http:// 或 https:// 开头的脚本地址。");
      return;
    }
    post("api/source", { url }, "source", "import");
  });
  $("#paste-source").addEventListener("click", async () => {
    try {
      const value = await navigator.clipboard.readText();
      if (value) {
        $("#source-url").value = value;
        updateButtons();
      } else toast("剪贴板为空，请长按输入框粘贴");
    } catch (_) {
      toast("浏览器未允许读取剪贴板，请长按输入框粘贴");
    }
  });
  $("#sync-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const payload = provider === "webdav" ? { provider, ...davPayload("sync") } : {
      provider, token: $("#sync-token").value.trim(), remote: $("#sync-remote").value.trim(),
    };
    post("api/sync", payload, "sync", "connect", payload.provider === "webdav" ? payload : null);
  });
  $("#sync-test").addEventListener("click", () => post("api/sync/test", davPayload("sync"), "sync", "test"));
  $("#storage-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const payload = davPayload("storage");
    post("api/storage", payload, "storage", "save", payload);
  });
  $("#storage-test").addEventListener("click", () => post("api/storage/test", davPayload("storage"), "storage", "test"));
  $("#signer-form").addEventListener("submit", (event) => {
    event.preventDefault();
    post("api/signer", {
      enabled: $("#signer-enabled").checked,
      custom: signerMode === "custom",
      url: $("#signer-url").value.trim(),
      token: $("#signer-token").value,
    }, "signer", "save");
  });

  window.addEventListener("pagehide", () => {
    window.clearTimeout(pollTimer);
    $("#sync-token").value = "";
    $("#sync-dav-password").value = "";
    $("#storage-dav-password").value = "";
    $("#signer-token").value = "";
  });
  window.addEventListener("pageshow", (event) => { if (event.persisted) schedulePoll(); });

  async function initialize() {
    try {
      const session = await request("api/session");
      $("#device-name").textContent = (session.deviceName || "电视") + " · 已连接";
      expiresAt = session.expiresAt || 0;
      allowSearch = session.allowSearch !== false;
      allowSettings = session.allowSettings !== false;
      $("[data-target=search]").hidden = !allowSearch;
      $(".p-settings").hidden = !allowSettings;
      setProvider(session.provider || "gist");
      if (session.webdav) {
        savedDav = session.webdav;
        fillDav("sync", session.webdav);
        fillDav("storage", session.webdav);
      } else {
        ["sync", "storage"].forEach((prefix) => { $(`#${prefix}-dav-directory`).value = "myMusic/"; });
      }
      $("#signer-enabled").checked = session.signer ? session.signer.enabled !== false : true;
      $("#signer-url").value = session.signer && session.signer.url ? session.signer.url : "";
      setSignerMode(session.signer && session.signer.custom ? "custom" : "public");
      if (session.target) {
        targetId = session.target.id || "";
        targetAvailable = session.target.available === true;
        targetHasValue = session.target.hasValue === true;
        targetLabel = session.target.label || "电视输入框";
        supportsEnter = session.target.supportsEnter !== false;
      }
      $("[data-target=field]").hidden = !targetId;
      selectTarget(targetId ? "field" : "search");
      connected = true;
      connecting.hidden = true;
      openScene(scenes.includes(session.initialScene) ? session.initialScene : "home");
      updateTargetCopy();
      updateButtons();
      updateExpiry();
      window.setInterval(updateExpiry, 30000);
      schedulePoll();
    } catch (error) {
      connecting.hidden = true;
      if (error.status === 410) stop("二维码已过期", "回电视重新打开输入面板并扫码。");
      else stop("连不上电视", "请确认手机与电视在同一网络，再重新扫码。");
    }
  }

  updateButtons();
  initialize();
})();

(() => {
  "use strict";

  const providers = {
    gist: {
      description: "使用 GitHub 账号创建一个私密 Gist，或连接已有 Gist。",
      tokenHelp: "需要 gist 权限。令牌不会写进同步文件，也不会被此网页保存。",
      tokenLink: "https://github.com/settings/tokens/new?scopes=gist&description=myMusic",
      tokenLinkLabel: "创建 GitHub 令牌",
      remoteLabel: "Gist ID（可选）",
      remotePlaceholder: "留空则新建一个私密 Gist",
    },
    gitee: {
      description: "使用 Gitee 账号创建一个私密代码片段，或连接已有代码片段。",
      tokenHelp: "私人令牌需要 gists 权限。令牌不会写进同步文件，也不会被此网页保存。",
      tokenLink: "https://gitee.com/profile/personal_access_tokens",
      tokenLinkLabel: "创建 Gitee 令牌",
      remoteLabel: "代码片段 ID（可选）",
      remotePlaceholder: "留空则新建一个私密代码片段",
    },
  };

  const form = document.getElementById("setup-form");
  const token = document.getElementById("token");
  const remote = document.getElementById("remote");
  const toggleToken = document.getElementById("toggle-token");
  const providerButtons = Array.from(document.querySelectorAll(".provider-option"));
  const submitButton = document.getElementById("submit-button");
  const submitLabel = document.getElementById("submit-label");
  const submitIcon = document.getElementById("submit-icon");
  const submitLoader = document.getElementById("submit-loader");
  const formError = document.getElementById("form-error");
  const statusCard = document.getElementById("status-card");
  const statusTitle = document.getElementById("status-title");
  const statusDetail = document.getElementById("status-detail");

  let provider = "gist";
  let busy = false;
  let completed = false;
  let hasSubmitted = false;
  let pollTimer = null;
  let failedPolls = 0;

  function setProvider(next) {
    if (!Object.prototype.hasOwnProperty.call(providers, next)) return;
    provider = next;
    const copy = providers[next];
    providerButtons.forEach((button) => {
      button.setAttribute("aria-pressed", String(button.dataset.provider === next));
    });
    document.getElementById("provider-description").textContent = copy.description;
    document.getElementById("token-help").textContent = copy.tokenHelp;
    document.getElementById("remote-label").textContent = copy.remoteLabel;
    remote.placeholder = copy.remotePlaceholder;
    const tokenLink = document.getElementById("token-link");
    tokenLink.href = copy.tokenLink;
    tokenLink.replaceChildren(document.createTextNode(copy.tokenLinkLabel + " "));
    const icon = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    icon.setAttribute("viewBox", "0 0 20 20");
    icon.setAttribute("fill", "none");
    icon.setAttribute("aria-hidden", "true");
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", "M7 5h8v8M15 5l-9 9M11 15H5V9");
    path.setAttribute("stroke", "currentColor");
    path.setAttribute("stroke-width", "1.7");
    path.setAttribute("stroke-linecap", "round");
    path.setAttribute("stroke-linejoin", "round");
    icon.appendChild(path);
    tokenLink.appendChild(icon);
  }

  function clearPoll() {
    if (pollTimer !== null) window.clearTimeout(pollTimer);
    pollTimer = null;
  }

  function setSubmitLabel(value) {
    submitLabel.textContent = value;
    submitButton.setAttribute("aria-label", value);
  }

  function setBusy(value, label) {
    busy = value;
    providerButtons.forEach((button) => { button.disabled = value || completed; });
    token.disabled = value || completed;
    remote.disabled = value || completed;
    toggleToken.disabled = value || completed;
    submitButton.disabled = value || completed;
    submitIcon.toggleAttribute("hidden", value);
    submitLoader.hidden = !value;
    setSubmitLabel(label || (value ? "正在连接…" : (hasSubmitted ? "重试连接" : "连接并同步")));
  }

  function showFormError(message) {
    formError.textContent = message;
    formError.hidden = false;
  }

  function clearFormError() {
    formError.textContent = "";
    formError.hidden = true;
  }

  function showStatus(state, title, detail) {
    statusCard.className = "status-card " + state.replace("_", "-");
    statusTitle.textContent = title;
    statusDetail.textContent = detail;
    statusCard.hidden = false;
  }

  async function request(path, options = {}) {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 10000);
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
      if (error.name === "AbortError") throw new Error("连接电视超时，请检查手机与电视是否仍在同一网络。");
      throw error;
    } finally {
      window.clearTimeout(timeout);
    }
  }

  function stopAtTerminal(message) {
    clearPoll();
    completed = true;
    setBusy(false);
    token.value = "";
    providerButtons.forEach((button) => { button.disabled = true; });
    token.disabled = true;
    remote.disabled = true;
    toggleToken.disabled = true;
    submitButton.disabled = true;
    setSubmitLabel("会话已结束");
    showStatus("error", "无法继续配置", message);
  }

  function handleRequestError(error) {
    clearPoll();
    if (error.status === 410) {
      stopAtTerminal("二维码已过期，请扫描电视上的新二维码。");
      return;
    }
    setBusy(false);
    showStatus("error", "连接未完成", error.message || "配置没有发送成功，请重试。");
  }

  function handleStatus(data) {
    const state = data.state;
    if (state === "working" || state === "needs_tv") {
      setBusy(true, state === "needs_tv" ? "请在电视上继续" : "正在连接…");
      showStatus(state, data.title || "正在连接", data.detail || "电视正在处理配置。");
      return;
    }
    if (state === "success") {
      clearPoll();
      completed = true;
      token.value = "";
      remote.value = "";
      setBusy(false);
      setSubmitLabel("已连接");
      showStatus("success", data.title || "连接成功", data.detail || "电视已完成连接，可以关闭此页面。");
      return;
    }
    if (state === "error") {
      clearPoll();
      setBusy(false);
      showStatus("error", data.title || "连接未完成", data.detail || "请检查配置后重试。");
      return;
    }
    clearPoll();
    setBusy(false);
    statusCard.hidden = true;
  }

  async function poll() {
    clearPoll();
    if (!busy || completed) return;
    try {
      const data = await request("api/status");
      failedPolls = 0;
      handleStatus(data);
      if (busy && !completed) pollTimer = window.setTimeout(poll, 900);
    } catch (error) {
      if (error.status === 410) {
        handleRequestError(error);
        return;
      }
      failedPolls += 1;
      if (failedPolls < 3) {
        pollTimer = window.setTimeout(poll, 1400);
      } else {
        handleRequestError(new Error("与电视的连接已中断，请检查网络；如果电视已退出配置页，请重新扫码。"));
      }
    }
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (busy || completed) return;
    clearFormError();
    const trimmedToken = token.value.trim();
    if (!trimmedToken) {
      showFormError("请填写访问令牌。");
      token.focus();
      return;
    }
    hasSubmitted = true;
    failedPolls = 0;
    setBusy(true, "正在发送…");
    showStatus("working", "正在发送配置", "请保持此页面打开，电视会继续验证并同步。");
    try {
      const data = await request("api/connect", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-MyMusic-Setup": "1",
        },
        body: JSON.stringify({ provider, token: trimmedToken, remote: remote.value.trim() }),
      });
      handleStatus(data);
      if (busy && !completed) pollTimer = window.setTimeout(poll, 700);
    } catch (error) {
      handleRequestError(error);
    }
  });

  providerButtons.forEach((button) => {
    button.addEventListener("click", () => {
      if (!busy && !completed) {
        setProvider(button.dataset.provider);
        clearFormError();
      }
    });
  });

  toggleToken.addEventListener("click", () => {
    const reveal = token.type === "password";
    token.type = reveal ? "text" : "password";
    toggleToken.textContent = reveal ? "隐藏" : "显示";
    toggleToken.setAttribute("aria-label", reveal ? "隐藏令牌" : "显示令牌");
    toggleToken.setAttribute("aria-pressed", String(reveal));
  });

  window.addEventListener("pagehide", () => {
    clearPoll();
    token.value = "";
  });
  window.addEventListener("pageshow", (event) => {
    if (event.persisted && busy && !completed) pollTimer = window.setTimeout(poll, 300);
  });

  async function initialize() {
    try {
      const session = await request("api/session");
      setProvider(session.provider);
      submitButton.disabled = false;
    } catch (error) {
      if (error.status === 410) {
        stopAtTerminal("二维码已过期，请扫描电视上的新二维码。");
      } else {
        submitButton.disabled = true;
        showFormError("无法读取电视配置会话。请确认手机与电视在同一网络，并重新扫码。");
      }
    }
  }

  setProvider("gist");
  initialize();
})();

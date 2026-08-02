'use strict';
(function () {
  window.BuiltinMiniApps = Object.freeze([
    Object.freeze({
      id: 'builtin-qr',
      title: '二维码工具',
      category: '工具',
      description: '生成二维码，或从图片与相机中识别二维码内容。',
      iconKey: 'qr',
      source: 'builtin',
      launch: 'qr'
    })
  ]);
  const X = window.X = {
    cloud: { section: 'files', path: '/', info: null, recycle: [], shares: [], tasks: [], downloads: [], uploadQueue: [], view: 'list', sortBy: 'name', sortAsc: true, searchQuery: '', selectedIds: [], selected: new Set() },
    announcement: { items: [], activeId: '', loading: false },
    feedback: { items: [] },
    ai: { models: [], conversations: [], currentId: '', current: null, messages: [], tasks: [], remainingTokens: 0, attachments: [], _convSearch: '' },
    music: { tracks: [], playlists: [], currentTrackId: '', lyricTimer: null, search: '', currentPlaylistId: '', currentList: [], playMode: 'sequence', recommend: [], sidebarFilter: 'all', lyricsCollapsed: true, subtitleFont: 18, subtitleRect: null, comments: { trackId: '', items: [], loaded: false, loading: false, open: false } },
    videos: { list: [], categories: [], currentVideoId: '', comments: [], danmaku: [], search: '', categoryId: '', playbackRate: 1, currentList: [], pageSize: 18, pageOffset: 0, total: 0, hasMore: true, lazyLoading: false, commentsPageSize: 20, commentsOffset: 0, commentsTotal: 0, commentsHasMore: true, commentsLoading: false, loadedCommentsFor: '' },
    preview: { src: '', name: '', rotation: 0, scaleX: 1, cropSquare: false, brightness: 100, contrast: 100, saturate: 100 },
    mobile: { enabled: false, panel: 'content' },
    builtinMiniApps: { activeId: '', qr: { dataUrl: '' } },
    admin: { userSort: 'createdAt', userDir: 'desc' }
  };

  function q(id) { return document.getElementById(id); }
  window.q = q; // 挂载到全局，供 onclick 字符串调用
  const DAILY_MUSIC_PLAYLIST_ID = '__daily_recommend__';
  function esc(v) { return typeof eH === 'function' ? eH(String(v == null ? '' : v)) : String(v == null ? '' : v); }
  window.fallbackAvatar = function(el, letter) {
    el.style.display = 'none';
    const parent = el.parentNode;
    if (parent) {
      const fb = document.createElement('span');
      fb.textContent = letter || '?';
      fb.style.cssText = 'font-weight:700;font-size:13px;color:var(--muted)';
      parent.appendChild(fb);
    }
  };
  function fmtDate(v) { return v ? new Date(v).toLocaleString('zh-CN') : '-'; }
  function fmtQuota(used, quota) { return Number(quota) < 0 ? `${fmtBytes(used || 0)} / 不限` : `${fmtBytes(used || 0)} / ${fmtBytes(quota || 0)}`; }
  function cloudPolicyName(policy) { return policy === 'delete' ? '直接删除文件' : policy === 'keep' ? '仅删除消息保留文件' : '移入回收站'; }
  function cloudTaskStatusText(status) {
    return ({
      pending: '等待中',
      running: '进行中',
      done: '已完成',
      failed: '失败',
      queued: '排队中',
      succeeded: '已完成'
    })[String(status || '')] || String(status || '未知');
  }
  function fileIcon(entry) {
    const name = String((entry && entry.name) || '').toLowerCase();
    const type = String((entry && entry.type) || '');
    const ct = String((entry && entry.contentType) || '').toLowerCase();
    if (type === 'folder') return '📁';
    if (ct.startsWith('image/')) return '🖼️';
    if (ct.startsWith('video/')) return '🎬';
    if (ct.startsWith('audio/')) return '🎵';
    if (/\.zip$/i.test(name)) return '🗜️';
    if (/\.(doc|docx|ppt|pptx|xls|xlsx|pdf)$/i.test(name)) return '📄';
    if (/\.(md|txt|json|xml|html|css|js|ts|tsx|jsx|java|py|cpp|c|h|hpp|go|rs|sql|yml|yaml)$/i.test(name)) return '💻';
    return '📦';
  }
  function previewType(entry) {
    const name = String((entry && entry.name) || '').toLowerCase();
    const ct = String((entry && entry.contentType) || '').toLowerCase();
    if (ct.startsWith('image/')) return 'image';
    if (ct.startsWith('video/')) return 'video';
    if (ct.startsWith('audio/')) return 'audio';
    if (/\.pdf$/i.test(name)) return 'pdf';
    if (/\.(doc|docx|ppt|pptx|xls|xlsx)$/i.test(name)) return 'office';
    if (/\.(mp3|flac|wav|m4a|ogg|aac)$/i.test(name)) return 'audio';
    if (/\.(mp4|mov|webm|mkv|avi)$/i.test(name)) return 'video';
    if (/\.md$/i.test(name)) return 'markdown';
    if (/\.(txt|json|xml|html|css|js|ts|tsx|jsx|java|py|cpp|c|h|hpp|go|rs|sql|yml|yaml)$/i.test(name)) return 'code';
    return 'file';
  }
  function httpErrorText(status) {
    return ({
      400: '请求参数不正确',
      401: '登录状态已失效，请重新登录',
      403: '你没有权限执行这个操作',
      404: '请求的内容不存在',
      408: '请求超时，请重试',
      413: '上传内容过大',
      429: '操作过于频繁，请稍后再试',
      500: '服务器开小差了，请稍后再试',
      502: '服务器暂时不可用',
      503: '服务器暂时不可用',
      504: '服务器响应超时'
    })[Number(status)] || '';
  }
  function normalizeUiError(error, fallbackText) {
    if (error && error.name === 'AbortError') return '请求超时，请重试';
    const raw = String(error && error.message || error || '').trim();
    if (!raw) return fallbackText || '操作失败';
    if (raw === 'Failed to fetch') return '网络连接失败，请检查服务器是否在线';
    if (raw === '服务器返回了无法解析的内容') return fallbackText || '服务器返回了异常内容';
    if (raw === 'The user aborted a request.' || raw === 'signal is aborted without reason') return '请求超时，请重试';
    return raw;
  }
  async function readApiResponse(res) {
    if (typeof safeJson === 'function') return safeJson(res);
    try { return await res.json(); } catch (e) { return { error: '服务器返回了无法解析的内容' }; }
  }
  async function fetchWithTimeout(url, options, timeoutMs) {
    const controller = typeof AbortController === 'function' ? new AbortController() : null;
    const timer = controller ? setTimeout(() => controller.abort(), Math.max(1, Number(timeoutMs) || 20000)) : null;
    try {
      const req = Object.assign({}, options || {});
      if (controller) req.signal = controller.signal;
      return await fetch(url, req);
    } catch (error) {
      throw new Error(normalizeUiError(error, '请求失败'));
    } finally {
      if (timer) clearTimeout(timer);
    }
  }
  function resolveApiError(res, data, fallbackText) {
    const raw = String(data && data.error || '').trim();
    if (raw && raw !== 'Failed to fetch' && raw !== '服务器返回了无法解析的内容') return raw;
    return httpErrorText(res && res.status) || normalizeUiError(raw, fallbackText) || fallbackText || '请求失败';
  }
  async function apiGet(url, options) {
    const res = await fetchWithTimeout(url, options, 20000);
    const data = await readApiResponse(res);
    if (!res.ok || (data && data.error)) throw new Error(resolveApiError(res, data, '加载失败'));
    return data;
  }
  async function apiPost(url, payload, options) {
    const req = Object.assign({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload || {})
    }, options || {});
    const res = await fetchWithTimeout(url, req, 20000);
    const data = await readApiResponse(res);
    if (!res.ok || (data && data.error)) throw new Error(resolveApiError(res, data, '提交失败'));
    return data;
  }
  function buildDownloadUrl(url) {
    if (!url) return '';
    return `${url}${url.includes('?') ? '&' : '?'}download=1`;
  }
  function triggerBrowserDownload(url, fileName) {
    if (!url) return false;
    const a = document.createElement('a');
    a.href = url;
    if (fileName) a.download = fileName;
    a.rel = 'noopener';
    document.body.appendChild(a);
    a.click();
    a.remove();
    return true;
  }
  async function copyText(text, successText) {
    const value = String(text || '');
    const fallbackCopy = () => {
      const ta = document.createElement('textarea');
      ta.value = value;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.focus();
      ta.select();
      const ok = document.execCommand('copy');
      ta.remove();
      if (!ok) throw new Error('copy_failed');
    };
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        try {
          await navigator.clipboard.writeText(value);
        } catch (e) {
          fallbackCopy();
        }
      } else {
        fallbackCopy();
      }
      toast(successText || '已复制');
      return true;
    } catch (e) {
      toast('复制失败，请检查浏览器权限', true);
      return false;
    }
  }
  window.copyText = copyText; // 挂载到全局，供 onclick 字符串调用
  function openFramePreview(src, htmlOrUrl, isHtml) {
    const ov = q('prevOv');
    const frame = q('prevFrame');
    ['prevImg', 'prevVideo', 'prevMonaco'].forEach(id => q(id) && q(id).classList.add('hidden'));
    if (!ov || !frame) return false;
    if (isHtml) {
      const blob = new Blob([String(htmlOrUrl || '')], { type: 'text/html' });
      const url = URL.createObjectURL(blob);
      if (frame.dataset.extraUrl) URL.revokeObjectURL(frame.dataset.extraUrl);
      frame.dataset.extraUrl = url;
      frame.src = url;
    } else {
      frame.src = htmlOrUrl;
    }
    frame.classList.remove('hidden');
    ov.classList.add('show');
    let previewFailed = false;
    const failTimer = setTimeout(function () {
      if (!previewFailed) { previewFailed = true; toast('预览加载超时，请尝试下载查看', true); }
    }, 15000);
    frame.onerror = function () {
      if (!previewFailed) { previewFailed = true; toast('预览加载失败，请尝试下载查看', true); }
    };
    ov.onclick = e => {
      if (e.target !== ov) return;
      clearTimeout(failTimer);
      frame.onerror = null;
      if (frame.dataset.extraUrl) {
        URL.revokeObjectURL(frame.dataset.extraUrl);
        delete frame.dataset.extraUrl;
      }
      if (typeof closePrev === 'function') closePrev();
    };
    return true;
  }
  function ensurePreviewTools() {
    const ov = q('prevOv');
    if (!ov || q('prevExtraTools')) return;
    const bar = document.createElement('div');
    bar.id = 'prevExtraTools';
    bar.className = 'hidden';
    bar.style.cssText = 'position:absolute;top:14px;left:18px;right:18px;display:flex;gap:6px;z-index:10;flex-wrap:wrap;align-items:center;justify-content:center';
    bar.innerHTML = "<button class='tb-btn' id='prevDownloadBtn' type='button' style='font-size:12px;padding:6px 12px'>下载</button>"
      + "<button class='tb-btn hidden' id='prevEditBtn' type='button' style='font-size:12px;padding:6px 12px;background:linear-gradient(135deg,#6366f1,#8b5cf6);color:#fff;border:none'>编辑</button>"
      + "<button class='tb-btn hidden' id='prevCropBtn' type='button' style='font-size:12px;padding:6px 12px'>裁剪</button>"
      + "<button class='tb-btn hidden' id='prevRotateBtn' type='button' style='font-size:12px;padding:6px 12px'>旋转</button>"
      + "<button class='tb-btn hidden' id='prevFlipBtn' type='button' style='font-size:12px;padding:6px 12px'>镜像</button>"
      + "<button class='tb-btn hidden' id='prevFilterBtn' type='button' style='font-size:12px;padding:6px 12px'>滤镜</button>"
      + "<button class='tb-btn hidden' id='prevResetBtn' type='button' style='font-size:12px;padding:6px 12px'>重置</button>"
      + "<button class='tb-btn hidden' id='prevExportBtn' type='button' style='font-size:12px;padding:6px 12px;background:linear-gradient(135deg,#10b981,#34d399);color:#fff;border:none'>导出</button>"
      + "<div id='prevFilterBar' class='x-img-edit-bar hidden' style='position:absolute;top:48px;left:0;right:0;background:rgba(15,23,42,.92);backdrop-filter:blur(16px);border-radius:16px;padding:16px;display:flex;gap:16px;flex-wrap:wrap;justify-content:center;box-shadow:0 20px 60px rgba(0,0,0,.4),inset 0 1px 0 rgba(255,255,255,.08)'></div>";
    ov.appendChild(bar);
    q('prevDownloadBtn').onclick = () => window.downloadPreviewAsset();
    q('prevEditBtn').onclick = () => window.openImageEditor();
    q('prevCropBtn').onclick = () => window.cropPreviewImage();
    q('prevRotateBtn').onclick = () => window.rotatePreviewImage();
    q('prevFlipBtn').onclick = () => window.flipPreviewImage();
    q('prevResetBtn').onclick = () => window.resetPreviewImageTransform();
    q('prevExportBtn').onclick = () => window.exportPreviewImage();
    q('prevFilterBtn').onclick = () => {
      const fb = q('prevFilterBar');
      if (!fb) return;
      fb.classList.toggle('hidden');
      if (!fb.classList.contains('hidden') && !fb.dataset.init) {
        fb.dataset.init = '1';
        fb.innerHTML = "<div class='x-filter-group'><label>亮度</label><input id='prevBrightness' type='range' min='0' max='200' value='100' oninput='applyPreviewFilter()'></div>"
          + "<div class='x-filter-group'><label>对比度</label><input id='prevContrast' type='range' min='0' max='200' value='100' oninput='applyPreviewFilter()'></div>"
          + "<div class='x-filter-group'><label>饱和度</label><input id='prevSaturate' type='range' min='0' max='200' value='100' oninput='applyPreviewFilter()'></div>"
          + "<div class='x-filter-group'><label>模糊</label><input id='prevBlur' type='range' min='0' max='10' value='0' step='0.5' oninput='applyPreviewFilter()'></div>"
          + "<div class='x-filter-group'><label>色相</label><input id='prevHue' type='range' min='-180' max='180' value='0' oninput='applyPreviewFilter()'></div>"
          + "<div class='x-filter-group'><label>Sepia</label><input id='prevSepia' type='range' min='0' max='100' value='0' oninput='applyPreviewFilter()'></div>"
          + "<div class='x-filter-group'><label>反色</label><input id='prevInvert' type='range' min='0' max='100' value='0' oninput='applyPreviewFilter()'></div>";
      }
    };
  }
  function syncPreviewImageTransform() {
    const img = q('prevImg');
    if (!img) return;
    img.style.transform = `rotate(${Number(X.preview.rotation || 0)}deg) scaleX(${Number(X.preview.scaleX || 1)})`;
    img.style.transformOrigin = 'center center';
    img.style.transition = 'transform .18s ease, filter .18s ease';
    img.style.objectFit = X.preview.cropSquare ? 'cover' : '';
    img.style.aspectRatio = X.preview.cropSquare ? '1 / 1' : '';
    const b = Number(X.preview.brightness || 100);
    const c = Number(X.preview.contrast || 100);
    const s = Number(X.preview.saturate || 100);
    const bl = Number(X.preview.blur || 0);
    const h = Number(X.preview.hue || 0);
    const sp = Number(X.preview.sepia || 0);
    const i = Number(X.preview.invert || 0);
    img.style.filter = `brightness(${b}%) contrast(${c}%) saturate(${s}%) blur(${bl}px) hue-rotate(${h}deg) sepia(${sp}%) invert(${i}%)`;
  }
  window.applyPreviewFilter = function () {
    const b = q('prevBrightness');
    const c = q('prevContrast');
    const s = q('prevSaturate');
    const bl = q('prevBlur');
    const h = q('prevHue');
    const sp = q('prevSepia');
    const i = q('prevInvert');
    X.preview.brightness = b ? Number(b.value) : 100;
    X.preview.contrast = c ? Number(c.value) : 100;
    X.preview.saturate = s ? Number(s.value) : 100;
    X.preview.blur = bl ? Number(bl.value) : 0;
    X.preview.hue = h ? Number(h.value) : 0;
    X.preview.sepia = sp ? Number(sp.value) : 0;
    X.preview.invert = i ? Number(i.value) : 0;
    syncPreviewImageTransform();
  };
  function togglePreviewTools(showImageTools) {
    ensurePreviewTools();
    const bar = q('prevExtraTools');
    if (!bar) return;
    bar.classList.remove('hidden');
    q('prevEditBtn').classList.toggle('hidden', !showImageTools);
    q('prevCropBtn').classList.toggle('hidden', !showImageTools);
    q('prevRotateBtn').classList.toggle('hidden', !showImageTools);
    q('prevFlipBtn').classList.toggle('hidden', !showImageTools);
    q('prevResetBtn').classList.toggle('hidden', !showImageTools);
    q('prevExportBtn').classList.toggle('hidden', !showImageTools);
    q('prevFilterBtn').classList.toggle('hidden', !showImageTools);
    if (!showImageTools && q('prevFilterBar')) q('prevFilterBar').classList.add('hidden');
  }

  function activeChatRoom() {
    const chatView = q('chatView');
    return chatView && chatView.classList.contains('active') && typeof room === 'string' && room ? room : '';
  }
  function parseCardPayload(raw) {
    if (!raw) return {};
    if (typeof raw === 'object') return raw;
    try { return JSON.parse(String(raw)); } catch (e) { return {}; }
  }
  function shareIcon(type) {
    const key = { cloud: 'cloud', note: 'notes', music: 'music', video: 'video', game: 'miniapps' }[type] || 'brand';
    return featureIcon(key, '');
  }
  function shareActionLabel(type) {
    return { cloud: '打开文件', note: '查看笔记', music: '立即播放', video: '立即播放', game: '打开小程序' }[type] || '查看详情';
  }
  async function pickShareTargetRoom() {
    return new Promise(async (resolve) => {
      const [friends, groups] = await Promise.all([
        apiGet('/api/friends').catch(() => []),
        apiGet('/api/groups').catch(() => [])
      ]);
      const overlay = document.createElement('div');
      overlay.id = 'shareTargetOverlay';
      overlay.className = 'share-target-overlay';
      overlay.onclick = (e) => { if (e.target === overlay) { overlay.remove(); resolve(''); } };
      const allFriends = friends || [];
      const allGroups = groups || [];
      let searchTerm = '';
      let selected = new Set();
      function renderList() {
        const term = searchTerm.toLowerCase();
        const filteredFriends = allFriends.filter(f => (f.nickname||f.userId||'').toLowerCase().includes(term));
        const filteredGroups = allGroups.filter(g => (g.groupName||g.groupId||'').toLowerCase().includes(term));
        let html = '<div class="share-target-title">选择发送目标</div>';
        html += '<input id="shareTargetSearch" class="share-target-search" type="text" placeholder="搜索好友或群聊..." value="'+esc(searchTerm)+'">';
        if (ME.isSuperAdmin && allFriends.length > 0) {
          const allSelected = filteredFriends.every(f => selected.has(f.userId));
          html += '<button class="tb-btn share-target-select-all" onclick="var s=document.querySelectorAll(\'.share-friend-cb\');s.forEach(function(cb){cb.checked=' + (!allSelected) + ';cb.dispatchEvent(new Event(\'change\',{bubbles:true}))});document.getElementById(\'shareTargetSearch\').focus()">' + (allSelected ? '取消全选' : '全选好友') + '</button>';
        }
        if (filteredFriends.length) {
          html += '<div class="share-target-section-label">好友（' + filteredFriends.length + '）</div>';
          filteredFriends.forEach(f => {
            const checked = selected.has(f.userId);
            html += '<label class="share-target-option"><input type="checkbox" class="share-friend-cb" data-val="' + esc(f.userId) + '" ' + (checked ? 'checked' : '') + '><span>' + esc(f.nickname || f.userId) + '</span></label>';
          });
        }
        if (filteredGroups.length) {
          html += '<div class="share-target-section-label">群聊（' + filteredGroups.length + '）</div>';
          filteredGroups.forEach(g => {
            const key = 'group_' + g.groupId;
            const checked = selected.has(key);
            html += '<label class="share-target-option"><input type="checkbox" class="share-friend-cb" data-val="' + esc(key) + '" ' + (checked ? 'checked' : '') + '><span>群聊：' + esc(g.groupName || g.groupId) + '</span></label>';
          });
        }
        if (!filteredFriends.length && !filteredGroups.length) html += '<div class="share-target-empty">无匹配结果</div>';
        html += '<div class="share-target-actions"><button class="tb-btn" id="shareTargetCancelBtn">取消</button><button class="tb-btn share-target-confirm" id="shareTargetConfirmBtn">发送' + (selected.size > 0 ? '（' + selected.size + '）' : '') + '</button></div>';
        return html;
      }
      function refresh() { box.innerHTML = renderList(); bindEvents(); }
      function bindEvents() {
        const searchInput = document.getElementById('shareTargetSearch');
        if (searchInput) searchInput.oninput = function() { searchTerm = this.value; refresh(); };
        const cancelBtn = document.getElementById('shareTargetCancelBtn');
        if (cancelBtn) cancelBtn.onclick = function() { overlay.remove(); resolve(''); };
        const confirmBtn = document.getElementById('shareTargetConfirmBtn');
        if (confirmBtn) confirmBtn.onclick = function() { overlay.remove(); resolve(Array.from(selected)); };
        document.querySelectorAll('.share-friend-cb').forEach(cb => {
          cb.onchange = function() { if (this.checked) selected.add(this.dataset.val); else selected.delete(this.dataset.val); refresh(); };
        });
        if (searchInput) setTimeout(function() { searchInput.focus(); }, 50);
      }
      const box = document.createElement('div');
      box.className = 'share-target-dialog';
      box.innerHTML = renderList();
      overlay.appendChild(box);
      document.body.appendChild(overlay);
      bindEvents();
    });
  }
  async function maybeSendShareCard(type, id, successText) {
    let targetRoom = activeChatRoom();
    if (targetRoom) {
      const useCurrent = await window.showConfirm('是否把分享卡片发送到当前会话？\n选择"取消"后可改为公共聊天室、好友或群聊。');
      if (!useCurrent) targetRoom = '';
    }
    if (!targetRoom) {
      const picked = await pickShareTargetRoom();
      if (!picked || (Array.isArray(picked) && !picked.length)) return false;
      if (Array.isArray(picked)) {
        let sent = 0;
        for (const roomId of picked) {
          try { await apiPost('/api/share/send-card', { type, id, room: roomId }); sent++; } catch (e) {}
        }
        if (sent > 0) toast('已发送到 ' + sent + ' 个会话');
        return sent > 0;
      }
      targetRoom = picked;
    }
    if (!targetRoom) return false;
    const msg = await apiPost('/api/share/send-card', { type, id, room: targetRoom });
    if (msg && msg.id && targetRoom === room && typeof appendMsg === 'function') appendMsg(msg);
    toast(successText || '分享卡片已发送');
    return true;
  }
  function buildShareCardMessage(msg) {
    const payload = parseCardPayload(msg.cardPayload);
    const shareType = String(payload.shareType || msg.cardType || '');
    const route = String(payload.url || '');
    const link = route ? `${location.origin}${route}` : '';
    const mine = msg.fromUserId === ME.userId;
    const div = document.createElement('div');
    div.className = `message ${mine ? 'mine' : 'other'}`;
    div.id = `msg-${msg.id}`;
    const t = typeof formatChatTime === 'function' ? formatChatTime(msg.timestamp) : fmtDate(msg.timestamp);
    const avHtml = mine
      ? (ME.avatarPath ? `<img src="${esc(ME.avatarPath)}" style="width:34px;height:34px;border-radius:50%;object-fit:cover" onerror="fallbackAvatar(this,'${esc((ME.nickname||'?').slice(0,1).toUpperCase())}')">` : esc((ME.nickname || '?')[0].toUpperCase()))
      : (msg.avatarPath ? `<img src="${esc(msg.avatarPath)}" style="width:34px;height:34px;border-radius:50%;object-fit:cover" onerror="fallbackAvatar(this,'${esc((msg.fromNickname||'?').slice(0,1).toUpperCase())}')">` : esc((msg.fromNickname || '?')[0].toUpperCase()));
    const canRecall = !msg.recalled && mine && (typeof ME !== "undefined" && ME.isSuperAdmin || (Date.now() - (+msg.timestamp || 0) <= 600000));
    const recB = canRecall ? `<button class="act del" type="button">撤回</button>` : '';
    const fwdB = !msg.recalled ? `<button class="act" type="button">转发</button>` : '';
    const saTag = typeof isSA === 'function' && isSA(msg.fromUserId) ? ' <span class="sa-b">超级管理员</span>' : '';
    const developerTag = msg.isDeveloper ? ' <span class="developer-b">开发者</span>' : '';
    const sharedDeveloperTag = payload.developerIsDeveloper ? ' <span class="developer-b">开发者</span>' : '';
    const fontKey = /^(songti|heiti|kaiti|fangsong|dengxian|mono)$/.test(msg.messageFont || '') ? msg.messageFont : 'default';
    const previewHtml = payload.contentPreview ? `<div style="font-size:12px;color:var(--muted);line-height:1.6;white-space:pre-wrap">${esc(payload.contentPreview)}</div>` : '';
    const coverHtml = payload.coverPath
      ? `<img src="${esc(payload.coverPath)}" style="width:72px;height:72px;border-radius:16px;object-fit:cover;flex-shrink:0">`
      : `<div style="width:72px;height:72px;border-radius:16px;display:flex;align-items:center;justify-content:center;flex-shrink:0;background:linear-gradient(135deg,var(--ac),var(--ac2));color:#fff;font-size:30px">${shareIcon(shareType)}</div>`;
    const mediaHtml = shareType === 'music' && payload.filePath
      ? `<audio controls style="width:100%;margin-top:10px" src="${esc(payload.filePath)}"></audio>`
      : shareType === 'video' && payload.filePath
        ? `<video controls style="width:100%;margin-top:10px;border-radius:14px;background:#000" src="${esc(payload.filePath)}"></video>`
        : '';
    div.innerHTML =
      `<div class="msg-av" style="background:linear-gradient(135deg,var(--ac),var(--ac2));color:white;" title="查看资料">${avHtml}</div>` +
      `<div class="msg-body">` +
      `<div class="msg-name">${esc(msg.fromNickname || '')}${saTag}${developerTag}</div>` +
      `<div class="bubble ${mine ? 'mine-b' : 'other-b'}${fontKey !== 'default' ? ' mf-' + fontKey : ''}" style="padding:0;background:transparent;border:none;box-shadow:none">` +
      `<div class="x-share-chat-card" style="width:min(360px,72vw);border:1px solid var(--in-bd);border-radius:18px;background:var(--bg);overflow:hidden;box-shadow:0 16px 36px rgba(15,23,42,.10)">` +
      `<div class="x-share-chat-hit" style="cursor:${route ? 'pointer' : 'default'}">` +
      `<div style="display:flex;gap:12px;padding:14px 14px 10px;align-items:flex-start">${coverHtml}<div style="min-width:0;flex:1"><div style="font-size:12px;color:var(--muted);margin-bottom:6px">${shareIcon(shareType)} ${esc({ cloud: '文件分享', note: '笔记分享', music: '音乐分享', video: '视频分享', game: '小程序分享' }[shareType] || '分享卡片')}</div><div style="font-size:15px;font-weight:700;color:var(--text);line-height:1.4">${esc(payload.title || msg.content || '分享内容')}</div><div style="font-size:12px;color:var(--muted);margin:6px 0 8px">${esc(payload.subtitle || '')}${sharedDeveloperTag}</div>${previewHtml}</div></div>` +
      `${mediaHtml}</div>` +
      `<div style="display:flex;gap:8px;padding:0 14px 14px">` +
      `<button class="tb-btn x-share-preview" type="button">查看卡片</button>` +
      `<button class="tb-btn x-share-open" type="button">${esc(shareActionLabel(shareType))}</button>` +
      `<button class="tb-btn x-share-copy" type="button">复制链接</button>` +
      `</div></div></div>` +
      `<div class="msg-time">${t}<span class="msg-acts">${recB}${fwdB}</span></div></div>`;
    const avatar = div.querySelector('.msg-av');
    if (avatar) avatar.onclick = () => typeof openUserProfile === 'function' && openUserProfile(msg.fromUserId);
    const card = div.querySelector('.x-share-chat-card');
    const cardHit = div.querySelector('.x-share-chat-hit');
    const previewBtn = div.querySelector('.x-share-preview');
    const openBtn = div.querySelector('.x-share-open');
    const copyBtn = div.querySelector('.x-share-copy');
    const acts = div.querySelector('.msg-acts');
    if (cardHit && route) cardHit.addEventListener('click', () => {
      if (shareType === 'game' && typeof launchSharedGameRoute === 'function') launchSharedGameRoute(route, false);
      else openShareCardOverlay(msg, payload, route);
    });
    if (card && !route) card.style.cursor = 'default';
    if (previewBtn) previewBtn.onclick = ev => {
      ev.stopPropagation();
      if (route) openShareCardOverlay(msg, payload, route);
    };
    if (openBtn) openBtn.onclick = ev => {
      ev.stopPropagation();
      if (route) {
        if (shareType === 'game' && typeof launchSharedGameRoute === 'function') launchSharedGameRoute(route, false);
        else openShareRoute(route);
      }
    };
    if (copyBtn) copyBtn.onclick = async ev => {
      ev.stopPropagation();
      if (link) await copyText(link, '分享链接已复制');
    };
    if (acts) {
      const btns = acts.querySelectorAll('button');
      if (btns[0] && canRecall) btns[0].onclick = () => typeof recallMsg === 'function' && recallMsg(msg.id);
      if (btns[canRecall ? 1 : 0] && !msg.recalled) btns[canRecall ? 1 : 0].onclick = () => typeof openFwd === 'function' && openFwd(msg.id);
    }
    if (typeof syncMsgSelectState === 'function') syncMsgSelectState(div, msg.id);
    return div;
  }

  function ensureShareCardOverlay() {
    if (q('shareCardOverlay')) return;
    const node = document.createElement('div');
    node.id = 'shareCardOverlay';
    node.className = 'x-share-overlay hidden';
    node.innerHTML = "<div class='x-share-overlay-backdrop' onclick='closeShareCardOverlay()'></div><div class='x-share-overlay-sheet'><button class='x-share-close' type='button' onclick='closeShareCardOverlay()'>×</button><div id='shareCardOverlayBody'></div></div>";
    document.body.appendChild(node);
  }
  function renderShareOverlayBody(msg, payload, route) {
    const shareType = String(payload.shareType || msg.cardType || '');
    const title = payload.title || msg.content || '分享内容';
    const subtitle = payload.subtitle || '';
    const developerBadge = payload.developerIsDeveloper ? "<span class='developer-b'>开发者</span>" : '';
    const link = route ? `${location.origin}${route}` : '';
    const coverHtml = payload.coverPath
      ? `<img class='x-share-cover' src='${esc(payload.coverPath)}' alt=''>`
      : `<div class='x-share-cover x-share-cover-fallback'>${shareIcon(shareType)}</div>`;
    const preview = payload.contentPreview ? `<div class='x-share-pre'>${esc(payload.contentPreview)}</div>` : '';
    const media = shareType === 'music' && payload.filePath
      ? `<div class='x-card'><audio controls style='width:100%' src='${esc(payload.filePath)}'></audio></div>`
      : shareType === 'video' && payload.filePath
        ? `<div class='x-card'><video controls style='width:100%;border-radius:16px;background:#000' src='${esc(payload.filePath)}'></video></div>`
        : '';
    const hasFile = payload.filePath && (shareType === 'cloud' || shareType === 'music' || shareType === 'video');
    const downloadBtn = hasFile
      ? `<button class='tb-btn x-overlay-download' type='button'>下载文件</button>`
      : '';
    return `<div class='x-share-card x-share-overlay-card'><div class='hero'>${coverHtml}<div style='flex:1;min-width:0'><div class='x-pill-row'><span class='x-badge'>${esc({ cloud: '云盘分享', note: '笔记分享', music: '音乐分享', video: '视频分享', game: '小程序分享' }[shareType] || '分享卡片')}</span>${subtitle ? `<span class='x-badge warn'>${esc(subtitle)}</span>` : ''}${developerBadge}</div><h2 style='margin:14px 0 8px'>${esc(title)}</h2><div class='x-toolbar' style='margin-top:16px'><button class='tb-btn x-overlay-copy' type='button'>复制链接</button><button class='tb-btn x-overlay-open' type='button'>${esc(shareActionLabel(shareType))}</button>${downloadBtn}</div></div></div>${preview}${media}</div>`;
  }
  window.openShareCardOverlay = function (msg, payload, route) {
    ensureShareCardOverlay();
    const root = q('shareCardOverlayBody');
    if (!root) return;
    root.innerHTML = renderShareOverlayBody(msg, payload, route);
    const copyBtn = root.querySelector('.x-overlay-copy');
    const openBtn = root.querySelector('.x-overlay-open');
    const downloadBtn = root.querySelector('.x-overlay-download');
    const link = route ? `${location.origin}${route}` : '';
    if (copyBtn) copyBtn.onclick = () => copyText(link, '分享链接已复制');
    if (openBtn) openBtn.onclick = () => {
      const shareType = String(payload.shareType || msg.cardType || '');
      if (shareType === 'game' && typeof launchSharedGameRoute === 'function') launchSharedGameRoute(route, false);
      else openShareRoute(route);
    };
    if (downloadBtn) downloadBtn.onclick = () => triggerBrowserDownload(
      buildDownloadUrl(withFileName(payload.filePath, payload.title || '文件')),
      payload.title || '文件'
    );
    q('shareCardOverlay').classList.remove('hidden');
  };
  window.closeShareCardOverlay = function () {
    const node = q('shareCardOverlay');
    if (node) node.classList.add('hidden');
  };

  function activeAnnouncementItem() {
    const list = Array.isArray(X.announcement.items) ? X.announcement.items : [];
    return list.find(item => item.id === X.announcement.activeId) || list[0] || null;
  }
  function renderAnnouncementComposer() {
    if (!ME || !ME.isSuperAdmin) return '';
    return `<div class='x-ann-compose'><div class='x-kv'><strong>发布公告</strong><span class='x-badge'>支持 Markdown</span></div><input id='announcementTitleInput' class='x-input' placeholder='标题（可选，默认系统公告）'><textarea id='announcementContentInput' class='x-textarea' placeholder='输入公告内容，支持 Markdown'></textarea><div class='x-toolbar x-ann-compose-actions'><button id='announcementSubmitBtn' class='tb-btn' onclick='submitAnnouncement()'>发布公告</button></div></div>`;
  }
  function renderAnnouncementCards() {
    const items = Array.isArray(X.announcement.items) ? X.announcement.items : [];
    if (!items.length) return "<div class='x-ann-empty'>当前还没有公告</div>";
    const limit = X.announcement.showAll ? items.length : Math.min(3, items.length);
    return items.slice(0, limit).map(item => `<button type='button' class='x-ann-item ${item.id === X.announcement.activeId ? 'active' : ''}' onclick="selectAnnouncement('${esc(item.id)}')"><div class='x-ann-item-title'>${esc(item.title || '系统公告')}</div><div class='x-ann-item-meta'>${fmtDate(item.updatedAt || item.createdAt)}<br>@${esc(item.authorNickname || '系统')}</div></button>`).join('');
  }
  function renderAnnouncementMoreButton() {
    const items = Array.isArray(X.announcement.items) ? X.announcement.items : [];
    if (items.length <= 3) return '';
    return X.announcement.showAll
      ? `<button type='button' class='x-ann-more' onclick='toggleAnnouncementMore()'>收起</button>`
      : `<button type='button' class='x-ann-more' onclick='toggleAnnouncementMore()'>更多 · 还有 ${items.length - 3} 条</button>`;
  }
  function renderAnnouncementSheet() {
    const host = q('announcementSheet');
    if (!host) return;
    const item = activeAnnouncementItem();
    host.innerHTML = `<div class='x-ann-shell'><div class='x-ann-side'><div><div class='x-ann-side-title'>公告</div><div class='x-ann-side-sub'>查看服务器通知、维护安排和功能变更。</div></div>${renderAnnouncementComposer()}<div class='x-ann-list ${X.announcement.showAll ? 'expanded' : ''}'>${renderAnnouncementCards()}</div>${renderAnnouncementMoreButton()}</div><div class='x-ann-main'><div class='x-ann-main-head'><div><div class='x-ann-title'>${esc(item && item.title || '服务器公告')}</div><div class='x-ann-meta'>${item ? `发布于 ${fmtDate(item.updatedAt || item.createdAt)} · 发布者 ${esc(item.authorNickname || '系统')}` : '当前还没有公告'}</div></div><button type='button' class='x-ann-close' onclick='closeAnnouncements()'>×</button></div><div class='x-ann-main-body'>${item ? `<div class='x-md-body'>${renderMarkdown(item.content || '')}</div>` : "<div class='x-ann-empty'>当前还没有公告</div>"}</div></div></div>`;
  }
  async function refreshAnnouncements(preferredId) {
    X.announcement.loading = true;
    try {
      const list = await apiGet('/api/announcements' + (X.announcement.showAll ? '' : '?limit=3'));
      X.announcement.items = Array.isArray(list) ? list : [];
      X.announcement.activeId = preferredId || (X.announcement.items[0] && X.announcement.items[0].id) || '';
      renderAnnouncementSheet();
    } finally {
      X.announcement.loading = false;
    }
  }
  window.selectAnnouncement = function (id) {
    X.announcement.activeId = id || '';
    renderAnnouncementSheet();
  };
  window.openAnnouncements = async function (preferredId) {
    X.announcement.showAll = false;
    ensureExtraDom();
    closeMobileMore();
    const overlay = q('announcementOverlay');
    const host = q('announcementSheet');
    if (!overlay || !host) return;
    overlay.classList.remove('hidden');
    host.innerHTML = "<div class='x-ann-shell'><div class='x-ann-side'><div class='x-ann-empty'>公告加载中...</div></div><div class='x-ann-main'><div class='x-ann-main-body'><div class='x-ann-empty'>公告加载中...</div></div></div></div>";
    try {
      await refreshAnnouncements(preferredId);
    } catch (e) {
      host.innerHTML = `<div class='x-ann-shell'><div class='x-ann-side'><div class='x-ann-empty'>${esc(e.message || '加载失败')}</div></div><div class='x-ann-main'><div class='x-ann-main-body'><div class='x-ann-empty'>${esc(e.message || '加载失败')}</div></div></div></div>`;
    }
  };
  window.closeAnnouncements = function () {
    const item = activeAnnouncementItem();
    if (item && item.id) {
      try { localStorage.setItem(announcementSeenKey(), item.id); } catch (_) {}
    }
    const overlay = q('announcementOverlay');
    if (overlay) overlay.classList.add('hidden');
  };
  function announcementSeenKey() {
    return 'announcement_seen_id_' + ((window.ME && ME.userId) || 'guest');
  }
  async function bootUnreadAnnouncement() {
    try {
      const latest = await apiGet('/api/announcements/latest');
      if (!latest || !latest.id) return;
      let seen = '';
      try { seen = localStorage.getItem(announcementSeenKey()) || ''; } catch (_) {}
      if (seen !== latest.id) openAnnouncements(latest.id);
    } catch (_) {}
  }
  setTimeout(bootUnreadAnnouncement, 600);
  window.submitAnnouncement = async function () {
    const titleInput = q('announcementTitleInput');
    const contentInput = q('announcementContentInput');
    const submitBtn = q('announcementSubmitBtn');
    const title = String(titleInput && titleInput.value || '').trim();
    const content = String(contentInput && contentInput.value || '').trim();
    if (!content) return toast('请输入公告内容', true);
    if (submitBtn) {
      submitBtn.disabled = true;
      submitBtn.textContent = '发布中...';
    }
    try {
      const created = await apiPost('/api/announcements/create', { title, content });
      if (titleInput) titleInput.value = '';
      if (contentInput) contentInput.value = '';
      toast('公告已发布');
      await refreshAnnouncements(created && created.id);
    } catch (e) {
      toast(e.message || '公告发布失败', true);
    } finally {
      if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.textContent = '发布公告';
      }
    }
  };

  function activeViewId() {
    return document.querySelector('.view.active')?.id || '';
  }
  function detectMobileShell() {
    const width = window.innerWidth || document.documentElement.clientWidth || 0;
    const height = window.innerHeight || document.documentElement.clientHeight || 1;
    const ratio = width / Math.max(height, 1);
    if (width > 0 && width <= 860) return true;
    const isMobileUA = /Mobi|Android|iPhone|iPad|iPod|Opera Mini|IEMobile|WPDesktop|BlackBerry/i.test(navigator.userAgent);
    const hasCoarsePointer = typeof window.matchMedia === 'function'
      && window.matchMedia('(pointer: coarse)').matches;
    return width <= 1080 && ratio <= 0.8 && (isMobileUA || hasCoarsePointer);
  }
  const MOBILE_NAV_OPTIONAL = [
    { key: 'discover', label: '发现好友', iconKey: 'contacts', action: 'openDiscover()' },
    { key: 'games', label: '小程序', iconKey: 'miniapps', action: 'openGames()' },
    { key: 'moments', label: '朋友圈', iconKey: 'moments', action: 'openMoments()' }
  ];
  const MOBILE_MORE_BASE_ITEMS = [
    { label: '云盘', iconKey: 'cloud', action: 'openCloud()' },
    { label: 'AI 助手', iconKey: 'ai', action: 'openAi()' },
    { label: '音乐', iconKey: 'music', action: 'openMusic()' },
    { label: '视频', iconKey: 'video', action: 'openVideos()' },
    { label: '笔记', iconKey: 'notes', action: 'openNotes()' },
    { label: '公告', iconKey: 'brand', action: 'openAnnouncements()' },
    { label: '反馈', iconKey: 'feedback', action: 'openFeedback()' }
  ];
  const IOS_SYMBOL_PATHS = {
    chat: '<path d="M4.5 6.25h15v9.5h-8.2l-4.8 3v-3H4.5z"/><path d="M8 10h.01M12 10h.01M16 10h.01"/>',
    contacts: '<circle cx="12" cy="8.25" r="3.25"/><path d="M5.5 19c.45-3.35 2.65-5 6.5-5s6.05 1.65 6.5 5"/>',
    miniapps: '<rect x="4" y="4" width="6.2" height="6.2" rx="1.5"/><rect x="13.8" y="4" width="6.2" height="6.2" rx="1.5"/><rect x="4" y="13.8" width="6.2" height="6.2" rx="1.5"/><rect x="13.8" y="13.8" width="6.2" height="6.2" rx="1.5"/>',
    moments: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="2.2"/><path d="M12 4v5.8M19 8l-5 2.9M19 16l-5-2.9M12 20v-5.8M5 16l5-2.9M5 8l5 2.9"/>',
    more: '<circle cx="5" cy="12" r="1.25"/><circle cx="12" cy="12" r="1.25"/><circle cx="19" cy="12" r="1.25"/>',
    profile: '<circle cx="12" cy="8" r="3.4"/><path d="M5.4 19.5c.35-3.75 2.55-5.6 6.6-5.6s6.25 1.85 6.6 5.6"/>',
    cloud: '<path d="M7.5 18.5h9.2a4 4 0 0 0 .35-7.98A5.7 5.7 0 0 0 6.2 9.25 4.65 4.65 0 0 0 7.5 18.5Z"/>',
    ai: '<path d="m12 3 1.4 4.1L17.5 8.5l-4.1 1.4L12 14l-1.4-4.1-4.1-1.4 4.1-1.4zM18.2 14.2l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8zM6.2 14.8l.65 1.85 1.85.65-1.85.65-.65 1.85-.65-1.85-1.85-.65 1.85-.65z"/>',
    music: '<path d="M9 18V6.5l10-2V16"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="16.5" cy="16" r="2.5"/>',
    video: '<rect x="3.5" y="5.5" width="17" height="13" rx="3"/><path d="m10 9 5 3-5 3z"/>',
    notes: '<path d="M6 3.5h9l3 3V20.5H6z"/><path d="M14.5 3.5v4h3.5M9 12h6M9 15.5h6"/>',
    brand: '<path d="M18 9a6 6 0 0 0-12 0c0 7-2.5 7-2.5 7h17S18 16 18 9Z"/><path d="M10 20h4"/>',
    feedback: '<path d="M4 5.5h16v12H9l-4.5 3v-3H4z"/><path d="M8 10h8M8 13h5"/>',
    admin: '<circle cx="12" cy="12" r="3"/><path d="M12 3.5v2M12 18.5v2M3.5 12h2M18.5 12h2M6 6l1.4 1.4M16.6 16.6 18 18M18 6l-1.4 1.4M7.4 16.6 6 18"/>'
  };
  function mobileTabIcon(key) {
    const paths = IOS_SYMBOL_PATHS[key] || IOS_SYMBOL_PATHS.miniapps;
    return `<svg class="ios-symbol" viewBox="0 0 24 24" aria-hidden="true">${paths}</svg>`;
  }
  function syncMobileNavOverflow() {
    const nav = q('mobileShellNav');
    if (!nav) return [];
    let hidden = [];
    if (X.mobile.enabled) {
      const navWidth = nav.getBoundingClientRect().width || window.innerWidth || document.documentElement.clientWidth || 0;
      const minButtonWidth = 58;
      const pinnedSlots = 3;
      const slots = Math.max(pinnedSlots, Math.min(6, Math.floor((navWidth - 10) / minButtonWidth)));
      const optionalSlots = Math.max(0, Math.min(MOBILE_NAV_OPTIONAL.length, slots - pinnedSlots));
      hidden = MOBILE_NAV_OPTIONAL.slice(optionalSlots).map(item => item.key);
    }
    X.mobile.hiddenNavKeys = hidden;
    const hiddenSet = new Set(hidden);
    nav.style.setProperty('--mobile-nav-count', String(Math.max(3, 6 - hidden.length)));
    nav.querySelectorAll('[data-nav]').forEach(btn => btn.classList.toggle('x-nav-overflow-hidden', hiddenSet.has(btn.dataset.nav)));
    return hidden;
  }
  function mobileNavKey() {
    if (X.mobile.enabled && X.mobile.panel === 'sidebar') return 'messages';
    const active = activeViewId();
    let key = 'more';
    if (active === 'discoverView' || active === 'userProfileView') key = 'discover';
    else if (active === 'gamesView') key = 'games';
    else if (active === 'momentsView') key = 'moments';
    else if (active === 'profileView') key = 'profile';
    else if (active === 'chatView') key = 'messages';
    return (X.mobile.hiddenNavKeys || []).includes(key) ? 'more' : key;
  }
  function updateMobileNavActive() {
    const nav = q('mobileShellNav');
    if (!nav) return;
    syncMobileNavOverflow();
    const current = mobileNavKey();
    nav.querySelectorAll('[data-nav]').forEach(btn => {
      const active = btn.dataset.nav === current;
      btn.classList.toggle('active', active);
      btn.setAttribute('aria-selected', String(active));
    });
    const backBtn = q('mobileChatBack');
    if (backBtn) backBtn.classList.toggle('hidden', !(X.mobile.enabled && X.mobile.panel !== 'sidebar' && activeViewId() === 'chatView'));
  }
  function setMobilePanel(panel) {
    X.mobile.panel = panel === 'sidebar' ? 'sidebar' : 'content';
    document.body.classList.toggle('mobile-panel-sidebar', X.mobile.enabled && X.mobile.panel === 'sidebar');
    updateMobileNavActive();
  }
  function syncMobileStateForRoute(path) {
    if (!X.mobile.enabled) return;
    const clean = ((path || location.pathname || '/chat').replace(/\/+$/g, '') || '/');
    if (clean === '/' || clean === '/chat') {
      setMobilePanel('sidebar');
      return;
    }
    setMobilePanel('content');
  }
  function renderMobileMoreGrid() {
    const grid = q('mobileMoreGrid');
    if (!grid) return;
    const hiddenKeys = new Set(syncMobileNavOverflow());
    const promoted = MOBILE_NAV_OPTIONAL
      .filter(item => hiddenKeys.has(item.key))
      .map(item => ({ label: item.label, iconKey: item.iconKey, action: item.action }));
    const items = promoted.concat(MOBILE_MORE_BASE_ITEMS);
    if (ME && ME.isSuperAdmin) items.push({ label: '服务器管理', iconKey: 'admin', action: 'openServerAdmin()' });
    grid.innerHTML = items.map(item => `<button type='button' class='x-mobile-more-btn' onclick="closeMobileMore();${item.action}"><span class='x-mobile-more-icon'>${mobileTabIcon(item.iconKey)}</span><strong>${esc(item.label)}</strong></button>`).join('');
  }
  window.openMobileNav = function (key) {
    closeMobileMore();
    if (key === 'messages') {
      try {
        if (typeof sw === 'function') sw('chatView', window.room ? `c-${window.room}` : 'c-public');
      } catch (e) {}
      if (typeof updateRoute === 'function') updateRoute('/chat', true);
      setMobilePanel('sidebar');
      return;
    }
    if (key === 'discover' && typeof openDiscover === 'function') return openDiscover();
    if (key === 'games' && typeof openGames === 'function') return openGames();
    if (key === 'moments' && typeof openMoments === 'function') return openMoments();
    if (key === 'more') return openMobileMore();
    if (key === 'profile' && typeof openProfile === 'function') return openProfile();
  };
  window.openMobileMore = function () {
    renderMobileMoreGrid();
    const node = q('mobileMore');
    if (node) {
      node.classList.remove('hidden');
      node.classList.add('show');
    }
    const nav = q('mobileShellNav');
    if (nav) nav.querySelectorAll('[data-nav]').forEach(btn => btn.classList.toggle('active', btn.dataset.nav === 'more'));
  };
  window.closeMobileMore = function () {
    const node = q('mobileMore');
    if (node) {
      node.classList.remove('show');
      node.classList.add('hidden');
    }
    updateMobileNavActive();
  };

  window.showConfirm = function (msg) {
    return new Promise(resolve => {
      window._confirmRes = function (value) {
        closeModal('cusConfirmModal');
        resolve(!!value);
      };
      const box = q('cusConfirmMsg');
      if (box) box.textContent = msg || '';
      openModal('cusConfirmModal');
    });
  };
  window.showPrompt = function (msg, def) {
    return new Promise(resolve => {
      window._promptRes = function (value) {
        closeModal('cusPromptModal');
        resolve(value);
      };
      const box = q('cusPromptMsg');
      const inp = q('cusPromptInput');
      if (box) box.textContent = msg || '';
      if (inp) inp.value = def || '';
      openModal('cusPromptModal');
      setTimeout(() => inp && inp.focus(), 30);
    });
  };
  window.showAlert = function (msg) {
    return new Promise(resolve => {
      window._alertRes = function () {
        closeModal('cusAlertModal');
        resolve();
      };
      const box = q('cusAlertMsg');
      if (box) box.textContent = msg || '';
      openModal('cusAlertModal');
    });
  };
  document.addEventListener('click', function (ev) {
    const target = ev.target;
    if (!target || !target.closest) return;
    if (target.closest('.prev-close')) {
      ev.preventDefault();
      ev.stopPropagation();
      if (typeof closePrev === 'function') closePrev();
      return;
    }
    if (target.closest('.x-share-close')) {
      ev.preventDefault();
      ev.stopPropagation();
      if (typeof closeShareCardOverlay === 'function') closeShareCardOverlay();
      return;
    }
    if (target.closest('.x-ann-close')) {
      ev.preventDefault();
      ev.stopPropagation();
      if (typeof closeAnnouncements === 'function') closeAnnouncements();
    }
  }, true);
  function ensureMobileChatBackButton() {
    const header = q('chatView') && q('chatView').querySelector('.ch-hdr');
    if (!header || q('mobileChatBack')) return;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.id = 'mobileChatBack';
    btn.className = 'tb-btn x-mobile-chat-back hidden';
    btn.textContent = '← 会话';
    btn.onclick = () => {
      if (typeof updateRoute === 'function') updateRoute('/chat', true);
      setMobilePanel('sidebar');
    };
    header.insertBefore(btn, header.firstChild);
  }
  function applyMobileShell() {
    ensureExtraDom();
    const wasMobileEnabled = !!X.mobile.enabled;
    const previousMobilePanel = X.mobile.panel;
    X.mobile.enabled = detectMobileShell();
    document.body.classList.toggle('mobile-shell', X.mobile.enabled);
    const nav = q('mobileShellNav');
    if (nav) nav.classList.toggle('hidden', !X.mobile.enabled);
    ensureMobileChatBackButton();
    renderMobileMoreGrid();
    if (!X.mobile.enabled) {
      document.body.classList.remove('mobile-panel-sidebar');
      closeMobileMore();
      updateMobileNavActive();
      return;
    }
    if (wasMobileEnabled && previousMobilePanel === 'content' && activeViewId() === 'chatView') {
      setMobilePanel('content');
      return;
    }
    syncMobileStateForRoute(location.pathname);
  }

  function ensureExtraDom() {
    if (!q('shareView')) {
      const node = document.createElement('div');
      node.className = 'view';
      node.id = 'shareView';
      node.innerHTML = "<div class='x-share-view'><div class='x-share-card' id='shareRoot'></div></div>";
      q('chatArea').appendChild(node);
    }
    if (!q('cloudFileInput')) {
      const input = document.createElement('input');
      input.type = 'file';
      input.multiple = true;
      input.id = 'cloudFileInput';
      input.className = 'hidden';
      input.onchange = () => uploadCloudFiles(Array.from(input.files || []));
      document.body.appendChild(input);
    }
    // 注入管理面板的额外 tab 容器（按钮已内置在 HTML 中）
    if (!q('adminPublicroom')) {
      const contentArea = q('serverAdminView');
      if (contentArea) {
        const area = contentArea.querySelector(':scope > div:nth-child(2)');
        if (area) {
          const prDiv = document.createElement('div');
          prDiv.id = 'adminPublicroom';
          prDiv.className = 'admin-tab hidden';
          area.appendChild(prDiv);
          const fbDiv = document.createElement('div');
          fbDiv.id = 'adminFeedback';
          fbDiv.className = 'admin-tab hidden';
          area.appendChild(fbDiv);
        }
      }
    }
    if (!q('cloudFolderInput')) {
      const input = document.createElement('input');
      input.type = 'file';
      input.multiple = true;
      input.id = 'cloudFolderInput';
      input.className = 'hidden';
      input.setAttribute('webkitdirectory', 'webkitdirectory');
      input.onchange = () => uploadCloudFiles(Array.from(input.files || []));
      document.body.appendChild(input);
    }
    if (!q('gameCoverInput')) {
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = 'image/*';
      input.id = 'gameCoverInput';
      input.className = 'hidden';
      document.body.appendChild(input);
    }
    if (!q('gamePreviewInput')) {
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = 'video/*';
      input.id = 'gamePreviewInput';
      input.className = 'hidden';
      document.body.appendChild(input);
    }
    // gameCoverInput/gamePreviewInput 的 onchange 由 Java JS 的 addEventListener 处理，此处不再重复绑定
    if (!q('miniVideoPlayer')) {
      const node = document.createElement('div');
      node.id = 'miniVideoPlayer';
      node.className = 'x-mini-player hidden';
      node.innerHTML = "<div class='x-mini-hd'><span id='miniVideoTitle'>小窗播放</span><div class='x-actions'><button class='tb-btn' onclick='restoreMiniVideo()'>恢复</button><button class='tb-btn' onclick='closeMiniVideo()'>关闭</button></div></div><video id='miniVideoEl' controls></video>";
      document.body.appendChild(node);
    }
    if (!q('announcementOverlay')) {
      const node = document.createElement('div');
      node.id = 'announcementOverlay';
      node.className = 'x-ann-overlay hidden';
      node.innerHTML = "<div class='x-ann-backdrop' onclick='closeAnnouncements()'></div><div class='x-ann-sheet' id='announcementSheet'></div>";
      document.body.appendChild(node);
    }
    if (!q('mobileShellNav')) {
      const node = document.createElement('div');
      node.id = 'mobileShellNav';
      node.className = 'x-mobile-nav hidden';
      node.setAttribute('role', 'tablist');
      node.setAttribute('aria-label', '主要导航');
      node.innerHTML = `<button type='button' role='tab' data-nav='messages' onclick="openMobileNav('messages')"><span class='ios-tab-symbol'>${mobileTabIcon('chat')}</span><strong>消息</strong></button><button type='button' role='tab' data-nav='discover' onclick="openMobileNav('discover')"><span class='ios-tab-symbol'>${mobileTabIcon('contacts')}</span><strong>发现</strong></button><button type='button' role='tab' data-nav='games' onclick="openMobileNav('games')"><span class='ios-tab-symbol'>${mobileTabIcon('miniapps')}</span><strong>小程序</strong></button><button type='button' role='tab' data-nav='moments' onclick="openMobileNav('moments')"><span class='ios-tab-symbol'>${mobileTabIcon('moments')}</span><strong>朋友圈</strong></button><button type='button' role='tab' data-nav='more' onclick="openMobileNav('more')"><span class='ios-tab-symbol'>${mobileTabIcon('more')}</span><strong>更多</strong></button><button type='button' role='tab' data-nav='profile' onclick="openMobileNav('profile')"><span class='ios-tab-symbol'>${mobileTabIcon('profile')}</span><strong>我</strong></button>`;
      document.body.appendChild(node);
    }
    if (!q('mobileMore')) {
      const node = document.createElement('div');
      node.id = 'mobileMore';
      node.className = 'x-mobile-more hidden';
      node.innerHTML = "<div class='x-mobile-more-backdrop' onclick='closeMobileMore()'></div><div class='x-mobile-more-sheet'><div class='x-mobile-more-handle'></div><div class='x-mobile-more-head'><div><strong>更多</strong><p>应用与服务</p></div><button type='button' class='x-mobile-more-close' onclick='closeMobileMore()' aria-label='关闭'>×</button></div><div class='x-mobile-more-grid' id='mobileMoreGrid'></div></div>";
      document.body.appendChild(node);
    }
  }

  const oldApplyRoute = window.applyRoute;
  window.applyRoute = async function (path, mode) {
    const clean = ((path || location.pathname || '/chat').replace(/\/+$/g, '') || '/');
    let result;
    if (clean === '/cloud') result = openCloud(mode);
    else if (clean === '/ai') result = openAi(mode);
    else if (clean === '/music') result = openMusic(mode);
    else if (clean === '/videos') result = openVideos(mode);
    else if (clean === '/feedback') result = openFeedback(mode);
    else if (clean === '/tools') result = openMiniTools(mode);
    else if (clean.startsWith('/share/')) result = openShareRoute(clean, mode);
    else if (clean.startsWith('/note/')) result = openNoteRoute(clean, mode);
    else result = oldApplyRoute ? oldApplyRoute(path, mode) : undefined;
    if (result && typeof result.then === 'function') result = await result;
    syncMobileStateForRoute(clean);
    return result;
  };

  const oldSw = window.sw;
  if (oldSw) {
    window.sw = function (id, cid) {
      const result = oldSw(id, cid);
      if (X.mobile.enabled && id !== 'chatView') setMobilePanel('content');
      updateMobileNavActive();
      return result;
    };
  }

  function ensureProfileSettingsLayout() {
    const view = q('profileView');
    if (!view || q('profileSettingsShell')) return;
    const scroll = view.children && view.children[1];
    if (!scroll) return;
    const oldGrid = Array.from(scroll.children || []).find(el => el && el.querySelector && el.querySelector('.pc'));
    if (!oldGrid) return;
    const cards = Array.from(oldGrid.querySelectorAll('.pc'));
    const shell = document.createElement('div');
    shell.id = 'profileSettingsShell';
    shell.className = 'profile-settings-shell';
    shell.innerHTML = [
      "<aside class='profile-settings-nav' aria-label='个人中心分组'>",
      "<button class='profile-settings-tab active' data-profile-section='basic' onclick=\"switchProfileSection('basic')\"><span class='profile-tab-mark mark-profile' aria-hidden='true'></span><span>资料</span></button>",
      "<button class='profile-settings-tab' data-profile-section='appearance' onclick=\"switchProfileSection('appearance')\"><span class='profile-tab-mark mark-appearance' aria-hidden='true'></span><span>外观</span></button>",
      "<button class='profile-settings-tab' data-profile-section='account' onclick=\"switchProfileSection('account')\"><span class='profile-tab-mark mark-security' aria-hidden='true'></span><span>安全</span></button>",
      "<button class='profile-settings-tab' data-profile-section='resources' onclick=\"switchProfileSection('resources')\"><span class='profile-tab-mark mark-resources' aria-hidden='true'></span><span>资源</span></button>",
      "</aside>",
      "<div class='profile-settings-content'>",
      "<section class='profile-section active' id='profileSectionBasic'></section>",
      "<section class='profile-section single' id='profileSectionAppearance'></section>",
      "<section class='profile-section single' id='profileSectionAccount'></section>",
      "<section class='profile-section' id='profileSectionResources'></section>",
      "</div>"
    ].join('');
    scroll.replaceChild(shell, oldGrid);
    const target = {
      basic: q('profileSectionBasic'),
      account: q('profileSectionAccount'),
      appearance: q('profileSectionAppearance'),
      resources: q('profileSectionResources')
    };
    cards.forEach(card => {
      let section = card.dataset.pcSection || '';
      if (!section) {
        // 兜底:旧版无 data-pc-section 标记时按内容推断
        section = 'basic';
        if (card.querySelector('#newUN') || card.querySelector('#oldPW') || card.querySelector('#pMsg-acct') || card.querySelector('[onclick*="openPasswordChange"]')) section = 'account';
        else if (card.querySelector('.theme-row')) section = 'appearance';
        else if (card.querySelector('#profileLevelInfo') || card.id === 'profileCloudBox') section = 'resources';
        else if (card.querySelector('#upNick') || card.querySelector('#upId')) section = 'basic';
      }
      (target[section] || target.basic).appendChild(card);
    });
  }

  window.switchProfileSection = function (section) {
    const active = section || 'basic';
    document.querySelectorAll('.profile-settings-tab').forEach(btn => {
      btn.classList.toggle('active', btn.dataset.profileSection === active);
    });
    document.querySelectorAll('.profile-section').forEach(panel => {
      panel.classList.toggle('active', panel.id === 'profileSection' + active.charAt(0).toUpperCase() + active.slice(1));
    });
  };

  function injectProfileExtras() {
    ensureProfileSettingsLayout();
    const view = q('profileView');
    if (!view || q('profileCloudBox')) return;
    const columns = view.querySelectorAll('.pc');
    const targetColumn = q('profileSectionResources') || (columns[4] && columns[4].parentElement);
    if (!targetColumn) return;
    const card = document.createElement('div');
    card.className = 'pc';
    card.id = 'profileCloudBox';
    card.innerHTML = "<h4 class='pc-ttl'>云盘与 AI</h4><div id='profileQuotaSummary' class='x-sub profile-quota-summary'>加载中...</div><label class='pc-label profile-policy-label'>删除文件后的处理方式</label><select id='cloudDeletePolicySelect' class='x-select profile-policy-select'><option value='recycle'>移入回收站（默认）</option><option value='delete'>直接删除服务器文件</option><option value='keep'>仅删除消息，保留云盘文件</option></select><button class='tb-btn' onclick='saveCloudDeletePolicy()'>保存云盘策略</button>";
    targetColumn.appendChild(card);
  }

  function syncMeFromSnapshot(data) {
    if (!data || !window.ME) return;
    [
      'username', 'nickname', 'messageFont', 'exp', 'level', 'levelDisplay', 'nextLevelExp', 'lastCheckIn',
      'checkInStreak', 'dailyGamePlayExpCount', 'dailyGamePlayExpLimit',
      'cloudUsedBytes', 'cloudQuotaBytes', 'cloudDeletePolicy',
      'cloudQuotaByLevel', 'aiDailyLimitByLevel', 'dailyGameUploadLimitByLevel',
      'msgsPerMinuteByLevel', 'aiRemainingTokens', 'aiUsedTokensToday',
      'requirePasswordChange', 'tutorialCompleted', 'aiImageUnlocked', 'aiVideoUnlocked',
      'bio', 'birthday', 'gender', 'language', 'avatarPath', 'bubbleSkin'
    ].forEach(key => {
      if (data[key] !== undefined) ME[key] = data[key];
    });
    if (window.X && X.ai && data.aiRemainingTokens !== undefined) X.ai.remainingTokens = data.aiRemainingTokens || 0;
    maybeStartNewUserTutorial();
  }

  async function refreshProfileExtras() {
    injectProfileExtras();
    const box = q('profileQuotaSummary');
    const select = q('cloudDeletePolicySelect');
    if (!box || !select) return;
    try {
      const me = await apiGet('/api/me');
      syncMeFromSnapshot(me);
      if (typeof initProfileUI === 'function') initProfileUI();
      box.innerHTML = `云盘：${fmtQuota(me.cloudUsedBytes || 0, me.cloudQuotaBytes || 0)}<br>AI 剩余：${Math.round(Number(me.aiRemainingTokens || 0))} 点额度`;
      select.value = me.cloudDeletePolicy || 'recycle';
    } catch (e) {
      box.textContent = e.message || '加载失败';
    }
  }

  window.saveCloudDeletePolicy = async function () {
    try {
      await apiPost('/api/profile/update', { type: 'cloudDeletePolicy', value: q('cloudDeletePolicySelect').value });
      toast('云盘策略已保存');
      refreshProfileExtras();
    } catch (e) {
      toast(e.message || '保存失败', true);
    }
  };

  const oldOpenProfile = window.openProfile;
  window.openProfile = function (navMode) {
    const result = oldOpenProfile ? oldOpenProfile(navMode) : undefined;
    setTimeout(refreshProfileExtras, 0);
    return result;
  };

  const oldSwitchAdminTab = window.switchAdminTab;
  window.switchAdminTab = function (tab) {
    adminTab = tab || 'overview';
    ['overview', 'users', 'groups', 'publicroom', 'feedback', 'recovery', 'superadmins'].forEach(name => {
      const id = name === 'superadmins' ? 'adminSuperAdmins' : name === 'publicroom' ? 'adminPublicroom' : name === 'feedback' ? 'adminFeedback' : name === 'recovery' ? 'adminRecovery' : 'admin' + name.charAt(0).toUpperCase() + name.slice(1);
      const el = q(id);
      if (el) el.classList.toggle('hidden', name !== adminTab);
    });
    document.querySelectorAll('[data-admin-tab]').forEach(button => {
      const active = button.getAttribute('data-admin-tab') === adminTab;
      button.classList.toggle('active', active);
      button.setAttribute('aria-current', active ? 'page' : 'false');
    });
    if (adminTab === 'publicroom') { if (typeof loadAdminPublicRoom === 'function') return loadAdminPublicRoom(); }
    if (adminTab === 'groups') { if (typeof loadAdminGroups === 'function') return loadAdminGroups(); }
    if (adminTab === 'feedback') { if (typeof loadAdminFeedback === 'function') return loadAdminFeedback(); }
    if (adminTab === 'recovery') return loadAdminRecovery();
    if (adminTab === 'users') { if (typeof loadAdminUsers === 'function') return loadAdminUsers(); }
    if (adminTab === 'superadmins') { if (typeof loadAdminSuperAdmins === 'function') return loadAdminSuperAdmins(); }
    try { return oldSwitchAdminTab ? oldSwitchAdminTab(tab) : undefined; } catch(e) { console.error('[admin] switchAdminTab error:', e); }
  };

  window.loadAdminUsers = async function () {
    const box = q('adminUsers');
    box.innerHTML = "<div class='admin-loading'>加载中...</div>";
    try {
      const sort = (X.admin && X.admin.userSort) || 'createdAt';
      const dir = (X.admin && X.admin.userDir) || 'desc';
      const resp = await apiGet(`/api/admin/users?sort=${encodeURIComponent(sort)}&dir=${encodeURIComponent(dir)}`);
      const users = resp && resp.users ? resp.users : [];
      const toolbar = `<div class='x-toolbar admin-table-toolbar'><div class='x-sub'>共 ${Number((resp && resp.total) || users.length)} 位注册用户</div><div class='admin-filter-controls'><select class='x-input admin-sort-primary' onchange='setAdminUserSort(this.value,null)'><option value='createdAt' ${sort === 'createdAt' ? 'selected' : ''}>按注册时间</option><option value='username' ${sort === 'username' ? 'selected' : ''}>按用户名</option><option value='userId' ${sort === 'userId' ? 'selected' : ''}>按用户ID</option></select><select class='x-input admin-sort-direction' onchange='setAdminUserSort(null,this.value)'><option value='desc' ${dir === 'desc' ? 'selected' : ''}>倒序</option><option value='asc' ${dir === 'asc' ? 'selected' : ''}>正序</option></select></div></div>`;
      box.innerHTML = `${toolbar}<div class='admin-table-scroll'><table class='x-table admin-user-table'><thead><tr><th>用户</th><th>注册时间</th><th>在线</th><th>云盘</th><th>AI</th><th>状态</th><th>操作</th></tr></thead><tbody>${users.map(u => {
        const avatar = u.avatarPath ? `<img src='${esc(u.avatarPath)}' class='admin-user-avatar' onerror="fallbackAvatar(this,'${esc((u.nickname||'?').slice(0,1).toUpperCase())}')">` : `<div class='admin-user-avatar admin-user-avatar-fallback'>${esc((u.nickname || '?').slice(0, 1).toUpperCase())}</div>`;
        const state = `${u.isPrimarySuperAdmin ? "<span class='x-badge warn'>服主</span>" : u.isSuperAdmin ? "<span class='x-badge'>超级管理员</span>" : "<span class='x-badge ok'>普通用户</span>"}${u.banned ? " <span class='x-badge warn'>已封禁</span>" : ''}${u.isCurrentUser ? " <span class='x-badge'>当前账号</span>" : ''}`;
        const canModerate = !u.isCurrentUser && !u.isSuperAdmin;
        const expBtn = (ME && ME.isPrimarySuperAdmin && !u.isCurrentUser) ? `<button class='tb-btn' onclick="adminGrantExp('${esc(u.userId)}')">发经验</button>` : '';
        const banBtn = canModerate ? (u.banned ? `<button class='tb-btn admin-action-safe' onclick="adminUnbanUser('${esc(u.userId)}')">解封</button>` : `<button class='tb-btn admin-action-danger' onclick="adminBanUser('${esc(u.userId)}')">封禁</button>`) : '';
        const featureBtn = canModerate ? `<button class='tb-btn admin-action-warning' onclick="adminFeatureBanUser('${esc(u.userId)}')">功能封禁</button>` : '';
        const forceBtn = canModerate ? `<button class='tb-btn' onclick="forceLogoutUser('${esc(u.userId)}')">强制下线</button>` : '';
        const ops = u.isCurrentUser
          ? "<span class='x-sub'>当前账号不可操作</span>"
          : `<div class='x-admin-inline'><button class='tb-btn' onclick="adminResetUserPassword('${esc(u.userId)}')">重置密码</button><button class='tb-btn' onclick="adminSetUserQuotaPrompt('${esc(u.userId)}',${Number(u.cloudQuotaBytes || 0)})">设置云盘</button><button class='tb-btn' onclick="adminSetAiTokensPrompt('${esc(u.userId)}',${Number(u.aiRemainingTokens || 0)})">AI点数</button>${forceBtn}${banBtn}${featureBtn}${expBtn}</div>`;
        return `<tr><td><button class='admin-user-identity' onclick="openUserProfile('${esc(u.userId)}')">${avatar}<span><strong>${esc(u.nickname || u.userId)}</strong><small>@${esc(u.userId)} · ${esc(u.username || '')}</small></span></button></td><td>${fmtDate(u.createdAt)}</td><td>${Number(u.activeSessions || 0)}</td><td><div>${fmtQuota(Number(u.cloudUsedBytes || 0), Number(u.cloudQuotaBytes || 0))}</div><div class='x-sub'>策略：${cloudPolicyName(u.cloudDeletePolicy)}</div></td><td><div>已用 ${Math.round(Number(u.aiUsedTokensToday || 0))}</div><div class='x-sub'>剩余 ${Math.round(Number(u.aiRemainingTokens || 0))}</div></td><td>${state}</td><td>${ops}</td></tr>`;
      }).join('')}</tbody></table></div>`;
    } catch (e) {
      box.innerHTML = `<div class='admin-error'>${esc(e.message || '加载失败')}</div>`;
    }
  };

  window.setAdminUserSort = function (sort, dir) {
    X.admin = X.admin || {};
    if (sort) X.admin.userSort = sort;
    if (dir) X.admin.userDir = dir;
    if (typeof loadAdminUsers === 'function') loadAdminUsers();
  };

  window.adminSetUserQuotaPrompt = async function (uid, currentQuota) {
    const input = await window.showPrompt(`请输入 @${uid} 的云盘配额（单位 GB，输入 0 恢复默认 2GB）：`, Number(currentQuota) > 0 ? String(Math.round(Number(currentQuota) / 1024 / 1024 / 1024)) : '2');
    if (input === null) return;
    const gb = Number(input);
    if (!Number.isFinite(gb) || gb < 0) return toast('请输入合法数字', true);
    try {
      await apiPost('/api/admin/set-user-quota', { targetUserId: uid, quotaBytes: gb === 0 ? -1 : Math.round(gb * 1024 * 1024 * 1024) });
      toast('云盘配额已更新');
      loadAdminUsers();
    } catch (e) {
      toast(e.message || '设置失败', true);
    }
  };

  window.adminSetAiTokensPrompt = async function (uid, currentTokens) {
    const usedToday = Math.round(Number(currentTokens || 0));
    const input = await window.showPrompt(`@${uid} 的AI已用点数: ${usedToday}\n输入新值（减小数字=增加可用额度，-1=重置为0/满额）：`, String(usedToday));
    if (input === null) return;
    const tokens = Number(input);
    if (!Number.isFinite(tokens)) return toast('请输入合法数字', true);
    try {
      await apiPost('/api/admin/set-ai-tokens', { targetUserId: uid, aiTokens: tokens });
      toast('AI点数已更新');
      loadAdminUsers();
    } catch (e) {
      toast(e.message || '设置失败', true);
    }
  };
  window.toggleAnnouncementMore = async function () {
    X.announcement.showAll = !X.announcement.showAll;
    await refreshAnnouncements(X.announcement.activeId);
  };

  window.loadAdminSuperAdmins = async function () {
    const box = q('adminSuperAdmins');
    if (!box) return;
    box.innerHTML = "<div class='admin-loading'>加载中...</div>";
    try {
      const admins = await apiGet('/api/admin/super-admins');
      const canOwnerManage = !!(ME && ME.isPrimarySuperAdmin);
      const rows = (admins || []).map(item => {
        const avatar = item.avatarPath
          ? `<img src='${esc(item.avatarPath)}' class='admin-user-avatar' onerror="fallbackAvatar(this,'${esc((item.nickname || '?').slice(0, 1).toUpperCase())}')">`
          : `<div class='admin-user-avatar admin-user-avatar-fallback'>${esc((item.nickname || '?').slice(0, 1).toUpperCase())}</div>`;
        const role = (item.isPrimary ? "<span class='x-badge warn'>服主</span>" : "<span class='x-badge'>超级管理员</span>")
          + (item.isDeveloper ? " <span class='developer-b'>开发者</span>" : '');
        const action = item.isPrimary
          ? "<span class='x-sub'>服主不可移除</span>"
          : canOwnerManage
            ? `<button class='tb-btn admin-action-danger' onclick="removeSuperAdminByOwner('${esc(item.userId)}')">移除</button>`
            : (item.userId === ME.userId ? "<button class='tb-btn' onclick='quitSuperAdmin()'>退出超管</button>" : "<span class='x-sub'>仅服主可移除</span>");
        return `<tr><td><div class='admin-user-summary'>${avatar}<div><strong>${esc(item.nickname || item.userId)} ${role}</strong><div class='x-sub'>@${esc(item.userId)}</div></div></div></td><td>${Number(item.activeSessions || 0)}</td><td>${action}</td></tr>`;
      }).join('');
      box.innerHTML = `<div class='admin-panel-actions'>${canOwnerManage ? "<button class='tb-btn' onclick='addSuperAdminPrompt()'>添加超级管理员</button>" : "<button class='tb-btn' onclick='quitSuperAdmin()'>退出超管</button>"}</div><div class='admin-table-scroll'><table class='x-table'><thead><tr><th>管理员</th><th>在线会话</th><th>操作</th></tr></thead><tbody>${rows || "<tr><td colspan='3' class='x-sub'>暂无超级管理员</td></tr>"}</tbody></table></div>`;
    } catch (e) {
      box.innerHTML = `<div class='admin-error'>${esc(e.message || '加载失败')}</div>`;
    }
  };

  window.removeSuperAdminByOwner = async function (uid, nickname) {
    if (!ME || !ME.isPrimarySuperAdmin) return toast('仅服主可移除超级管理员', true);
    if (!(await window.showConfirm(`确定移除 ${nickname || uid} 的超级管理员身份吗？`))) return;
    try {
      await apiPost('/api/admin/remove-super-admin', { targetUserId: uid });
      toast('已移除超级管理员');
      loadAdminSuperAdmins();
      loadAdminUsers();
    } catch (e) {
      toast(e.message || '移除失败', true);
    }
  };

  async function loadAdminPublicRoom() {
    const box = q('adminPublicroom');
    if (!box) return;
    box.innerHTML = "<div class='admin-loading'>加载中...</div>";
    try {
      const [config, resp] = await Promise.all([apiGet('/api/public-room/config'), apiGet('/api/admin/users')]);
      const users = resp && resp.users ? resp.users : [];
      const adminIds = config.adminUserIds || config.adminIds || [];
      const mutedIds = config.mutedUserIds || [];
      const mutedUsersHtml = mutedIds.length ? mutedIds.map(uid => { const user = users.find(item => item.userId === uid) || {}; return `<div class='x-row'><span class='admin-list-marker muted' aria-hidden='true'></span><div class='x-meta'><div class='x-title'>${esc(user.nickname || uid)}</div><div class='x-sub'>@${esc(uid)}</div></div><div class='x-actions'><button class='tb-btn' onclick="unmutePublicRoomUser('${esc(uid)}')">解除禁言</button></div></div>`; }).join('') : "<div class='x-empty'>暂无被禁言用户</div>";
      box.innerHTML = `<div class='x-grid x-grid-2 admin-settings-grid'><div class='x-card'><div class='x-section-title'>公共聊天室设置</div><div class='x-kv'><span>服主</span><strong>@${esc(config.ownerId || '')}</strong></div><div class='x-kv'><span>全员禁言</span><strong>${config.allMuted ? '已开启' : '已关闭'}</strong></div><div class='x-toolbar admin-card-actions'><button class='tb-btn' onclick="togglePublicRoomMute(${config.allMuted ? 'false' : 'true'})">${config.allMuted ? '解除全员禁言' : '开启全员禁言'}</button><button class='tb-btn admin-action-danger' onclick='deletePublicRoomOldMessagesPrompt()'>删除旧消息</button></div></div><div class='x-card'><div class='x-section-title'>管理员列表</div><div class='x-list'>${adminIds.length ? adminIds.map(uid => { const user = users.find(item => item.userId === uid) || {}; return `<div class='x-row'><span class='admin-list-marker' aria-hidden='true'></span><div class='x-meta'><div class='x-title'>${esc(user.nickname || uid)}</div><div class='x-sub'>@${esc(uid)}</div></div><div class='x-actions'><button class='tb-btn' onclick="removePublicRoomAdmin('${esc(uid)}')">移除</button></div></div>`; }).join('') : "<div class='x-empty'>暂时没有额外管理员</div>"}</div><div class='x-toolbar admin-card-actions'><button class='tb-btn' onclick='addPublicRoomAdminPrompt()'>添加管理员</button></div></div><div class='x-card'><div class='x-section-title'>被禁言用户</div><div class='x-list'>${mutedUsersHtml}</div><div class='x-toolbar admin-card-actions'><button class='tb-btn' onclick='mutePublicRoomUserPrompt()'>禁言用户</button></div></div></div>`;
    } catch (e) {
      console.error('[admin] loadAdminPublicRoom error:', e);
      box.innerHTML = `<div class='admin-error'>${esc(e.message || '加载失败')}</div>`;
    }
  }

  async function loadAdminFeedback() {
    const box = q('adminFeedback');
    box.innerHTML = "<div class='admin-loading'>加载中...</div>";
    try {
      const items = await apiGet('/api/feedback/list');
      box.innerHTML = `<div class='x-list'>${items.length ? items.map(item => renderFeedbackItem(item, true)).join('') : "<div class='x-empty'>暂时没有反馈</div>"}</div>`;
    } catch (e) {
      box.innerHTML = `<div class='admin-error'>${esc(e.message || '加载失败')}</div>`;
    }
  }

  window.togglePublicRoomMute = async function (allMuted) {
    try { await apiPost('/api/public-room/toggle-all-mute', { allMuted }); toast(allMuted ? '已开启全员禁言' : '已解除全员禁言'); loadAdminPublicRoom(); } catch (e) { toast(e.message || '操作失败', true); }
  };
  window.mutePublicRoomUserPrompt = async function () {
    const uid = await window.showPrompt('请输入要禁言的用户 ID：');
    if (!uid || !uid.trim()) return;
    try { await apiPost('/api/public-room/mute-user', { targetUserId: uid.trim() }); toast('已禁言该用户'); loadAdminPublicRoom(); } catch (e) { toast(e.message || '操作失败', true); }
  };
  window.unmutePublicRoomUser = async function (uid) {
    if (!uid) return;
    try { await apiPost('/api/public-room/unmute-user', { targetUserId: uid }); toast('已解除禁言'); loadAdminPublicRoom(); } catch (e) { toast(e.message || '操作失败', true); }
  };
  window.addPublicRoomAdminPrompt = async function () {
    const uid = await window.showPrompt('请输入要添加为公共聊天室管理员的用户 ID：');
    if (!uid || !uid.trim()) return;
    try { await apiPost('/api/public-room/add-admin', { targetUserId: uid.trim() }); toast('已添加管理员'); loadAdminPublicRoom(); } catch (e) { toast(e.message || '添加失败', true); }
  };
  window.removePublicRoomAdmin = async function (uid) {
    if (!(await window.showConfirm(`确定移除 @${uid} 的公共聊天室管理员权限吗？`))) return;
    try { await apiPost('/api/public-room/remove-admin', { targetUserId: uid }); toast('已移除管理员'); loadAdminPublicRoom(); } catch (e) { toast(e.message || '移除失败', true); }
  };
  window.deletePublicRoomOldMessagesPrompt = async function () {
    const days = await window.showPrompt('删除多少天以前的公共聊天室消息？', '30');
    if (days === null) return;
    const n = Number(days);
    if (!Number.isFinite(n) || n <= 0) return toast('请输入大于 0 的天数', true);
    try { await apiPost('/api/public-room/delete-old-messages', { days: n }); toast('旧消息已处理'); } catch (e) { toast(e.message || '删除失败', true); }
  };

  async function openShareRoute(path, navMode) {
    ensureExtraDom();
    const parts = String(path || '').split('/');
    const type = decodeURIComponent(parts[2] || '');
    const id = decodeURIComponent(parts[3] || '');
    if (!type || !id) return toast('分享链接不完整', true);
    if (poll) clearInterval(poll);
    prevView = 'shareView';
    sw('shareView', '');
    updateRoute(`/share/${encodeURIComponent(type)}/${encodeURIComponent(id)}`, navMode === undefined ? true : navMode);
    const root = q('shareRoot');
    root.innerHTML = "<div class='x-empty'>分享内容加载中...</div>";
    try {
      const data = await apiGet(`/api/share/data?type=${encodeURIComponent(type)}&id=${encodeURIComponent(id)}`);
      renderShareData(data);
    } catch (e) {
      root.innerHTML = `<div class='x-empty ui-error-state'>${esc(e.message || '加载失败')}</div>`;
    }
  }

  function openNoteRoute(path, navMode) {
    const parts = String(path || '').split('/');
    const noteId = decodeURIComponent(parts[2] || '');
    if (!noteId) return;
    if (typeof openNotes === 'function') openNotes(navMode);
    setTimeout(() => {
      if (typeof openNoteById === 'function') openNoteById(noteId, true);
    }, 300);
  }

  async function loadAdminRecovery() {
    const box = q('adminRecovery');
    if (!box) return;
    box.innerHTML = "<div class='admin-loading'>加载中...</div>";
    try {
      const items = await apiGet('/api/admin/password-recovery');
      box.innerHTML = `<div class='x-list'>${items.length ? items.map(item => {
        const statusText = item.status === 'processed' ? '已处理' : item.status === 'rejected' ? '已驳回' : '待处理';
        const account = item.accountExists ? '账户存在' : '未找到账户';
      return `<div class='x-card recovery-ticket'><div class='recovery-ticket-head'><div><div class='x-title'>@${esc(item.username || '')}</div><div class='x-sub'>${esc(account)} · ${fmtDate(item.createdAt)}</div></div><span class='x-status-badge'>${esc(statusText)}</span></div><div class='recovery-ticket-reason'>${esc(item.reason || '')}</div><div class='x-toolbar recovery-ticket-actions'><button class='tb-btn' onclick="updateRecoveryStatus('${esc(item.id)}','processed')">标记已处理</button><button class='tb-btn admin-action-danger' onclick="updateRecoveryStatus('${esc(item.id)}','rejected')">驳回</button></div></div>`;
      }).join('') : "<div class='x-empty'>暂时没有找回密码申请</div>"}</div>`;
    } catch (e) {
      box.innerHTML = `<div class='admin-error'>${esc(e.message || '加载失败')}</div>`;
    }
  }

  window.updateRecoveryStatus = async function(id, status) {
    try {
      await apiPost('/api/admin/password-recovery/status', { id, status });
      toast('状态已更新');
      loadAdminRecovery();
    } catch (e) { toast(e.message || '更新失败', true); }
  };

  const noteAutoSaveState = {
    noteId: '',
    title: '',
    content: '',
    timer: 0,
    saving: false,
    dirty: false,
    retryTimer: 0
  };
  function noteAutoSaveActive() {
    try {
      return typeof currentNoteId !== 'undefined' && !!currentNoteId
        && typeof noteMode !== 'undefined' && noteMode === 'edit';
    } catch (e) {
      return false;
    }
  }
  function noteAutoSaveId() {
    try { return typeof currentNoteId !== 'undefined' ? String(currentNoteId || '') : ''; } catch (e) { return ''; }
  }
  function noteAutoSaveTitle() {
    const titleInput = q('noteTitleInput');
    return titleInput ? titleInput.value.trim() : '';
  }
  function noteAutoSaveContent() {
    try {
      if (typeof noteMdEditor !== 'undefined' && noteMdEditor) return noteMdEditor.content || '';
    } catch (e) {}
    try {
      return typeof currentNoteMarkdown !== 'undefined' ? (currentNoteMarkdown || '') : '';
    } catch (e) {
      return '';
    }
  }
  function ensureNoteAutoSaveStatus() {
    let status = q('noteAutoSaveStatus');
    if (status) return status;
    const toolbar = q('noteToolbar');
    if (!toolbar) return null;
    status = document.createElement('span');
    status.id = 'noteAutoSaveStatus';
    status.className = 'note-autosave-status';
    status.textContent = '';
    const shareBtn = q('noteShareBtn');
    if (shareBtn && shareBtn.parentNode === toolbar) toolbar.insertBefore(status, shareBtn);
    else toolbar.appendChild(status);
    return status;
  }
  function setNoteAutoSaveStatus(text, state) {
    const status = ensureNoteAutoSaveStatus();
    if (!status) return;
    status.textContent = text || '';
    status.dataset.state = state || '';
    status.classList.toggle('hidden', !text);
  }
  function resetNoteAutoSaveSnapshot(force) {
    const id = noteAutoSaveId();
    if (!id) {
      noteAutoSaveState.noteId = '';
      noteAutoSaveState.title = '';
      noteAutoSaveState.content = '';
      noteAutoSaveState.dirty = false;
      clearTimeout(noteAutoSaveState.timer);
      setNoteAutoSaveStatus('', '');
      return;
    }
    if (!force && noteAutoSaveState.noteId === id) return;
    noteAutoSaveState.noteId = id;
    noteAutoSaveState.title = noteAutoSaveTitle();
    noteAutoSaveState.content = noteAutoSaveContent();
    noteAutoSaveState.dirty = false;
    clearTimeout(noteAutoSaveState.timer);
    clearTimeout(noteAutoSaveState.retryTimer);
    setNoteAutoSaveStatus(noteAutoSaveActive() ? '自动保存已开启' : '', 'idle');
  }
  function updateNoteCacheAfterSave(note) {
    if (!note || !note.id) return;
    try {
      if (typeof currentNoteMarkdown !== 'undefined') currentNoteMarkdown = note.content || '';
    } catch (e) {}
    const titleBar = q('noteTitleBar');
    if (titleBar) titleBar.textContent = note.title || '未命名笔记';
    const preview = q('notePreviewContent');
    if (preview && typeof renderMD === 'function') preview.innerHTML = renderMD(note.content || '') || '<div style="color:var(--muted)">空白笔记</div>';
    try {
      if (Array.isArray(notesCache)) {
        const found = notesCache.find(item => item && item.id === note.id);
        const summary = {
          id: note.id,
          title: note.title || '未命名笔记',
          preview: String(note.content || '').replace(/\s+/g, ' ').trim().slice(0, 20),
          createdAt: note.createdAt,
          updatedAt: note.updatedAt,
          shareId: note.shareId
        };
        if (found) Object.assign(found, summary);
        else notesCache.unshift(summary);
        if (typeof renderNoteList === 'function') renderNoteList();
      }
    } catch (e) {}
  }
  async function flushNoteAutoSave() {
    if (!noteAutoSaveActive()) {
      resetNoteAutoSaveSnapshot(false);
      return false;
    }
    const noteId = noteAutoSaveId();
    const title = noteAutoSaveTitle();
    const content = noteAutoSaveContent();
    if (!noteId || (noteAutoSaveState.noteId === noteId
        && noteAutoSaveState.title === title && noteAutoSaveState.content === content)) {
      noteAutoSaveState.dirty = false;
      setNoteAutoSaveStatus('已保存', 'saved');
      return true;
    }
    if (noteAutoSaveState.saving) {
      noteAutoSaveState.dirty = true;
      return false;
    }
    noteAutoSaveState.saving = true;
    noteAutoSaveState.dirty = false;
    setNoteAutoSaveStatus('保存中...', 'saving');
    try {
      const note = await apiPost('/api/note/update', {
        noteId,
        title,
        content
      });
      if (noteAutoSaveId() === noteId) {
        noteAutoSaveState.noteId = noteId;
        noteAutoSaveState.title = title;
        noteAutoSaveState.content = content;
        updateNoteCacheAfterSave(note);
        setNoteAutoSaveStatus('已自动保存', 'saved');
      }
      return true;
    } catch (e) {
      noteAutoSaveState.dirty = true;
      setNoteAutoSaveStatus('自动保存失败', 'error');
      clearTimeout(noteAutoSaveState.retryTimer);
      noteAutoSaveState.retryTimer = setTimeout(() => scheduleNoteAutoSave(0), 5000);
      return false;
    } finally {
      noteAutoSaveState.saving = false;
      const nextTitle = noteAutoSaveTitle();
      const nextContent = noteAutoSaveContent();
      if (noteAutoSaveActive() && (nextTitle !== noteAutoSaveState.title || nextContent !== noteAutoSaveState.content)) {
        scheduleNoteAutoSave(900);
      }
    }
  }
  function scheduleNoteAutoSave(delay) {
    if (!noteAutoSaveActive()) return;
    noteAutoSaveState.dirty = true;
    if (typeof refreshCurrentNotePreview === 'function') refreshCurrentNotePreview();
    clearTimeout(noteAutoSaveState.timer);
    setNoteAutoSaveStatus('有未保存更改', 'dirty');
    noteAutoSaveState.timer = setTimeout(flushNoteAutoSave, typeof delay === 'number' ? delay : 1200);
  }
  function checkNoteAutoSaveDirty() {
    if (!noteAutoSaveActive()) return;
    const id = noteAutoSaveId();
    if (noteAutoSaveState.noteId !== id) {
      resetNoteAutoSaveSnapshot(true);
      return;
    }
    const title = noteAutoSaveTitle();
    const content = noteAutoSaveContent();
    if (!noteAutoSaveState.dirty && (title !== noteAutoSaveState.title || content !== noteAutoSaveState.content)) {
      scheduleNoteAutoSave(1200);
    }
  }
  function bindNoteAutoSave() {
    const titleInput = q('noteTitleInput');
    if (titleInput && !titleInput.dataset.autosaveBound) {
      titleInput.dataset.autosaveBound = '1';
      titleInput.addEventListener('input', () => scheduleNoteAutoSave(800));
    }
    ensureNoteAutoSaveStatus();
  }
  setInterval(() => {
    bindNoteAutoSave();
    checkNoteAutoSaveDirty();
  }, 700);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) flushNoteAutoSave();
  });
  const oldOpenNoteByIdAutoSave = window.openNoteById;
  if (oldOpenNoteByIdAutoSave) {
    window.openNoteById = async function (...args) {
      await flushNoteAutoSave();
      const result = await oldOpenNoteByIdAutoSave.apply(this, args);
      setTimeout(() => {
        bindNoteAutoSave();
        resetNoteAutoSaveSnapshot(true);
      }, 80);
      return result;
    };
  }
  const oldEnterNoteEditModeAutoSave = window.enterNoteEditMode;
  if (oldEnterNoteEditModeAutoSave) {
    window.enterNoteEditMode = async function (...args) {
      const result = await oldEnterNoteEditModeAutoSave.apply(this, args);
      bindNoteAutoSave();
      resetNoteAutoSaveSnapshot(true);
      return result;
    };
  }
  const oldEnterNotePreviewModeAutoSave = window.enterNotePreviewMode;
  if (oldEnterNotePreviewModeAutoSave) {
    window.enterNotePreviewMode = async function (...args) {
      await flushNoteAutoSave();
      return oldEnterNotePreviewModeAutoSave.apply(this, args);
    };
  }
  const oldCloseCurrentNoteAutoSave = window.closeCurrentNote;
  if (oldCloseCurrentNoteAutoSave) {
    window.closeCurrentNote = async function (...args) {
      await flushNoteAutoSave();
      const result = oldCloseCurrentNoteAutoSave.apply(this, args);
      resetNoteAutoSaveSnapshot(true);
      return result;
    };
  }
  const oldDeleteCurrentNoteAutoSave = window.deleteCurrentNote;
  if (oldDeleteCurrentNoteAutoSave) {
    window.deleteCurrentNote = async function (...args) {
      clearTimeout(noteAutoSaveState.timer);
      return oldDeleteCurrentNoteAutoSave.apply(this, args);
    };
  }
  const oldSaveCurrentNoteAutoSave = window.saveCurrentNote;
  if (oldSaveCurrentNoteAutoSave) {
    window.saveCurrentNote = async function (...args) {
      clearTimeout(noteAutoSaveState.timer);
      const result = await oldSaveCurrentNoteAutoSave.apply(this, args);
      setTimeout(() => resetNoteAutoSaveSnapshot(true), 120);
      return result;
    };
  }

  function cacheSharedGame(data) {
    if (!data || !data.id) return null;
    window.gamesCache = Array.isArray(window.gamesCache) ? window.gamesCache : [];
    window.gameMap = window.gameMap || {};
    const idx = window.gamesCache.findIndex(item => String(item && item.id) === String(data.id));
    if (idx >= 0) window.gamesCache[idx] = data;
    else window.gamesCache.unshift(data);
    window.gameMap[data.id] = data;
    return data;
  }
  window.launchSharedGameById = async function (gameId, navMode) {
    if (!gameId) return toast('小程序不存在', true);
    try {
      const data = await apiGet('/api/share/data?type=game&id=' + encodeURIComponent(gameId));
      const game = cacheSharedGame(data);
      if (!game) return toast('小程序不存在', true);
      window.selectedGameId = game.id;
      const version = typeof latestGameVersion === 'function'
        ? latestGameVersion(game)
        : (((game.versions || []).slice().sort((a, b) => (+b.uploadTime || 0) - (+a.uploadTime || 0)))[0] || null);
      window.selectedGameVersionId = version && version.id || '';
      if (!window.selectedGameVersionId) return toast('这个小程序还没有可用版本', true);
      if (typeof window.startSelectedGame === 'function') await window.startSelectedGame(false, navMode === undefined ? false : navMode);
      else if (typeof startSelectedGame === 'function') await startSelectedGame(false, navMode === undefined ? false : navMode);
    } catch (e) {
      toast(e.message || '打开小程序失败', true);
    }
  };
  window.launchSharedGameRoute = async function (route, navMode) {
    const parts = String(route || '').split('/');
    const id = decodeURIComponent(parts[3] || parts[2] || '');
    if (!id) return toast('小程序链接不完整', true);
    if (typeof closeShareCardOverlay === 'function') closeShareCardOverlay();
    await window.launchSharedGameById(id, navMode === undefined ? false : navMode);
  };

  function renderShareData(data) {
    const root = q('shareRoot');
    if (data.type === 'note') {
      root.innerHTML = `<div class='x-note-hero'><div class='x-pill-row'><span class='x-badge'>笔记分享</span><span class='x-badge warn'>来自 ${esc(data.ownerNickname || '')}</span>${data.ownerIsDeveloper ? "<span class='developer-b'>开发者</span>" : ''}</div><h2>${esc(data.title || '未命名笔记')}</h2><div class='x-sub'>最近更新：${fmtDate(data.updatedAt)}</div><div class='x-toolbar'><button class='tb-btn' onclick="copyText(location.href,'链接已复制')">复制链接</button></div><div class='x-share-pre md'>${typeof renderMD === 'function' ? renderMD(data.content || '') : esc(data.content || '')}</div></div>`;
      return;
    }
    if (data.type === 'cloud') {
      const entry = data.entry || {};
      root.innerHTML = `<div class='hero'>${entry.contentType && String(entry.contentType).startsWith('image/') ? `<img class='x-share-cover' src='${esc(withFileName(entry.filePath, entry.name))}' alt=''>` : `<div class='x-share-cover' style='display:flex;align-items:center;justify-content:center;font-size:48px'>${fileIcon(entry)}</div>`}<div style='flex:1;min-width:0'><div class='x-pill-row'><span class='x-badge'>云盘分享</span><span class='x-badge warn'>来自 ${esc(data.ownerNickname || '')}</span>${data.ownerIsDeveloper ? "<span class='developer-b'>开发者</span>" : ''}</div><h2 style='margin:12px 0 8px'>${esc(entry.name || '未命名文件')}</h2><div class='x-sub'>类型：${esc(entry.contentType || entry.type || '文件')} · 大小：${fmtBytes(entry.size || 0)} · 更新时间：${fmtDate(entry.updatedAt)}</div><div class='x-toolbar' style='margin-top:14px'><button class='tb-btn' onclick="triggerBrowserDownload('${esc(buildDownloadUrl(withFileName(entry.filePath, entry.name || '文件')))}','${esc(entry.name || '文件')}')">下载</button><button class='tb-btn' onclick="saveCloudShareById('${esc(data.share && data.share.id || '')}')">保存到我的云盘</button><button class='tb-btn' onclick="copyText(location.href,'链接已复制')">复制链接</button></div></div></div>`;
      return;
    }
    if (data.type === 'music') {
      root.innerHTML = `<div class='hero'>${data.cover ? `<img class='x-share-cover' src='${esc(data.cover)}' alt=''>` : `<div class='x-share-cover' style='display:flex;align-items:center;justify-content:center;font-size:48px'>🎵</div>`}<div style='flex:1;min-width:0'><div class='x-pill-row'><span class='x-badge'>音乐分享</span></div><h2 style='margin:12px 0 8px'>${esc(data.title || '未命名歌曲')}</h2><div class='x-sub'>歌手：${esc(data.artist || '未知')} · 专辑：${esc(data.album || '未填写')} · 播放：${Number(data.playCount || 0).toLocaleString('zh-CN')}</div><div class='x-toolbar' style='margin-top:14px'><button class='tb-btn' onclick="openMusic('replace');playTrackById('${esc(data.id)}')">立即播放</button><button class='tb-btn' onclick="copyText(location.href,'链接已复制')">复制链接</button></div></div></div><div class='x-card'><audio controls style='width:100%' src='${esc(data.filePath || '')}'></audio></div>`;
      return;
    }
    if (data.type === 'video') {
      root.innerHTML = `<div class='hero'>${data.coverPath ? `<img class='x-share-cover' src='${esc(data.coverPath)}' alt=''>` : `<div class='x-share-cover' style='display:flex;align-items:center;justify-content:center;font-size:48px'>🎬</div>`}<div style='flex:1;min-width:0'><div class='x-pill-row'><span class='x-badge'>视频分享</span><span class='x-badge'>${esc(data.categoryName || '未分类')}</span></div><h2 style='margin:12px 0 8px'>${esc(data.title || '未命名视频')}</h2><div class='x-sub'>播放：${Number(data.playCount || 0).toLocaleString('zh-CN')} · 上传时间：${fmtDate(data.createdAt)}</div><div class='x-toolbar' style='margin-top:14px'><button class='tb-btn' onclick="openVideos('replace');playVideoById('${esc(data.id)}')">立即播放</button><button class='tb-btn' onclick="copyText(location.href,'链接已复制')">复制链接</button></div></div></div><div class='x-card'><video controls style='width:100%;border-radius:16px;background:#000' src='${esc(data.filePath || '')}'></video></div>`;
      return;
    }
    if (data.type === 'game') {
      root.innerHTML = `<div class='hero'>${data.coverPath ? `<img class='x-share-cover' src='${esc(data.coverPath)}' alt=''>` : `<div class='x-share-cover' style='display:flex;align-items:center;justify-content:center;font-size:48px'>◫</div>`}<div style='flex:1;min-width:0'><div class='x-pill-row'><span class='x-badge'>小程序分享</span><span class='x-badge'>${esc(data.category || '游戏')}</span><span class='x-badge'>热度 ${Number(data.heatDisplay || 0).toLocaleString('zh-CN')}</span></div><h2 style='margin:12px 0 8px'>${esc(data.title || '未命名小程序')}</h2><div class='x-sub'>开发者：${esc(data.developerNickname || '')}${data.developerIsDeveloper ? " <span class='developer-b'>开发者</span>" : ''}</div><div class='x-toolbar' style='margin-top:14px'><button class='tb-btn' onclick="launchSharedGameById('${esc(data.id)}',false)">打开小程序</button><button class='tb-btn' onclick="copyText(location.href,'链接已复制')">复制链接</button></div></div></div><div class='x-share-pre'>${esc(data.desc || '暂无简介')}</div>`;
      return;
    }
    root.innerHTML = "<div class='x-empty'>暂不支持该分享类型</div>";
  }

  function joinCloudPath(parentPath, name) {
    const parent = String(parentPath || '/').replace(/\/+$/g, '') || '';
    return (parent ? parent : '') + '/' + String(name || '').replace(/^\/+/g, '');
  }

  function updateUploadFloater() {
    const el = document.getElementById('uploadFloater');
    if (!el) return;
    const queue = (X.cloud.uploadQueue || []).slice();
    if (!queue.length) { el.classList.remove('open'); return; }
    el.classList.add('open');
    const total = queue.reduce((sum, item) => sum + Number(item.total || 0), 0);
    const loaded = queue.reduce((sum, item) => sum + Number(item.loaded || 0), 0);
    const pct = total > 0 ? Math.min(100, Math.round(loaded / total * 100)) : 0;
    el.querySelector('.uf-hdr span').textContent = `⬆️ 上传中 (${queue.length})`;
    el.querySelector('.uf-bar span').style.width = pct + '%';
    el.querySelector('.uf-body').innerHTML = queue.map(item => {
      const ip = item.total > 0 ? Math.min(100, Math.round(item.loaded / item.total * 100)) : 0;
      return `<div class='uf-item'><span class='uf-name'>${esc(item.name || '')}</span><div class='uf-prog'><span style='width:${ip}%'></span></div><span class='uf-pct'>${ip}%</span></div>`;
    }).join('');
  }
  function renderCloudUploadQueue() { return ''; } // replaced by floating panel
  function renderCloudTaskCards(list, emptyText) {
    const tasks = (list || []).slice();
    if (!tasks.length) return `<div class='x-empty'>${esc(emptyText || '暂无后台任务')}</div>`;
    return tasks.map(item => {
      const total = Number(item.totalBytes || 0);
      const done = Number(item.processedBytes || 0);
      const pct = total > 0 ? Math.min(100, Math.round(done / total * 100)) : cloudTaskStatusText(item.status) === '已完成' ? 100 : 0;
      return `<article class='x-card cloud-task-card'><div class='x-kv'><strong>${esc(item.title || item.type || '任务')}</strong><span class='x-badge'>${esc(cloudTaskStatusText(item.status))}</span></div><div class='x-sub cloud-task-detail'>${esc(item.detail || '')}</div><div class='x-bar'><span style='width:${pct}%'></span></div><div class='x-sub cloud-task-meta'>${pct}% · ${fmtBytes(done)} / ${fmtBytes(total)} · ${fmtSpeed(item.speedBytesPerSec || 0)}</div></article>`;
    }).join('');
  }

  function markCloudUploadQueue(name, patch) {
    const list = X.cloud.uploadQueue || [];
    const idx = list.findIndex(item => item.name === name && item.status !== '完成');
    if (idx >= 0) list[idx] = Object.assign({}, list[idx], patch || {});
    updateUploadFloater();
  }

  window.openCloud = function (navMode) {
    if (checkAndWarnFeatureBan('cloud')) return;
    if (poll) clearInterval(poll);
    prevView = 'cloudView';
    sw('cloudView', 'c-cloud');
    updateRoute('/cloud', navMode === undefined ? true : navMode);
    poll = setInterval(() => {
      const view = q('cloudView');
      if (!view || !view.classList.contains('active')) return;
      renderCloudSection();
    }, 4000);
    cloudRefresh();
  };
  function updateCloudSidebarQuota(info) {
    const box = q('cloudQuotaMini');
    if (!box || !info) return;
    const used = Number(info.usedBytes || 0);
    const total = Number(info.quotaBytes || 0);
    const unlimited = total === -1 || total === 0;
    const pct = unlimited ? 100 : Math.min(100, Math.round(used / Math.max(total, 1) * 100));
    box.innerHTML = `<div class='cloud-quota-mini-head'><span>存储空间</span><strong>${unlimited ? '不限' : pct + '%'}</strong></div><div class='cloud-quota-mini-bar'><span style='width:${pct}%'></span></div><div class='cloud-quota-mini-text'>${fmtBytes(used)} / ${unlimited ? '不限' : fmtBytes(total)}</div>`;
  }
  window.switchCloudSection = function (section) { X.cloud.section = section; cloudRefresh(); };
  window.cloudRefresh = async function () {
    ensureExtraDom();
    const root = q('cloudRoot');
    const sections = [
      { key: 'files', iconClass: 'cs-files', label: '我的文件' },
      { key: 'favorites', iconClass: 'cs-favorites', label: '收藏夹' },
      { key: 'safebox', iconClass: 'cs-safebox', label: '保险箱' },
      { key: 'recycle', iconClass: 'cs-recycle', label: '回收站' }
    ];
    const sidebarHtml = sections.map(s =>
      `<button class='cloud-sidebar-item ${X.cloud.section === s.key ? 'active' : ''}' onclick="switchCloudSection('${s.key}')"><span class='cs-icon ${s.iconClass}' aria-hidden='true'></span><span>${s.label}</span></button>`
    ).join('') +
      `<div class='cloud-sidebar-sep'></div>` +
      `<button class='cloud-sidebar-item ${X.cloud.section === 'shares' ? 'active' : ''}' onclick="switchCloudSection('shares')"><span class='cs-icon cs-shares' aria-hidden='true'></span><span>已分享链接</span></button>` +
      `<button class='cloud-sidebar-item ${X.cloud.section === 'downloads' ? 'active' : ''}' onclick="switchCloudSection('downloads')"><span class='cs-icon cs-downloads' aria-hidden='true'></span><span>下载历史</span></button>` +
      `<button class='cloud-sidebar-item ${X.cloud.section === 'tasks' ? 'active' : ''}' onclick="switchCloudSection('tasks')"><span class='cs-icon cs-tasks' aria-hidden='true'></span><span>上传与任务</span></button>`;
    root.innerHTML = `<div class='cloud-layout cloud-layout-v2'><aside class='cloud-sidebar cloud-sidebar-v2' aria-label='云盘分类'><div class='cloud-brand'><div class='cloud-brand-icon'>${featureIcon('cloud', '')}</div><div><div class='cloud-brand-title'>云盘</div><div class='cloud-brand-sub'>${esc(ME.nickname || 'User')}</div></div></div><div id='cloudQuotaMini' class='cloud-quota-mini'><div class='cloud-quota-mini-head'><span>存储空间</span><strong>--</strong></div><div class='cloud-quota-mini-bar'><span></span></div><div class='cloud-quota-mini-text'>加载中...</div></div><nav class='cloud-sidebar-nav cloud-sidebar-nav-v2'>${sidebarHtml}</nav></aside><main class='cloud-main cloud-main-v2' id='cloudMain'>${emptyState('emptyFiles', '选择左侧分类开始', 'cloud-empty')}</main></div>`;
    await renderCloudSection();
  };

  function cloudSortEntries(entries) {
    const list = (entries || []).slice();
    const key = X.cloud.sortBy || 'name';
    const asc = X.cloud.sortAsc !== false;
    list.sort((a, b) => {
      if (a.type === 'folder' && b.type !== 'folder') return -1;
      if (a.type !== 'folder' && b.type === 'folder') return 1;
      let va, vb;
      if (key === 'name') { va = (a.name || '').toLowerCase(); vb = (b.name || '').toLowerCase(); }
      else if (key === 'time') { va = a.updatedAt || 0; vb = b.updatedAt || 0; }
      else if (key === 'size') { va = a.size || 0; vb = b.size || 0; }
      else { va = (a.name || '').toLowerCase(); vb = (b.name || '').toLowerCase(); }
      if (va < vb) return asc ? -1 : 1;
      if (va > vb) return asc ? 1 : -1;
      return 0;
    });
    return list;
  }

  function cloudFilterEntries(entries) {
    const query = (X.cloud.searchQuery || '').trim().toLowerCase();
    if (!query) return entries || [];
    return (entries || []).filter(e => (e.name || '').toLowerCase().includes(query));
  }

  function cloudSortArrow(key) {
    if (X.cloud.sortBy !== key) return '';
    return X.cloud.sortAsc ? ' \u25B2' : ' \u25BC';
  }

  function cloudFileTableHtml(entries, showCheckbox) {
    if (!entries || !entries.length) return emptyState('emptyFiles', '\u8FD9\u91CC\u8FD8\u662F\u7A7A\u7684', 'cloud-empty');
    const sorted = cloudSortEntries(cloudFilterEntries(entries));
    if (!sorted.length) return `<div class='cloud-empty cloud-search-empty'><span class='cloud-empty-mark' aria-hidden='true'></span><span>\u6CA1\u6709\u5339\u914D\u7684\u6587\u4EF6</span></div>`;
    const selAll = sorted.length > 0 && sorted.every(e => X.cloud.selected.has(e.id));
    const thName = `<th class='${X.cloud.sortBy === 'name' ? 'sort-active' : ''}' onclick='X.cloud.sortBy="name";cloudRefresh()'>\u540D\u79F0<span class='sort-arrow'>${cloudSortArrow('name')}</span></th>`;
    const thTime = `<th class='${X.cloud.sortBy === 'time' ? 'sort-active' : ''}' onclick='X.cloud.sortBy="time";cloudRefresh()'>\u4FEE\u6539\u65F6\u95F4<span class='sort-arrow'>${cloudSortArrow('time')}</span></th>`;
    const thSize = `<th class='${X.cloud.sortBy === 'size' ? 'sort-active' : ''}' onclick='X.cloud.sortBy="size";cloudRefresh()'>\u5927\u5C0F<span class='sort-arrow'>${cloudSortArrow('size')}</span></th>`;
    const rows = sorted.map(entry => {
      const sel = X.cloud.selected.has(entry.id) ? 'checked' : '';
      const selCls = X.cloud.selected.has(entry.id) ? 'selected' : '';
      const dblClick = entry.type === 'folder'
        ? `ondblclick="openCloudPath('${esc(joinCloudPath(entry.parentPath, entry.name))}')"`
        : `ondblclick="previewCloudEntry('${esc(entry.id)}')"`;
      const icon = entry.type === 'folder' ? '\uD83D\uDCC1' : fileIcon(entry);
      const actions = entry.type === 'folder'
        ? `<button class='cloud-tb-btn' onclick="openCloudPath('${esc(joinCloudPath(entry.parentPath, entry.name))}')">\u8FDB\u5165</button>`
        : `<button class='cloud-tb-btn' onclick="previewCloudEntry('${esc(entry.id)}')">\u9884\u89C8</button>` +
          (/\.zip$/i.test(entry.name || '') ? `<button class='cloud-tb-btn' onclick="unzipCloudEntry('${esc(entry.id)}')">` + '\u89E3\u538B' + `</button>` : '') +
          `<button class='cloud-tb-btn' onclick="shareCloudEntry('${esc(entry.id)}')">` + '\u5206\u4EAB' + `</button>`;
      return `<tr class='cloud-file-row ${selCls}' ${dblClick}>` +
        (showCheckbox ? `<td><input type='checkbox' class='cloud-cb' data-id='${esc(entry.id)}' onclick='event.stopPropagation()' onchange='toggleCloudSelect(this)' ${sel}></td>` : '') +
        `<td><div class='cloud-file-name'><span class='cloud-file-icon'>${icon}</span><span class='cloud-file-label'>${esc(entry.name || '')}</span></div></td>` +
        `<td class='cloud-file-time'>${fmtDate(entry.updatedAt)}</td>` +
        `<td class='cloud-file-size'>${entry.type === 'folder' ? '-' : fmtBytes(entry.size || 0)}</td>` +
        `<td><div class='cloud-file-actions'>${actions}<button class='cloud-tb-btn' onclick="renameCloudEntryPrompt('${esc(entry.id)}','${esc(entry.name)}')">` + '\u91CD\u547D\u540D' + `</button><button class='cloud-tb-btn' onclick="deleteCloudEntry('${esc(entry.id)}')">` + '\u5220\u9664' + `</button></div></td></tr>`;
    }).join('');
    const cbHead = showCheckbox ? `<th class='cloud-select-column'><input type='checkbox' onclick='event.stopPropagation()' onchange='cloudToggleAll(this)' ${selAll ? 'checked' : ''}></th>` : '';
    return `<table class='cloud-file-table'><thead>${cbHead}${thName}${thTime}${thSize}<th class='cloud-actions-column'><span class='sr-only'>操作</span></th></thead><tbody>${rows}</tbody></table>`;
  }

  function cloudFileCardHtml(entries, showCheckbox) {
    if (!entries || !entries.length) return emptyState('emptyFiles', '\u8FD9\u91CC\u8FD8\u662F\u7A7A\u7684', 'cloud-empty');
    const sorted = cloudSortEntries(cloudFilterEntries(entries));
    if (!sorted.length) return `<div class='cloud-empty cloud-search-empty'><span class='cloud-empty-mark' aria-hidden='true'></span><span>&#x6CA1;&#x6709;&#x5339;&#x914D;&#x7684;&#x6587;&#x4EF6;</span></div>`;
    const cards = sorted.map(entry => {
      const sel = X.cloud.selected.has(entry.id) ? 'selected' : '';
      const icon = entry.type === 'folder' ? '&#x1F4C1;' : fileIcon(entry);
      const isImg = /\.(jpe?g|png|gif|webp|bmp|svg|ico)$/i.test(entry.name || '');
      let imgPreviewHtml;
      if (isImg && entry.filePath) {
        imgPreviewHtml = `<img class='cloud-card-preview-image' src='${withFileName(entry.filePath, entry.name)}' alt='${esc(entry.name)}' onerror="this.classList.add('hidden');this.nextElementSibling.classList.remove('hidden')"><span class='cloud-card-preview-fallback hidden'>${icon}</span>`;
      } else {
        imgPreviewHtml = `<span class='cloud-card-preview-fallback'>${icon}</span>`;
      }
      const clickAction = entry.type === 'folder'
        ? `onclick="openCloudPath('${esc(joinCloudPath(entry.parentPath, entry.name))}')"`
        : `onclick="previewCloudEntry('${esc(entry.id)}')"`;
      const dblClick = entry.type === 'folder'
        ? `ondblclick="openCloudPath('${esc(joinCloudPath(entry.parentPath, entry.name))}')"`
        : `ondblclick="previewCloudEntry('${esc(entry.id)}')"`;
      const actions = entry.type === 'folder'
        ? `<button class='cloud-tb-btn' onclick="event.stopPropagation();openCloudPath('${esc(joinCloudPath(entry.parentPath, entry.name))}')">&#x8FDB;&#x5165;</button>`
        : `<button class='cloud-tb-btn' onclick="event.stopPropagation();previewCloudEntry('${esc(entry.id)}')">&#x9884;&#x89C8;</button>` +
          (/\.zip$/i.test(entry.name || '') ? `<button class='cloud-tb-btn' onclick="event.stopPropagation();unzipCloudEntry('${esc(entry.id)}')">&#x89E3;&#x538B;</button>` : '') +
          `<button class='cloud-tb-btn' onclick="event.stopPropagation();shareCloudEntry('${esc(entry.id)}')">&#x5206;&#x4EAB;</button>`;
      return `<div class='cloud-card ${sel}' ${clickAction} ${dblClick}>` +
        (showCheckbox ? `<input type='checkbox' class='cloud-cb cloud-card-cb' data-id='${esc(entry.id)}' onclick='event.stopPropagation()' onchange='toggleCloudSelect(this)' ${X.cloud.selected.has(entry.id) ? 'checked' : ''}>` : '') +
        `<div class='cloud-card-preview'>${imgPreviewHtml}</div>` +
        `<div class='cloud-card-info'>` +
          `<div class='cloud-card-name' title='${esc(entry.name)}'>${esc(entry.name || '')}</div>` +
          `<div class='cloud-card-meta'>${fmtDate(entry.updatedAt)} &#xB7; ${entry.type === 'folder' ? '-' : fmtBytes(entry.size || 0)}</div>` +
        `</div>` +
        `<div class='cloud-card-actions'>${actions}` +
          `<button class='cloud-tb-btn' onclick="event.stopPropagation();renameCloudEntryPrompt('${esc(entry.id)}','${esc(entry.name || '')}')">&#x91CD;&#x547D;&#x540F;</button>` +
          `<button class='cloud-tb-btn cloud-danger-action' onclick="event.stopPropagation();deleteCloudEntry('${esc(entry.id)}')">&#x5220;&#x9664;</button>` +
        `</div>` +
      `</div>`;
    }).join('');
    return `<div class='cloud-card-grid'>${cards}</div>`;
  }

  window.cloudToggleAll = function(cb) {
    const entries = (X.cloud.info && X.cloud.info.entries) || [];
    const filtered = cloudFilterEntries(entries);
    const sorted = cloudSortEntries(filtered);
    if (cb.checked) { sorted.forEach(e => X.cloud.selected.add(e.id)); }
    else { sorted.forEach(e => X.cloud.selected.delete(e.id)); }
    renderCloudSection();
  };

  window.cloudSearchInput = function(val) {
    X.cloud.searchQuery = val;
    renderCloudSection();
  };

  window.cloudToggleSort = function(key) {
    if (X.cloud.sortBy === key) X.cloud.sortAsc = !X.cloud.sortAsc;
    else { X.cloud.sortBy = key; X.cloud.sortAsc = true; }
    cloudRefresh();
  };

  window.cloudToggleView = function() {
    X.cloud.view = X.cloud.view === 'list' ? 'grid' : 'list';
    cloudRefresh();
  };

  window.cloudBatchDownload = async function() {
    const ids = Array.from(X.cloud.selected || []);
    if (!ids.length) return toast('\u8BF7\u5148\u9009\u62E9\u6587\u4EF6', true);
    if (ids.length === 1) {
      const entry = ((X.cloud.info && X.cloud.info.entries) || []).find(e => e.id === ids[0]);
      if (entry && entry.type !== 'folder') {
        window.open(withFileName(entry.filePath || '', entry.name || ''), '_blank');
        X.cloud.selected.clear();
        cloudRefresh();
        return;
      }
    }
    const now = new Date();
    const ts = `${now.getFullYear()}${String(now.getMonth()+1).padStart(2,'0')}${String(now.getDate()).padStart(2,'0')}${String(now.getHours()).padStart(2,'0')}${String(now.getMinutes()).padStart(2,'0')}${String(now.getSeconds()).padStart(2,'0')}`;
    const zipName = `${ts}_${ids.length}.zip`;
    try {
      toast('\u6B63\u5728\u538B\u7F29...');
      await apiPost('/api/cloud/compress-batch', { entryIds: JSON.stringify(ids), zipName });
      X.cloud.selected.clear();
      toast('\u538B\u7F29\u4EFB\u52A1\u5DF2\u63D0\u4EA4\uFF0C\u5B8C\u6210\u540E\u81EA\u52A8\u4E0B\u8F7D');
      X.cloud.section = 'tasks';
      cloudRefresh();
    } catch (e) { toast(e.message || '\u6279\u91CF\u538B\u7F29\u5931\u8D25', true); }
  };

  window.cloudBatchFavorite = async function() {
    const ids = Array.from(X.cloud.selected || []);
    if (!ids.length) return toast('\u8BF7\u5148\u9009\u62E9\u6587\u4EF6', true);
    let done = 0, fail = 0;
    for (const id of ids) {
      try { await apiPost('/api/cloud/toggle-favorite', { entryId: id }); done++; }
      catch (e) { fail++; }
    }
    X.cloud.selected.clear();
    toast(`\u5DF2\u6536\u85CF ${done} \u9879` + (fail ? `\uFF0C${fail} \u9879\u5931\u8D25` : ''));
    cloudRefresh();
  };

  window.cloudBatchRename = async function() {
    const ids = Array.from(X.cloud.selected || []);
    if (!ids.length) return toast('\u8BF7\u5148\u9009\u62E9\u6587\u4EF6', true);
    const prefix = await window.showPrompt('\u8BF7\u8F93\u5165\u6279\u91CF\u91CD\u547D\u540D\u524D\u7F00\uFF08\u5C06\u52A0\u5728\u6587\u4EF6\u540D\u524D\uFF09\uFF1A', '');
    if (prefix === null || !prefix) return;
    let done = 0, fail = 0;
    const entries = (X.cloud.info && X.cloud.info.entries) || [];
    for (const id of ids) {
      const entry = entries.find(e => e.id === id);
      if (!entry) continue;
      try { await apiPost('/api/cloud/rename', { entryId: id, name: prefix + entry.name }); done++; }
      catch (e) { fail++; }
    }
    X.cloud.selected.clear();
    toast(`\u5DF2\u91CD\u547D\u540D ${done} \u9879` + (fail ? `\uFF0C${fail} \u9879\u5931\u8D25` : ''));
    cloudRefresh();
  };

  async function renderCloudSection(noFetch = false) {
    const main = q('cloudMain');
    try {
      if (X.cloud.section === 'files' || X.cloud.section === 'safebox' || X.cloud.section === 'favorites') {
        const [cloudInfo, tasks] = await Promise.all([
          apiGet(`/api/cloud/list?path=${encodeURIComponent(X.cloud.path || '/')}`),
          apiGet('/api/cloud/tasks')
        ]);
        X.cloud.info = cloudInfo;
        X.cloud.tasks = tasks || [];
        const info = X.cloud.info;
        if (!X.cloud.selected) X.cloud.selected = new Set();
        const pathParts = (info.path || '/').split('/').filter(Boolean);
        const breadcrumb = '<a class="cloud-bc-link cloud-bc-home" onclick="openCloudPath(\'/\')"><span class="cloud-home-mark" aria-hidden="true"></span><span>根目录</span></a>' + pathParts.map((p, i) => {
          const fullPath = '/' + pathParts.slice(0, i + 1).join('/');
          return `<span class='cloud-bc-sep'>/</span><a class='cloud-bc-link' onclick='openCloudPath(${JSON.stringify(fullPath)})'>${esc(p)}</a>`;
        }).join('');
        const quotaUsed = info.usedBytes || 0;
        const quotaTotal = info.quotaBytes || 0;
        const quotaPct = quotaTotal > 0 && quotaTotal !== -1 ? Math.min(100, (quotaUsed / quotaTotal) * 100) : 0;
        updateCloudSidebarQuota(info);
        const selCount = X.cloud.selected.size;
        let entries = info.entries || [];
        if (X.cloud.section === 'favorites') {
          entries = entries.filter(e => e.favorite);
        }
        if (X.cloud.section === 'safebox') {
          entries = entries.filter(e => e.safebox);
        }
        const batchBar = selCount > 0 ? `<div class='cloud-batch-bar'><span class='cloud-batch-count'>已选择 ${selCount} 项</span><div class='cloud-batch-actions'><button class='cloud-tb-btn' onclick='cloudBatchDownload()'>下载</button><button class='cloud-tb-btn' onclick='batchCloudMove()'>移动</button><button class='cloud-tb-btn' onclick='batchCloudCopy()'>复制</button><button class='cloud-tb-btn' onclick='cloudBatchFavorite()'>收藏</button><button class='cloud-tb-btn' onclick='cloudBatchRename()'>批量重命名</button><button class='cloud-tb-btn cloud-danger-action' onclick='batchCloudDelete()'>删除</button><button class='cloud-tb-btn' onclick='X.cloud.selected.clear();cloudRefresh()'>取消选择</button></div></div>` : '';
        const toolbarLeft = selCount > 0 ? '' : `<button class='cloud-tb-btn' onclick='goCloudParent()'>&#x2190; \u8FD4\u56DE\u4E0A\u7EA7</button><button class='cloud-tb-btn cloud-primary-action' onclick="q('cloudFileInput').click()">\u4E0A\u4F20\u6587\u4EF6</button><button class='cloud-tb-btn' onclick="q('cloudFolderInput').click()">\u4E0A\u4F20\u6587\u4EF6\u5939</button><button class='cloud-tb-btn' onclick='createCloudFolderPrompt()'>\u65B0\u5EFA\u6587\u4EF6\u5939</button><button class='cloud-tb-btn' onclick='createCloudFilePrompt()'>\u65B0\u5EFA\u6587\u4EF6</button>`;
        const searchVal = esc(X.cloud.searchQuery || '');
        const toolbarRight = `<div class='cloud-search-box'><input type='search' aria-label='\u641C\u7D22\u4E91\u76D8\u6587\u4EF6' placeholder='\u641C\u7D22\u4E91\u76D8\u6587\u4EF6' value='${searchVal}' oninput='cloudSearchInput(this.value)'>${X.cloud.searchQuery ? `<button class='cs-clear' onclick='X.cloud.searchQuery="";cloudRefresh()' aria-label='\u6E05\u7A7A\u641C\u7D22'>\u2715</button>` : ''}</div><button class='cloud-tb-btn' onclick='cloudRefresh()'>\u5237\u65B0</button><button class='cloud-tb-btn ${X.cloud.view === 'grid' ? 'active' : ''}' onclick='cloudToggleView()'>${X.cloud.view === 'grid' ? '\u5217\u8868\u89C6\u56FE' : '\u7F51\u683C\u89C6\u56FE'}</button>`;
        main.innerHTML =
          `<div class='cloud-breadcrumb-bar'>${breadcrumb}</div>` +
          `<div class='cloud-toolbar'><div class='cloud-toolbar-left'>${toolbarLeft}</div><div class='cloud-toolbar-right'>${toolbarRight}</div></div>` +
          batchBar +
          `<div class='cloud-drop-zone' id='cloudDropZone'><span class='cloud-drop-mark' aria-hidden='true'></span><span>\u62D6\u62FD\u6587\u4EF6\u5230\u6B64\u5904\u5373\u53EF\u4E0A\u4F20\u5230\u5F53\u524D\u76EE\u5F55</span></div>` +
          `${selCount > 0 ? '' : '<div class=\'cloud-file-list\'>' + (X.cloud.view === 'grid' ? cloudFileCardHtml(entries, true) : cloudFileTableHtml(entries, true)) + '</div>'}` +
          `<div class='cloud-status-bar'><span>\u4E91\u76D8\u7A7A\u95F4\uFF1A${fmtQuota(quotaUsed, quotaTotal)}</span>${quotaTotal > 0 && quotaTotal !== -1 ? `<div class='cloud-quota-bar'><span style='width:${quotaPct.toFixed(1)}%'></span></div>` : ''}<span class='cloud-status-spacer'></span><span>\u5220\u9664\u7B56\u7565\uFF1A${cloudPolicyName(info.deletePolicy)}</span></div>`;
        bindCloudDrop();
      } else if (X.cloud.section === 'recycle') {
        X.cloud.recycle = await apiGet('/api/cloud/recycle');
        const entries = X.cloud.recycle || [];
        if (!entries.length) {
          main.innerHTML = `<div class='cloud-toolbar'></div>${emptyState('emptyFiles', '\u56DE\u6536\u7AD9\u4E3A\u7A7A', 'cloud-empty')}`;
        } else {
          const rows = entries.map(entry =>
            `<tr class='cloud-file-row'><td><div class='cloud-file-name'><span class='cloud-file-icon'>${fileIcon(entry)}</span><span class='cloud-file-label'>${esc(entry.name)}</span></div></td><td class='cloud-file-time'>${fmtDate(entry.deletedAt)}</td><td class='cloud-file-size'>` + '\u5269\u4F59' + ` ${Number(entry.daysLeft || 0).toLocaleString('zh-CN')} ` + '\u5929' + `</td><td><div class='cloud-file-actions cloud-file-actions-visible'><button class='cloud-tb-btn' onclick="restoreCloudEntry('${esc(entry.id)}')">` + '\u6062\u590D' + `</button><button class='cloud-tb-btn cloud-danger-action' onclick="purgeCloudEntry('${esc(entry.id)}')">` + '\u6C38\u4E45\u5220\u9664' + `</button></div></td></tr>`
          ).join('');
          main.innerHTML =
            `<div class='cloud-toolbar'><div class='cloud-toolbar-left'></div></div>` +
            `<div class='cloud-file-list'><table class='cloud-file-table'><thead><th>` + '\u540D\u79F0' + `</th><th>` + '\u5220\u9664\u65F6\u95F4' + `</th><th>` + '\u5269\u4F59' + `</th><th class='cloud-actions-column'><span class='sr-only'>` + '\u64CD\u4F5C' + `</span></th></thead><tbody>${rows}</tbody></table></div>`;
        }
      } else if (X.cloud.section === 'shares') {
        X.cloud.shares = await apiGet('/api/cloud/shares');
        const items = X.cloud.shares || [];
        if (!items.length) {
          main.innerHTML = `<div class='cloud-toolbar'></div>${emptyState('emptyFiles', '\u8FD8\u6CA1\u6709\u5206\u4EAB\u94FE\u63A5', 'cloud-empty')}`;
        } else {
          const rows = items.map(item =>
            `<tr class='cloud-file-row'><td><div class='cloud-file-name'><span class='cloud-file-icon cloud-share-mark' aria-hidden='true'></span><span class='cloud-file-label'>${esc(item.title || item.id)}</span></div></td><td class='cloud-file-time'>${fmtDate(item.updatedAt)}</td><td class='cloud-file-size'>` + '\u8BBF\u95EE' + ` ${Number(item.visitCount || 0).toLocaleString('zh-CN')} ` + '\u6B21' + `</td><td><div class='cloud-file-actions cloud-file-actions-visible'><button class='cloud-tb-btn' onclick="copyText(location.origin + '${esc(item.url)}','` + '\u5206\u4EAB\u94FE\u63A5\u5DF2\u590D\u5236' + `')">` + '\u590D\u5236\u94FE\u63A5' + `</button><button class='cloud-tb-btn' onclick="openShareRoute('${esc(item.url)}')">` + '\u6253\u5F00' + `</button></div></td></tr>`
          ).join('');
          main.innerHTML =
            `<div class='cloud-toolbar'><div class='cloud-toolbar-left'></div></div>` +
            `<div class='cloud-file-list'><table class='cloud-file-table'><thead><th>` + '\u540D\u79F0' + `</th><th>` + '\u66F4\u65B0\u65F6\u95F4' + `</th><th>` + '\u8BBF\u95EE' + `</th><th class='cloud-actions-column'><span class='sr-only'>` + '\u64CD\u4F5C' + `</span></th></thead><tbody>${rows}</tbody></table></div>`;
        }
      } else if (X.cloud.section === 'downloads') {
        X.cloud.downloads = await apiGet('/api/cloud/downloads');
        const items = X.cloud.downloads || [];
        if (!items.length) {
          main.innerHTML = `<div class='cloud-toolbar'></div>${emptyState('emptyFiles', '\u6682\u65F6\u6CA1\u6709\u4E0B\u8F7D\u5386\u53F2', 'cloud-empty')}`;
        } else {
          const rows = items.map(item =>
            `<tr class='cloud-file-row'><td><div class='cloud-file-name'><span class='cloud-file-icon cloud-download-mark' aria-hidden='true'></span><span class='cloud-file-label'>${esc(item.fileName || '\u6587\u4EF6')}</span></div></td><td class='cloud-file-time'>${fmtDate(item.downloadedAt)}</td><td class='cloud-file-size'>${fmtBytes(item.size || 0)}</td><td></td></tr>`
          ).join('');
          main.innerHTML =
            `<div class='cloud-toolbar'><div class='cloud-toolbar-left'><button class='cloud-tb-btn' onclick='clearCloudDownloads()'>` + '\u6E05\u7A7A\u4E0B\u8F7D\u5386\u53F2' + `</button></div></div>` +
            `<div class='cloud-file-list'><table class='cloud-file-table'><thead><th>` + '\u6587\u4EF6\u540D' + `</th><th>` + '\u4E0B\u8F7D\u65F6\u95F4' + `</th><th>` + '\u5927\u5C0F' + `</th><th class='cloud-actions-column'></th></thead><tbody>${rows}</tbody></table></div>`;
        }
      } else {
        X.cloud.tasks = await apiGet('/api/cloud/tasks');
        main.innerHTML = `<div class='cloud-toolbar cloud-section-toolbar'><strong>上传与后台任务</strong></div><div class='cloud-task-list'>${renderCloudTaskCards(X.cloud.tasks, '\u6682\u65E0\u540E\u53F0\u4EFB\u52A1')}</div>`;
      }
    } catch (e) {
      main.innerHTML = `<div class='cloud-empty ui-error-state'>${esc(e.message || '\u52A0\u8F7D\u5931\u8D25')}</div>`;
    }
  }

  function bindCloudDrop() {
    const zone = q('cloudDropZone');
    if (!zone) return;
    ['dragenter', 'dragover'].forEach(name => zone.addEventListener(name, ev => { ev.preventDefault(); zone.classList.add('drag'); }));
    ['dragleave', 'drop'].forEach(name => zone.addEventListener(name, ev => { ev.preventDefault(); zone.classList.remove('drag'); }));
    zone.addEventListener('drop', ev => uploadCloudFiles(Array.from((ev.dataTransfer && ev.dataTransfer.files) || [])));
  }

  window.goCloudParent = function () { const path = String(X.cloud.path || '/'); if (path === '/' || !path) return; const parts = path.split('/').filter(Boolean); parts.pop(); X.cloud.path = '/' + parts.join('/'); if (X.cloud.path === '') X.cloud.path = '/'; cloudRefresh(); };
  window.openCloudPath = function (path) { X.cloud.path = path || '/'; X.cloud.section = 'files'; cloudRefresh(); };
  window.previewCloudEntry = async function (entryId) {
    const entry = (X.cloud.info && X.cloud.info.entries || []).find(item => item.id === entryId);
    if (!entry) return;
    if (/\.zip$/i.test(entry.name || '')) {
      try {
        const tree = await apiGet(`/api/cloud/zip-tree?entryId=${encodeURIComponent(entryId)}`);
        const text = (tree || []).map(item => `${item.directory ? '📁' : '📄'} ${item.path}${item.directory ? '' : ` (${fmtBytes(item.size || 0)})`}`).join('\n') || '压缩包为空';
        const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
        openPrev('code', URL.createObjectURL(blob), `${entry.name || '压缩包'}.tree.txt`);
      } catch (e) {
        toast(e.message || '预览失败', true);
      }
      return;
    }
    openPrev(previewType(entry), withFileName(entry.filePath || '', entry.name || '文件'), entry.name || '文件');
  };
  window.renameCloudEntryPrompt = async function (entryId, oldName) { const name = await window.showPrompt('请输入新的名称：', oldName || ''); if (name === null || !name.trim()) return; try { await apiPost('/api/cloud/rename', { entryId, name: name.trim() }); toast('已重命名'); cloudRefresh(); } catch (e) { toast(e.message || '重命名失败', true); } };
  window.deleteCloudEntry = async function (entryId) { if (!(await window.showConfirm('确定删除这个文件或文件夹？'))) return; try { await apiPost('/api/cloud/delete', { entryId }); toast('已移入回收站'); cloudRefresh(); } catch (e) { toast(e.message || '删除失败', true); } };
  window.restoreCloudEntry = async function (entryId) { try { await apiPost('/api/cloud/restore', { entryId }); toast('已恢复'); cloudRefresh(); } catch (e) { toast(e.message || '恢复失败', true); } };
  window.purgeCloudEntry = async function (entryId) { if (!(await window.showConfirm('确定永久删除？'))) return; try { await apiPost('/api/cloud/purge', { entryId }); toast('已永久删除'); cloudRefresh(); } catch (e) { toast(e.message || '删除失败', true); } };
  window.createCloudFolderPrompt = async function () { const name = await window.showPrompt('请输入文件夹名称：', '新建文件夹'); if (name === null || !name.trim()) return; try { await apiPost('/api/cloud/create-folder', { parentPath: X.cloud.path, name: name.trim() }); toast('文件夹已创建'); cloudRefresh(); } catch (e) { toast(e.message || '创建失败', true); } };
  window.createCloudFilePrompt = async function () { const name = await window.showPrompt('请输入文件名：', '未命名文件.md'); if (name === null || !name.trim()) return; try { await apiPost('/api/cloud/create-file', { parentPath: X.cloud.path, name: name.trim(), content: `# ${name.trim()}\n\n` }); toast('文件已创建'); cloudRefresh(); } catch (e) { toast(e.message || '创建失败', true); } };
  window.shareCloudEntry = async function (entryId) {
    try {
      const share = await apiPost('/api/cloud/share', { entryId });
      await copyText(location.origin + share.url, '分享链接已复制');
      try { await maybeSendShareCard('cloud', entryId, '文件分享卡片已发送'); } catch (e) { toast(e.message || '发送卡片失败', true); }
      cloudRefresh();
    } catch (e) {
      toast(e.message || '分享失败', true);
    }
  };
  window.saveCloudShareById = async function (shareId) { try { await apiPost('/api/cloud/save-share', { shareId, parentPath: '/' }); toast('已保存到我的云盘'); } catch (e) { toast(e.message || '保存失败', true); } };
  window.clearCloudDownloads = async function () { try { await apiPost('/api/cloud/clear-downloads', {}); toast('下载历史已清空'); cloudRefresh(); } catch (e) { toast(e.message || '清空失败', true); } };
  window.unzipCloudEntry = async function (entryId) { try { await apiPost('/api/cloud/unzip', { entryId }); X.cloud.section = 'tasks'; toast('解压任务已提交'); cloudRefresh(); } catch (e) { toast(e.message || '解压失败', true); } };
  window.compressCloudEntry = async function (entryId) { try { await apiPost('/api/cloud/compress', { entryId }); X.cloud.section = 'tasks'; toast('压缩任务已提交'); cloudRefresh(); } catch (e) { toast(e.message || '压缩失败', true); } };

  async function uploadCloudFiles(files) {
    if (!files || !files.length) return;
    try {
      for (const file of files) {
        const started = Date.now();
        X.cloud.uploadQueue.push({ name: file.name, total: file.size || 0, loaded: 0, speed: 0, status: '上传中' });
        const uploaded = await storeLooseFile(file, ev => {
          const elapsed = Math.max((Date.now() - started) / 1000, 0.1);
          markCloudUploadQueue(file.name, {
            total: Number(ev.total || file.size || 0),
            loaded: Number(ev.loaded || 0),
            speed: Number(ev.loaded || 0) / elapsed,
            status: '上传到临时存储'
          });
          if (X.cloud.section === 'tasks' || X.cloud.section === 'files' || X.cloud.section === 'safebox' || X.cloud.section === 'favorites') renderCloudSection();
        });
        await apiPost('/api/cloud/import-stored', {
          filePath: uploaded.filePath,
          fileName: uploaded.fileName || file.name,
          parentPath: X.cloud.path || '/'
        });
        markCloudUploadQueue(file.name, { loaded: file.size || 0, total: file.size || 0, status: '完成' });
      }
      toast('上传完成');
      X.cloud.section = 'files';
      X.cloud.uploadQueue = (X.cloud.uploadQueue || []).filter(item => item.status !== '完成');
      cloudRefresh();
    } catch (e) {
      toast(e.message || '上传失败', true);
    }
  }

  window.openFeedback = function (navMode) {
    if (poll) clearInterval(poll);
    prevView = 'feedbackView';
    sw('feedbackView', '');
    updateRoute('/feedback', navMode === undefined ? true : navMode);
    feedbackRefresh();
  };
  function renderFeedbackItem(item, isAdmin) {
    const status = String(item.status || '等待审核');
    const statusClass = status === '已处理' ? 'resolved' : status === '正在处理' ? 'working' : 'pending';
    return `<article class='feedback-ticket'><div class='feedback-ticket-icon'>${featureIcon('feedback', '')}</div><div class='x-meta'><div class='x-title'>${esc(item.title || '未命名反馈')}</div><div class='x-sub'>提交人：${esc(item.userNickname || item.userId || '')} · ${fmtDate(item.createdAt)}</div><div class='feedback-ticket-content'>${esc(item.content || '')}</div></div><div class='x-actions feedback-ticket-actions'><span class='feedback-status ${statusClass}'>${esc(status)}</span>${isAdmin ? `<select class='x-select feedback-status-select' onchange="updateFeedbackStatus('${esc(item.id)}',this.value)"><option value='等待审核' ${status === '等待审核' ? 'selected' : ''}>等待审核</option><option value='正在处理' ${status === '正在处理' ? 'selected' : ''}>正在处理</option><option value='已处理' ${status === '已处理' ? 'selected' : ''}>已处理</option></select>` : ''}</div></article>`;
  }
  window.feedbackRefresh = async function () {
    const root = q('feedbackRoot');
    root.innerHTML = "<div class='x-card'>加载中...</div>";
    try {
      X.feedback.items = await apiGet('/api/feedback/list');
      const isAdmin = !!ME.isSuperAdmin;
      root.innerHTML = `<div class='x-shell feedback-shell'><aside class='x-nav feedback-guide'><div class='x-nav-title'>反馈说明</div><p class='x-sub'>普通用户可以提交反馈并查看自己的处理进度，服主和超级管理员可以查看全部反馈并修改状态。</p><div class='feedback-status-legend'><div><span class='feedback-status-dot pending'></span><strong>等待审核</strong><small>尚未开始处理</small></div><div><span class='feedback-status-dot working'></span><strong>正在处理</strong><small>已经进入排查或修复</small></div><div><span class='feedback-status-dot resolved'></span><strong>已处理</strong><small>已经完成或确认处理完毕</small></div></div></aside><main class='x-main feedback-main'><div class='x-card feedback-compose-card'><div class='x-section-title'>提交反馈</div><div class='x-form'><label for='feedbackTitleInput'>标题</label><input id='feedbackTitleInput' class='x-input' placeholder='简要描述问题'><label for='feedbackContentInput'>详细内容</label><textarea id='feedbackContentInput' class='x-textarea' placeholder='请写明发生位置、操作步骤与预期结果'></textarea><button class='tb-btn feedback-submit' onclick='submitFeedbackTicket()'>提交反馈</button></div></div><section class='x-card feedback-list-card'><div class='x-section-title'>${isAdmin ? '全部反馈' : '我的反馈'}</div><div class='x-list feedback-list'>${X.feedback.items.length ? X.feedback.items.map(item => renderFeedbackItem(item, isAdmin)).join('') : "<div class='x-empty feedback-empty'>还没有反馈记录</div>"}</div></section></main></div>`;
    } catch (e) {
      root.innerHTML = `<div class='x-empty ui-error-state'>${esc(e.message || '加载失败')}</div>`;
    }
  };
  window.submitFeedbackTicket = async function () { const title = q('feedbackTitleInput').value.trim(), content = q('feedbackContentInput').value.trim(); if (!content) return toast('请填写反馈内容', true); try { await apiPost('/api/feedback/create', { title: title || '未命名反馈', content }); toast('反馈已提交'); q('feedbackTitleInput').value = ''; q('feedbackContentInput').value = ''; feedbackRefresh(); } catch (e) { toast(e.message || '提交失败', true); } };
  window.updateFeedbackStatus = async function (ticketId, status) { try { await apiPost('/api/feedback/status', { ticketId, status }); toast('状态已更新'); feedbackRefresh(); } catch (e) { toast(e.message || '更新失败', true); } };

  window.shareCurrentNote = async function () {
    if (!currentNoteId) return toast('请先打开笔记', true);
    try {
      const share = await apiPost('/api/note/share', { noteId: currentNoteId });
      const noteLink = `${location.origin}/note/${encodeURIComponent(currentNoteId)}`;
      await copyText(noteLink, '笔记链接已复制');
      try { await maybeSendShareCard('note', currentNoteId, '笔记分享卡片已发送'); } catch (e) { toast(e.message || '发送卡片失败', true); }
    } catch (e) {
      toast(e.message || '分享失败', true);
    }
  };

  function parseMusicLyrics(text) {
    const rows = String(text || '').replace(/^\uFEFF/, '').split(/\r?\n/);
    const timed = [];
    const plain = [];
    rows.forEach(raw => {
      const line = String(raw || '').trim();
      if (!line) return;
      const tags = Array.from(line.matchAll(/\[(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?\]/g));
      if (tags.length) {
        const content = line.replace(/\[(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?\]/g, '').trim();
        tags.forEach(tag => {
          const sec = Number(tag[1]) * 60 + Number(tag[2]) + Number((tag[3] || '0').padEnd(3, '0')) / 1000;
          timed.push({ time: sec, text: content || '...' });
        });
      } else {
        plain.push({ time: null, text: line });
      }
    });
    if (timed.length) return { timed: true, lines: timed.sort((a, b) => a.time - b.time) };
    return { timed: false, lines: plain };
  }
  function parseLrc(text) {
    const parsed = parseMusicLyrics(text);
    return parsed.timed ? parsed.lines : [];
  }
  function getFavoritePlaylist() {
    return (X.music.playlists || []).find(item => item.favorite);
  }
  function musicTrackInFavorite(trackId) {
    const favorite = getFavoritePlaylist();
    return !!(favorite && Array.isArray(favorite.trackIds) && favorite.trackIds.includes(trackId));
  }
  function buildMusicList() {
    let list = (X.music.tracks || []).slice();
    if (X.music.currentPlaylistId === DAILY_MUSIC_PLAYLIST_ID) {
      const ids = new Set((X.music.recommend || []).map(item => item.id));
      list = (X.music.recommend || []).filter(item => ids.has(item.id));
    } else if (X.music.currentPlaylistId) {
      const playlist = (X.music.playlists || []).find(item => item.id === X.music.currentPlaylistId);
      const ids = new Set((playlist && playlist.trackIds) || []);
      list = list.filter(item => ids.has(item.id));
    }
    const keyword = String(X.music.search || '').trim().toLowerCase();
    if (keyword) {
      list = list.filter(track => `${track.title || ''} ${track.artist || ''} ${track.album || ''}`.toLowerCase().includes(keyword));
    }
    X.music.currentList = list.map(item => item.id);
    return list;
  }
  function isDailyMusicPlaylist() {
    return X.music.currentPlaylistId === DAILY_MUSIC_PLAYLIST_ID;
  }
  function currentMusicSectionTitle() {
    if (isDailyMusicPlaylist()) return '每日推荐';
    if (X.music.currentPlaylistId) {
      const playlist = (X.music.playlists || []).find(item => item.id === X.music.currentPlaylistId);
      return playlist && playlist.name ? playlist.name : '歌单';
    }
    return '全部曲目';
  }
  function musicModeText(mode) {
    return mode === 'repeat-one' ? '单曲循环' : mode === 'shuffle' ? '随机播放' : '顺序播放';
  }
  function currentMusicIndex() {
    return Math.max(0, (X.music.currentList || []).indexOf(X.music.currentTrackId));
  }
  function pickNextMusicTrackId(step, autoAdvance) {
    const list = (X.music.currentList || []).slice();
    if (!list.length) return '';
    const currentId = X.music.currentTrackId && list.includes(X.music.currentTrackId) ? X.music.currentTrackId : list[0];
    const currentIndex = Math.max(0, list.indexOf(currentId));
    if (autoAdvance && X.music.playMode === 'repeat-one') return currentId;
    if (X.music.playMode === 'shuffle' && list.length > 1) {
      const candidates = list.filter(id => id !== currentId);
      return candidates[Math.floor(Math.random() * candidates.length)] || currentId;
    }
    return list[(currentIndex + step + list.length) % list.length] || currentId;
  }
  function renderMusicPlaylists() {
    const fav = getFavoritePlaylist();
    const normal = (X.music.playlists || []).filter(p => !p.favorite);
    let html = '';
    if (fav) html += `<button class='music-sidebar-item ${X.music.currentPlaylistId === fav.id ? 'active' : ''}' onclick="filterPlaylistTracks('${esc(fav.id)}')"><span class='msi-icon msi-favorite' aria-hidden='true'></span><span class='msi-name'>${esc(fav.name)}</span><span class='msi-count'>${(fav.trackIds || []).length}</span></button>`;
    normal.forEach(pl => {
      html += `<button class='music-sidebar-item ${X.music.currentPlaylistId === pl.id ? 'active' : ''}' onclick="filterPlaylistTracks('${esc(pl.id)}')"><span class='msi-icon msi-playlist' aria-hidden='true'></span><span class='msi-name'>${esc(pl.name)}</span><span class='msi-count'>${(pl.trackIds || []).length}</span></button>`;
    });
    return html;
  }
  function renderMusicSidebar() {
    const fav = getFavoritePlaylist();
    const totalTracks = (X.music.tracks || []).length;
    const favCount = fav ? (fav.trackIds || []).length : 0;
    const dailyCount = (X.music.recommend || []).length;
    return `<div class='music-sidebar-head'><div class='music-sidebar-title'>我的音乐</div><button class='music-sidebar-create' onclick='createPlaylistPrompt(false)'>创建歌单</button></div><div class='music-sidebar-tags'><button class='music-sidebar-tag ${X.music.sidebarFilter === "all" ? "active" : ""}' onclick='musicSidebarFilter("all")'>全部</button><button class='music-sidebar-tag ${X.music.sidebarFilter === "fav" ? "active" : ""}' onclick='musicSidebarFilter("fav")'>喜爱</button><button class='music-sidebar-tag ${X.music.sidebarFilter === "pl" ? "active" : ""}' onclick='musicSidebarFilter("pl")'>歌单</button></div><div class='music-sidebar-sep'></div><div class='music-sidebar-list'><button class='music-sidebar-item ${isDailyMusicPlaylist() ? "active" : ""}' onclick='openDailyRecommendations(true)'><span class='msi-icon msi-daily' aria-hidden='true'></span><span class='msi-name'>每日推荐</span><span class='msi-count'>${dailyCount}</span></button><button class='music-sidebar-item ${!X.music.currentPlaylistId && X.music.sidebarFilter === "all" ? "active" : ""}' onclick='showAllMusicTracks()'><span class='msi-icon msi-library'>${featureIcon('music', '')}</span><span class='msi-name'>全部歌曲</span><span class='msi-count'>${totalTracks}</span></button>${ME.isSuperAdmin ? "<button class='music-sidebar-item' onclick='uploadMusicPrompt()'><span class='msi-icon msi-upload' aria-hidden='true'></span><span class='msi-name'>上传音乐</span></button>" : ''}${X.music.sidebarFilter === 'fav' && fav ? `<button class='music-sidebar-item active'><span class='msi-icon msi-favorite' aria-hidden='true'></span><span class='msi-name'>${esc(fav.name)}</span><span class='msi-count'>${favCount}</span></button>` : ''}${X.music.sidebarFilter === 'pl' || X.music.sidebarFilter === 'all' ? renderMusicPlaylists() : ''}</div>`;
  }
  function renderRecommendCards(list) {
    if (!list || !list.length) return "<div class='x-empty small'>今天还没有可推荐的歌曲</div>";
    return list.map(track => `<article class='music-recommend-card' onclick="playDailyRecommendationTrack('${esc(track.id)}')"><div class='mrc-cover'>${track.cover ? `<img src='${esc(track.cover)}' alt=''>` : featureIcon('music', '音乐')}</div><button class='mrc-play' onclick="event.stopPropagation();playDailyRecommendationTrack('${esc(track.id)}')" aria-label='播放'>▶</button><div class='mrc-title'>${esc(track.title || '未命名歌曲')}</div><div class='mrc-artist'>${esc(track.artist || '未知歌手')}</div><button class='mrc-share' onclick="event.stopPropagation();shareMusicTrack('${esc(track.id)}')" title='分享'>分享</button></article>`).join('');
  }
  window.openDailyRecommendations = function (playFirst) {
    X.music.currentPlaylistId = DAILY_MUSIC_PLAYLIST_ID;
    X.music.sidebarFilter = 'all';
    const list = buildMusicList();
    renderMusicSidebarOnly();
    renderCurrentMusicList();
    const title = q('musicTrackSectionTitle');
    if (title) title.textContent = currentMusicSectionTitle();
    if (playFirst && list.length) playTrackById(list[0].id);
  };
  window.playDailyRecommendationTrack = function (trackId) {
    X.music.currentPlaylistId = DAILY_MUSIC_PLAYLIST_ID;
    buildMusicList();
    renderMusicSidebarOnly();
    renderCurrentMusicList();
    playTrackById(trackId);
  };
  function renderMusicSidebarOnly() {
    const side = q('musicSidebar');
    if (side) side.innerHTML = renderMusicSidebar();
  }

  window.openMusic = function (navMode) {
    if (checkAndWarnFeatureBan('music')) return;
    if (poll) clearInterval(poll);
    prevView = 'musicView';
    sw('musicView', 'c-music');
    updateRoute('/music', navMode === undefined ? true : navMode);
    musicRefresh();
  };
  window.musicRefresh = async function () {
    const root = q('musicRoot');
    root.innerHTML = "<div class='music-dark music-state-panel'>加载中...</div>";
    try {
      const [tracks, playlists, recommend] = await Promise.all([apiGet('/api/music/tracks'), apiGet('/api/music/playlists'), apiGet('/api/music/recommend')]);
      X.music.tracks = tracks || [];
      X.music.playlists = playlists || [];
      X.music.recommend = recommend || [];
      const list = buildMusicList();
      root.innerHTML = `<div class='music-dark'><div class='music-layout'><aside class='music-sidebar' id='musicSidebar' aria-label='音乐分类'>${renderMusicSidebar()}</aside><main class='music-content'><section class='music-recommend'><div class='music-recommend-head'><div><div class='music-recommend-title'>每日推荐</div><div class='music-recommend-sub'>每天刷新，不会加入收藏歌单</div></div><button class='music-daily-play' onclick='openDailyRecommendations(true)'>播放推荐</button></div><div class='music-recommend-grid'>${renderRecommendCards(X.music.recommend)}</div></section><section class='music-track-section'><div class='music-track-header'><div class='music-track-header-title' id='musicTrackSectionTitle'>${esc(currentMusicSectionTitle())}</div><input class='music-search' id='musicSearchInput' type='search' placeholder='搜索歌曲、歌手、专辑' value='${esc(X.music.search || '')}' oninput='searchMusicTracks(this.value)'></div><div class='music-card-grid' id='musicTrackGrid'>${renderMusicTrackCards(list)}</div></section><section id='musicCommentsPanel' class='music-comments-panel hidden'></section><section id='musicLyricsPanel' class='music-lyrics-panel collapsed hidden'><div class='music-lyrics-header' onclick='toggleMusicLyrics()'><div class='music-lyrics-title'>滚动歌词</div><button class='music-lyrics-toggle'>${X.music.lyricsCollapsed ? '▼' : '▲'}</button></div><div class='music-lyrics-body' id='musicLyrics'><div class='music-lyric-line'>暂无歌词</div></div></section></main></div><div id='musicPlayerBar' class='music-bar hidden'><div class='music-bar-cover' id='musicBarCover'></div><div class='music-bar-info'><div class='music-bar-title' id='musicBarTitle'>未命名歌曲</div><div class='music-bar-artist' id='musicBarArtist'>未知歌手</div></div><div class='music-bar-progress-wrap' id='musicBarSeek' onpointerdown='beginMusicSeek(event)' onclick='seekMusicBar(event)'><div class='music-bar-progress' id='musicBarProgress' style='width:0%'></div><div class='music-bar-thumb' id='musicBarThumb' style='left:0%'></div></div><div class='music-bar-controls'><button class='music-bar-btn' onclick='advanceMusicQueue(-1,false)' title='上一首'>上一首</button><button class='music-bar-btn music-bar-play' id='musicBarPlayBtn' onclick='toggleMusicPlay()' title='播放/暂停'>播放</button><button class='music-bar-btn' onclick='advanceMusicQueue(1,false)' title='下一首'>下一首</button><button class='music-bar-btn' onclick='openMusicComments()' title='评论'>评论</button><button class='music-bar-btn music-subtitle-btn' id='musicSubtitleBtn' onclick='openMusicSubtitle()' title='字幕'>歌词</button><button class='music-bar-btn' onclick='shareCurrentMusicTrack()' title='分享'>分享</button><span class='music-bar-time'><span id='musicBarCurrent'>0:00</span> / <span id='musicBarDuration'>0:00</span></span></div></div></div>`;
      syncCurrentMusicPlayerToUi();
      if (X.music.comments && X.music.comments.open) renderMusicCommentsPanel();
    } catch (e) {
      root.innerHTML = `<div class='music-dark music-state-panel ui-error-state'>${esc(e.message || '加载失败')}</div>`;
    }
  };
  function renderMusicTrackCards(list) {
    if (!list || !list.length) return `<div class='music-empty-state'>${featureIcon('music', '音乐')}<span>还没有歌曲</span></div>`;
    return list.map(track => {
      const hasLyrics = !!String(track.lyrics || '').trim();
      const favBtn = isDailyMusicPlaylist() ? '' : `<button class='mtc-btn ${musicTrackInFavorite(track.id) ? 'favorited' : ''}' title='喜爱' onclick="event.stopPropagation();toggleFavoriteTrack('${esc(track.id)}')">${musicTrackInFavorite(track.id) ? '♥' : '♡'}</button>`;
      const adminBtns = ME && ME.isPrimarySuperAdmin ? `<button class='mtc-btn' title='编辑资料' onclick="event.stopPropagation();editMusicTrack('${esc(track.id)}')">编</button><button class='mtc-btn danger' title='删除音乐' onclick="event.stopPropagation();deleteMusicTrack('${esc(track.id)}')">删</button>` : '';
      return `<article class='music-track-card ${track.id === X.music.currentTrackId ? 'playing' : ''}' onclick="playTrackById('${esc(track.id)}')"><div class='mtc-cover'>${track.cover ? `<img src='${esc(track.cover)}' alt=''>` : featureIcon('music', '音乐')}<button class='mtc-play' onclick="event.stopPropagation();playTrackById('${esc(track.id)}')" aria-label='播放'>▶</button></div><div class='mtc-title'>${esc(track.title || '未命名歌曲')}</div><div class='mtc-artist'>${esc(track.artist || '未知歌手')}</div><div class='mtc-actions'>${hasLyrics ? `<button class='mtc-btn mtc-subtitle' title='字幕' onclick="event.stopPropagation();openMusicSubtitle('${esc(track.id)}')">歌词</button>` : ''}<button class='mtc-btn' title='评论' onclick="event.stopPropagation();openMusicComments('${esc(track.id)}')">评论</button>${favBtn}<button class='mtc-btn' title='分享' onclick="event.stopPropagation();shareMusicTrack('${esc(track.id)}')">分享</button>${adminBtns}</div></article>`;
    }).join('');
  }
  window.renderCurrentMusicList = function () {
    const node = q('musicTrackGrid');
    if (node) node.innerHTML = renderMusicTrackCards(buildMusicList());
    renderMusicSidebarOnly();
    const title = q('musicTrackSectionTitle');
    if (title) title.textContent = currentMusicSectionTitle();
  };
  window.showAllMusicTracks = function () {
    X.music.currentPlaylistId = '';
    X.music.sidebarFilter = 'all';
    renderCurrentMusicList();
  };
  function currentMusicTrack() {
    return (X.music.tracks || []).find(item => item.id === X.music.currentTrackId) || null;
  }
  function musicTrackById(trackId) {
    return (X.music.tracks || []).find(item => item.id === trackId) || (X.music.recommend || []).find(item => item.id === trackId) || null;
  }
  function renderMusicCommentsPanel() {
    const panel = q('musicCommentsPanel');
    if (!panel) return;
    const state = X.music.comments || {};
    if (!state.open) { panel.classList.add('hidden'); return; }
    const track = musicTrackById(state.trackId) || currentMusicTrack();
    const items = state.items || [];
    panel.classList.remove('hidden');
    const body = state.loading
      ? "<div class='music-comment-empty'>评论加载中...</div>"
      : items.length
        ? items.map(cm => `<div class='music-comment-item'><div><strong>${esc(cm.nickname || cm.userId || '')}</strong><small>${fmtDate(cm.createdAt)}</small></div><p>${esc(cm.content || '')}</p></div>`).join('')
        : "<div class='music-comment-empty'>还没有评论</div>";
    panel.innerHTML = `<div class='music-comments-head'><div><strong>${esc(track && track.title || '音乐评论')}</strong><span>评论会在打开时加载</span></div><button class='music-comments-close' onclick='closeMusicComments()'>×</button></div><div class='music-comments-list'>${body}</div><div class='music-comment-box'><textarea id='musicCommentInput' class='x-textarea' placeholder='写下你的评论...'></textarea><button onclick='submitMusicComment()'>发送</button></div>`;
  }
  async function loadMusicComments(trackId, force) {
    const state = X.music.comments || (X.music.comments = {});
    if (!trackId) return;
    if (!force && state.loaded && state.trackId === trackId) return;
    state.trackId = trackId;
    state.loading = true;
    state.open = true;
    renderMusicCommentsPanel();
    try {
      state.items = await apiGet(`/api/music/comments?trackId=${encodeURIComponent(trackId)}`) || [];
      state.loaded = true;
    } catch (e) {
      toast(e.message || '评论加载失败', true);
      state.items = [];
      state.loaded = false;
    } finally {
      state.loading = false;
      renderMusicCommentsPanel();
    }
  }
  window.openMusicComments = async function (trackId) {
    const id = trackId || X.music.currentTrackId;
    if (!id) return toast('请先选择一首歌', true);
    X.music.comments = Object.assign({}, X.music.comments || {}, { trackId: id, open: true });
    await loadMusicComments(id, false);
  };
  window.closeMusicComments = function () {
    X.music.comments = Object.assign({}, X.music.comments || {}, { open: false });
    renderMusicCommentsPanel();
  };
  window.submitMusicComment = async function () {
    const input = q('musicCommentInput');
    const state = X.music.comments || {};
    const content = input ? input.value.trim() : '';
    if (!state.trackId) return toast('请先选择一首歌', true);
    if (!content) return toast('请输入评论内容', true);
    try {
      await apiPost('/api/music/comment', { trackId: state.trackId, content });
      if (input) input.value = '';
      state.loaded = false;
      await loadMusicComments(state.trackId, true);
      toast('评论已发送');
    } catch (e) {
      toast(e.message || '评论发送失败', true);
    }
  };
  function syncCurrentMusicPlayerToUi() {
    const track = currentMusicTrack();
    const audio = q('musicPlayerEl');
    const bar = q('musicPlayerBar');
    if (!track || !audio || !bar) return;
    bar.classList.remove('hidden');
    q('musicBarCover').innerHTML = track.cover ? `<img class='music-bar-cover-action' src='${esc(track.cover)}' alt='' onclick='openMusicDetail()'>` : `<button type='button' class='music-bar-cover-action music-bar-cover-fallback' onclick='openMusicDetail()'>${featureIcon('music', '音乐')}</button>`;
    q('musicBarTitle').textContent = track.title || '未命名歌曲';
    q('musicBarArtist').textContent = track.artist || '未知歌手';
    const subtitleBtn = q('musicSubtitleBtn');
    if (subtitleBtn) subtitleBtn.classList.toggle('hidden', !String(track.lyrics || '').trim());
    const parsedLyrics = parseMusicLyrics(track.lyrics || '');
    const lyricsBox = q('musicLyrics');
    if (lyricsBox) {
      lyricsBox.dataset.lines = JSON.stringify(parsedLyrics.timed ? parsedLyrics.lines : []);
      lyricsBox.innerHTML = renderLyricLinesHtml(parsedLyrics, 'music-lyric-line');
    }
    updateMusicBarTime();
    updateMusicBarPlayState(!audio.paused);
    syncMusicLyricsViews();
  }
  function renderLyricLinesHtml(parsed, className) {
    const lines = (parsed && parsed.lines) || [];
    if (!lines.length) return `<div class='${className} empty'>暂无字幕</div>`;
    return lines.map((line, idx) => {
      const seek = parsed.timed ? ` onclick='seekMusicLyric(${idx})'` : '';
      const timeAttr = parsed.timed ? ` data-index='${idx}'` : '';
      return `<div class='${className}'${timeAttr}${seek}>${esc(line.text || '...')}</div>`;
    }).join('');
  }
  function syncLyricBox(box, lineClass, active, timed, scrollMode) {
    if (!box) return;
    const nodes = box.querySelectorAll('.' + lineClass);
    nodes.forEach((line, i) => line.classList.toggle('active', timed && i === active));
    if (!timed || active < 0) return;
    const el = box.querySelector(`.${lineClass}[data-index='${active}']`);
    if (el) el.scrollIntoView({ block: scrollMode || 'nearest', behavior: 'smooth' });
  }
  function syncMusicLyricsViews() {
    const audio = q('musicPlayerEl');
    const track = currentMusicTrack();
    if (!audio || !track) return;
    const parsed = parseMusicLyrics(track.lyrics || '');
    let active = -1;
    if (parsed.timed) {
      active = 0;
      for (let i = 0; i < parsed.lines.length; i++) if (audio.currentTime >= parsed.lines[i].time) active = i;
    }
    syncLyricBox(q('musicLyrics'), 'music-lyric-line', active, parsed.timed, 'nearest');
    syncLyricBox(q('musicDetailLyrics'), 'music-detail-lyric-line', active, parsed.timed, 'center');
    syncLyricBox(q('musicSubtitleBody'), 'music-subtitle-line', active, parsed.timed, 'center');
    const subtitleTitle = q('musicSubtitleTitle');
    if (subtitleTitle) subtitleTitle.textContent = track.title || '字幕';
    const btn = q('mdPlayBtn');
    if (btn) btn.textContent = audio.paused ? '▶ 播放' : '⏸ 暂停';
  }
  window.playTrackById = async function (trackId, keepPlayCount) {
    const track = musicTrackById(trackId);
    if (!track) return;
    const wasCurrent = X.music.currentTrackId === trackId;
    X.music.currentTrackId = trackId;
    renderCurrentMusicList();
    if (!keepPlayCount) {
      try { await apiPost('/api/music/play', { trackId }); } catch (e) {}
    }
    const existingAudio = q('musicPlayerEl');
    if (existingAudio && existingAudio.dataset.trackId === trackId) {
      const wasEnded = existingAudio.ended;
      if (!keepPlayCount) existingAudio.currentTime = 0;
      if (wasEnded) existingAudio.currentTime = 0;
      if (!keepPlayCount || wasEnded) existingAudio.play().catch(() => {});
      syncCurrentMusicPlayerToUi();
      if (q('musicSubtitleFloat')) renderMusicSubtitleWindow();
      return;
    }
    if (existingAudio) existingAudio.remove();
    const audioEl = document.createElement('audio');
    audioEl.id = 'musicPlayerEl';
    audioEl.dataset.trackId = trackId;
    audioEl.autoplay = true;
    audioEl.src = track.filePath || '';
    audioEl.onended = () => advanceMusicQueue(1, true);
    audioEl.onplay = () => updateMusicBarPlayState(true);
    audioEl.onpause = () => updateMusicBarPlayState(false);
    audioEl.ontimeupdate = () => updateMusicBarTime();
    audioEl.onloadedmetadata = () => updateMusicBarTime();
    document.body.appendChild(audioEl);
    syncCurrentMusicPlayerToUi();
    if (X.music.lyricTimer) clearInterval(X.music.lyricTimer);
    X.music.lyricTimer = setInterval(syncMusicLyricsViews, 350);
    if (q('musicSubtitleFloat')) renderMusicSubtitleWindow();
    if (X.music.comments && X.music.comments.open && wasCurrent) renderMusicCommentsPanel();
    syncMusicLyricsViews();
  };
  window.seekMusicLyric = function (idx) {
    const audio = q('musicPlayerEl');
    const track = currentMusicTrack();
    if (!audio || !track) return;
    const parsed = parseMusicLyrics(track.lyrics || '');
    if (parsed.timed && parsed.lines[idx] && typeof parsed.lines[idx].time === 'number') {
      audio.currentTime = parsed.lines[idx].time;
      if (audio.paused) audio.play().catch(() => {});
    }
  };
  function defaultMusicSubtitleRect() {
    const w = Math.min(560, Math.max(320, Math.floor(window.innerWidth * 0.46)));
    const h = Math.min(180, Math.max(136, Math.floor(window.innerHeight * 0.18)));
    return { left: Math.max(16, Math.floor((window.innerWidth - w) / 2)), top: Math.max(72, Math.floor(window.innerHeight - h - 96)), width: w, height: h };
  }
  function saveMusicSubtitleRect() {
    const box = q('musicSubtitleFloat');
    if (!box) return;
    const r = box.getBoundingClientRect();
    X.music.subtitleRect = { left: Math.round(r.left), top: Math.round(r.top), width: Math.round(r.width), height: Math.round(r.height) };
  }
  function renderMusicSubtitleWindow() {
    const track = currentMusicTrack();
    if (!track) return;
    const parsed = parseMusicLyrics(track.lyrics || '');
    let box = q('musicSubtitleFloat');
    if (!box) {
      box = document.createElement('div');
      box.id = 'musicSubtitleFloat';
      box.className = 'music-subtitle-float';
      document.body.appendChild(box);
      box.addEventListener('mouseup', saveMusicSubtitleRect);
    }
    const rect = X.music.subtitleRect || defaultMusicSubtitleRect();
    const width = Math.min(Math.max(280, rect.width || 520), Math.max(280, window.innerWidth - 16));
    const height = Math.min(Math.max(136, rect.height || 156), Math.max(136, window.innerHeight - 56));
    box.style.left = Math.max(8, Math.min(rect.left, window.innerWidth - width - 8)) + 'px';
    box.style.top = Math.max(48, Math.min(rect.top, window.innerHeight - height - 8)) + 'px';
    box.style.width = width + 'px';
    box.style.height = height + 'px';
    box.innerHTML = `<div class='music-subtitle-head' onpointerdown='startMusicSubtitleDrag(event)'><div><strong id='musicSubtitleTitle'>${esc(track.title || '字幕')}</strong><span>${parsed.timed ? '同步字幕' : '文本字幕'}</span></div><div class='music-subtitle-tools'><button onclick='changeMusicSubtitleFont(-2)' title='缩小'>A-</button><button onclick='changeMusicSubtitleFont(2)' title='放大'>A+</button><button onclick='closeMusicSubtitle()' title='关闭'>×</button></div></div><div class='music-subtitle-body' id='musicSubtitleBody' style='font-size:${Number(X.music.subtitleFont || 18)}px'>${renderLyricLinesHtml(parsed, 'music-subtitle-line')}</div>`;
    syncMusicLyricsViews();
  }
  window.openMusicSubtitle = async function (trackId) {
    const id = trackId || X.music.currentTrackId;
    if (!id) return toast('请先选择一首歌', true);
    const target = (X.music.tracks || []).find(item => item.id === id);
    if (!target) return toast('歌曲不存在', true);
    if (!String(target.lyrics || '').trim()) return toast('这首歌暂无字幕', true);
    if (id !== X.music.currentTrackId) await playTrackById(id);
    renderMusicSubtitleWindow();
  };
  window.closeMusicSubtitle = function () {
    saveMusicSubtitleRect();
    const box = q('musicSubtitleFloat');
    if (box) box.remove();
  };
  window.changeMusicSubtitleFont = function (delta) {
    X.music.subtitleFont = Math.max(14, Math.min(40, Number(X.music.subtitleFont || 18) + Number(delta || 0)));
    const body = q('musicSubtitleBody');
    if (body) body.style.fontSize = X.music.subtitleFont + 'px';
  };
  window.startMusicSubtitleDrag = function (ev) {
    const box = q('musicSubtitleFloat');
    if (!box || (ev.target && ev.target.closest && ev.target.closest('button'))) return;
    ev.preventDefault();
    const startX = ev.clientX, startY = ev.clientY;
    const rect = box.getBoundingClientRect();
    const move = e => {
      const left = Math.max(8, Math.min(rect.left + e.clientX - startX, window.innerWidth - rect.width - 8));
      const top = Math.max(48, Math.min(rect.top + e.clientY - startY, window.innerHeight - rect.height - 8));
      box.style.left = left + 'px';
      box.style.top = top + 'px';
    };
    const up = () => {
      document.removeEventListener('pointermove', move);
      document.removeEventListener('pointerup', up);
      saveMusicSubtitleRect();
    };
    document.addEventListener('pointermove', move);
    document.addEventListener('pointerup', up);
  };
  window.advanceMusicQueue = function (step, autoAdvance) {
    const nextId = pickNextMusicTrackId(step || 1, !!autoAdvance);
    if (nextId) playTrackById(nextId, !!autoAdvance);
  };
  window.toggleMusicPlayMode = function () {
    X.music.playMode = X.music.playMode === 'sequence' ? 'repeat-one' : X.music.playMode === 'repeat-one' ? 'shuffle' : 'sequence';
    toast(`已切换为${musicModeText(X.music.playMode)}`);
  };
  window.toggleMusicPlay = function () {
    const audio = q('musicPlayerEl');
    if (!audio) return;
    if (audio.paused) audio.play().catch(() => {}); else audio.pause();
  };
  window.updateMusicBarPlayState = function (playing) {
    const btn = q('musicBarPlayBtn');
    if (btn) btn.textContent = playing ? '暂停' : '播放';
  };
  window.updateMusicBarTime = function () {
    const audio = q('musicPlayerEl');
    if (!audio) return;
    const cur = q('musicBarCurrent');
    const dur = q('musicBarDuration');
    const prog = q('musicBarProgress');
    const thumb = q('musicBarThumb');
    const fmt = (t) => { if (!t || !Number.isFinite(t)) return '0:00'; const m = Math.floor(t / 60); const s = Math.floor(t % 60); return m + ':' + (s < 10 ? '0' : '') + s; };
    const pct = audio.duration ? Math.max(0, Math.min(100, (audio.currentTime / audio.duration) * 100)) : 0;
    if (cur) cur.textContent = fmt(audio.currentTime);
    if (dur) dur.textContent = fmt(audio.duration);
    if (prog) prog.style.width = pct + '%';
    if (thumb) thumb.style.left = pct + '%';
  };
  window.seekMusicBar = function (ev) {
    const audio = q('musicPlayerEl');
    const wrap = q('musicBarSeek');
    if (!audio || !wrap || !audio.duration) return;
    const rect = wrap.getBoundingClientRect();
    const ratio = Math.max(0, Math.min(1, (ev.clientX - rect.left) / Math.max(rect.width, 1)));
    audio.currentTime = ratio * audio.duration;
    updateMusicBarTime();
  };
  window.beginMusicSeek = function (ev) {
    ev.preventDefault();
    seekMusicBar(ev);
    const move = e => seekMusicBar(e);
    const up = () => {
      document.removeEventListener('pointermove', move);
      document.removeEventListener('pointerup', up);
    };
    document.addEventListener('pointermove', move);
    document.addEventListener('pointerup', up);
  };
  window.toggleFavoriteTrack = async function (trackId) {
    try {
      let favorite = getFavoritePlaylist();
      if (!favorite) favorite = await apiPost('/api/music/create-playlist', { name: '我的喜爱', favorite: true });
      await apiPost('/api/music/toggle-playlist', { playlistId: favorite.id, trackId });
      const [tracks, playlists] = await Promise.all([apiGet('/api/music/tracks'), apiGet('/api/music/playlists')]);
      X.music.tracks = tracks || [];
      X.music.playlists = playlists || [];
      renderCurrentMusicList();
      syncCurrentMusicPlayerToUi();
      toast('喜爱列表已更新');
    } catch (e) {
      toast(e.message || '操作失败', true);
    }
  };
  window.shareMusicTrack = async function (trackId) {
    const link = `${location.origin}/share/music/${encodeURIComponent(trackId)}`;
    await copyText(link, '音乐链接已复制');
    try { await maybeSendShareCard('music', trackId, '音乐分享卡片已发送'); } catch (e) { toast(e.message || '发送卡片失败', true); }
  };
  window.shareCurrentMusicTrack = function () {
    const track = currentMusicTrack();
    if (!track) { toast('当前没有播放的音乐', true); return; }
    shareMusicTrack(track.id);
  };
  window.toggleTrackInPlaylist = async function (playlistId, trackId) { try { await apiPost('/api/music/toggle-playlist', { playlistId, trackId }); toast('歌单已更新'); const [tracks, playlists] = await Promise.all([apiGet('/api/music/tracks'), apiGet('/api/music/playlists')]); X.music.tracks = tracks || []; X.music.playlists = playlists || []; renderCurrentMusicList(); syncCurrentMusicPlayerToUi(); } catch (e) { toast(e.message || '操作失败', true); } };
  window.searchMusicTracks = function (keyword) {
    X.music.search = String(keyword || '');
    renderCurrentMusicList();
  };
  window.showMusicImportDoc = function () {
    const doc = {
      title: '音乐 JSON 导入说明',
      artist: '歌手',
      album: '专辑',
      lyrics: '[00:00.00] 歌词内容',
      cover: 'https://example.com/cover.jpg'
    };
    openPrev('code', URL.createObjectURL(new Blob([JSON.stringify(doc, null, 2)], { type: 'application/json;charset=utf-8' })), 'music-import-example.json');
  };
  window.createPlaylistPrompt = async function (favorite) { const name = await window.showPrompt('请输入歌单名称：', favorite ? '我的喜爱' : '新歌单'); if (name === null || !name.trim()) return; try { await apiPost('/api/music/create-playlist', { name: name.trim(), favorite: !!favorite }); toast('歌单已创建'); musicRefresh(); } catch (e) { toast(e.message || '创建失败', true); } };
  window.filterPlaylistTracks = function (playlistId) { X.music.currentPlaylistId = playlistId || ''; musicRefresh(); };
  window.musicSidebarFilter = function (filter) { X.music.sidebarFilter = filter; musicRefresh(); };
  window.toggleMusicLyrics = function () { X.music.lyricsCollapsed = !X.music.lyricsCollapsed; const panel = q('musicLyricsPanel'); if (panel) { panel.classList.toggle('collapsed', X.music.lyricsCollapsed); const toggle = panel.querySelector('.music-lyrics-toggle'); if (toggle) toggle.textContent = X.music.lyricsCollapsed ? '▼' : '▲'; } };
  function isMusicZipFile(file) {
    const name = String(file && file.name || '').toLowerCase();
    return name.endsWith('.zip') || String(file && file.type || '').includes('zip');
  }
  async function storeMusicLooseFile(file, statusText) {
    const started = Date.now();
    X.cloud.uploadQueue.push({ name: file.name, total: file.size || 0, loaded: 0, speed: 0, status: statusText || '上传音乐' });
    updateUploadFloater();
    try {
      const uploaded = await storeLooseFile(file, ev => {
        const elapsed = Math.max((Date.now() - started) / 1000, 0.1);
        markCloudUploadQueue(file.name, {
          total: Number(ev.total || file.size || 0),
          loaded: Number(ev.loaded || 0),
          speed: Number(ev.loaded || 0) / elapsed,
          status: statusText || '上传音乐'
        });
      });
      markCloudUploadQueue(file.name, { loaded: file.size || 0, total: file.size || 0, status: '完成' });
      return uploaded;
    } catch (e) {
      markCloudUploadQueue(file.name, { status: '失败' });
      throw e;
    } finally {
      setTimeout(() => {
        X.cloud.uploadQueue = (X.cloud.uploadQueue || []).filter(item => item.status !== '完成');
        updateUploadFloater();
      }, 1200);
    }
  }
  function resetMusicMetaLabels(autoMeta) {
    const titleEl = document.getElementById('mufTitle');
    const artistEl = document.getElementById('mufArtist');
    const albumEl = document.getElementById('mufAlbum');
    if (!titleEl || !artistEl || !albumEl) return;
    titleEl.disabled = false;
    artistEl.disabled = false;
    albumEl.disabled = false;
    titleEl.parentElement.querySelector('label').innerHTML = '歌曲标题' + (autoMeta.hasTitle ? '<span class="muf-tag">自动识别</span>' : '<span class="muf-tag">文件名填充</span>');
    artistEl.parentElement.querySelector('label').innerHTML = '歌手' + (autoMeta.hasArtist ? '<span class="muf-tag">自动识别</span>' : '');
    albumEl.parentElement.querySelector('label').innerHTML = '专辑' + (autoMeta.hasAlbum ? '<span class="muf-tag">自动识别</span>' : '');
  }
  async function chooseMusicCover(coverArea, initialCover, autoCover) {
    let coverPath = initialCover || '';
    const render = () => {
    const preview = coverPath ? `<img class="muf-cover-preview" src="${esc(coverPath)}" alt="封面"><span class="muf-tag">${autoCover ? '自动识别' : '已上传'}</span>` : '<span id="mufCoverStatus" class="muf-cover-status">未选择</span>';
      coverArea.innerHTML = `${preview}<button class="muf-cover-btn" id="mufCoverBtn">${coverPath ? '更换封面' : '上传封面'}</button>`;
      document.getElementById('mufCoverBtn').onclick = () => {
        const ci = document.createElement('input');
        ci.type = 'file';
        ci.accept = 'image/*,.png,.jpg,.jpeg,.webp';
        ci.onchange = async () => {
          const cf = ci.files && ci.files[0];
          if (!cf) return;
          try {
            const cu = await storeMusicLooseFile(cf, '上传封面');
            coverPath = cu.filePath || '';
            autoCover = false;
            render();
          } catch (e) { toast(e.message || '封面上传失败', true); }
        };
        ci.click();
      };
    };
    render();
    return () => coverPath;
  }
  async function uploadOneMusicFile(file) {
    const uploaded = await storeMusicLooseFile(file, '上传音乐');
    let autoMeta = {};
    try {
      const resp = await fetch('/api/music/extract-meta?filePath=' + encodeURIComponent(uploaded.filePath) + '&fileName=' + encodeURIComponent(uploaded.fileName || file.name));
      if (resp.ok) autoMeta = await resp.json();
    } catch (e) { console.warn('元数据提取请求失败', e); }
    const titleEl = document.getElementById('mufTitle');
    const artistEl = document.getElementById('mufArtist');
    const albumEl = document.getElementById('mufAlbum');
    const lyricsEl = document.getElementById('mufLyrics');
    const coverArea = document.getElementById('mufCoverArea');
    titleEl.value = autoMeta.title || file.name.replace(/\.[^.]+$/, '');
    artistEl.value = autoMeta.artist || '';
    albumEl.value = autoMeta.album || '';
    lyricsEl.value = autoMeta.lyrics || '';
    resetMusicMetaLabels(autoMeta);
    const getCoverPath = await chooseMusicCover(coverArea, autoMeta.cover || '', !!autoMeta.hasCover);
    const result = await new Promise(r => {
      const titleHead = document.querySelector('#musicUploadModal h3');
      if (titleHead) titleHead.textContent = '上传音乐';
      window._musicUploadRes = v => { closeModal('musicUploadModal'); r(v); };
      openModal('musicUploadModal');
    });
    if (!result) return false;
    const title = titleEl.value.trim() || file.name.replace(/\.[^.]+$/, '');
    const artist = artistEl.value.trim() || '未知歌手';
    const album = albumEl.value.trim() || '';
    const lyrics = lyricsEl.value || '';
    await apiPost('/api/music/upload', { title, artist, album, lyrics, filePath: uploaded.filePath, cover: getCoverPath() });
    return true;
  }
  async function uploadMusicZip(file) {
    const uploaded = await storeMusicLooseFile(file, '上传音乐ZIP');
    const res = await apiPost('/api/music/import-zip', { filePath: uploaded.filePath, fileName: uploaded.fileName || file.name });
    const imported = Number(res.importedCount || 0);
    const skipped = Number(res.skippedCount || 0);
    toast(`ZIP 导入完成：${imported} 首${skipped ? `，跳过 ${skipped} 个` : ''}`);
  }
  window.uploadMusicPrompt = async function () {
    const input = document.createElement('input');
    input.type = 'file';
    input.multiple = true;
    input.accept = 'audio/*,.mp3,.flac,.wav,.m4a,.aac,.ogg,.opus,.wma,.zip';
    input.onchange = async () => {
      const files = Array.from(input.files || []);
      if (!files.length) return;
      try {
        let changed = false;
        for (const file of files) {
          if (isMusicZipFile(file)) {
            await uploadMusicZip(file);
            changed = true;
          } else {
            changed = await uploadOneMusicFile(file) || changed;
          }
        }
        if (changed) {
          toast(files.length === 1 ? '音乐已上传' : '音乐批量上传完成');
          musicRefresh();
        }
      } catch (e) {
        toast(e.message || '上传失败', true);
      }
    };
    input.click();
  };

  window.editMusicTrack = async function (trackId) {
    if (!ME || !ME.isPrimarySuperAdmin) return toast('仅服主可更新音乐资料', true);
    const track = musicTrackById(trackId);
    if (!track) return toast('歌曲不存在', true);
    const titleHead = document.querySelector('#musicUploadModal h3');
    if (titleHead) titleHead.textContent = '更新音乐资料';
    const titleEl = document.getElementById('mufTitle');
    const artistEl = document.getElementById('mufArtist');
    const albumEl = document.getElementById('mufAlbum');
    const lyricsEl = document.getElementById('mufLyrics');
    const coverArea = document.getElementById('mufCoverArea');
    titleEl.value = track.title || '';
    artistEl.value = track.artist || '';
    albumEl.value = track.album || '';
    lyricsEl.value = track.lyrics || '';
    resetMusicMetaLabels({});
    const getCoverPath = await chooseMusicCover(coverArea, track.cover || '', false);
    const result = await new Promise(r => {
      window._musicUploadRes = v => { closeModal('musicUploadModal'); r(v); };
      openModal('musicUploadModal');
    });
    if (titleHead) titleHead.textContent = '上传音乐';
    if (!result) return;
    try {
      await apiPost('/api/music/update', {
        trackId,
        title: titleEl.value.trim() || '未命名歌曲',
        artist: artistEl.value.trim() || '未知歌手',
        album: albumEl.value.trim(),
        lyrics: lyricsEl.value || '',
        cover: getCoverPath()
      });
      toast('音乐资料已更新');
      await musicRefresh();
    } catch (e) {
      toast(e.message || '更新失败', true);
    }
  };

  window.deleteMusicTrack = async function (trackId) {
    if (!ME || !ME.isPrimarySuperAdmin) return toast('仅服主可删除音乐', true);
    const track = musicTrackById(trackId);
    if (!track) return toast('歌曲不存在', true);
    if (!(await window.showConfirm(`确定删除《${track.title || '未命名歌曲'}》吗？`))) return;
    try {
      await apiPost('/api/music/delete', { trackId });
      if (X.music.currentTrackId === trackId) {
        const audio = q('musicPlayerEl');
        if (audio) { audio.pause(); audio.remove(); }
        X.music.currentTrackId = '';
        closeMusicSubtitle();
        closeMusicDetail();
      }
      if (X.music.comments && X.music.comments.trackId === trackId) closeMusicComments();
      toast('音乐已删除');
      await musicRefresh();
    } catch (e) {
      toast(e.message || '删除失败', true);
    }
  };


  window.openMusicDetail = function () {
    const track = currentMusicTrack();
    if (!track) return toast('当前没有播放的音乐', true);
    const parsedLyrics = parseMusicLyrics(track.lyrics || '');
    let html = '<div class="music-detail">';
    html += '<div class="music-detail-topbar">';
    html += '<button onclick="closeMusicDetail()">✕ 关闭</button>';
    html += '<button onclick="toggleMusicPlay()" id="mdPlayBtn">' + (q('musicPlayerEl') && !q('musicPlayerEl').paused ? '⏸ 暂停' : '▶ 播放') + '</button>';
    html += '<button onclick="shareMusicTrack(\'' + esc(track.id) + '\')" title="分享">↗ 分享</button>';
    html += '</div>';
    html += '<div class="music-detail-hero">';
    if (track.cover) html += '<img src="' + esc(track.cover) + '" alt="">';
    else html += '<div class="music-detail-cover-fallback">' + featureIcon('music', '音乐') + '</div>';
    html += '<div class="music-detail-title">' + esc(track.title || '未命名歌曲') + '</div>';
    html += '<div class="music-detail-meta">' + esc(track.artist || '未知歌手') + (track.album ? ' · ' + esc(track.album) : '') + '</div>';
    html += '</div>';
    html += '<div class="music-detail-body">';
    html += '<div class="music-detail-label">歌词</div>';
    if (parsedLyrics.lines.length) {
      html += '<div class="music-detail-lyrics" id="musicDetailLyrics">';
      html += renderLyricLinesHtml(parsedLyrics, 'music-detail-lyric-line');
      html += '</div>';
    } else {
      html += '<div class="music-comment-empty">暂无歌词</div>';
    }
    html += '</div></div>';
    const overlay = document.createElement('div');
    overlay.id = 'musicDetailOverlay';
    overlay.className = 'music-detail-overlay';
    overlay.innerHTML = html;
    document.body.appendChild(overlay);
    document.body.classList.add('music-detail-open');
    syncMusicLyricsViews();
  };
  window.closeMusicDetail = function () {
    const overlay = q('musicDetailOverlay');
    if (overlay) overlay.remove();
    document.body.classList.remove('music-detail-open');
  };

  window.openVideos = function (navMode) {
    if (checkAndWarnFeatureBan('video')) return;
    if (poll) clearInterval(poll);
    prevView = 'videosView';
    sw('videosView', 'c-videos');
    updateRoute('/videos', navMode === undefined ? true : navMode);
    videosRefresh();
  };
  window.videosRefresh = async function () {
    const root = q('videoRoot');
    if (!root) return;
    root.innerHTML = "<div class='yt-loading'>加载中...</div>";
    try {
      const categories = await apiGet('/api/videos/categories');
      X.videos.categories = categories || [];
      X.videos.list = [];
      X.videos.currentList = [];
      X.videos.currentVideoId = '';
      X.videos.pageOffset = 0;
      X.videos.total = 0;
      X.videos.hasMore = true;
      X.videos.lazyLoading = false;
      root.innerHTML = `<div class='yt-shell'><header class='yt-topbar'><div class='yt-wordmark'>${featureIcon('video', '视频')}<span>视频</span></div><div class='yt-search'><input id='videoSearchInput' type='search' placeholder='搜索视频' value='${esc(X.videos.search || '')}' oninput='searchVideos(this.value)' onkeydown='if(event.key==="Enter")searchVideos(this.value)'><button onclick='searchVideos(q("videoSearchInput").value)' title='搜索'>搜索</button></div>${ME.isSuperAdmin ? "<div class='yt-upload-actions'><button onclick='createVideoCategoryPrompt()'>新建栏目</button><button onclick='uploadVideoPrompt()'>上传视频</button></div>" : ''}</header>${renderVideoCategoryRail()}<div id='videoMainContent'>${renderVideoList([])}</div></div>`;
      bindVideoLazyScroll();
      await loadMoreVideoCards(true);
    } catch (e) {
      root.innerHTML = `<div class='x-empty ui-error-state'>${esc(e.message || '加载失败')}</div>`;
    }
  };
  function videoListApiUrl(offset) {
    const params = new URLSearchParams();
    params.set('offset', String(Math.max(0, Number(offset || 0))));
    params.set('limit', String(Math.max(6, Number(X.videos.pageSize || 18))));
    if ((X.videos.search || '').trim()) params.set('q', (X.videos.search || '').trim());
    if (X.videos.categoryId) params.set('categoryId', X.videos.categoryId);
    return '/api/videos/list?' + params.toString();
  }
  function normalizePagedVideoResult(data) {
    const items = Array.isArray(data) ? data : (Array.isArray(data && data.items) ? data.items : []);
    return {
      items,
      total: Array.isArray(data) ? items.length : Number(data && data.total != null ? data.total : items.length),
      nextOffset: Array.isArray(data) ? items.length : Number(data && data.nextOffset != null ? data.nextOffset : items.length),
      hasMore: Array.isArray(data) ? false : !!(data && data.hasMore)
    };
  }
  function videoCategoryName(categoryId) {
    const cat = (X.videos.categories || []).find(c => c.id === categoryId);
    return cat && cat.name ? cat.name : '未分类';
  }
  function renderVideoCategoryRail() {
    const chips = [`<button class='yt-chip ${!X.videos.categoryId ? 'active' : ''}' onclick="filterVideosByCategory('')">全部</button>`]
      .concat((X.videos.categories || []).map(c => `<button class='yt-chip ${X.videos.categoryId === c.id ? 'active' : ''}' onclick="filterVideosByCategory('${esc(c.id)}')">${esc(c.name)}</button>`));
    return `<div class='yt-chip-row'>${chips.join('')}</div>`;
  }
  function renderVideoThumb(item, compact) {
    const cover = item.coverPath
      ? `<img src='${esc(item.coverPath)}' alt=''>`
      : `<div class='yt-thumb-fallback'>${featureIcon('video', '视频')}</div>`;
    return `<div class='yt-thumb ${compact ? 'compact' : ''}'>${cover}<span class='yt-thumb-play'>▶</span></div>`;
  }
  function renderRelatedVideoItem(item) {
    return `<button class='yt-related-item ${item.id === X.videos.currentVideoId ? 'active' : ''}' onclick="playVideoById('${esc(item.id)}')">${renderVideoThumb(item, true)}<span><strong>${esc(item.title || '未命名视频')}</strong><small>${esc(videoCategoryName(item.categoryId))} · ${Number(item.playCount || 0).toLocaleString('zh-CN')} 次播放</small></span></button>`;
  }
  function renderVideoCard(item) {
    return `<article class='yt-card ${item.id === X.videos.currentVideoId ? 'active' : ''}' onclick="playVideoById('${esc(item.id)}')">${renderVideoThumb(item, false)}<div class='yt-card-body'><div class='yt-card-title'>${esc(item.title || '未命名视频')}</div><div class='yt-card-meta'>${esc(videoCategoryName(item.categoryId))} · ${Number(item.playCount || 0).toLocaleString('zh-CN')} 次播放</div><div class='yt-card-desc'>${esc(item.description || '')}</div></div></article>`;
  }
  function renderVideoCards(list) {
    if (!list.length && X.videos.lazyLoading) return "<div class='yt-loading'>加载中...</div>";
    if (!list.length) return emptyState('emptyVideos', '还没有视频', 'yt-empty');
    return list.map(renderVideoCard).join('');
  }
  function renderVideoLazyFooter(list) {
    const total = Number(X.videos.total || (Array.isArray(list) ? list.length : 0));
    if (!total && !X.videos.lazyLoading) return '';
    if (!X.videos.hasMore && total) return `<div class='yt-lazy-end'>已显示全部 ${total} 个视频</div>`;
    const label = X.videos.lazyLoading ? '加载中...' : '继续向下滑动加载更多';
    return `<div class='yt-lazy-loader ${X.videos.lazyLoading ? 'loading' : ''}' id='videoLazyLoader'><span></span>${label}</div>`;
  }
  function renderVideoList(list) {
    const total = Number(X.videos.total || list.length || 0);
    return `<div class='yt-watch-layout no-player' id='videoWatchLayout'><main class='yt-watch-main'><div id='videoPlayerWrap' class='yt-player-slot hidden'></div><div class='yt-section-head'><strong>${(X.videos.search || '').trim() ? '搜索结果' : '全部视频'}</strong><span id='videoSectionCount'>${list.length}/${total} 个视频</span></div><div class='yt-card-grid' id='videoCardGrid'>${renderVideoCards(list)}</div>${renderVideoLazyFooter(list)}</main><aside id='videoRelatedRail' class='yt-related-rail hidden'></aside></div>`;
  }
  function updateVideoLazyFooter(list) {
    const old = q('videoLazyLoader') || document.querySelector('.yt-lazy-end');
    if (old) {
      old.outerHTML = renderVideoLazyFooter(list);
      return;
    }
    const grid = q('videoCardGrid');
    if (grid) grid.insertAdjacentHTML('afterend', renderVideoLazyFooter(list));
  }
  function updateVideoCardsOnly(list) {
    const grid = q('videoCardGrid');
    if (grid) grid.innerHTML = renderVideoCards(list);
    const count = q('videoSectionCount');
    if (count) count.textContent = `${list.length}/${Number(X.videos.total || list.length || 0)} 个视频`;
    updateVideoLazyFooter(list);
    document.querySelectorAll('.yt-card').forEach(el => el.classList.toggle('active', el.getAttribute('onclick') && el.getAttribute('onclick').includes(X.videos.currentVideoId)));
    bindVideoLazyScroll();
  }
  function bindVideoLazyScroll() {
    const box = q('videoWatchLayout');
    if (!box || box.dataset.lazyBound === '1') return;
    box.dataset.lazyBound = '1';
    box.addEventListener('scroll', window.maybeLoadMoreVideos, { passive: true });
  }
  function applyVideoFilters() {
    let list = (X.videos.list || []).slice();
    X.videos.currentList = list.map(item => item.id);
    return list;
  }
  function currentVideoIndex() {
    return Math.max(0, (X.videos.currentList || []).indexOf(X.videos.currentVideoId));
  }
  function getRecommendedVideos(video) {
    return (X.videos.list || [])
      .filter(item => item.id !== video.id)
      .map(item => {
        let score = 0;
        if (video.categoryId && item.categoryId === video.categoryId) score += 60;
        if (String(item.title || '').charAt(0) === String(video.title || '').charAt(0)) score += 10;
        score += Math.min(Number(item.playCount || 0), 30);
        return { item, score };
      })
      .sort((a, b) => b.score - a.score || Number(b.item.playCount || 0) - Number(a.item.playCount || 0))
      .slice(0, 5)
      .map(entry => entry.item);
  }
  function videoSearchScore(item, keyword) {
    const text = String(keyword || '').trim().toLowerCase();
    if (!text) return 1;
    const title = String(item && item.title || '').toLowerCase();
    const description = String(item && item.description || '').toLowerCase();
    const cat = (X.videos.categories || []).find(c => c.id === item.categoryId);
    const category = String(cat && cat.name || '').toLowerCase();
    let score = 0;
    if (title === text) score += 120;
    else if (title.startsWith(text)) score += 90;
    else if (title.includes(text)) score += 60;
    if (category === text) score += 45;
    else if (category.startsWith(text)) score += 35;
    else if (category.includes(text)) score += 20;
    if (description.startsWith(text)) score += 18;
    else if (description.includes(text)) score += 10;
    score += fuzzyTextScore(title + ' ' + category + ' ' + description, text);
    return score;
  }
  function fuzzyTextScore(source, keyword) {
    source = String(source || '').toLowerCase();
    keyword = String(keyword || '').toLowerCase();
    if (!source || !keyword) return 0;
    let pos = -1;
    let matched = 0;
    let gap = 0;
    for (const ch of keyword) {
      const next = source.indexOf(ch, pos + 1);
      if (next < 0) return 0;
      if (pos >= 0) gap += Math.max(0, next - pos - 1);
      pos = next;
      matched++;
    }
    return Math.max(4, Math.round(28 * matched / Math.max(keyword.length, 1) - Math.min(gap, 18)));
  }
  let videoSearchTimer = null;
  function reloadVideoList() {
    X.videos.currentVideoId = '';
    X.videos.comments = [];
    X.videos.danmaku = [];
    const main = q('videoMainContent');
    if (main) main.innerHTML = renderVideoList([]);
    bindVideoLazyScroll();
    loadMoreVideoCards(true);
  }
  window.searchVideos = function (keyword) {
    X.videos.search = String(keyword || '');
    clearTimeout(videoSearchTimer);
    videoSearchTimer = setTimeout(reloadVideoList, 180);
  };
  window.maybeLoadMoreVideos = function () {
    const box = q('videoWatchLayout');
    if (!box) return;
    if (!X.videos.lazyLoading && X.videos.hasMore && box.scrollHeight - box.scrollTop - box.clientHeight < 220) loadMoreVideoCards(false);
    if (typeof maybeLoadMoreVideoComments === 'function') maybeLoadMoreVideoComments();
  };
  window.loadMoreVideoCards = async function (reset) {
    if (X.videos.lazyLoading) return;
    if (!reset && !X.videos.hasMore) return;
    if (reset) {
      X.videos.list = [];
      X.videos.currentList = [];
      X.videos.pageOffset = 0;
      X.videos.total = 0;
      X.videos.hasMore = true;
    }
    X.videos.lazyLoading = true;
    updateVideoCardsOnly(X.videos.list || []);
    try {
      const page = normalizePagedVideoResult(await apiGet(videoListApiUrl(reset ? 0 : X.videos.pageOffset)));
      const merged = reset ? [] : (X.videos.list || []).slice();
      const seen = new Set(merged.map(item => item.id));
      page.items.forEach(item => {
        if (item && item.id && !seen.has(item.id)) {
          merged.push(item);
          seen.add(item.id);
        }
      });
      X.videos.list = merged;
      X.videos.total = page.total;
      X.videos.pageOffset = page.nextOffset;
      X.videos.hasMore = page.hasMore;
      applyVideoFilters();
    } catch (e) {
      toast(e.message || '加载视频失败', true);
      X.videos.hasMore = false;
    } finally {
      X.videos.lazyLoading = false;
      updateVideoCardsOnly(X.videos.list || []);
      setTimeout(window.maybeLoadMoreVideos, 60);
    }
  };
  window.filterVideosByCategory = function (categoryId) {
    X.videos.categoryId = categoryId || '';
    const rail = document.querySelector('#videoRoot .yt-chip-row');
    if (rail) rail.outerHTML = renderVideoCategoryRail();
    reloadVideoList();
  };
  function normalizePagedCommentResult(data) {
    const items = Array.isArray(data) ? data : (Array.isArray(data && data.items) ? data.items : []);
    return {
      items,
      total: Array.isArray(data) ? items.length : Number(data && data.total != null ? data.total : items.length),
      nextOffset: Array.isArray(data) ? items.length : Number(data && data.nextOffset != null ? data.nextOffset : items.length),
      hasMore: Array.isArray(data) ? false : !!(data && data.hasMore)
    };
  }
  function resetVideoComments(videoId) {
    X.videos.comments = [];
    X.videos.commentsOffset = 0;
    X.videos.commentsTotal = 0;
    X.videos.commentsHasMore = true;
    X.videos.commentsLoading = false;
    X.videos.loadedCommentsFor = videoId || '';
  }
  function renderVideoComments() {
    const comments = Array.isArray(X.videos.comments) ? X.videos.comments : [];
    const total = Number(X.videos.commentsTotal || comments.length || 0);
    const rows = comments.map(cm => `<div class='yt-comment'><strong>${esc(cm.nickname || cm.userId || '')}</strong><small>${fmtDate(cm.createdAt)}</small><div>${esc(cm.content || '')}</div></div>`).join('');
    const empty = !comments.length && !X.videos.commentsLoading && !X.videos.commentsHasMore ? "<div class='yt-empty small'>还没有评论</div>" : '';
    let footer = '';
    if (X.videos.commentsLoading) {
      footer = `<div class='yt-comment-lazy loading' id='videoCommentLazyLoader'><span></span>评论加载中...</div>`;
    } else if (X.videos.commentsHasMore) {
      footer = `<button type='button' class='yt-comment-lazy' id='videoCommentLazyLoader' onclick='loadVideoComments(false,true)'>下滑或点击加载评论</button>`;
    } else if (comments.length) {
      footer = `<div class='yt-lazy-end'>已显示全部 ${total} 条评论</div>`;
    }
    return `<div class='yt-section-head yt-comments-head'><strong>评论</strong><span>${comments.length}/${total} 条</span></div><div class='yt-comments' id='videoCommentsList'>${rows || empty}</div>${footer}`;
  }
  function updateVideoCommentsOnly() {
    const panel = q('videoCommentsPanel');
    if (panel) panel.innerHTML = renderVideoComments();
  }
  window.loadVideoComments = async function (reset, force) {
    const videoId = X.videos.currentVideoId;
    if (!videoId || X.videos.commentsLoading) return;
    if (!reset && !force && !X.videos.commentsHasMore) return;
    if (reset || X.videos.loadedCommentsFor !== videoId) resetVideoComments(videoId);
    if (!X.videos.commentsHasMore && !reset) return;
    X.videos.commentsLoading = true;
    updateVideoCommentsOnly();
    try {
      const params = new URLSearchParams();
      params.set('videoId', videoId);
      params.set('offset', String(reset ? 0 : Math.max(0, Number(X.videos.commentsOffset || 0))));
      params.set('limit', String(Math.max(5, Number(X.videos.commentsPageSize || 20))));
      const page = normalizePagedCommentResult(await apiGet('/api/videos/comments?' + params.toString()));
      const merged = reset ? [] : (X.videos.comments || []).slice();
      const seen = new Set(merged.map(item => item.id));
      page.items.forEach(item => {
        if (item && item.id && !seen.has(item.id)) {
          merged.push(item);
          seen.add(item.id);
        }
      });
      X.videos.comments = merged;
      X.videos.commentsTotal = page.total;
      X.videos.commentsOffset = page.nextOffset;
      X.videos.commentsHasMore = page.hasMore;
    } catch (e) {
      toast(e.message || '加载评论失败', true);
      X.videos.commentsHasMore = false;
    } finally {
      X.videos.commentsLoading = false;
      updateVideoCommentsOnly();
    }
  };
  window.maybeLoadMoreVideoComments = function () {
    const marker = q('videoCommentLazyLoader');
    const box = q('videoWatchLayout');
    if (!marker || !box || X.videos.commentsLoading || !X.videos.commentsHasMore) return;
    const markerRect = marker.getBoundingClientRect();
    const boxRect = box.getBoundingClientRect();
    if (markerRect.top <= boxRect.bottom + 220) loadVideoComments(false, true);
  };
  async function ensureVideoLoaded(videoId) {
    let video = (X.videos.list || []).find(item => item.id === videoId);
    if (video) return video;
    try {
      const page = normalizePagedVideoResult(await apiGet('/api/videos/list?id=' + encodeURIComponent(videoId) + '&limit=1'));
      video = page.items && page.items[0];
      if (video && video.id) {
        X.videos.list = [video].concat((X.videos.list || []).filter(item => item.id !== video.id));
        X.videos.total = Math.max(Number(X.videos.total || 0), X.videos.list.length);
        applyVideoFilters();
        updateVideoCardsOnly(X.videos.list || []);
      }
    } catch (e) {}
    return video;
  }
  window.playVideoById = async function (videoId, countPlay) {
    const video = await ensureVideoLoaded(videoId);
    if (!video) return;
    X.videos.currentVideoId = videoId;
    resetVideoComments(videoId);
    if (countPlay !== false) {
      try { await apiPost('/api/videos/play', { videoId }); } catch (e) {}
      video.playCount = Number(video.playCount || 0) + 1;
    }
    const danmaku = await apiGet(`/api/videos/danmaku?videoId=${encodeURIComponent(videoId)}`).catch(() => []);
    X.videos.danmaku = danmaku || [];
    const recommended = getRecommendedVideos(video);
    const layout = q('videoWatchLayout');
    if (layout) layout.classList.remove('no-player');
    const related = q('videoRelatedRail');
    if (related) {
      related.classList.remove('hidden');
      related.innerHTML = `<div class='yt-related-title'>相关推荐</div>${recommended.length ? recommended.map(renderRelatedVideoItem).join('') : "<div class='yt-empty small'>暂无推荐视频</div>"}`;
    }
    const playerWrap = q('videoPlayerWrap');
    if (!playerWrap) return;
    playerWrap.classList.remove('hidden');
    playerWrap.innerHTML = `<div class='yt-player-card'><div class='x-video-wrap yt-video-wrap'><video id='videoMainEl' autoplay src='${esc(video.filePath || '')}' onplay='startVideoDanmaku()' preload='metadata'></video><div id='videoDanmakuLayer' class='x-danmaku-layer'></div><button class='x-video-play-center' id='videoCenterPlay' onclick='toggleVideoPlay()'>▶</button><div class='x-video-controls' id='videoControls'><div class='x-video-controls-row x-video-progress-row'><div class='x-video-progress-wrap' id='videoProgressWrap'><div class='x-video-progress-bg'><div class='x-video-progress-buffered' id='videoBufferedBar'></div><div class='x-video-progress-fill' id='videoProgressFill'></div><div class='x-video-progress-thumb' id='videoProgressThumb'></div></div></div></div><div class='x-video-controls-row'><button class='x-video-btn' id='videoPlayBtn' onclick='toggleVideoPlay()' title='播放/暂停'><span id='videoPlayIcon'>暂停</span></button><span class='x-video-time'><span id='videoCurrentTime'>0:00</span> / <span id='videoDuration'>0:00</span></span><div class='x-video-volume-wrap'><button class='x-video-btn' id='videoMuteBtn' onclick='toggleVideoMute()' title='静音'>音量</button><div class='x-video-volume-slider' id='videoVolumeSlider'><input type='range' id='videoVolumeRange' min='0' max='1' step='0.05' value='1' oninput='setVideoVolume(this.value)'></div></div><div class='x-video-controls-spacer'></div><button class='x-video-btn' id='videoSpeedBtn' onclick='toggleSpeedPopup()' title='倍速'>${X.videos.playbackRate || 1}x</button><div class='x-video-speed-popup' id='videoSpeedPopup'><button class='x-video-speed-opt' data-speed='0.5' onclick='setVidSpeed(\"0.5\")'>0.5x</button><button class='x-video-speed-opt' data-speed='0.75' onclick='setVidSpeed(\"0.75\")'>0.75x</button><button class='x-video-speed-opt active' data-speed='1' onclick='setVidSpeed(\"1\")'>1.0x</button><button class='x-video-speed-opt' data-speed='1.25' onclick='setVidSpeed(\"1.25\")'>1.25x</button><button class='x-video-speed-opt' data-speed='1.5' onclick='setVidSpeed(\"1.5\")'>1.5x</button><button class='x-video-speed-opt' data-speed='2' onclick='setVidSpeed(\"2\")'>2.0x</button></div><button class='x-video-btn' id='videoDanmakuBtn' onclick='toggleVideoDanmaku()' title='弹幕开关'>弹幕</button><button class='x-video-btn' onclick='captureVideoFrame()' title='截图'>截图</button><button class='x-video-btn' onclick='toggleBrowserPictureInPicture()' title='画中画'>小窗</button><button class='x-video-btn x-video-full-btn' id='videoFullscreenBtn' onclick='toggleVideoFullscreen()' title='全屏'>全屏</button></div></div></div><div class='yt-title-row'><div><h2>${esc(video.title || '')}</h2><p>${esc(videoCategoryName(video.categoryId))} · ${Number(video.playCount || 0).toLocaleString('zh-CN')} 次播放 · 第 ${Math.min(currentVideoIndex() + 1, Math.max((X.videos.currentList || []).length, 1))}/${Math.max((X.videos.currentList || []).length, 1)}</p></div><div class='yt-actions'><button onclick='switchVideoByOffset(-1)'>上一个</button><button onclick='switchVideoByOffset(1)'>下一个</button><button onclick='sendVideoDanmakuPrompt()'>弹幕</button><button onclick='minimizeCurrentVideo()'>站内小窗</button><button onclick="shareVideoById('${esc(video.id)}')">分享</button></div></div><div class='yt-description'>${esc(video.description || '暂无简介')}</div><div class='yt-comment-box'><textarea id='videoCommentInput' class='x-textarea' placeholder='添加评论...'></textarea><button onclick='submitVideoComment()'>评论</button></div><div class='yt-comments-panel' id='videoCommentsPanel'>${renderVideoComments()}</div></div>`;
    document.querySelectorAll('.yt-card').forEach(el => el.classList.toggle('active', el.getAttribute('onclick') && el.getAttribute('onclick').includes(videoId)));
    const player = q('videoMainEl');
    if (player) player.playbackRate = Number(X.videos.playbackRate || 1);
    setTimeout(() => {
      initVideoPlayer();
      try { playerWrap.scrollIntoView({ block: 'start', behavior: 'smooth' }); } catch (e) {}
      maybeLoadMoreVideoComments();
    }, 100);
  };
  window.switchVideoByOffset = function (step) {
    const list = (X.videos.currentList || []).slice();
    if (!list.length) return;
    const nextId = list[(currentVideoIndex() + step + list.length) % list.length];
    if (nextId) playVideoById(nextId);
  };
  window.setVideoPlaybackRate = function (value) {
    const video = q('videoMainEl');
    const rate = Number(value || 1);
    if (!video || !Number.isFinite(rate) || rate <= 0) return;
    video.playbackRate = rate;
    X.videos.playbackRate = rate;
    const mini = q('miniVideoEl');
    if (mini && !mini.paused) mini.playbackRate = rate;
    toast(`已切换到 ${rate}x`);
  };
  window.submitVideoComment = async function () {
    const input = q('videoCommentInput');
    const content = input ? input.value.trim() : '';
    if (!content) return toast('请输入评论内容', true);
    try {
      await apiPost('/api/videos/comment', { videoId: X.videos.currentVideoId, content });
      if (input) input.value = '';
      await loadVideoComments(true, true);
      toast('评论已发送');
    } catch (e) {
      toast(e.message || '发送失败', true);
    }
  };
  window.shareVideoById = async function (videoId) {
    const link = `${location.origin}/share/video/${encodeURIComponent(videoId)}`;
    await copyText(link, '视频链接已复制');
    try { await maybeSendShareCard('video', videoId, '视频分享卡片已发送'); } catch (e) { toast(e.message || '发送卡片失败', true); }
  };
  window.createVideoCategoryPrompt = async function () { const name = await window.showPrompt('请输入新栏目名称：', '新栏目'); if (name === null || !name.trim()) return; try { await apiPost('/api/videos/create-category', { name: name.trim() }); toast('栏目已创建'); videosRefresh(); } catch (e) { toast(e.message || '创建失败', true); } };
  window.uploadVideoPrompt = async function () {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'video/*,.mp4,.mov,.mkv,.webm';
    input.onchange = async () => {
      const file = input.files && input.files[0];
      if (!file) return;
      try {
        const uploaded = await storeLooseFile(file);
        const title = await window.showPrompt('视频标题：', file.name.replace(/\.[^.]+$/, '')) || file.name.replace(/\.[^.]+$/, '');
        const description = await window.showPrompt('视频简介：', '') || '';
        const categoryName = await window.showPrompt('所属栏目（不存在会自动新建）：', '默认栏目') || '默认栏目';
        let coverPath = '';
        if (await window.showConfirm('是否上传视频封面？')) {
          const coverInput = document.createElement('input');
          coverInput.type = 'file';
          coverInput.accept = 'image/*,.png,.jpg,.jpeg,.webp';
          await new Promise(resolve => {
            let settled = false;
            const done = () => {
              if (settled) return;
              settled = true;
              window.removeEventListener('focus', onFocus, true);
              resolve();
            };
            const onFocus = () => setTimeout(done, 200);
            coverInput.onchange = async () => {
              const coverFile = coverInput.files && coverInput.files[0];
              if (!coverFile) return done();
              try {
                const coverUploaded = await storeLooseFile(coverFile);
                coverPath = coverUploaded.filePath || '';
              } catch (e) {
                toast(e.message || '封面上传失败', true);
              }
              done();
            };
            window.addEventListener('focus', onFocus, true);
            coverInput.click();
          });
        }
        const category = await apiPost('/api/videos/create-category', { name: categoryName });
        await apiPost('/api/videos/upload', { title, description, categoryId: category.id, filePath: uploaded.filePath, coverPath });
        toast('视频已上传');
        videosRefresh();
      } catch (e) {
        toast(e.message || '上传失败', true);
      }
    };
    input.click();
  };

  let danmakuEnabled = true;
  window.toggleVideoDanmaku = function () { danmakuEnabled = !danmakuEnabled; toast(danmakuEnabled ? '已开启弹幕' : '已关闭弹幕'); };
  window.startVideoDanmaku = function () {
    const video = q('videoMainEl');
    const layer = q('videoDanmakuLayer');
    if (!video || !layer) return;
    layer.innerHTML = '';
    const list = (X.videos.danmaku || []).slice().sort((a, b) => Number(a.timeSec || 0) - Number(b.timeSec || 0));
    let idx = 0;
    const timer = setInterval(() => {
      if (!video || video.paused || !danmakuEnabled) return;
      while (idx < list.length && Number(list[idx].timeSec || 0) <= video.currentTime + 0.2) {
        const dm = list[idx++];
        const el = document.createElement('div');
        el.className = 'x-danmaku-item';
        el.textContent = dm.content || '';
        el.style.color = dm.color || '#ffffff';
        el.style.top = dm.position === 'bottom' ? '70%' : dm.position === 'middle' ? '40%' : `${8 + (idx % 8) * 7}%`;
        layer.appendChild(el);
        setTimeout(() => el.remove(), 8200);
      }
      if (idx >= list.length && video.ended) clearInterval(timer);
    }, 200);
  };
  window.sendVideoDanmakuPrompt = async function () {
    const presetColors = ['#ffffff','#ff0000','#00ff00','#0088ff','#ffaa00','#ff00ff','#00ffff','#ffff00','#ff6600','#88ff00'];
    let overlay = q('danmakuPromptOverlay');
    if (overlay) overlay.remove();
    overlay = document.createElement('div');
    overlay.id = 'danmakuPromptOverlay';
    overlay.className = 'x-danmaku-prompt';
    overlay.innerHTML = `<div class='x-danmaku-prompt-card'><h3>发送弹幕</h3><input id='danmakuContentInput' class='x-input' placeholder='输入弹幕内容' maxlength='50' autofocus><div><label class='x-sub'>颜色</label><div class='x-color-grid' id='danmakuColorGrid'>${presetColors.map((c, i) => `<div class='x-color-swatch${i === 0 ? ' active' : ''}' style='background:${c}' data-color='${c}' onclick='(function(el){document.querySelectorAll("#danmakuColorGrid .x-color-swatch").forEach(s=>s.classList.remove("active"));el.classList.add("active")})(this)'></div>`).join('')}<input id='danmakuCustomColor' class='x-color-custom' type='color' value='#ffffff' onchange='document.querySelectorAll("#danmakuColorGrid .x-color-swatch").forEach(s=>s.classList.remove("active"))'></div></div><div><label class='x-sub'>位置</label><select id='danmakuPositionSelect' class='x-select x-select-full'><option value='top'>顶部</option><option value='middle'>中间</option><option value='bottom'>底部</option></select></div><div class='x-toolbar'><button class='tb-btn' onclick='submitDanmakuFromPrompt()'>发送</button><button class='tb-btn' onclick='document.getElementById("danmakuPromptOverlay").remove()'>取消</button></div></div>`;
    document.body.appendChild(overlay);
    overlay.addEventListener('click', e => { if (e.target === overlay) overlay.remove(); });
    const input = q('danmakuContentInput');
    if (input) input.focus();
  };
  window.submitDanmakuFromPrompt = async function () {
    const content = q('danmakuContentInput');
    const activeSwatch = document.querySelector('#danmakuColorGrid .x-color-swatch.active');
    const customColor = q('danmakuCustomColor');
    const position = q('danmakuPositionSelect');
    if (!content || !content.value.trim()) { toast('请输入弹幕内容', true); return; }
    const color = activeSwatch ? activeSwatch.dataset.color : (customColor ? customColor.value : '#ffffff');
    const pos = position ? position.value : 'top';
    const video = q('videoMainEl');
    try {
      await apiPost('/api/videos/danmaku', { videoId: X.videos.currentVideoId, content: content.value.trim(), color, position: pos, timeSec: video ? video.currentTime : 0 });
      toast('弹幕已发送');
      const overlay = q('danmakuPromptOverlay');
      if (overlay) overlay.remove();
      playVideoById(X.videos.currentVideoId);
    } catch (e) { toast(e.message || '发送失败', true); }
  };
  window.captureVideoFrame = function () {
    const video = q('videoMainEl');
    if (!video) return;
    const canvas = document.createElement('canvas');
    canvas.width = video.videoWidth || 1280;
    canvas.height = video.videoHeight || 720;
    canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height);
    canvas.toBlob(async blob => {
      if (!blob) return toast('截图失败', true);
      const file = new File([blob], `视频截图-${Date.now()}.png`, { type: 'image/png' });
      try {
        const uploaded = await storeLooseFile(file);
        openPrev('image', withFileName(uploaded.filePath, file.name));
      } catch (e) {
        toast(e.message || '截图上传失败', true);
      }
    }, 'image/png');
  };
  window.minimizeCurrentVideo = function () {
    const main = q('videoMainEl');
    const mini = q('miniVideoPlayer');
    const miniEl = q('miniVideoEl');
    if (!main || !mini || !miniEl) return;
    mini.classList.remove('hidden');
    miniEl.src = main.currentSrc || main.src || '';
    miniEl.currentTime = main.currentTime || 0;
    miniEl.playbackRate = main.playbackRate || 1;
    miniEl.play().catch(() => {});
    q('miniVideoTitle').textContent = ((X.videos.list || []).find(item => item.id === X.videos.currentVideoId) || {}).title || '小窗播放';
  };
  window.toggleBrowserPictureInPicture = async function () {
    const video = q('videoMainEl');
    if (!video || !document.pictureInPictureEnabled || typeof video.requestPictureInPicture !== 'function') {
      return toast('当前浏览器不支持画中画', true);
    }
    try {
      if (document.pictureInPictureElement === video) {
        await document.exitPictureInPicture();
        toast('已退出画中画');
      } else {
        await video.requestPictureInPicture();
        toast('已进入画中画');
      }
    } catch (e) {
      toast(e.message || '画中画切换失败', true);
    }
  };
  window.restoreMiniVideo = function () {
    const mini = q('miniVideoEl');
    const main = q('videoMainEl');
    if (mini && main) {
      main.currentTime = mini.currentTime || 0;
      main.playbackRate = mini.playbackRate || 1;
      main.play().catch(() => {});
    }
    closeMiniVideo();
  };
  window.closeMiniVideo = function () {
    const node = q('miniVideoPlayer');
    const el = q('miniVideoEl');
    if (el) { el.pause(); el.src = ''; }
    if (node) node.classList.add('hidden');
  };

  // ===== Custom Video Player Controls =====
  let videoControlsTimer = null;
  let _vpKeyHandler = null;
  window.initVideoPlayer = function () {
    const wrap = q('videoMainEl');
    if (!wrap) return;
    const video = wrap;
    const controls = q('videoControls');
    if (!controls) return;
    // Remove old keydown listener if re-initializing
    if (_vpKeyHandler) document.removeEventListener('keydown', _vpKeyHandler);
    const showControls = () => {
      controls.classList.remove('hidden');
      clearTimeout(videoControlsTimer);
      videoControlsTimer = setTimeout(() => { if (!video.paused) controls.classList.add('hidden'); }, 3000);
    };
    const hideControlsNow = () => { controls.classList.add('hidden'); clearTimeout(videoControlsTimer); };
    video.addEventListener('mouseenter', showControls);
    video.addEventListener('mouseleave', () => { if (!video.paused) setTimeout(() => { if (!controls.matches(':hover')) hideControlsNow(); }, 500); });
    controls.addEventListener('mouseenter', showControls);
    controls.addEventListener('mouseleave', () => { if (!video.paused) setTimeout(() => { if (!controls.matches(':hover')) hideControlsNow(); }, 500); });
    video.addEventListener('mousemove', showControls);
    video.addEventListener('play', () => { wrap.parentElement.classList.remove('paused'); q('videoPlayIcon').textContent = '暂停'; showControls(); });
    video.addEventListener('pause', () => { wrap.parentElement.classList.add('paused'); q('videoPlayIcon').textContent = '播放'; controls.classList.remove('hidden'); clearTimeout(videoControlsTimer); });
    video.addEventListener('timeupdate', updateVideoProgress);
    video.addEventListener('loadedmetadata', updateVideoProgress);
    video.addEventListener('progress', updateVideoBuffered);
    video.addEventListener('volumechange', () => syncVideoMuteButton(video));
    bindVideoSeekDrag(video);
    syncVideoMuteButton(video);
    _vpKeyHandler = function (e) {
      if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA' || e.target.contentEditable === 'true') return;
      const vid = q('videoMainEl');
      if (!vid || vid !== document.activeElement && !vid.contains(e.target) && e.target !== document.body && !e.target.closest('.x-video-wrap')) return;
      if (e.code === 'Space') { e.preventDefault(); toggleVideoPlay(); }
      else if (e.code === 'ArrowLeft') { e.preventDefault(); vid.currentTime = Math.max(0, vid.currentTime - 5); }
      else if (e.code === 'ArrowRight') { e.preventDefault(); vid.currentTime = Math.min(vid.duration || 0, vid.currentTime + 5); }
      else if (e.code === 'ArrowUp') { e.preventDefault(); vid.volume = Math.min(1, (vid.volume || 0) + 0.1); }
      else if (e.code === 'ArrowDown') { e.preventDefault(); vid.volume = Math.max(0, (vid.volume || 0) - 0.1); }
      else if (e.code === 'KeyF') { e.preventDefault(); toggleVideoFullscreen(); }
      else if (e.code === 'KeyM') { e.preventDefault(); toggleVideoMute(); }
    };
    document.addEventListener('keydown', _vpKeyHandler);
    updateVideoProgress();
    setTimeout(showControls, 500);
  };
  window.toggleVideoPlay = function () {
    const video = q('videoMainEl');
    if (!video) return;
    if (video.paused) video.play().catch(() => {}); else video.pause();
  };
  window.seekVideo = function (e) {
    const video = q('videoMainEl');
    const wrap = q('videoProgressWrap');
    if (!video || !wrap || !video.duration) return;
    const rect = wrap.getBoundingClientRect();
    const pct = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    video.currentTime = pct * video.duration;
  };
  function bindVideoSeekDrag(video) {
    const wrap = q('videoProgressWrap');
    if (!video || !wrap || wrap.dataset.dragBound === '1') return;
    wrap.dataset.dragBound = '1';
    let dragging = false;
    const seekFromPointer = e => {
      if (!video.duration) return;
      const point = e.touches && e.touches[0] ? e.touches[0] : e;
      const rect = wrap.getBoundingClientRect();
      const pct = Math.max(0, Math.min(1, (point.clientX - rect.left) / Math.max(rect.width, 1)));
      video.currentTime = pct * video.duration;
      updateVideoProgress();
    };
    wrap.addEventListener('pointerdown', e => {
      e.preventDefault();
      e.stopPropagation();
      dragging = true;
      try { wrap.setPointerCapture(e.pointerId); } catch (_) {}
      seekFromPointer(e);
    });
    wrap.addEventListener('pointermove', e => {
      if (!dragging) return;
      e.preventDefault();
      seekFromPointer(e);
    });
    const stop = e => {
      if (!dragging) return;
      dragging = false;
      try { wrap.releasePointerCapture(e.pointerId); } catch (_) {}
    };
    wrap.addEventListener('pointerup', stop);
    wrap.addEventListener('pointercancel', stop);
  }
  window.updateVideoProgress = function () {
    const video = q('videoMainEl');
    if (!video || !video.duration) return;
    const pct = (video.currentTime / video.duration) * 100;
    const fill = q('videoProgressFill');
    const thumb = q('videoProgressThumb');
    const cur = q('videoCurrentTime');
    const dur = q('videoDuration');
    if (fill) fill.style.width = pct + '%';
    if (thumb) thumb.style.left = pct + '%';
    if (cur) cur.textContent = fmtVidTime(video.currentTime);
    if (dur) dur.textContent = fmtVidTime(video.duration);
  };
  window.updateVideoBuffered = function () {
    const video = q('videoMainEl');
    const buf = q('videoBufferedBar');
    if (!video || !buf || !video.buffered || !video.buffered.length || !video.duration) return;
    const end = video.buffered.end(video.buffered.length - 1);
    buf.style.width = (end / video.duration * 100) + '%';
  };
  window.toggleVideoMute = function () {
    const video = q('videoMainEl');
    if (!video) return;
    video.muted = !video.muted;
    syncVideoMuteButton(video);
  };
  window.setVideoVolume = function (val) {
    const video = q('videoMainEl');
    if (!video) return;
    video.muted = false;
    video.volume = Number(val);
    syncVideoMuteButton(video);
  };
  function syncVideoMuteButton(video) {
    const btn = q('videoMuteBtn');
    if (!btn || !video) return;
    btn.textContent = video.muted || video.volume === 0 ? '已静音' : '音量';
  }
  window.toggleSpeedPopup = function () {
    const popup = q('videoSpeedPopup');
    if (!popup) return;
    popup.classList.toggle('open');
  };
  window.setVidSpeed = function (speed) {
    const video = q('videoMainEl');
    const rate = Number(speed);
    if (!video || !Number.isFinite(rate) || rate <= 0) return;
    video.playbackRate = rate;
    X.videos.playbackRate = rate;
    const btn = q('videoSpeedBtn');
    if (btn) btn.textContent = rate + 'x';
    const popup = q('videoSpeedPopup');
    if (popup) { popup.classList.remove('open'); popup.querySelectorAll('.x-video-speed-opt').forEach(el => el.classList.toggle('active', Number(el.dataset.speed) === rate)); }
  };
  window.toggleVideoFullscreen = function () {
    const wrap = q('videoMainEl');
    if (!wrap) return;
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {}); else wrap.parentElement.parentElement.requestFullscreen().catch(() => {});
  };
  function fmtVidTime(t) {
    if (!t || !Number.isFinite(t)) return '0:00';
    const m = Math.floor(t / 60);
    const s = Math.floor(t % 60);
    return m + ':' + (s < 10 ? '0' : '') + s;
  }

  // Override setVideoPlaybackRate to also update custom controls
  const origSetRate = window.setVideoPlaybackRate;
  window.setVideoPlaybackRate = function (value) {
    const video = q('videoMainEl');
    const rate = Number(value || 1);
    if (!video || !Number.isFinite(rate) || rate <= 0) return;
    video.playbackRate = rate;
    X.videos.playbackRate = rate;
    if (origSetRate) origSetRate(value);
    const btn = q('videoSpeedBtn');
    if (btn) btn.textContent = rate + 'x';
    const popup = q('videoSpeedPopup');
    if (popup) { popup.querySelectorAll('.x-video-speed-opt').forEach(el => el.classList.toggle('active', Number(el.dataset.speed) === rate)); }
  };

  const oldOpenPrev = window.openPrev;
  if (oldOpenPrev) {
    window.openPrev = function (type, src, name) {
      const fileName = String(name || src || '').toLowerCase();
      const absoluteSrc = src ? new URL(src, location.origin).href : '';
      X.preview.src = src || '';
      X.preview.name = name || (src ? src.split('/').pop() : '预览文件');
      X.preview.rotation = 0;
      X.preview.scaleX = 1;
      X.preview.cropSquare = false;
      if (type === 'audio') {
        togglePreviewTools(false);
        return openFramePreview(src, `<!doctype html><html><head><meta charset="utf-8"><title>${esc(name || '音频预览')}</title><style>body{margin:0;font-family:"Microsoft YaHei",sans-serif;background:#f8fafc;display:flex;align-items:center;justify-content:center;min-height:100vh}main{width:min(680px,92vw);background:#fff;border-radius:20px;padding:28px;box-shadow:0 24px 60px rgba(15,23,42,.12)}h1{margin:0 0 14px;font-size:20px;color:#0f172a}audio{width:100%}a{display:inline-block;margin-top:14px;color:#2563eb;text-decoration:none}</style></head><body><main><h1>${esc(name || '音频预览')}</h1><audio controls autoplay src="${esc(src || '')}"></audio><a href="${esc(src || '')}" download>下载音频</a></main></body></html>`, true);
      }
      if (type === 'pdf' || /\.pdf$/i.test(fileName)) {
        togglePreviewTools(false);
        return openFramePreview(src, absoluteSrc, false);
      }
      if (type === 'office' || /\.(doc|docx|ppt|pptx|xls|xlsx)$/i.test(fileName)) {
        togglePreviewTools(false);
        if (location.hostname === 'localhost' || location.hostname === '127.0.0.1' || location.hostname === '0.0.0.0') {
          toast('Office 预览需要公网地址，本地环境请下载文件查看', true);
          return oldOpenPrev ? oldOpenPrev('file', src, name) : (window.open(src, '_blank'));
        }
        return openFramePreview(src, `https://view.officeapps.live.com/op/embed.aspx?src=${encodeURIComponent(absoluteSrc)}`, false);
      }
      const result = oldOpenPrev(type, src, name);
      togglePreviewTools(type === 'image');
      return result;
    };
  }
  const oldClosePrev = window.closePrev;
  if (oldClosePrev) {
    window.closePrev = function () {
      if (typeof deactivateGameImmersive === 'function') deactivateGameImmersive();
      const frame = q('prevFrame');
      if (frame && frame.dataset.extraUrl) {
        URL.revokeObjectURL(frame.dataset.extraUrl);
        delete frame.dataset.extraUrl;
      }
      const tools = q('prevExtraTools');
      if (tools) tools.classList.add('hidden');
      oldClosePrev();
    };
  }
  window.downloadPreviewAsset = function () {
    if (!X.preview.src) return;
    const a = document.createElement('a');
    a.href = X.preview.src;
    a.download = X.preview.name || 'download';
    document.body.appendChild(a);
    a.click();
    a.remove();
  };
  window.rotatePreviewImage = function () {
    X.preview.rotation = (Number(X.preview.rotation || 0) + 90) % 360;
    syncPreviewImageTransform();
  };
  window.flipPreviewImage = function () {
    X.preview.scaleX = Number(X.preview.scaleX || 1) * -1;
    syncPreviewImageTransform();
  };
  window.resetPreviewImageTransform = function () {
    X.preview.rotation = 0;
    X.preview.scaleX = 1;
    X.preview.cropSquare = false;
    X.preview.brightness = 100;
    X.preview.contrast = 100;
    X.preview.saturate = 100;
    syncPreviewImageTransform();
    const b = q('prevBrightness'); if (b) b.value = 100;
    const c = q('prevContrast'); if (c) c.value = 100;
    const s = q('prevSaturate'); if (s) s.value = 100;
  };
  window.cropPreviewImage = function () {
    X.preview.cropSquare = !X.preview.cropSquare;
    syncPreviewImageTransform();
    toast(X.preview.cropSquare ? '已切换为中心方形裁剪' : '已取消裁剪');
  };
  window.exportPreviewImage = function () {
    if (!X.preview.src) return toast('没有可导出的图片', true);
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = function () {
      const rotation = ((Number(X.preview.rotation || 0) % 360) + 360) % 360;
      const cropSquare = !!X.preview.cropSquare;
      const srcW = img.naturalWidth || img.width;
      const srcH = img.naturalHeight || img.height;
      const cropSize = cropSquare ? Math.min(srcW, srcH) : 0;
      const sx = cropSquare ? Math.floor((srcW - cropSize) / 2) : 0;
      const sy = cropSquare ? Math.floor((srcH - cropSize) / 2) : 0;
      const drawW = cropSquare ? cropSize : srcW;
      const drawH = cropSquare ? cropSize : srcH;
      const canvas = document.createElement('canvas');
      if (rotation === 90 || rotation === 270) {
        canvas.width = drawH;
        canvas.height = drawW;
      } else {
        canvas.width = drawW;
        canvas.height = drawH;
      }
      const ctx = canvas.getContext('2d');
      ctx.save();
      ctx.filter = `brightness(${Number(X.preview.brightness || 100)}%) contrast(${Number(X.preview.contrast || 100)}%) saturate(${Number(X.preview.saturate || 100)}%)`;
      ctx.translate(canvas.width / 2, canvas.height / 2);
      ctx.rotate(rotation * Math.PI / 180);
      ctx.scale(Number(X.preview.scaleX || 1), 1);
      ctx.drawImage(img, sx, sy, drawW, drawH, -drawW / 2, -drawH / 2, drawW, drawH);
      ctx.restore();
      canvas.toBlob(blob => {
        if (!blob) return toast('导出失败', true);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        const base = String(X.preview.name || 'image').replace(/\.[^.]+$/, '');
        a.href = url;
        a.download = `${base}-编辑.png`;
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      }, 'image/png');
    };
    img.onerror = () => toast('图片加载失败，无法导出', true);
    img.src = X.preview.src;
  };

  window.openAi = function (navMode) {
    if (checkAndWarnFeatureBan('ai')) return;
    const alreadyActive = q('aiView') && q('aiView').classList.contains('active');
    if (X.ai._taskTimer) clearInterval(X.ai._taskTimer);
    if (poll) clearInterval(poll);
    prevView = 'aiView';
    sw('aiView', 'c-ai');
    updateRoute('/ai', navMode === undefined ? true : navMode);
    if (!alreadyActive || !q('aiRoot')?.firstChild) {
      X.ai.currentId = ""; X.ai.current = null; X.ai.messages = [];
      aiRefresh();
    } else {
      aiRefreshSidebar();
      // Restore streaming indicator if there are pending tasks
      restoreAiStreamingState();
    }
    startAiTaskTimer();
    poll = setInterval(() => {
      if (!q('aiView')?.classList.contains('active')) return;
      if (X.ai._streaming) return;
      aiRefreshSidebar();
    }, 5000);
  };
  function restoreAiStreamingState() {
    const tasks = X.ai.tasks || [];
    const runningTasks = tasks.filter(t => t.status === 'running' || t.status === 'queued');
    if (runningTasks.length > 0 && X.ai.currentId) {
      // Check if any message in current conversation is still streaming
      const msgs = X.ai.messages || [];
      const streamingMsg = msgs.find(m => m._streaming);
      if (streamingMsg) {
        X.ai._streaming = true;
        // Re-show generating indicator
        const msgsWrap = q('aiMessagesWrap');
        if (msgsWrap) {
          const existingIndicator = msgsWrap.querySelector('.ai-generating-indicator');
          if (!existingIndicator) {
            const indicator = document.createElement('div');
            indicator.className = 'ai-generating-indicator';
            indicator.innerHTML = '<span class="ai-spinner"></span>生成中...';
            msgsWrap.appendChild(indicator);
          }
        }
      }
    }
  }
  async function aiRefreshSidebar() {
    const saved = q('aiPromptInput')?.value || '';
    try {
      const [conversations, taskInfo] = await Promise.all([
        apiGet('/api/ai/conversations'),
        apiGet('/api/ai/tasks')
      ]);
      X.ai.conversations = conversations || [];
      X.ai.tasks = taskInfo.tasks || [];
      X.ai.remainingTokens = Number(taskInfo.remainingTokens || 0);
      const sideEl = q('aiRoot')?.querySelector('.ai-side');
      if (sideEl) sideEl.outerHTML = aiRenderSidebar();
      const badge = document.querySelector('.ai-badge');
      if (badge) badge.textContent = aiFormatTokens(X.ai.remainingTokens);
      startAiTaskTimer();
    } catch (_) {}
    const inp = q('aiPromptInput');
    if (inp && !inp.value) inp.value = saved;
  }
  function aiCurrentModel() {
    const sel = q('aiModelSelect');
    if (!sel) return null;
    return (X.ai.models || []).find(m => m.id === sel.value) || null;
  }
  function aiFormatTokens(n) {
    if (n < 0 || !Number.isFinite(n) || n === null || n === undefined) return '不限';
    return Math.round(n) + ' 点';
  }
  function aiRenderSidebar() {
    const searchTerm = (X.ai._convSearch || '').toLowerCase();
    const convs = X.ai.conversations || [];
    const filtered = searchTerm ? convs.filter(c => (c.title || '').toLowerCase().includes(searchTerm)) : convs;
    const convList = filtered.length ? filtered.map(c => {
      const active = c.id === X.ai.currentId;
      const icon = featureIcon(c.type === 'video' ? 'video' : 'ai', '');
      return `<div class='ai-conv-item${active ? ' active' : ''}' onclick="aiOpenConversation('${esc(c.id)}')">` +
        `<div class='ai-conv-icon'>${icon}</div>` +
        `<div class='ai-conv-info'><div class='ai-conv-title'>${esc(c.title || '新标签')}</div><div class='ai-conv-sub'>${esc(c.modelId || '')}</div></div>` +
        `<button class='ai-conv-del' onclick="event.stopPropagation();aiDeleteConversation('${esc(c.id)}')">×</button></div>`;
    }).join('') : emptyState('emptyMessages', searchTerm ? '没有匹配的对话' : '还没有 AI 对话', 'compact');
    const createButtons = [`<button class='ai-btn' onclick="aiCreateConversation('chat')">对话</button>`];
    if (ME && ME.aiImageUnlocked) createButtons.push(`<button class='ai-btn' onclick="aiCreateConversation('image')">图片</button>`);
    if (ME && ME.aiVideoUnlocked) createButtons.push(`<button class='ai-btn' onclick="aiCreateConversation('video')">视频</button>`);
    const lockedHint = (!ME || (!ME.aiImageUnlocked || !ME.aiVideoUnlocked))
      ? `<div class='ai-unlock-hint'>生成类权益会随等级解锁</div>` : '';
    return `<div class='ai-side'>` +
      `<div class='ai-side-header'><div class='ai-side-title'>AI 助手</div><div class='ai-badge'>${aiFormatTokens(X.ai.remainingTokens)}</div></div>` +
      `<div class='ai-conversation-search'><input class='x-input ai-conversation-search-input' id='aiConvSearchInput' type='search' placeholder='搜索对话...' value='${esc(X.ai._convSearch || '')}' oninput='X.ai._convSearch=this.value;aiRefreshSidebar()'></div>` +
      `<div class='ai-btn-row'>${createButtons.join('')}</div>${lockedHint}` +
      `<div class='ai-conv-list'>${convList}</div>` +
      `<div class='ai-tasks-card'><div class='ai-tasks-title'>最近任务</div>${renderAiTaskList(aiRecentTasks(6), false, '2 天内暂无任务')}</div></div>`;
  }
  function aiRenderSettingsPanel() {
    const cur = X.ai.current;
    if (!cur) return '';
    const models = (X.ai.models || []).filter(m => m.type === (cur.type || 'chat'));
    const selectedModel = models.find(m => m.id === cur.modelId) || models[0];
    if (selectedModel && cur.modelId !== selectedModel.id) cur.modelId = selectedModel.id;
    const modelOpts = models.map(m => {
      const ratio = m.outputRatio || m.inputRatio || 1;
      const ratioLabel = 'x' + ratio;
      const selected = m.id === cur.modelId ? 'selected' : '';
      return `<div class='ai-model-opt ${selected}' data-model-id='${esc(m.id)}' onclick='window.selectAiModel&&selectAiModel("${esc(m.id)}")'>`
        + `<div class='ai-model-name'>${esc(m.label || m.name || m.id)}</div>`
        + `<div class='ai-model-meta'>${ratioLabel} · ${esc(m.type || 'chat')}</div>`
        + `</div>`;
    }).join('') || `<div class='x-sub'>当前等级没有可用模型</div>`;
    const supportsThinking = selectedModel && selectedModel.supportsThinking;
    let reasoningHtml = '';
    if (supportsThinking) {
      reasoningHtml = `<div class='ai-field'><label>推理深度</label><select id='aiReasoningSelect' class='x-select'><option value='default'>默认</option><option value='minimal'>微念</option><option value='low'>浮想</option><option value='medium'>斟酌</option><option value='high'>沉思</option></select></div>`;
    }
    let typeSpecific = '';
    if (cur.type === 'image') {
      typeSpecific = `<div class='ai-row2'><div class='ai-field'><label>图片尺寸</label><select id='aiImageSizeInput' class='x-select'><option value='1920x1920'>1:1 小</option><option value='2048x2048'>1:1 大</option><option value='2400x1536'>16:10 横</option><option value='1536x2400'>10:16 竖</option></select></div><div class='ai-field'><label>风格</label><input id='aiImageStyleInput' class='x-input' value='${esc(cur.imageStyle || '通用')}' placeholder='通用/写实/插画'></div></div><div class='ai-field'><label>张数</label><input id='aiImageCountInput' class='x-input' type='number' min='1' max='4' value='${esc(cur.imageCount || 1)}'></div>`;
    }
    if (cur.type === 'video') {
      typeSpecific = `<div class='ai-row2'><div class='ai-field'><label>时长（秒）</label><input id='aiVideoDurationInput' class='x-input' type='number' min='2' max='12' value='${esc(cur.videoDuration || 5)}'></div><div class='ai-field'><label>分辨率</label><select id='aiVideoSizeInput' class='x-select'><option value='480p'>480p</option><option value='720p'>720p</option><option value='1080p'>1080p</option></select></div></div><div class='ai-row2'><div class='ai-field'><label>种子</label><input id='aiVideoSeedInput' class='x-input' type='number' value='${esc(cur.videoSeed || 0)}'></div><div class='ai-field'><label>首帧/尾帧</label><div class='ai-btn-row ai-frame-actions'><button class='ai-btn-sm' onclick='pickAiFrame("first")'>上传首帧</button><button class='ai-btn-sm' onclick='pickAiFrame("last")'>上传尾帧</button></div><div class='x-sub' id='aiVideoFramesHint'>${cur.videoFirstFramePath ? '✓首帧' : '未上传首帧'} · ${cur.videoLastFramePath ? '✓尾帧' : '未上传尾帧'}</div></div></div>`;
    }
    const streamLabel = cur.type === 'chat' || cur.type === 'image' ?
      `<label class='ai-toggle'><input id='aiStreamOutputToggle' type='checkbox' ${cur.streamOutput === false ? '' : 'checked'}><span>流式输出</span></label>` +
      (cur.type === 'chat' || cur.type === 'image' ? `<button class='ai-btn-sm' onclick='pickAiAttachment()'>${cur.type === 'image' ? '添加参考图' : '添加图片'}</button>` : '') : '';
    return `<aside class='ai-settings-panel hidden' id='aiSettingsPanel' aria-label='模型与会话设置'>` +
      `<div class='ai-row2'><div class='ai-field ai-field-grow'><label>模型 <span class='ai-field-hint'>(点击选择)</span></label><div id='aiModelSelector' class='ai-model-grid'>${modelOpts}</div><input type='hidden' id='aiModelSelect' value='${esc(selectedModel ? selectedModel.id : cur.modelId || '')}'></div>${reasoningHtml}</div>` +
      `<div class='ai-row2'><div class='ai-field'><label>上下文条数</label><input id='aiContextCountInput' class='x-input' type='number' min='1' max='50' value='${esc(cur.contextCount || 10)}'></div><div class='ai-field'><label>最大输出额度</label><input id='aiMaxTokensInput' class='x-input' type='number' min='0' value='${esc(cur.maxTokens || 0)}'></div></div>` +
      typeSpecific +
      `<div class='ai-toolbar'>${streamLabel}</div>` +
      `<div class='ai-field'><label>系统提示词</label><textarea id='aiSystemPromptInput' class='x-textarea ai-system-prompt'>${esc(cur.systemPrompt || '')}</textarea></div>` +
      `<div class='ai-settings-actions'><button class='ai-btn-sm' onclick='saveAiConversationSettings()'>保存设置</button></div></aside>`;
  }
  function aiRenderMain() {
    const cur = X.ai.current;
    if (!cur) return emptyState('emptyMessages', '选择或新建一个会话开始', 'ai-main-empty');
    const icon = featureIcon(cur.type === 'image' ? 'ai' : cur.type === 'video' ? 'video' : 'ai', '');
    return `<div class='ai-chat-head'>` +
      `<div class='ai-chat-head-icon'>${icon}</div>` +
      `<div class='ai-chat-head-info'><strong>${esc(cur.title || '新标签')}</strong><div class='x-sub'>${esc(cur.modelId || '')}</div></div>` +
      `<button class='ai-icon-btn' onclick='toggleAiSettings()' title='模型与会话设置'>设置</button></div>` +
      aiRenderSettingsPanel() +
      `<div id='aiMessagesWrap' class='ai-msgs'>${renderAiMessages()}</div>` +
      `<div class='ai-input-area'>${renderAiAttachmentBar()}<div class='ai-input-row'><textarea id='aiPromptInput' class='x-textarea' placeholder='输入提示词…' rows='3'></textarea><button id='aiSendBtn' class='ai-send-btn' onclick='sendAiPrompt()'>发送</button></div></div>`;
  }
  window.aiRefresh = async function () {
    const root = q('aiRoot');
    const savedPrompt = q('aiPromptInput')?.value || '';
    root.innerHTML = "<div class='x-card'>加载中…</div>";
    try {
      const [models, conversations, taskInfo] = await Promise.all([apiGet('/api/ai/models'), apiGet('/api/ai/conversations'), apiGet('/api/ai/tasks')]);
      X.ai.models = models || [];
      X.ai.conversations = conversations || [];
      X.ai.tasks = taskInfo.tasks || [];
      X.ai.remainingTokens = Number(taskInfo.remainingTokens || 0);
      X.ai.current = (X.ai.conversations || []).find(item => item.id === X.ai.currentId) || null;
      X.ai.messages = X.ai.currentId ? await apiGet(`/api/ai/messages?conversationId=${encodeURIComponent(X.ai.currentId)}`) : [];
      root.innerHTML = `<div class='ai-layout'>${aiRenderSidebar()}<div class='ai-main'>${aiRenderMain()}</div></div>`;
      const reasoning = q('aiReasoningSelect');
      if (reasoning && X.ai.current) reasoning.value = X.ai.current.reasoningDepth || 'default';
      const imageSize = q('aiImageSizeInput');
      if (imageSize && X.ai.current) imageSize.value = X.ai.current.imageSize || '1920x1920';
      const videoSize = q('aiVideoSizeInput');
      if (videoSize && X.ai.current) videoSize.value = X.ai.current.videoSize || '720p';
      const inp = q('aiPromptInput');
      if (inp) {
        if (savedPrompt) inp.value = savedPrompt;
        inp.addEventListener('keydown', function(e) { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendAiPrompt(); } });
      }
      const msgs = q('aiMessagesWrap');
      if (msgs) aiScrollToBottom(true);
    } catch (e) {
      root.innerHTML = `<div class='x-empty ui-error-state'>${esc(e.message || '加载失败')}</div>`;
    }
  };
  window.toggleAiSettings = function () {
    const panel = q('aiSettingsPanel');
    if (!panel) return;
    panel.classList.toggle('hidden');
  };
  window.onAiModelChange = function() {
    const cur = X.ai.current;
    if (!cur) return;
    const sel = q('aiModelSelect');
    if (sel) cur.modelId = sel.value;
    const settingsEl = document.querySelector('.ai-settings-panel');
    if (settingsEl) {
      const wasVisible = !settingsEl.classList.contains('hidden');
      const newSettings = document.createElement('div');
      newSettings.innerHTML = aiRenderSettingsPanel();
      settingsEl.replaceWith(newSettings.firstElementChild);
      const newPanel = q('aiSettingsPanel');
      if (newPanel && wasVisible) newPanel.classList.remove('hidden');
      const reasoning = q('aiReasoningSelect');
      if (reasoning && cur) reasoning.value = cur.reasoningDepth || 'default';
      const imageSize = q('aiImageSizeInput');
      if (imageSize && cur) imageSize.value = cur.imageSize || '1920x1920';
      const videoSize = q('aiVideoSizeInput');
      if (videoSize && cur) videoSize.value = cur.videoSize || '720p';
    }
  };
  window.selectAiModel = function(modelId) {
    const cur = X.ai.current;
    if (!cur || !modelId) return;
    cur.modelId = modelId;
    const hiddenInput = q('aiModelSelect');
    if (hiddenInput) hiddenInput.value = modelId;
    onAiModelChange();
    const grid = q('aiModelSelector');
    if (grid) {
      grid.querySelectorAll('.ai-model-opt').forEach(el => {
        el.classList.toggle('selected', el.dataset.modelId === modelId);
      });
    }
  };
  function renderAiAttachmentBar() {
    if (!(X.ai.attachments || []).length) return '';
    return `<div class='ai-attachment-bar'>${X.ai.attachments.map((item, index) => `<span class='x-badge ai-attachment-chip'>${esc(item.name || '图片')} <button class='tb-btn ai-attachment-remove' onclick='removeAiAttachment(${index})'>移除</button></span>`).join('')}</div>`;
  }
  function renderAiMessageRole(m) {
    const bits = [m.role === 'user' ? '你' : 'AI', fmtDate(m.createdAt)];
    if (m._streaming) bits.push('生成中');
    if (m._error) bits.push(m._error);
    if (m.weightedTokens) bits.push(`消耗 ${Math.round(Number(m.weightedTokens))}`);
    return bits.filter(Boolean).join(' · ');
  }
  function renderAiMessageBody(m) {
    if (m.type === 'image' && m.filePath) return `<img class='ai-message-media ai-message-image' src='${esc(m.filePath)}' alt='AI 生成图片'>`;
    if (m.type === 'video' && m.filePath) return `<video class='ai-message-media ai-message-video' controls src='${esc(m.filePath)}'></video>`;
    let html = '';
    // Show reasoning during streaming too
    if (m.reasoningContent || (m._streaming && m._taskPending)) {
      const reasoningText = m.reasoningContent || '';
      const isStreaming = m._streaming ? ' ai-reasoning-streaming' : '';
      html += `<details class='ai-reasoning${isStreaming}' ${m._streaming ? 'open' : ''}><summary>思考过程${m._streaming ? ' (生成中...)' : ''}</summary><div class='ai-reasoning-body'>${esc(reasoningText)}</div></details>`;
    }
    if (m.role === 'assistant') {
      html += `<div class='x-md-body'>${renderMarkdown(m.content || '')}</div>`;
    } else {
      html += `<div class='ai-user-message-text'>${esc(m.content || '')}</div>`;
    }
    return html;
  }
  function renderAiMessageItem(m) {
    const msgId = esc(m.id || m._draftId || '');
    const stateCls = `${m.role === 'user' ? 'user' : ''}${m._streaming ? ' ai-streaming' : ''}${m._error ? ' ai-error' : ''}`;
    const copyBtns = m.role === 'assistant' && !m._streaming && m.content
      ? `<div class='ai-copy-acts'><button class='tb-btn' onclick='copyAiMsg("${esc(m.id)}","md")'>Markdown</button><button class='tb-btn' onclick='copyAiMsg("${esc(m.id)}","text")'>纯文本</button></div>`
      : '';
    return `<div class='x-chat-msg ${stateCls.trim()}' data-ai-msg-id='${msgId}'><div class='role'>${esc(renderAiMessageRole(m))}</div><div class='ai-msg-body'>${renderAiMessageBody(m)}</div>${copyBtns}</div>`;
  }
  function renderAiMessages() {
    return (X.ai.messages || []).length ? X.ai.messages.map(renderAiMessageItem).join('') : emptyState('emptyMessages', '还没有消息', 'ai-message-empty');
  }
  function stripAiMD(t){return t.replace(/```[\s\S]*?```/g,'[代码]').replace(/`([^`]+)`/g,'$1').replace(/!\[.*?\]\(.*?\)/g,'[图片]').replace(/\[([^\]]*)\]\(.*?\)/g,'$1').replace(/[#*_~>|]+/g,'').replace(/-{3,}/g,'').replace(/^[-*+]\s/gm,'').replace(/^\d+\.\s/gm,'').replace(/\n{2,}/g,'\n').trim();}
  window.copyAiMsg = async function(msgId,mode){
    const m=(X.ai.messages||[]).find(x=>x.id===msgId||x._draftId===msgId);
    if(!m||!m.content)return toast('内容为空',true);
    const text=mode==='text'?stripAiMD(m.content):m.content;
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        try { await navigator.clipboard.writeText(text); }
        catch (e) { throw new Error('clipboard_failed'); }
      } else {
        throw new Error('no_clipboard_api');
      }
    } catch (e) {
      // fallback: textarea + execCommand
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.focus();
      ta.select();
      try {
        document.execCommand('copy');
        ta.remove();
      } catch (e2) {
        ta.remove();
        return toast('复制失败，请检查浏览器权限', true);
      }
    }
    toast(mode==='text'?'纯文本已复制':'Markdown 已复制');
  };
  function renderMarkdown(text) {
    if (!text) return '';
    if (typeof renderMD === 'function') {
      // 确保单换行在 marked 中渲染为 <br>（marked breaks:true 不一定可靠）
      // 用 GFM 行尾双空格语法处理单换行，保护代码块不被修改
      const cb = [];
      text = text.replace(/```[\s\S]*?```/g, m => { cb.push(m); return '\x00CB' + (cb.length - 1) + '\x00'; });
      text = text.replace(/(?<!\n)\n(?!\n)/g, '  \n');
      text = text.replace(/\x00CB(\d+)\x00/g, (_, i) => cb[+i]);
      return renderMD(text);
    }
    // 简单回退实现
    let html = esc(text);
    // 先处理代码块（防止内部被转义）
    const codeBlocks = [];
    html = html.replace(/```(\w*)\n([\s\S]*?)```/g, function(_, lang, code) {
      const idx = codeBlocks.length;
      codeBlocks.push(`<div class='x-md-code-block'><div class='x-md-code-header'><span>${esc(lang || '代码')}</span><button class='tb-btn x-md-copy-button' onclick='(function(btn){var c=btn.closest(".x-md-code-block").querySelector("code");navigator.clipboard.writeText(c.textContent).then(function(){btn.textContent="复制成功√";setTimeout(function(){btn.textContent="复制"},1000)})})(this)'>复制</button></div><code>${code}</code></div>`);
      return `\x00CODEBLOCK${idx}\x00`;
    });
    html = html.replace(/`([^`]+)`/g, '<code class="x-md-inline-code">$1</code>');
    html = html.replace(/^### (.+)$/gm, '<h4>$1</h4>');
    html = html.replace(/^## (.+)$/gm, '<h3>$1</h3>');
    html = html.replace(/^# (.+)$/gm, '<h2>$1</h2>');
    html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');
    html = html.replace(/^[-*] (.+)$/gm, '<li>$1</li>');
    html = html.replace(/(<li>[\s\S]*?<\/li>)/g, '<ul>$1</ul>');
    html = html.replace(/<\/ul>\s*<ul>/g, '');
    html = html.replace(/^> (.+)$/gm, '<blockquote>$1</blockquote>');
    html = html.replace(/<\/blockquote>\s*<blockquote>/g, '<br>');
    html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    // 换行处理：保留原始换行，用<br>替换
    html = html.replace(/\n/g, '<br>');
    // 恢复代码块
    codeBlocks.forEach((block, idx) => {
      html = html.replace(`\x00CODEBLOCK${idx}\x00`, block);
    });
    return html;
  }
  function aiTaskStatusText(status) {
    return ({
      queued: '排队中',
      running: '生成中',
      succeeded: '已完成',
      done: '已完成',
      failed: '失败',
      cancelled: '已取消',
      expired: '已超时'
    })[String(status || '')] || String(status || '未知');
  }
  function fmtAiWait(task) {
    const start = Number(task && task.createdAt || 0);
    if (!start) return '-';
    const end = ['done', 'succeeded', 'failed', 'cancelled', 'expired'].includes(String(task && task.status || ''))
      ? Number(task && task.updatedAt || start)
      : Date.now();
    const sec = Math.max(0, Math.round((end - start) / 1000));
    if (sec < 60) return `${sec} 秒`;
    if (sec < 3600) return `${Math.floor(sec / 60)} 分 ${sec % 60} 秒`;
    return `${Math.floor(sec / 3600)} 小时 ${Math.floor((sec % 3600) / 60)} 分`;
  }
  const AI_RECENT_TASK_WINDOW_MS = 2 * 24 * 60 * 60 * 1000;
  function aiRecentTasks(limit) {
    const cutoff = Date.now() - AI_RECENT_TASK_WINDOW_MS;
    return (X.ai.tasks || []).filter(t => {
      const createdAt = Number(t && t.createdAt || 0);
      const updatedAt = Number(t && t.updatedAt || 0);
      return Math.max(createdAt, updatedAt) >= cutoff;
    }).slice(0, limit || 6);
  }
  function renderAiTaskList(tasks, detailed, emptyText) {
    const list = (tasks || []).slice();
    if (!list.length) return `<div class='x-sub'>${esc(emptyText || (detailed ? '当前会话还没有任务' : '暂无任务'))}</div>`;
    return list.map(t => {
      const resultBtn = t.outputPath ? `<button class='tb-btn' onclick="${t.taskType === 'image' ? `openPrev('image','${esc(t.outputPath)}','AI 图片')` : `openPrev('video','${esc(t.outputPath)}','AI 视频')`}">查看结果</button>` : '';
      const tokenLine = detailed
        ? `预估 ${Math.round(Number(t.estimatedTokens || 0))} 点 · 实际 ${Math.round(Number(t.finalTokens || 0))} 点`
        : `耗用 ${Math.round(Number(t.finalTokens || t.estimatedTokens || 0))} 点`;
      return `<article class='x-card ai-task-card'><div class='x-kv'><strong>${esc(t.prompt || 'AI 任务')}</strong><span class='x-badge'>${esc(aiTaskStatusText(t.status))}</span></div><div class='x-sub ai-task-meta'>等待 <span class='ai-task-time' data-start='${Number(t.createdAt)||0}' data-status='${esc(t.status||'')}'>${fmtAiWait(t)}</span> · ${tokenLine}</div>${detailed ? `<div class='x-sub ai-task-model'>模型：${esc(t.modelId || '')}${t.providerTaskId ? ` · 任务号：${esc(t.providerTaskId)}` : ''}</div>` : ''}${resultBtn ? `<div class='ai-task-actions'>${resultBtn}</div>` : ''}</article>`;
    }).join('');
  }
  function startAiTaskTimer() {
    if (X.ai._taskTimer) clearInterval(X.ai._taskTimer);
    X.ai._taskTimer = setInterval(() => {
      document.querySelectorAll('.ai-task-time').forEach(el => {
        const start = Number(el.dataset.start || 0);
        const status = el.dataset.status || '';
        if (!start) return;
        const end = ['done','succeeded','failed','cancelled','expired'].includes(status) ? start : Date.now();
        const sec = Math.max(0, Math.round((end - start) / 1000));
        el.textContent = sec < 60 ? `${sec} 秒` : sec < 3600 ? `${Math.floor(sec/60)} 分 ${sec%60} 秒` : `${Math.floor(sec/3600)} 小时 ${Math.floor((sec%3600)/60)} 分`;
      });
    }, 1000);
  }
  function aiScrollToBottom(force) {
    const wrap = q('aiMessagesWrap');
    if (!wrap) return;
    if (!force && wrap.scrollTop < wrap.scrollHeight - wrap.clientHeight - 80) return;
    wrap.scrollTop = wrap.scrollHeight;
  }
  function rerenderAiMessages() {
    const wrap = q('aiMessagesWrap');
    if (!wrap) return;
    wrap.innerHTML = renderAiMessages();
    aiScrollToBottom();
  }
  function appendAiMessageNode(message) {
    const wrap = q('aiMessagesWrap');
    if (!wrap) return rerenderAiMessages();
    const empty = wrap.querySelector('.x-empty');
    if (empty) empty.remove();
    const shell = document.createElement('div');
    shell.innerHTML = renderAiMessageItem(message);
    const node = shell.firstElementChild;
    if (node) wrap.appendChild(node);
    aiScrollToBottom();
  }
  function scheduleAiMessageUpdate(message) {
    if (!message) return;
    if (message._rafScheduled) return;
    message._rafScheduled = true;
    requestAnimationFrame(() => {
      message._rafScheduled = false;
      const wrap = q('aiMessagesWrap');
      const key = message.id || message._draftId || '';
      const selector = `[data-ai-msg-id="${String(key).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"]`;
      const node = wrap && key ? wrap.querySelector(selector) : null;
      if (!node) return rerenderAiMessages();
      node.className = `x-chat-msg ${(message.role === 'user' ? 'user ' : '') + (message._streaming ? 'ai-streaming ' : '') + (message._error ? 'ai-error' : '')}`.trim();
      const role = node.querySelector('.role');
      const body = node.querySelector('.ai-msg-body');
      if (role) role.textContent = renderAiMessageRole(message);
      if (body) body.innerHTML = renderAiMessageBody(message);
      aiScrollToBottom();
    });
  }
  function aiConversationPreviewTitle(prompt) {
    const text = String(prompt || '').trim().replace(/\s+/g, ' ');
    if (!text) return '新标签';
    return text.length > 18 ? `${text.slice(0, 18)}...` : text;
  }
  window.aiOpenConversation = function (id) { X.ai.currentId = id; aiRefresh(); };
  window.aiDeleteConversation = async function (id) {
    if (!(await window.showConfirm('确定删除这个 AI 会话？所有消息和任务将被清除。'))) return;
    try {
      await apiPost('/api/ai/conversation/delete', { conversationId: id });
      if (X.ai.currentId === id) { X.ai.currentId = ''; X.ai.current = null; X.ai.messages = []; }
      toast('会话已删除');
      aiRefresh();
    } catch (e) {
      toast(e.message || '删除失败', true);
    }
  };
  window.aiCreateConversation = async function (type) {
    try {
      if (type === 'image' && !(ME && ME.aiImageUnlocked)) return toast('当前等级暂未解锁图片生成', true);
      if (type === 'video' && !(ME && ME.aiVideoUnlocked)) return toast('当前等级暂未解锁视频生成', true);
      if (!Array.isArray(X.ai.models) || !X.ai.models.length) {
        try { X.ai.models = await apiGet('/api/ai/models') || []; } catch (_) {}
      }
      const firstVisible = (X.ai.models || []).find(m => m.type === type);
      const defaults = { chat: 'deepseek-v4-flash', image: 'doubao-seedream-5-0-260128', video: 'doubao-seedance-1-0-pro-fast-251015' };
      const c = await apiPost('/api/ai/conversation/create', { type, modelId: (firstVisible && firstVisible.id) || defaults[type] || 'deepseek-v4-flash' });
      X.ai.attachments = [];
      X.ai.currentId = c.id;
      aiRefresh();
    } catch (e) {
      toast(e.message || '创建失败', true);
    }
  };
  window.pickAiAttachment = async function () {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.multiple = true;
    input.onchange = async () => {
      const files = Array.from(input.files || []);
      if (!files.length) return;
      try {
        for (const file of files) {
          const uploaded = await storeLooseFile(file);
          X.ai.attachments.push({ type: 'image', filePath: uploaded.filePath, name: uploaded.fileName || file.name });
        }
        aiRefresh();
      } catch (e) {
        toast(e.message || '图片上传失败', true);
      }
    };
    input.click();
  };
  window.removeAiAttachment = function (index) {
    X.ai.attachments.splice(index, 1);
    aiRefresh();
  };
  window.pickAiFrame = async function (kind) {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.onchange = async () => {
      const file = input.files && input.files[0];
      if (!file) return;
      try {
        const uploaded = await storeLooseFile(file);
        if (!X.ai.current) return;
        if (kind === 'first') X.ai.current.videoFirstFramePath = uploaded.filePath;
        else X.ai.current.videoLastFramePath = uploaded.filePath;
        aiRefresh();
      } catch (e) {
        toast(e.message || '上传失败', true);
      }
    };
    input.click();
  };
  window.saveAiConversationSettings = async function () {
    if (!X.ai.current) return;
    try {
      const reasoningEl = q('aiReasoningSelect');
      const payload = Object.assign({}, X.ai.current, {
        userId: ME.userId,
        modelId: q('aiModelSelect').value,
        reasoningDepth: reasoningEl ? reasoningEl.value : (X.ai.current.reasoningDepth || 'default'),
        contextCount: Number(q('aiContextCountInput') ? q('aiContextCountInput').value : X.ai.current.contextCount || 10),
        maxTokens: Number(q('aiMaxTokensInput') ? q('aiMaxTokensInput').value : X.ai.current.maxTokens || 0),
        systemPrompt: q('aiSystemPromptInput').value,
        streamOutput: !!(q('aiStreamOutputToggle') && q('aiStreamOutputToggle').checked),
        imageSize: q('aiImageSizeInput') ? q('aiImageSizeInput').value : X.ai.current.imageSize,
        imageStyle: q('aiImageStyleInput') ? q('aiImageStyleInput').value : X.ai.current.imageStyle,
        imageCount: Number(q('aiImageCountInput') ? q('aiImageCountInput').value : X.ai.current.imageCount || 1),
        videoDuration: Number(q('aiVideoDurationInput') ? q('aiVideoDurationInput').value : X.ai.current.videoDuration || 5),
        videoSize: q('aiVideoSizeInput') ? q('aiVideoSizeInput').value : X.ai.current.videoSize,
        videoSeed: Number(q('aiVideoSeedInput') ? q('aiVideoSeedInput').value : X.ai.current.videoSeed || 0),
        videoFirstFramePath: X.ai.current.videoFirstFramePath || '',
        videoLastFramePath: X.ai.current.videoLastFramePath || ''
      });
      await apiPost('/api/ai/conversation/update', payload);
      toast('AI 设置已保存');
      aiRefresh();
    } catch (e) {
      toast(e.message || '保存失败', true);
    }
  };
  window.sendAiPrompt = async function () {
    if (!X.ai.current) return;
    const prompt = q('aiPromptInput').value.trim();
    if (!prompt) return toast('请输入提示词', true);
    let userMsg = null;
    let assistantMsg = null;
    try {
      q('aiPromptInput').value = '';
      const useStream = X.ai.current.type === 'chat' && (!q('aiStreamOutputToggle') || q('aiStreamOutputToggle').checked);
      const videoDurationInput = q('aiVideoDurationInput');
      const rawVideoDuration = videoDurationInput ? Number(videoDurationInput.value) : Number(X.ai.current.videoDuration || 5);
      const normalizedVideoDuration = Number.isFinite(rawVideoDuration) ? Math.max(2, Math.min(12, Math.round(rawVideoDuration))) : 5;
      const payload = {
        conversationId: X.ai.current.id,
        prompt,
        modelId: q('aiModelSelect') ? q('aiModelSelect').value : X.ai.current.modelId,
        reasoningDepth: q('aiReasoningSelect') ? q('aiReasoningSelect').value : X.ai.current.reasoningDepth,
        stream: useStream,
        attachments: (X.ai.attachments || []).slice(),
        imageOptions: {
          size: q('aiImageSizeInput') ? q('aiImageSizeInput').value : X.ai.current.imageSize,
          style: q('aiImageStyleInput') ? q('aiImageStyleInput').value : X.ai.current.imageStyle,
          count: q('aiImageCountInput') ? q('aiImageCountInput').value : X.ai.current.imageCount
        },
        videoOptions: {
          duration: normalizedVideoDuration,
          size: q('aiVideoSizeInput') ? q('aiVideoSizeInput').value : X.ai.current.videoSize,
          seed: q('aiVideoSeedInput') ? q('aiVideoSeedInput').value : X.ai.current.videoSeed,
          firstFramePath: X.ai.current.videoFirstFramePath || '',
          lastFramePath: X.ai.current.videoLastFramePath || ''
        }
      };
      if (!useStream) {
        const result = await apiPost('/api/ai/send', payload);
        if (result && result.error) toast(result.error, true);
        else {
          X.ai.attachments = [];
          // 重新加载消息而不整页刷新
          X.ai.messages = await apiGet(`/api/ai/messages?conversationId=${encodeURIComponent(X.ai.currentId)}`);
          if (result && result.remainingTokens !== undefined) X.ai.remainingTokens = Number(result.remainingTokens);
          const badge = document.querySelector('.ai-badge');
          if (badge) badge.textContent = aiFormatTokens(X.ai.remainingTokens);
          rerenderAiMessages();
        }
        return;
      }
      const sendBtn = q('aiSendBtn');
      if (sendBtn) sendBtn.disabled = true;
      X.ai._streaming = true;
      const now = Date.now();
      const draftPrefix = `ai_draft_${now}_${Math.random().toString(36).slice(2, 7)}`;
      userMsg = { _draftId: `${draftPrefix}_u`, role: 'user', type: 'text', content: prompt, createdAt: now };
      assistantMsg = { _draftId: `${draftPrefix}_a`, role: 'assistant', type: 'text', content: '', reasoningContent: '', createdAt: now + 1, _streaming: true };
      X.ai.messages = (X.ai.messages || []).concat([userMsg, assistantMsg]);
      appendAiMessageNode(userMsg);
      appendAiMessageNode(assistantMsg);
      const controller = typeof AbortController === 'function' ? new AbortController() : null;
      let stallTimer = null;
      const resetStall = () => {
        if (stallTimer) clearTimeout(stallTimer);
        stallTimer = setTimeout(() => {
          if (controller) controller.abort();
        }, 20000);
      };
      resetStall();
      const res = await fetch('/api/ai/send-stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
        signal: controller ? controller.signal : undefined
      });
      resetStall();
      if (!res.ok || !res.body) {
        const data = await readApiResponse(res);
        throw new Error(resolveApiError(res, data, '发送失败'));
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';
      while (true) {
        const step = await reader.read();
        if (step.done) break;
        resetStall();
        buffer += decoder.decode(step.value, { stream: true });
        const lines = buffer.split(/\r?\n/);
        buffer = lines.pop() || '';
        for (const line of lines) {
          if (!line.trim()) continue;
          const evt = JSON.parse(line);
          if (evt.type === 'delta') {
            if (evt.reasoning_content) {
              assistantMsg.reasoningContent += String(evt.reasoning_content || '');
              scheduleAiMessageUpdate(assistantMsg);
            }
            if (evt.content) {
              assistantMsg.content += String(evt.content || '');
              scheduleAiMessageUpdate(assistantMsg);
            }
          } else if (evt.type === 'error') {
            throw new Error(evt.error || '发送失败');
          } else if (evt.type === 'done') {
            if (stallTimer) clearTimeout(stallTimer);
            if (evt.message && typeof evt.message === 'object') {
              assistantMsg.id = evt.message.id || assistantMsg.id;
              assistantMsg.createdAt = evt.message.createdAt || assistantMsg.createdAt;
              assistantMsg.weightedTokens = evt.message.weightedTokens || assistantMsg.weightedTokens;
              assistantMsg.content = evt.message.content || assistantMsg.content;
              if (evt.message.reasoningContent) assistantMsg.reasoningContent = evt.message.reasoningContent;
            }
            if (X.ai.current && X.ai.current.title === '新标签') {
              X.ai.current.title = aiConversationPreviewTitle(prompt);
              const ttl = document.querySelector('.ai-chat-head-info strong');
              if (ttl) ttl.textContent = X.ai.current.title;
            }
            X.ai.remainingTokens = Number(evt.remainingTokens || X.ai.remainingTokens || 0);
            X.ai.attachments = [];
            X.ai._streaming = false;
            assistantMsg._streaming = false;
            // 只更新侧边栏额度，不整页刷新
            const badge = document.querySelector('.ai-badge');
            if (badge) badge.textContent = aiFormatTokens(X.ai.remainingTokens);
            scheduleAiMessageUpdate(assistantMsg);
            if (sendBtn) sendBtn.disabled = false;
            return;
          }
        }
      }
      if (stallTimer) clearTimeout(stallTimer);
      X.ai.attachments = [];
      X.ai._streaming = false;
      assistantMsg._streaming = false;
      const badge2 = document.querySelector('.ai-badge');
      if (badge2) badge2.textContent = aiFormatTokens(X.ai.remainingTokens);
      scheduleAiMessageUpdate(assistantMsg);
      if (sendBtn) sendBtn.disabled = false;
    } catch (e) {
      X.ai._streaming = false;
      const sendBtn = q('aiSendBtn');
      if (sendBtn) sendBtn.disabled = false;
      const errorText = normalizeUiError(e, '发送失败');
      const promptInput = q('aiPromptInput');
      if (promptInput && !promptInput.value.trim()) promptInput.value = prompt;
      X.ai.messages = (X.ai.messages || []).filter(item => item !== userMsg && item !== assistantMsg);
      rerenderAiMessages();
      toast(errorText, true);
    }
  };

  async function uploadCurrentGameAsset(kind, file) {
    if (!file) return;
    if (kind === 'cover') {
      if (file.size > 3 * 1024 * 1024) return toast('封面图片不能超过 3MB', true);
      if (!file.type.startsWith('image/')) return toast('封面必须是图片文件', true);
    } else {
      if (file.size > 10 * 1024 * 1024) return toast('预览视频不能超过 10MB', true);
      if (!file.type.startsWith('video/')) return toast('预览必须是视频文件', true);
      try {
        const url = URL.createObjectURL(file);
        const v = document.createElement('video');
        v.preload = 'metadata';
        const valid = await new Promise(res => { v.onloadedmetadata = () => { URL.revokeObjectURL(url); res(v.duration <= 15); }; v.onerror = () => { URL.revokeObjectURL(url); res(false); }; v.src = url; });
        if (!valid) return toast('预览视频不能超过 15 秒', true);
      } catch (e) { return toast('视频校验失败: ' + e.message, true); }
    }
    try {
      const label = kind === 'cover' ? '封面上传' : '预览视频上传';
      toast(label + '中…');
      const uploaded = await storeLooseFile(file, ev => {
        if (ev.lengthComputable) {
          const pct = Math.round(ev.loaded / ev.total * 100);
          toast(label + '中… ' + pct + '%');
        }
      });
      const gid = window.selectedGameId;
      const game = window.gameMap && window.gameMap[gid];
      if (!game) return toast('请先选择一个小程序', true);
      const payload = { gameId: game.id };
      if (kind === 'cover') payload.coverPath = uploaded.filePath;
      else payload.previewVideoPath = uploaded.filePath;
      await apiPost('/api/games/upload-asset', payload);
      toast(kind === 'cover' ? '封面已更新' : '预览视频已更新');
      if (typeof loadGamesList === 'function') await loadGamesList();
    } catch (e) {
      toast(e.message || '上传失败', true);
    }
  }

  const oldOpenSelectedGameVersion = window.openSelectedGameVersion;
  if (oldOpenSelectedGameVersion) {
    window.openSelectedGameVersion = function () {
      oldOpenSelectedGameVersion();
      const tools = q('gameVersionDetailTools');
      const game = window.gameMap && window.gameMap[window.selectedGameId];
      // gameId already set via window.selectedGameId, upload handled by Java JS handlers
      if (!tools || !game) return;
      if (!q('gameImmersiveBtn')) {
        const btn = document.createElement('button');
        btn.id = 'gameImmersiveBtn';
        btn.className = 'tb-btn game-immersive-action';
        btn.textContent = '沉浸式游玩';
        btn.onclick = () => window.startSelectedGameImmersive && window.startSelectedGameImmersive();
        tools.appendChild(btn);
      }
      if (!game.canManage) return;
      if (!q('gameAssetTools')) {
        const wrap = document.createElement('div');
        wrap.id = 'gameAssetTools';
        wrap.className = 'game-asset-tools';
        wrap.innerHTML = "<button class='tb-btn' onclick=\"q('gameCoverInput').click()\">上传封面</button><button class='tb-btn' onclick=\"q('gamePreviewInput').click()\">上传预览</button>";
        tools.prepend(wrap);
      }
    };
  }

  function gameRouteKey(game) {
    const versions = Array.isArray(game && game.versions) ? game.versions.filter(Boolean) : [];
    const firstUpload = versions.reduce((min, item) => {
      const val = Number(item && item.uploadTime || 0);
      if (!val) return min;
      return !min || val < min ? val : min;
    }, 0);
    return String(firstUpload || game && game.createdAt || game && game.id || '');
  }

  const oldLaunchDownloadedGame = window.launchDownloadedGame;
  if (oldLaunchDownloadedGame) {
    window.launchDownloadedGame = function (blob, game, version, navMode) {
      if (window.gameImmersiveRequested) {
        const result = oldLaunchDownloadedGame(blob, game, version, navMode);
        const frame = q('prevFrame');
        const routeKey = gameRouteKey(game);
        if (frame && routeKey) frame.dataset.gameUrlKey = routeKey;
        if (routeKey && typeof updateRoute === 'function') updateRoute(`/game/${encodeURIComponent(routeKey)}`, navMode === undefined ? true : navMode);
        activateGameImmersive(game, version);
        return result;
      }
      return launchGameFloating(blob, game, version, navMode);
    };
  }
  function ensureGameFloatPlayer() {
    let panel = q('gameFloatPlayer');
    if (panel) return panel;
    panel = document.createElement('div');
    panel.id = 'gameFloatPlayer';
    panel.className = 'game-float-player';
    panel.innerHTML = `<div class="game-float-head"><div><strong id="gameFloatTitle">小程序</strong><span id="gameFloatVersion"></span></div><div class="game-float-actions"><button type="button" onclick="enterGameFloatImmersive()">全屏</button><button type="button" onclick="closeGameFloatPlayer()">×</button></div></div><iframe id="gameFloatFrame" class="game-float-frame" sandbox="allow-scripts allow-forms allow-modals allow-pointer-lock allow-downloads" src=""></iframe>`;
    document.body.appendChild(panel);
    if (typeof makeDraggableFloat === 'function') makeDraggableFloat(panel, panel.querySelector('.game-float-head'));
    return panel;
  }
  function launchGameFloating(blob, game, version, navMode) {
    const panel = ensureGameFloatPlayer();
    const frame = q('gameFloatFrame');
    if (!frame) return;
    if (frame.dataset.gameUrl) {
      URL.revokeObjectURL(frame.dataset.gameUrl);
      delete frame.dataset.gameUrl;
    }
    const url = URL.createObjectURL(blob);
    const routeKey = gameRouteKey(game);
    window.currentGameBlobUrl = url;
    window.currentFloatingGame = { game, version };
    frame.dataset.gameUrl = url;
    frame.dataset.gameUrlKey = routeKey;
    frame.dataset.gameId = game && game.id || '';
    frame.dataset.versionId = version && version.id || '';
    frame.setAttribute('sandbox', 'allow-scripts allow-forms allow-modals allow-pointer-lock allow-downloads');
    frame.src = url;
    const title = q('gameFloatTitle');
    const ver = q('gameFloatVersion');
    if (title) title.textContent = game && game.title || '小程序';
    if (ver) ver.textContent = version && version.version ? 'v' + version.version : '';
    panel.classList.remove('hidden');
    if (routeKey && typeof updateRoute === 'function') updateRoute(`/game/${encodeURIComponent(routeKey)}`, navMode === undefined ? true : navMode);
    toast('小程序已在小窗打开');
    return panel;
  }
  window.closeGameFloatPlayer = function () {
    const panel = q('gameFloatPlayer');
    const frame = q('gameFloatFrame');
    if (frame) {
      if (frame.dataset.gameUrl) URL.revokeObjectURL(frame.dataset.gameUrl);
      frame.src = 'about:blank';
    }
    if (panel) panel.remove();
    window.currentFloatingGame = null;
    window.currentGameBlobUrl = '';
  };
  window.enterGameFloatImmersive = function () {
    const panel = q('gameFloatPlayer');
    const floatFrame = q('gameFloatFrame');
    const ov = q('prevOv');
    const frame = q('prevFrame');
    if (!panel || !floatFrame || !floatFrame.dataset.gameUrl || !ov || !frame) return;
    ['prevImg', 'prevVideo', 'prevMd', 'prevFrame', 'prevMonaco'].forEach(id => q(id) && q(id).classList.add('hidden'));
    if (frame.dataset.gameUrl && frame.dataset.gameUrl !== floatFrame.dataset.gameUrl) {
      URL.revokeObjectURL(frame.dataset.gameUrl);
    }
    frame.dataset.gameUrl = floatFrame.dataset.gameUrl;
    frame.dataset.gameUrlKey = floatFrame.dataset.gameUrlKey || '';
    frame.dataset.gameId = floatFrame.dataset.gameId || '';
    frame.dataset.versionId = floatFrame.dataset.versionId || '';
    frame.setAttribute('sandbox', 'allow-scripts allow-forms allow-modals allow-pointer-lock allow-downloads');
    frame.src = floatFrame.dataset.gameUrl;
    frame.classList.remove('hidden');
    ov.classList.add('show');
    ov.onclick = e => { if (e.target === ov && typeof closePrev === 'function') closePrev(); };
    delete floatFrame.dataset.gameUrl;
    floatFrame.src = 'about:blank';
    panel.remove();
    const meta = window.currentFloatingGame || {};
    activateGameImmersive(meta.game || { title: '小程序' }, meta.version || {});
  };
  window.startSelectedGameImmersive = function () {
    window.gameImmersiveRequested = true;
    window.gameImmersiveActive = false;
    if (typeof startSelectedGame === 'function') startSelectedGame(false);
  };
  function activateGameImmersive(game, version) {
    window.gameImmersiveRequested = false;
    window.gameImmersiveActive = true;
    document.body.classList.add('game-immersive-active');
    const ov = q('prevOv');
    if (ov) ov.classList.add('game-immersive-ov');
    const runHud = q('gameRunHud');
    if (runHud) runHud.remove();
    ensureGameImmersiveHud(game, version);
    toast('已进入沉浸式游玩，期间不会接收聊天消息');
  }
  function deactivateGameImmersive() {
    window.gameImmersiveRequested = false;
    window.gameImmersiveActive = false;
    document.body.classList.remove('game-immersive-active');
    const ov = q('prevOv');
    if (ov) ov.classList.remove('game-immersive-ov');
    const hud = q('gameImmersiveHud');
    if (hud) hud.remove();
    const runHud = q('gameRunHud');
    if (runHud) runHud.remove();
  }
  function ensureGameRunHud(game, version) {
    const ov = q('prevOv');
    if (!ov) return;
    const old = q('gameRunHud');
    if (old) old.remove();
    const hud = document.createElement('div');
    hud.id = 'gameRunHud';
    hud.className = 'game-run-hud';
    hud.innerHTML = `<strong>${esc(game && game.title || '小程序')}</strong><span>${esc(version && version.version || '')}</span><button type='button' onclick='enterCurrentGameImmersive()'>全屏使用</button>`;
    ov.appendChild(hud);
  }
  function ensureGameImmersiveHud(game, version) {
    const ov = q('prevOv');
    if (!ov || q('gameImmersiveHud')) return;
    const hud = document.createElement('div');
    hud.id = 'gameImmersiveHud';
    hud.className = 'game-immersive-hud';
    hud.innerHTML = `<strong>${esc(game && game.title || '小程序')}</strong><span>${esc(version && version.version || '')}</span><button type='button' onclick='exitGameImmersive()'>退出全屏</button>`;
    ov.appendChild(hud);
  }
  window.enterCurrentGameImmersive = function () {
    const game = window.gameMap && window.gameMap[window.selectedGameId];
    const version = game && (typeof sortedGameVersions === 'function' ? sortedGameVersions(game) : []).find(v => v.id === window.selectedGameVersionId)
      || (game && typeof latestGameVersion === 'function' ? latestGameVersion(game) : null);
    activateGameImmersive(game || { title: '小程序' }, version || {});
  };
  window.exitGameImmersive = function () {
    deactivateGameImmersive();
    if (typeof closePrev === 'function') closePrev();
  };
  [['pollMsgs', true], ['pollUnread', true], ['refreshSidebar', true], ['showNotify', false], ['notifyMention', false]].forEach(([name, asyncFn]) => {
    const oldFn = window[name];
    if (typeof oldFn !== 'function') return;
    window[name] = asyncFn
      ? async function (...args) {
          if (window.gameImmersiveActive) return;
          return oldFn.apply(this, args);
        }
      : function (...args) {
          if (window.gameImmersiveActive) return;
          return oldFn.apply(this, args);
        };
  });

  const oldReopenGameByRoute = window.reopenGameByRoute;
  if (oldReopenGameByRoute) {
    window.reopenGameByRoute = async function (path, navMode) {
      const clean = String(path || '');
      const routeGameId = decodeURIComponent(clean.slice('/game/'.length).split('/')[0] || '');
      if (clean.startsWith('/game/') && Array.isArray(window.gamesCache) && routeGameId) {
        const game = window.gamesCache.find(item => String(gameRouteKey(item)) === routeGameId || String(item.id || '') === routeGameId);
        if (game) {
          window.selectedGameId = game.id;
          window.selectedGameVersionId = ((typeof latestGameVersion === 'function' ? latestGameVersion(game) : null) || {}).id || '';
          return startSelectedGame(true, navMode);
        }
      }
      return oldReopenGameByRoute(path, navMode);
    };
  }

  const oldDownloadSelectedGameSource = window.downloadSelectedGameSource;
  if (oldDownloadSelectedGameSource) {
    window.downloadSelectedGameSource = function () {
      const game = window.gameMap && window.gameMap[window.selectedGameId];
      if (!game) return toast('请先选择小程序', true);
      const version = (typeof sortedGameVersions === 'function' ? sortedGameVersions(game) : []).find(v => v.id === window.selectedGameVersionId)
        || (typeof latestGameVersion === 'function' ? latestGameVersion(game) : null);
      if (!version || !version.filePath) return toast('未找到源码文件', true);
      triggerBrowserDownload(buildDownloadUrl(withFileName(version.filePath, version.fileName || `${game.title || 'game'}.html`)), version.fileName || `${game.title || 'game'}.html`);
    };
  }

  const oldOpenChat = window.openChat;
  if (oldOpenChat) {
    window.openChat = function (rid, title, avContent, sub, isGroup, navMode) {
      const result = oldOpenChat(rid, title, avContent, sub, isGroup, navMode);
      const manageBtn = q('groupMgmtBtn');
      const manageText = manageBtn && manageBtn.querySelector('button');
      if (manageText && rid === 'public') manageText.textContent = '公共聊天室管理 ⚙️';
      closeMobileMore();
      if (X.mobile.enabled) setMobilePanel('content');
      return result;
    };
  }

  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) return;
    const video = q('videoMainEl');
    if (video && !video.paused) minimizeCurrentVideo();
  });

  window.loadAdminPublicroom = async function () {
    const box = q('adminPublicroom');
    if (!box) return;
    box.innerHTML = "<div class='admin-loading'>加载中...</div>";
    try {
      const [config, resp] = await Promise.all([apiGet('/api/public-room/config'), apiGet('/api/admin/users')]);
      const users = resp && resp.users ? resp.users : [];
      const admins = new Set(config.adminUserIds || []);
      const rows = users.map(u => {
        const isAdmin = admins.has(u.userId);
        const badge = isAdmin ? "<span class='sa-b'>管理员</span>" : "<span class='x-sub'>普通用户</span>";
        const action = u.userId === ME.userId
          ? "<span class='x-sub'>当前账号</span>"
          : isAdmin
            ? `<button class='tb-btn' onclick="removePublicRoomAdminPrompt('${esc(u.userId)}')">移除管理员</button>`
            : `<button class='tb-btn' onclick="addPublicRoomAdminPrompt('${esc(u.userId)}')">设为管理员</button>`;
        return `<tr><td><strong>${esc(u.nickname || '')}</strong><div class='x-sub'>@${esc(u.userId || '')}</div></td><td>${badge}</td><td>${Number(u.activeSessions || 0)}</td><td>${action}</td></tr>`;
      }).join('');
      box.innerHTML = `<div class='pc admin-public-room'><div class='admin-section-head'><div><h4>公共聊天室</h4><div class='x-sub'>管理员可禁言所有人、删除旧消息并维护公共聊天室秩序。</div></div><div class='admin-panel-actions'><button class='tb-btn' onclick="togglePublicRoomAllMute(${config.allMuted ? 'false' : 'true'})">${config.allMuted ? '关闭全员禁言' : '开启全员禁言'}</button><button class='tb-btn admin-action-danger' onclick='deletePublicRoomOldMessagesPrompt()'>删除旧消息</button></div></div><div class='admin-room-status'>当前状态：<span class='${config.allMuted ? 'danger' : 'safe'}'>${config.allMuted ? '已开启全员禁言' : '允许普通用户发言'}</span></div><div class='admin-table-scroll'><table class='admin-table'><thead><tr><th>用户</th><th>身份</th><th>在线会话</th><th>操作</th></tr></thead><tbody>${rows || "<tr><td colspan='4' class='x-sub'>暂无用户</td></tr>"}</tbody></table></div></div>`;
    } catch (e) {
      box.innerHTML = `<div class='admin-error'>${esc(e.message || '加载失败')}</div>`;
    }
  };
  window.togglePublicRoomAllMute = async function (allMuted) {
    try {
      await apiPost('/api/public-room/toggle-all-mute', { allMuted: !!allMuted });
      toast(allMuted ? '已开启全员禁言' : '已关闭全员禁言');
      loadAdminPublicroom();
    } catch (e) {
      toast(e.message || '操作失败', true);
    }
  };
  window.addPublicRoomAdminPrompt = async function (userId) {
    if (!userId) {
      const uid = await window.showPrompt('请输入要添加为公共聊天室管理员的用户 ID：');
      if (!uid || !uid.trim()) return;
      userId = uid.trim();
    }
    try {
      await apiPost('/api/public-room/add-admin', { targetUserId: userId });
      toast('已添加公共聊天室管理员');
      loadAdminPublicroom();
    } catch (e) {
      toast(e.message || '操作失败', true);
    }
  };
  window.removePublicRoomAdminPrompt = async function (userId) {
    if (!userId || !(await window.showConfirm(`确定移除 @${userId} 的公共聊天室管理员身份吗？`))) return;
    try {
      await apiPost('/api/public-room/remove-admin', { targetUserId: userId });
      toast('已移除公共聊天室管理员');
      loadAdminPublicroom();
    } catch (e) {
      toast(e.message || '操作失败', true);
    }
  };

  window.handleGroupManage = function () {
    if (window.curGrpId && typeof openGroupInfo === 'function') {
      openGroupInfo();
      return;
    }
    if (window.room === 'public' && typeof openPublicRoomInfo === 'function') {
      openPublicRoomInfo();
      return;
    }
  };

  function getMutedRooms() {
    try { return JSON.parse(localStorage.getItem('mutedRooms') || '[]'); } catch (e) { return []; }
  }
  function setMutedRoom(roomId, muted) {
    const list = getMutedRooms();
    if (muted && !list.includes(roomId)) list.push(roomId);
    if (!muted) { const idx = list.indexOf(roomId); if (idx >= 0) list.splice(idx, 1); }
    localStorage.setItem('mutedRooms', JSON.stringify(list));
  }
  window.isRoomMuted = function (roomId) {
    return getMutedRooms().includes(roomId || window.room);
  };

  function injectMuteToggleToGroupModal() {
    const content = q('gmContent');
    if (!content || !window.curGrpId) return;
    if (q('muteToggleRow')) return;
    const isMuted = isRoomMuted('group_' + window.curGrpId);
    const row = document.createElement('div');
    row.id = 'muteToggleRow';
    row.style.cssText = 'display:flex;align-items:center;justify-content:space-between;padding:10px 0;border-top:1px solid var(--in-bd);margin-top:10px';
    row.innerHTML = "<div><div style='font-size:13px;font-weight:600'>消息免打扰</div><div style='font-size:11px;color:var(--muted)'>开启后该群新消息不再播放提示音</div></div><label style='display:flex;align-items:center;gap:6px;cursor:pointer'><input type='checkbox' id='muteToggleCheck' " + (isMuted ? 'checked' : '') + " onchange='toggleRoomMute()'><span style='font-size:13px'>" + (isMuted ? '已开启' : '已关闭') + "</span></label>";
    content.appendChild(row);
  }

  window.toggleRoomMute = function () {
    const check = q('muteToggleCheck');
    if (!check || !window.curGrpId) return;
    const muted = check.checked;
    setMutedRoom('group_' + window.curGrpId, muted);
    const label = check.parentElement.querySelector('span');
    if (label) label.textContent = muted ? '已开启' : '已关闭';
    toast(muted ? '已开启免打扰' : '已关闭免打扰');
  };

  // 重写 showNotify 以支持免打扰
  const origShowNotify = window.showNotify;
  if (origShowNotify) {
    window.showNotify = function (msg, key) {
      if (!msg || !key) return;
      // 检查免打扰
      const mutedRooms = getMutedRooms();
      if (mutedRooms.includes(key)) return;
      return origShowNotify(msg, key);
    };
  }

  window.loadAdminGroups = async function () {
    const box = q('adminGroups');
    if (!box) return;
    box.innerHTML = "<div class='admin-loading'>加载中...</div>";
    try {
      const groups = await apiGet('/api/admin/groups');
      box.innerHTML = `<div class='admin-table-scroll'><table class='admin-table'><thead><tr><th>群聊</th><th>群主</th><th>成员</th><th>操作</th></tr></thead><tbody>${(groups || []).map(g => {
        const ownerBadges = `${g.ownerIsPrimarySuperAdmin ? " <span class='owner-b'>服主</span>" : ''}${g.ownerIsSuperAdmin ? " <span class='sa-b'>超管</span>" : ''}${g.ownerIsDeveloper ? " <span class='developer-b'>开发者</span>" : ''}`;
        const joinLabel = g.currentUserIsMember ? '打开群聊' : '加入群聊';
        return `<tr><td><strong>${esc(g.groupName || '')}</strong><div class='x-sub'>#${esc(g.groupId || '')}</div></td><td>${esc(g.ownerNickname || '')}${ownerBadges} <span class='x-sub'>@${esc(g.ownerId || '')}</span></td><td>${Number(g.memberCount || 0)} 人 / ${Number(g.adminCount || 0)} 管理员</td><td><div class='x-admin-inline'><button class='tb-btn' onclick="joinGroupAsSuperAdmin('${esc(g.groupId)}')">${joinLabel}</button><button class='tb-btn admin-action-danger' onclick="adminDeleteGroup('${esc(g.groupId)}')">删除群聊</button></div></td></tr>`;
      }).join('') || "<tr><td colspan='4' class='x-sub'>暂无群聊</td></tr>"}</tbody></table></div>`;
    } catch (e) {
      box.innerHTML = "<div class='admin-error'>加载失败</div>";
    }
  };
  window.joinGroupAsSuperAdmin = async function (groupId) {
    if (!groupId) return;
    try {
      const res = await apiPost('/api/group/join-as-admin', { groupId });
      toast(res && res.alreadyMember ? '已在群聊中' : '已加入群聊');
      if (typeof loadAdminGroups === 'function') loadAdminGroups();
      if (typeof loadGroups === 'function') await loadGroups();
      let group = null;
      if (Array.isArray(window.groupsCache)) {
        group = window.groupsCache.find(item => String(item.groupId) === String(groupId));
      }
      if (!group) {
        try {
          const info = await apiGet('/api/group/info?groupId=' + encodeURIComponent(groupId));
          group = { groupId, groupName: info.groupName || groupId, memberCount: (info.members || []).length, iconPath: info.iconPath || '' };
        } catch (e) {}
      }
      if (group && typeof openChat === 'function') {
        openChat('group_' + groupId, group.groupName || groupId, typeof groupAvatarContent === 'function' ? groupAvatarContent(group) : '<span style="font-size:16px">👥</span>', (group.memberCount || 0) + ' 人', true);
      }
    } catch (e) {
      toast(e.message || '加入失败', true);
    }
  };

  const groupUserPickerState = { title: '', items: [], selected: new Set(), search: '', onConfirm: null };
  function ensureGroupUserPicker() {
    if (q('groupUserPickerModal')) return;
    const node = document.createElement('div');
    node.id = 'groupUserPickerModal';
    node.className = 'group-user-picker hidden';
    node.innerHTML = `
      <div class="group-user-picker-backdrop" onclick="closeGroupUserPicker()"></div>
      <div class="group-user-picker-sheet">
        <div class="group-user-picker-head">
          <div>
            <strong id="groupUserPickerTitle">选择用户</strong>
            <span id="groupUserPickerCount">0 / 0</span>
          </div>
          <button type="button" onclick="closeGroupUserPicker()">×</button>
        </div>
        <input id="groupUserPickerSearch" class="group-user-picker-search" type="search" placeholder="搜索昵称、用户名或 ID">
        <div class="group-user-picker-tools">
          <button type="button" id="groupUserPickerSelectVisible">全选搜索结果</button>
          <button type="button" id="groupUserPickerClearVisible">清空搜索结果</button>
        </div>
        <div id="groupUserPickerList" class="group-user-picker-list"></div>
        <div class="group-user-picker-foot">
          <button type="button" onclick="closeGroupUserPicker()">取消</button>
          <button type="button" id="groupUserPickerConfirm">确认</button>
        </div>
      </div>`;
    document.body.appendChild(node);
    q('groupUserPickerSearch').addEventListener('input', e => {
      groupUserPickerState.search = e.target.value || '';
      renderGroupUserPicker();
    });
    q('groupUserPickerSelectVisible').onclick = () => {
      visibleGroupUserPickerItems().forEach(item => groupUserPickerState.selected.add(item.userId));
      renderGroupUserPicker();
    };
    q('groupUserPickerClearVisible').onclick = () => {
      visibleGroupUserPickerItems().forEach(item => groupUserPickerState.selected.delete(item.userId));
      renderGroupUserPicker();
    };
    q('groupUserPickerConfirm').onclick = async () => {
      const picked = groupUserPickerState.items.filter(item => groupUserPickerState.selected.has(item.userId));
      if (!picked.length) return toast('请至少选择一个用户', true);
      const fn = groupUserPickerState.onConfirm;
      if (typeof fn === 'function') await fn(picked);
    };
  }
  function normalizePickerUser(user) {
    return {
      userId: String(user && user.userId || ''),
      nickname: String(user && user.nickname || user && user.username || user && user.userId || ''),
      username: String(user && user.username || ''),
      avatarPath: user && user.avatarPath || '',
      isSuperAdmin: !!(user && user.isSuperAdmin),
      isPrimarySuperAdmin: !!(user && user.isPrimarySuperAdmin),
      isDeveloper: !!(user && user.isDeveloper)
    };
  }
  function visibleGroupUserPickerItems() {
    const kw = String(groupUserPickerState.search || '').trim().toLowerCase();
    const list = groupUserPickerState.items || [];
    if (!kw) return list;
    return list.filter(item => [item.userId, item.nickname, item.username].some(v => String(v || '').toLowerCase().includes(kw)));
  }
  function renderGroupUserPicker() {
    const list = q('groupUserPickerList');
    if (!list) return;
    const visible = visibleGroupUserPickerItems();
    const picked = groupUserPickerState.selected.size;
    const total = groupUserPickerState.items.length;
    const count = q('groupUserPickerCount');
    if (count) count.textContent = `${picked} / ${total}`;
    list.innerHTML = '';
    if (!visible.length) {
      list.innerHTML = '<div class="group-user-picker-empty">没有匹配的用户</div>';
      return;
    }
    visible.forEach(item => {
      const row = document.createElement('label');
      row.className = 'group-user-picker-row';
      const checked = groupUserPickerState.selected.has(item.userId);
      const av = item.avatarPath
        ? `<img src="${esc(item.avatarPath)}" alt="">`
        : `<span>${esc((item.nickname || item.userId || '?').slice(0, 1).toUpperCase())}</span>`;
      const badges = `${item.isPrimarySuperAdmin ? '<em class="owner-b">服主</em>' : ''}${item.isSuperAdmin ? '<em class="sa-b">超管</em>' : ''}${item.isDeveloper ? '<em class="developer-b">开发者</em>' : ''}`;
      row.innerHTML = `<input type="checkbox" value="${esc(item.userId)}" ${checked ? 'checked' : ''}><div class="group-user-picker-av">${av}</div><div class="group-user-picker-meta"><strong>${esc(item.nickname || item.userId)}${badges}</strong><small>@${esc(item.userId)}${item.username ? ' · ' + esc(item.username) : ''}</small></div>`;
      const input = row.querySelector('input');
      input.onchange = () => {
        if (input.checked) groupUserPickerState.selected.add(item.userId);
        else groupUserPickerState.selected.delete(item.userId);
        renderGroupUserPicker();
      };
      list.appendChild(row);
    });
  }
  window.closeGroupUserPicker = function () {
    const modal = q('groupUserPickerModal');
    if (modal) modal.classList.add('hidden');
  };
  function openGroupUserPicker(options) {
    ensureGroupUserPicker();
    groupUserPickerState.title = options.title || '选择用户';
    groupUserPickerState.items = (options.items || []).map(normalizePickerUser).filter(item => item.userId);
    groupUserPickerState.selected = new Set();
    groupUserPickerState.search = '';
    groupUserPickerState.onConfirm = options.onConfirm || null;
    q('groupUserPickerTitle').textContent = groupUserPickerState.title;
    q('groupUserPickerSearch').value = '';
    renderGroupUserPicker();
    q('groupUserPickerModal').classList.remove('hidden');
    setTimeout(() => q('groupUserPickerSearch') && q('groupUserPickerSearch').focus(), 30);
  }
  async function groupPickerUsersForInvite(info) {
    try {
      const users = await apiGet('/api/users');
      return (Array.isArray(users) ? users : []).filter(u => !(info.members || []).some(m => String(m.userId) === String(u.userId)));
    } catch (e) {
      const friends = await apiGet('/api/friends');
      return (Array.isArray(friends) ? friends : []).filter(u => !(info.members || []).some(m => String(m.userId) === String(u.userId)));
    }
  }
  window.promptInvite = async function () {
    if (!window.curGrpId) return;
    try {
      const info = await apiGet('/api/group/info?groupId=' + encodeURIComponent(window.curGrpId));
      const users = await groupPickerUsersForInvite(info);
      if (!users.length) return toast('没有可邀请的用户', true);
      openGroupUserPicker({
        title: '邀请进群',
        items: users,
        onConfirm: async picked => {
          let ok = 0, fail = 0, lastError = '';
          for (const user of picked) {
            try {
              await apiPost('/api/group/invite', { groupId: window.curGrpId, targetUserId: user.userId });
              ok++;
            } catch (e) {
              fail++;
              lastError = e.message || lastError;
            }
          }
          closeGroupUserPicker();
          if (ok) toast('已邀请 ' + ok + ' 位用户');
          if (fail) toast(fail + ' 位邀请失败' + (lastError ? '：' + lastError : ''), true);
          if (typeof openGroupInfo === 'function') openGroupInfo();
          if (typeof loadGroups === 'function') loadGroups();
        }
      });
    } catch (e) {
      toast(e.message || '加载用户失败', true);
    }
  };
  window.promptForceAdd = async function () {
    if (!ME || !ME.isSuperAdmin) return toast('仅超级管理员可用', true);
    if (!window.curGrpId) return;
    try {
      const info = await apiGet('/api/group/info?groupId=' + encodeURIComponent(window.curGrpId));
      const memberIds = new Set((info.members || []).map(m => String(m.userId)));
      const raw = await apiGet('/api/users');
      const users = (Array.isArray(raw) ? raw : []).filter(u => !memberIds.has(String(u.userId)));
      if (!users.length) return toast('没有可加入的用户', true);
      openGroupUserPicker({
        title: '强制加群',
        items: users,
        onConfirm: async picked => {
          let ok = 0, fail = 0, lastError = '';
          for (const user of picked) {
            try {
              await apiPost('/api/group/force-add-member', { groupId: window.curGrpId, targetUserId: user.userId });
              ok++;
            } catch (e) {
              fail++;
              lastError = e.message || lastError;
            }
          }
          closeGroupUserPicker();
          if (ok) toast('已加入 ' + ok + ' 位用户');
          if (fail) toast(fail + ' 位加入失败' + (lastError ? '：' + lastError : ''), true);
          if (typeof openGroupInfo === 'function') openGroupInfo();
          if (typeof loadGroups === 'function') loadGroups();
        }
      });
    } catch (e) {
      toast(e.message || '加载用户失败', true);
    }
  };

  function isNormalGameRunning() {
    const floatPanel = q('gameFloatPlayer');
    const floatFrame = q('gameFloatFrame');
    if (floatPanel && floatFrame && floatFrame.dataset && floatFrame.dataset.gameId && !window.gameImmersiveActive) return true;
    const ov = q('prevOv');
    const frame = q('prevFrame');
    return !!(ov && frame && ov.classList.contains('show') && !frame.classList.contains('hidden') && frame.dataset && frame.dataset.gameId && !window.gameImmersiveActive);
  }
  function makeDraggableFloat(panel, handle) {
    if (!panel || !handle) return;
    let sx = 0, sy = 0, left = 0, top = 0, dragging = false;
    const move = e => {
      if (!dragging) return;
      const p = e.touches ? e.touches[0] : e;
      panel.style.left = Math.max(8, Math.min(window.innerWidth - panel.offsetWidth - 8, left + p.clientX - sx)) + 'px';
      panel.style.top = Math.max(8, Math.min(window.innerHeight - panel.offsetHeight - 8, top + p.clientY - sy)) + 'px';
      panel.style.right = 'auto';
      panel.style.bottom = 'auto';
      if (e.cancelable) e.preventDefault();
    };
    const up = () => { dragging = false; document.removeEventListener('mousemove', move); document.removeEventListener('mouseup', up); document.removeEventListener('touchmove', move); document.removeEventListener('touchend', up); };
    const down = e => {
      const p = e.touches ? e.touches[0] : e;
      const rect = panel.getBoundingClientRect();
      sx = p.clientX; sy = p.clientY; left = rect.left; top = rect.top; dragging = true;
      document.addEventListener('mousemove', move);
      document.addEventListener('mouseup', up);
      document.addEventListener('touchmove', move, { passive: false });
      document.addEventListener('touchend', up);
    };
    handle.addEventListener('mousedown', down);
    handle.addEventListener('touchstart', down, { passive: true });
  }
  function openGameMessageFloat(msg, key, title, pre, openTarget) {
    const old = q('gameMsgFloat');
    if (old) old.remove();
    const panel = document.createElement('div');
    panel.id = 'gameMsgFloat';
    panel.className = 'game-msg-float';
    const text = msg.msgType === 'image' && msg.filePath
      ? `<img src='${esc(msg.filePath)}' alt='' class='game-msg-float-media'>`
      : msg.msgType === 'video' && msg.filePath
        ? `<video src='${esc(msg.filePath)}' controls class='game-msg-float-media'></video>`
        : `<div class='game-msg-float-text'>${esc(msg.content || pre || '')}</div>`;
    panel.innerHTML = `<div class='game-msg-float-head'><span>${esc(title)}</span><button type='button' onclick='q("gameMsgFloat")&&q("gameMsgFloat").remove()'>×</button></div><div class='game-msg-float-body'><div class='game-msg-float-from'>${esc(msg.fromNickname || '')}${msg.isDeveloper ? ' <span class="developer-b">开发者</span>' : ''}</div>${text}<div class='game-msg-float-actions'><button type='button' id='gameMsgFloatOpen'>打开聊天</button><button type='button' onclick='q("gameMsgFloat")&&q("gameMsgFloat").remove()'>稍后</button></div></div>`;
    document.body.appendChild(panel);
    const openBtn = q('gameMsgFloatOpen');
    if (openBtn) openBtn.onclick = () => { panel.remove(); openTarget(); };
    makeDraggableFloat(panel, panel.querySelector('.game-msg-float-head'));
  }

  window.showNotify = function (msg, key) {
    if (!msg || !key || msg.fromUserId === ME.userId) return;
    if (window.gameImmersiveActive) return;
    if (typeof isRoomVisible === 'function' && isRoomVisible(key)) return;
    const openTarget = () => {
      try { if (q('prevOv') && q('prevOv').classList.contains('show') && typeof closePrev === 'function') closePrev(); } catch (e) {}
      try { if (q('miniVideoPlayer') && !q('miniVideoPlayer').classList.contains('hidden') && typeof closeMiniVideo === 'function') closeMiniVideo(); } catch (e) {}
      const item = q(`c-${key}`);
      if (item) {
        setTimeout(() => item.click(), 0);
      } else if (key === 'public' && typeof openChat === 'function') {
        openChat('public', '公共聊天室', featureIcon('chat', ''), '所有人可见', false);
      }
    };
    const title = (() => {
      const item = q(`c-${key}`);
      const name = item && item.querySelector('.ci-name');
      return name ? name.textContent.trim() : (key === 'public' ? '公共聊天室' : key);
    })();
    const pre = msg.msgType === 'image' ? '[图片]' : msg.msgType === 'video' ? '[视频]' : msg.msgType === 'sticker' ? '[表情包]' : msg.msgType === 'file' ? '[文件]' : String(msg.content || '').slice(0, 40);
    if (document.hidden) {
      if ('Notification' in window && Notification.permission === 'granted') {
        const n = new Notification(title, { body: `${msg.fromNickname || ''}：${pre}`, icon: msg.avatarPath || '' });
        n.onclick = () => { window.focus(); openTarget(); n.close(); };
      }
      return;
    }
    const n = document.createElement('div');
    n.className = 'in-notify';
    const av = msg.avatarPath
      ? `<img src="${esc(msg.avatarPath)}" style="width:36px;height:36px;border-radius:50%;object-fit:cover" onerror="fallbackAvatar(this,'${esc((msg.fromNickname||'?').slice(0,1).toUpperCase())}')">`
      : `<div style="width:36px;height:36px;border-radius:50%;background:linear-gradient(135deg,var(--ac),var(--ac2));display:flex;align-items:center;justify-content:center;color:white;font-weight:700;font-size:14px;flex-shrink:0">${esc((msg.fromNickname || '?')[0].toUpperCase())}</div>`;
    n.innerHTML = `<div style="display:flex;align-items:center;gap:10px">${av}<div style="flex:1;min-width:0"><div style="font-size:13px;font-weight:600;color:var(--text)">${esc(title)}</div><div style="font-size:12px;color:var(--muted)">${esc(msg.fromNickname || '')}${msg.isDeveloper ? ' <span class="developer-b">开发者</span>' : ''}：${esc(pre)}</div></div></div>`;
    n.onclick = () => {
      n.remove();
      if (isNormalGameRunning()) openGameMessageFloat(msg, key, title, pre, openTarget);
      else openTarget();
    };
    document.body.appendChild(n);
    setTimeout(() => { if (n.parentNode) n.remove(); }, 4000);
  };

  window.openUserProfile = async function (uid, navMode) {
    if (!uid || uid === ME.userId) {
      if (typeof openProfile === 'function') openProfile(navMode);
      return;
    }
    window.prevView = document.querySelector('.view.active')?.id || 'chatView';
    if (typeof sw === 'function') sw('userProfileView', '');
    if (typeof updateRoute === 'function') updateRoute(`/users/${encodeURIComponent(uid)}`, navMode === undefined ? true : navMode);
    try {
      const u = await apiGet(`/api/user/profile?userId=${encodeURIComponent(uid)}`);
      const name = q('upName');
      const idSub = q('upIdSub');
      const nick = q('upViewNick');
      const avLarge = q('upAvLarge');
      const av = q('upAv');
      const bio = q('upBio');
      const meta = q('upMeta');
      const acts = q('upActions');
      const mc = q('upMoments');
      if (name) name.textContent = u.nickname || '-';
      if (idSub) idSub.textContent = `@${u.userId || ''}`;
      if (nick) {
        nick.innerHTML = '';
        nick.appendChild(document.createTextNode(u.nickname || '-'));
        if (u.isPrimarySuperAdmin) {
          const tag = document.createElement('span');
          tag.className = 'owner-b';
          tag.textContent = '服主';
          nick.appendChild(document.createTextNode(' '));
          nick.appendChild(tag);
        }
        if (u.isSuperAdmin) {
          const tag = document.createElement('span');
          tag.className = 'sa-b';
          tag.textContent = '超级管理员';
          nick.appendChild(document.createTextNode(' '));
          nick.appendChild(tag);
        }
      }
      const avatarHtml = u.avatarPath
        ? `<img class="user-avatar-image" src="${esc(u.avatarPath)}" alt="">`
        : `<img class="user-avatar-fallback-image" src="${featureIconPath('profile')}" alt="">`;
      if (avLarge) avLarge.innerHTML = u.avatarPath
        ? `<img class="user-profile-avatar-image" src="${esc(u.avatarPath)}" alt="" onerror="this.remove();this.parentNode.textContent='${esc((u.nickname || '?').slice(0, 1).toUpperCase())}'">`
        : esc((u.nickname || '?')[0].toUpperCase());
      if (av) av.innerHTML = u.avatarPath
        ? `<img class="user-avatar-image" src="${esc(u.avatarPath)}" alt="" onerror="this.remove();this.parentNode.textContent='${esc((u.nickname || '?').slice(0, 1).toUpperCase())}'">`
        : esc((u.nickname || '?')[0].toUpperCase());
      if (bio) bio.textContent = u.bio || '';
      if (meta) {
        meta.innerHTML = '';
        if (u.birthday) {
          const s = document.createElement('span');
          s.textContent = `生日 ${u.birthday}`;
          meta.appendChild(s);
        }
        if (u.gender) {
          const s = document.createElement('span');
          s.textContent = u.gender === 'male' ? '♂ 男' : u.gender === 'female' ? '♀ 女' : '⚧ 其他';
          meta.appendChild(s);
        }
      }
      if (acts) {
        acts.innerHTML = '';
        const btn = document.createElement('button');
        btn.className = 'btn-primary user-profile-primary-action';
        if (u.isFriend) {
          btn.textContent = '发消息';
          btn.onclick = () => typeof openChat === 'function' && openChat(u.userId, u.nickname, avatarHtml, '私聊', false);
        } else {
          btn.textContent = '+ 加好友';
          btn.onclick = () => typeof openFriendModal === 'function' && openFriendModal(u.userId, u.nickname);
        }
        acts.appendChild(btn);
        if (u.canAdminModerate) {
          const adminBtn = document.createElement('button');
          adminBtn.className = u.banned ? 'tb-btn admin-action-safe' : 'tb-btn admin-action-danger';
          adminBtn.textContent = u.banned ? '解封用户' : '封禁用户';
          adminBtn.onclick = async () => {
            if (u.banned) {
              if (typeof adminUnbanUser === 'function') {
                await adminUnbanUser(u.userId);
                openUserProfile(u.userId, false);
              }
            } else if (typeof adminBanUser === 'function') {
              adminBanUser(u.userId, u.nickname || u.userId);
            }
          };
          acts.appendChild(adminBtn);
        }
      }
      const moments = await apiGet(`/api/user/moments?userId=${encodeURIComponent(uid)}&offset=0`);
      if (mc) {
        mc.innerHTML = '';
        if (!Array.isArray(moments) || !moments.length) {
          mc.innerHTML = "<div class='user-profile-empty'>暂无动态</div>";
        } else {
          moments.forEach(m => mc.appendChild(buildMomentEl(m)));
        }
      }
    } catch (e) {
      toast('加载失败', true);
    }
  };

  window.__searchScope = window.__searchScope || 'all';
  const oldSetSearchScope = window.setSearchScope;
  window.setSearchScope = function (scope) {
    window.__searchScope = scope || 'all';
    if (typeof oldSetSearchScope === 'function') return oldSetSearchScope(scope);
  };
  window.joinAndOpenGroup = async function (groupId) {
    if (!groupId) return;
    try {
      if (ME && ME.isSuperAdmin) {
        await apiPost('/api/group/join-as-admin', { groupId });
        if (typeof loadGroups === 'function') await loadGroups();
      }
      let group = Array.isArray(window.groupsCache)
        ? window.groupsCache.find(item => String(item.groupId) === String(groupId))
        : null;
      if (!group) {
        const info = await apiGet('/api/group/info?groupId=' + encodeURIComponent(groupId));
        group = { groupId, groupName: info.groupName || groupId, memberCount: (info.members || []).length, iconPath: info.iconPath || '' };
      }
      if (typeof openChat === 'function') {
        openChat('group_' + groupId, group.groupName || groupId, typeof groupAvatarContent === 'function' ? groupAvatarContent(group) : '<span style="font-size:16px">👥</span>', (group.memberCount || 0) + ' 人', true);
      }
    } catch (e) {
      toast((e && e.message) || '无法打开该群聊', true);
    }
  };
  window.doSearch = async function () {
    const input = q('searchInput');
    const box = q('searchResults');
    const keyword = input ? input.value.trim() : '';
    if (!keyword || !box) return;
    box.innerHTML = '<div style="color:var(--muted);text-align:center;padding:20px">搜索中...</div>';
    const scope = window.__searchScope || 'all';
    const js = value => JSON.stringify(String(value == null ? '' : value));
    try {
      let url = '/api/search?q=' + encodeURIComponent(keyword) + '&scope=' + encodeURIComponent(scope);
      if (typeof room === 'string' && room && room !== 'public') url += '&room=' + encodeURIComponent(room);
      const data = await apiGet(url);
      let html = '';
      if (data.users && data.users.length) {
        html += '<div style="font-size:13px;color:var(--muted);padding:6px 0">用户</div>';
        data.users.forEach(u => {
          const lvNum = String(u.levelDisplay || 'Lv1').replace(/[^0-9]/g, '') || '1';
          const action = u.isCurrentUser
            ? '<span class="x-badge">我</span>'
            : u.isFriend
              ? `<button class='tb-btn' onclick='closeModal("searchModal");openChat(${js(u.userId)},${js(u.nickname || u.userId)},"<span style=\\"font-size:16px\\">👤</span>","私聊",false)'>发消息</button>`
              : `<button class='tb-btn' style='background:#dcfce7;color:#166534;border-color:#bbf7d0' onclick='closeModal("searchModal");openFriendModal(${js(u.userId)},${js(u.nickname || u.userId)})'>添加好友</button>`;
          html += `<div class='search-item' style='display:flex;align-items:center;gap:10px' onclick='closeModal("searchModal");openUserProfile(${js(u.userId)})'><div style='flex:1;min-width:0'><div style='font-weight:700'>${esc(u.nickname || u.userId)} <span class='lv-badge lv-${esc(lvNum)}'>${esc(u.levelDisplay || 'Lv1')}</span></div><div style='font-size:12px;color:var(--muted)'>@${esc(u.userId)}</div></div><div onclick='event.stopPropagation()'>${action}</div></div>`;
        });
      }
      if (data.groups && data.groups.length) {
        html += '<div style="font-size:13px;color:var(--muted);padding:6px 0">群聊</div>';
        data.groups.forEach(g => {
          html += `<div class='search-item' onclick='closeModal("searchModal");joinAndOpenGroup(${js(g.groupId)})'><div style='font-weight:700'>${esc(g.groupName || g.groupId)}</div><div style='font-size:12px;color:var(--muted)'>${Number(g.memberCount || 0)} 人</div></div>`;
        });
      }
      if (data.messages && data.messages.length) {
        html += `<div style="font-size:13px;color:var(--muted);padding:6px 0">${scope === 'sent' ? '发送消息' : '聊天记录'}</div>`;
        data.messages.forEach(m => {
          html += `<div class='search-item' onclick='closeModal("searchModal");scrollToMessage(${js(m.chatRoomId)},${js(m.id)})'><div style='font-size:12px;color:var(--muted)'>${esc(m.fromNickname || '')} · ${typeof formatChatTime === 'function' ? formatChatTime(m.timestamp) : fmtDate(m.timestamp)}</div><div style='font-size:13px'>${esc(m.content || '')}</div></div>`;
        });
      }
      if (data.aiConversations && data.aiConversations.length) {
        html += '<div style="font-size:13px;color:var(--muted);padding:6px 0">AI 对话</div>';
        data.aiConversations.forEach(c => {
          html += `<div class='search-item' onclick='closeModal("searchModal");openAi();setTimeout(function(){aiOpenConversation(${js(c.id)});},100)'><div style='font-weight:700'>${esc(c.title || '新标签')}</div><div style='font-size:12px;color:var(--muted)'>${esc(c.type || 'chat')} · ${esc(c.modelId || '')}</div></div>`;
        });
      }
      box.innerHTML = html || '<div style="color:var(--muted);text-align:center;padding:20px">无结果</div>';
    } catch (e) {
      box.innerHTML = '<div style="color:#ef4444">搜索失败</div>';
    }
  };

  const oldShareSelectedGame = window.shareSelectedGame;
  if (oldShareSelectedGame) {
    window.shareSelectedGame = async function () {
      const game = window.gameMap && window.gameMap[window.selectedGameId];
      if (!game) return toast('请先选择小程序', true);
      const link = `${location.origin}/share/game/${encodeURIComponent(game.id)}`;
      await copyText(link, '小程序分享链接已复制');
      try { await maybeSendShareCard('game', game.id, '小程序分享卡片已发送'); } catch (e) { toast(e.message || '发送卡片失败', true); }
    };
  }

  const oldBuildMsg = window.buildMsg;
  if (oldBuildMsg) {
    window.buildMsg = function (msg) {
      if (msg && msg.msgType === 'card' && msg.cardType) return buildShareCardMessage(msg);
      return oldBuildMsg(msg);
    };
    setTimeout(async function rerenderCurrentRoomMessages() {
      try {
        if (typeof room === 'undefined' || !room) return;
        const list = q('msgList');
        if (!list) return;
        const res = await fetch(`/api/messages/paged?room=${encodeURIComponent(room)}&before=0`);
        const msgs = await (typeof safeJson === 'function' ? safeJson(res) : res.json());
        if (!Array.isArray(msgs)) return;
        list.innerHTML = '<div class="lm-btn hidden" id="lmBtn"><button onclick="loadMoreMsgs()">↑ 加载更多</button></div>';
        lastId = 0;
        oldestId = 0;
        msgs.forEach(msg => {
          lastId = Math.max(lastId, +(msg && msg.id || 0));
          if (!oldestId || +(msg && msg.id || 0) < oldestId) oldestId = +(msg && msg.id || 0);
          list.appendChild(window.buildMsg(msg));
        });
        hasMore = msgs.length >= 20;
        const moreBtn = q('lmBtn');
        if (moreBtn) moreBtn.classList.toggle('hidden', !hasMore);
        list.scrollTop = list.scrollHeight;
      } catch (_) {}
    }, 0);
  }

  // File upload menu
  let _uploadProgressEl = null;
  let _uploadQueueRunning = 0;
  window.toggleChatMore = function() {
    const el = document.getElementById('chatMorePanel');
    const picker = document.getElementById('picker');
    if (!el) return;
    if (picker && picker.classList.contains('show')) picker.classList.remove('show');
    const show = !el.classList.contains('show');
    el.classList.toggle('show', show);
  };
  function closeChatMorePanel() {
    const el = document.getElementById('chatMorePanel');
    if (el) {
      el.classList.remove('show');
    }
  }
  window.uploadFiles = function() { triggerChatCloudUpload(false); };
  function triggerChatCloudUpload(isFolder) {
    closeChatMorePanel();
    const inp = document.createElement('input');
    inp.type = 'file';
    inp.multiple = !isFolder;
    if (isFolder) inp.webkitdirectory = true;
    inp.style.display = 'none';
    inp.onchange = async function() {
      const files = Array.from(inp.files || []);
      inp.remove();
      if (!files.length) return;
      await uploadChatFilesToCloudAndShare(files);
    };
    document.body.appendChild(inp);
    inp.click();
  }
  async function uploadChatFilesToCloudAndShare(files) {
    const targetRoom = typeof room === 'string' && room ? room : 'public';
    const total = files.length;
    let ok = 0, fail = 0;
    for (const file of files) {
      const started = Date.now();
      X.cloud.uploadQueue.push({ name: file.name, total: file.size || 0, loaded: 0, speed: 0, status: '上传中' });
      try {
        const uploaded = await storeLooseFile(file, ev => {
          const elapsed = Math.max((Date.now() - started) / 1000, 0.1);
          markCloudUploadQueue(file.name, {
            total: Number(ev.total || file.size || 0),
            loaded: Number(ev.loaded || 0),
            speed: Number(ev.loaded || 0) / elapsed,
            status: '上传到云盘'
          });
        });
        const entry = await apiPost('/api/cloud/import-stored', {
          filePath: uploaded.filePath,
          fileName: uploaded.fileName || file.name,
          parentPath: '/'
        });
        markCloudUploadQueue(file.name, { loaded: file.size || 0, total: file.size || 0, status: '完成' });
        const msg = await apiPost('/api/share/send-card', { type: 'cloud', id: entry.id, room: targetRoom });
        if (msg && msg.id && targetRoom === room && typeof appendMsg === 'function') appendMsg(msg);
        ok++;
      } catch (e) {
        fail++;
        markCloudUploadQueue(file.name, { status: '失败' });
        toast((e && e.message) || `${file.name} 上传失败`, true);
      }
    }
    X.cloud.uploadQueue = (X.cloud.uploadQueue || []).filter(item => item.status !== '完成');
    updateUploadFloater();
    if (ok) toast(`已上传到云盘并发送 ${ok} 个分享卡片`);
    if (fail && !ok) toast('文件上传失败', true);
    if (X.cloud && X.cloud.section === 'files' && typeof cloudRefresh === 'function') cloudRefresh();
  }
  async function uploadFilesWithProgress(files) {
    const total = files.length;
    let done = 0;
    let panelId = 'fp_' + Date.now();
    _uploadQueueRunning = total;
    showUploadPanel(panelId, files);
    for (const f of files) {
      try {
        await uploadSingleFile(f, panelId);
        done++;
        updateUploadPanelTotal(panelId, done, total);
      } catch (e) {
        addUploadPanelError(panelId, f.name, e.message);
      }
    }
    _uploadQueueRunning = 0;
    setTimeout(() => hideUploadPanel(panelId), 3000);
  }
  async function uploadSingleFile(file, panelId) {
    const roomId = typeof room === 'string' ? room : 'public';
    const isImg = file.type.startsWith('image/');
    const isVid = file.type.startsWith('video/');
    const type = isVid ? 'video' : (isImg ? 'image' : 'file');
    const url = '/api/upload-file-stream?room=' + encodeURIComponent(roomId) + '&type=' + encodeURIComponent(type) + '&fileName=' + encodeURIComponent(file.name);
    await new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', url);
      xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');
      xhr.upload.onprogress = ev => {
        if (ev.lengthComputable) {
          const pct = Math.round(ev.loaded / ev.total * 100);
          updateUploadPanelItem(panelId, file.name, pct);
        }
      };
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) resolve();
        else { try { const d = JSON.parse(xhr.responseText); reject(new Error(d.error || '上传失败')); } catch(e) { reject(new Error('上传失败')); } }
      };
      xhr.onerror = () => reject(new Error('网络错误'));
      xhr.send(file);
    });
    updateUploadPanelItem(panelId, file.name, 100);
  }
  function showUploadPanel(panelId, files) {
    let html = '<div class="file-progress" id="' + panelId + '"><div style="font-weight:700;margin-bottom:8px">📤 上传中</div><div id="' + panelId + '_total" style="font-size:12px;color:var(--muted);margin-bottom:6px">0 / ' + files.length + '</div>';
    files.forEach(f => {
      html += '<div class="file-progress-item" id="' + panelId + '_' + esc(f.name) + '"><span class="file-progress-name">' + esc(f.name) + '</span><div class="file-progress-bar"><div class="file-progress-fill" id="' + panelId + '_bar_' + esc(f.name) + '" style="width:0%"></div></div><span id="' + panelId + '_pct_' + esc(f.name) + '" style="font-size:11px;min-width:36px;text-align:right">0%</span></div>';
    });
    html += '</div>';
    const existing = document.getElementById(panelId);
    if (existing) existing.outerHTML = html;
    else document.body.insertAdjacentHTML('beforeend', html);
    _uploadProgressEl = document.getElementById(panelId);
  }
  function updateUploadPanelItem(panelId, name, pct) {
    const bar = document.getElementById(panelId + '_bar_' + name);
    const txt = document.getElementById(panelId + '_pct_' + name);
    if (bar) bar.style.width = pct + '%';
    if (txt) txt.textContent = pct + '%';
  }
  function updateUploadPanelTotal(panelId, done, total) {
    const el = document.getElementById(panelId + '_total');
    if (el) el.textContent = done + ' / ' + total;
  }
  function addUploadPanelError(panelId, name, msg) {
    const item = document.getElementById(panelId + '_' + name);
    if (item) {
      const pct = document.getElementById(panelId + '_pct_' + name);
      if (pct) pct.textContent = '失败';
      pct.style.color = '#ef4444';
    }
  }
  function hideUploadPanel(panelId) {
    const el = document.getElementById(panelId);
    if (el) el.remove();
  }
  window.cloudFilePicker = function() {
    closeChatMorePanel();
    openCloud();
    toast('已打开云盘，选择文件后点“分享”即可发送卡片');
  };

  // Camera capture
  let _cameraStream = null;
  let _cameraRecorder = null;
  let _cameraChunks = [];
  let _cameraRecording = false;
  window.openCamera = async function() {
    const modal = document.getElementById('cameraModal');
    if (!modal) return toast('相机组件未加载');
    closeChatMorePanel();
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      return toast('当前浏览器不支持直接拍照', true);
    }
    modal.classList.remove('hidden');
    try {
      _cameraStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment', width: { ideal: 1280 }, height: { ideal: 720 } }, audio: true });
      const preview = document.getElementById('cameraPreview');
      if (preview) {
        preview.srcObject = _cameraStream;
        preview.play().catch(function(e){ console.log('[camera] play error:', e); });
      }
    } catch (e) {
      toast('无法直接访问相机，请使用系统拍摄选择', true);
      console.error('[camera] getUserMedia error:', e);
      closeCamera();
    }
  };
  function openCameraCaptureFallback() {
    const inp = document.createElement('input');
    inp.type = 'file';
    inp.accept = 'image/*,video/*';
    inp.setAttribute('capture', 'environment');
    inp.style.display = 'none';
    inp.onchange = function() {
      const f = inp.files && inp.files[0];
      inp.remove();
      if (!f) return;
      const roomId = typeof room === 'string' ? room : 'public';
      const type = (f.type || '').startsWith('video/') ? 'video' : ((f.type || '').startsWith('image/') ? 'image' : 'file');
      doUpload(f, type, roomId);
    };
    document.body.appendChild(inp);
    inp.click();
  }
  window.capturePhoto = function() {
    const video = document.getElementById('cameraPreview');
    const canvas = document.getElementById('cameraCanvas');
    if (!_cameraStream || !video || !canvas) return toast('请先打开摄像头', true);
    if (!video.videoWidth && !video.videoHeight) return toast('相机画面还没准备好，请稍等一下', true);
    canvas.width = video.videoWidth || 1280;
    canvas.height = video.videoHeight || 720;
    const ctx = canvas.getContext('2d');
    ctx.drawImage(video, 0, 0);
    canvas.toBlob(async function(blob) {
      if (!blob) return toast('拍照失败', true);
      const f = new File([blob], 'camera_' + Date.now() + '.jpg', { type: 'image/jpeg' });
      closeCamera();
      const roomId = typeof room === 'string' ? room : 'public';
      doUpload(f, 'image', roomId);
    }, 'image/jpeg', 0.92);
  };
  window.toggleVideoRecording = function() {
    if (_cameraRecording) return stopVideoRecording();
    startVideoRecording();
  };
  function startVideoRecording() {
    if (typeof MediaRecorder === 'undefined') return toast('当前浏览器不支持网页录像', true);
    if (!_cameraStream) return toast('请先打开摄像头', true);
    if (typeof MediaRecorder === 'undefined') return toast('当前浏览器不支持网页录像，请用系统拍摄', true), openCameraCaptureFallback();
    _cameraChunks = [];
    try {
      _cameraRecorder = new MediaRecorder(_cameraStream, { mimeType: MediaRecorder.isTypeSupported('video/webm;codecs=h264') ? 'video/webm;codecs=h264' : 'video/webm' });
      _cameraRecorder.ondataavailable = e => { if (e.data.size > 0) _cameraChunks.push(e.data); };
      _cameraRecorder.onstop = async function() {
        const blob = new Blob(_cameraChunks, { type: 'video/webm' });
        if (blob.size < 100) return;
        const f = new File([blob], 'camera_' + Date.now() + '.webm', { type: 'video/webm' });
        closeCamera();
        const roomId = typeof room === 'string' ? room : 'public';
        doUpload(f, 'video', roomId);
      };
      _cameraRecorder.start(1000);
      _cameraRecording = true;
      document.getElementById('cameraRecordBtn').textContent = '停止录像';
      document.getElementById('cameraRecordBtn').classList.add('recording');
    } catch (e) { toast('录像启动失败: ' + e.message, true); }
  }
  function stopVideoRecording() {
    if (_cameraRecorder && _cameraRecorder.state !== 'inactive') {
      _cameraRecorder.stop();
    }
    _cameraRecording = false;
    document.getElementById('cameraRecordBtn').textContent = '开始录像';
    document.getElementById('cameraRecordBtn').classList.remove('recording');
  }
  window.closeCamera = function() {
    if (_cameraRecorder && _cameraRecorder.state !== 'inactive') _cameraRecorder.stop();
    if (_cameraStream) { _cameraStream.getTracks().forEach(t => t.stop()); _cameraStream = null; }
    _cameraRecording = false;
    const modal = document.getElementById('cameraModal');
    const recordBtn = document.getElementById('cameraRecordBtn');
    if (recordBtn) { recordBtn.textContent = '开始录像'; recordBtn.classList.remove('recording'); }
    if (modal) modal.classList.add('hidden');
  };


  // ===== Full Image Editor =====
  window.openImageEditor = function() {
    const img = q('prevImg');
    if (!img || !img.src) return toast('没有可编辑的图片', true);
    ensureImageEditor();
    const ov = q('imgEditorOv');
    const canvas = q('imgEditorCanvas');
    if (!ov || !canvas) return;
    const ctx = canvas.getContext('2d');
    const image = new Image();
    image.crossOrigin = 'anonymous';
    image.onload = function() {
      canvas.width = image.naturalWidth;
      canvas.height = image.naturalHeight;
      ctx.drawImage(image, 0, 0);
      X.imgEditor = { image: image, original: image, history: [], historyIdx: -1, tool: 'brush', color: '#ef4444', size: 8, drawing: false, textMode: false };
      pushImageHistory();
      ov.classList.add('show');
    };
    image.onerror = function() { toast('图片加载失败，可能存在跨域限制', true); };
    image.src = img.src;
  };
  function ensureImageEditor() {
    if (q('imgEditorOv')) return;
    const ov = document.createElement('div');
    ov.id = 'imgEditorOv';
    ov.className = 'prev-ov';
    ov.style.cssText = 'position:fixed;inset:0;background:rgba(15,23,42,.92);z-index:2100;display:none;align-items:center;justify-content:center;flex-direction:column;gap:12px;padding:20px';
    ov.innerHTML = "<div style='position:absolute;top:14px;left:18px;right:18px;display:flex;gap:8px;flex-wrap:wrap;align-items:center;z-index:10;justify-content:center'>"
      + "<button class='tb-btn' onclick='closeImageEditor()' style='background:rgba(255,255,255,.1);color:#fff;border:1px solid rgba(255,255,255,.15)'>关闭</button>"
      + "<button class='tb-btn' onclick='undoImageEdit()' style='background:rgba(255,255,255,.1);color:#fff;border:1px solid rgba(255,255,255,.15)'>撤销</button>"
      + "<button class='tb-btn' onclick='redoImageEdit()' style='background:rgba(255,255,255,.1);color:#fff;border:1px solid rgba(255,255,255,.15)'>重做</button>"
      + "<span style='width:1px;height:22px;background:rgba(255,255,255,.15)'></span>"
      + "<button class='tb-btn' id='ieToolBrush' onclick=\"setImageTool('brush')\" style='background:rgba(99,102,241,.5);color:#fff;border:none'>画笔</button>"
      + "<button class='tb-btn' id='ieToolText' onclick=\"setImageTool('text')\" style='background:rgba(255,255,255,.1);color:#fff;border:1px solid rgba(255,255,255,.15)'>文字</button>"
      + "<button class='tb-btn' id='ieToolMosaic' onclick=\"setImageTool('mosaic')\" style='background:rgba(255,255,255,.1);color:#fff;border:1px solid rgba(255,255,255,.15)'>马赛克</button>"
      + "<button class='tb-btn' id='ieToolEraser' onclick=\"setImageTool('eraser')\" style='background:rgba(255,255,255,.1);color:#fff;border:1px solid rgba(255,255,255,.15)'>橡皮</button>"
      + "<span style='width:1px;height:22px;background:rgba(255,255,255,.15)'></span>"
      + "<input type='color' id='ieColor' value='#ef4444' onchange='setImageColor(this.value)' style='width:36px;height:32px;border:none;border-radius:8px;cursor:pointer;background:transparent'>"
      + "<input type='range' id='ieSize' value='8' min='1' max='50' oninput='setImageSize(this.value)' style='width:80px'>"
      + "<span style='width:1px;height:22px;background:rgba(255,255,255,.15)'></span>"
      + "<button class='tb-btn' onclick='saveImageEdit()' style='background:linear-gradient(135deg,#10b981,#34d399);color:#fff;border:none;font-weight:700'>保存</button>"
      + "</div>"
      + "<div style='position:relative;max-width:90vw;max-height:calc(90vh - 80px);overflow:auto;border-radius:12px;box-shadow:0 20px 60px rgba(0,0,0,.5)'>"
      + "<canvas id='imgEditorCanvas' style='display:block;max-width:100%;max-height:calc(90vh - 80px);cursor:crosshair;background:#000'></canvas>"
      + "</div>";
    document.body.appendChild(ov);
    const canvas = q('imgEditorCanvas');
    canvas.addEventListener('mousedown', onImageEditorMouseDown);
    canvas.addEventListener('mousemove', onImageEditorMouseMove);
    canvas.addEventListener('mouseup', onImageEditorMouseUp);
    canvas.addEventListener('mouseleave', onImageEditorMouseUp);
    canvas.addEventListener('click', onImageEditorClick);
    // Touch support
    canvas.addEventListener('touchstart', e => {
      e.preventDefault();
      const t = e.touches[0];
      const eventPoint = { offsetX: t.clientX - canvas.getBoundingClientRect().left, offsetY: t.clientY - canvas.getBoundingClientRect().top };
      if (X.imgEditor && X.imgEditor.tool === 'text') onImageEditorClick(eventPoint);
      else onImageEditorMouseDown(eventPoint);
    });
    canvas.addEventListener('touchmove', e => { e.preventDefault(); const t = e.touches[0]; onImageEditorMouseMove({ offsetX: t.clientX - canvas.getBoundingClientRect().left, offsetY: t.clientY - canvas.getBoundingClientRect().top }); });
    canvas.addEventListener('touchend', onImageEditorMouseUp);
  }
  function getCanvasPoint(e, canvas) {
    const rect = canvas.getBoundingClientRect();
    const scaleX = canvas.width / rect.width;
    const scaleY = canvas.height / rect.height;
    return { x: (e.offsetX !== undefined ? e.offsetX : (e.clientX - rect.left)) * scaleX, y: (e.offsetY !== undefined ? e.offsetY : (e.clientY - rect.top)) * scaleY };
  }
  function onImageEditorMouseDown(e) {
    if (!X.imgEditor) return;
    if (X.imgEditor.tool === 'text') return;
    X.imgEditor.drawing = true;
    const canvas = q('imgEditorCanvas');
    const pt = getCanvasPoint(e, canvas);
    X.imgEditor.lastX = pt.x;
    X.imgEditor.lastY = pt.y;
    if (X.imgEditor.tool === 'mosaic') { drawMosaic(pt.x, pt.y); }
    else if (X.imgEditor.tool === 'brush' || X.imgEditor.tool === 'eraser') { drawBrush(pt.x, pt.y, pt.x, pt.y); }
  }
  function onImageEditorMouseMove(e) {
    if (!X.imgEditor || !X.imgEditor.drawing) return;
    const canvas = q('imgEditorCanvas');
    const pt = getCanvasPoint(e, canvas);
    if (X.imgEditor.tool === 'mosaic') { drawMosaic(pt.x, pt.y); }
    else if (X.imgEditor.tool === 'brush' || X.imgEditor.tool === 'eraser') { drawBrush(X.imgEditor.lastX, X.imgEditor.lastY, pt.x, pt.y); }
    X.imgEditor.lastX = pt.x;
    X.imgEditor.lastY = pt.y;
  }
  function onImageEditorMouseUp() {
    if (!X.imgEditor || !X.imgEditor.drawing) return;
    X.imgEditor.drawing = false;
    pushImageHistory();
  }
  function onImageEditorClick(e) {
    if (!X.imgEditor || X.imgEditor.tool !== 'text') return;
    const canvas = q('imgEditorCanvas');
    const pt = getCanvasPoint(e, canvas);
    showImageTextInput(pt.x, pt.y);
  }
  function showImageTextInput(x, y) {
    const canvas = q('imgEditorCanvas');
    const wrap = canvas && canvas.parentElement;
    if (!canvas || !wrap || !X.imgEditor) return;
    window.cancelImageText();
    X.imgEditor.textX = x;
    X.imgEditor.textY = y;
    X.imgEditor.textBase = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height);
    X.imgEditor.textEditing = true;
    const rect = canvas.getBoundingClientRect();
    const wrapRect = wrap.getBoundingClientRect();
    const scaleX = rect.width / canvas.width;
    const scaleY = rect.height / canvas.height;
    const input = document.createElement('input');
    input.id = 'imgTextInput';
    input.type = 'text';
    input.placeholder = '在这里输入文字…';
    input.autocomplete = 'off';
    input.style.cssText = 'position:absolute;z-index:20;width:min(240px,45vw);padding:5px 8px;color:' + X.imgEditor.color + ';font-weight:700;background:rgba(15,23,42,.42);border:1px solid rgba(255,255,255,.55);border-radius:7px;outline:none;box-shadow:0 6px 24px rgba(15,23,42,.28);backdrop-filter:blur(10px);transform:translateY(-2px)';
    input.style.fontSize = Math.max(14, (X.imgEditor.size * 3 + 12) * scaleY) + 'px';
    input.style.left = Math.max(0, rect.left - wrapRect.left + x * scaleX) + 'px';
    input.style.top = Math.max(0, rect.top - wrapRect.top + y * scaleY) + 'px';
    input.addEventListener('input', () => renderImageTextPreview(input.value));
    input.addEventListener('keydown', event => {
      if (event.key === 'Enter') { event.preventDefault(); window.confirmImageText(); }
      else if (event.key === 'Escape') { event.preventDefault(); window.cancelImageText(); }
    });
    input.addEventListener('blur', () => {
      if (X.imgEditor && X.imgEditor.textEditing) window.confirmImageText();
    });
    wrap.appendChild(input);
    input.focus();
  }
  function drawImageEditorText(text) {
    if (!X.imgEditor || !text) return;
    const canvas = q('imgEditorCanvas');
    const ctx = canvas.getContext('2d');
    ctx.font = 'bold ' + (X.imgEditor.size * 3 + 12) + 'px sans-serif';
    ctx.fillStyle = X.imgEditor.color;
    ctx.textBaseline = 'top';
    ctx.shadowColor = 'rgba(0,0,0,.5)';
    ctx.shadowBlur = 4;
    ctx.fillText(text, X.imgEditor.textX, X.imgEditor.textY);
    ctx.shadowBlur = 0;
  }
  function renderImageTextPreview(text) {
    if (!X.imgEditor || !X.imgEditor.textBase) return;
    const canvas = q('imgEditorCanvas');
    canvas.getContext('2d').putImageData(X.imgEditor.textBase, 0, 0);
    drawImageEditorText(text);
  }
  window.confirmImageText = function() {
    const input = q('imgTextInput');
    if (!X.imgEditor || !X.imgEditor.textEditing) return;
    const text = input ? input.value : '';
    if (!text.trim()) return window.cancelImageText();
    renderImageTextPreview(text);
    X.imgEditor.textEditing = false;
    pushImageHistory();
    X.imgEditor.textBase = null;
    if (input) input.remove();
  };
  window.cancelImageText = function() {
    const input = q('imgTextInput');
    if (X.imgEditor && X.imgEditor.textEditing && X.imgEditor.textBase) {
      const canvas = q('imgEditorCanvas');
      if (canvas) canvas.getContext('2d').putImageData(X.imgEditor.textBase, 0, 0);
    }
    if (X.imgEditor) {
      X.imgEditor.textEditing = false;
      X.imgEditor.textBase = null;
    }
    if (input) input.remove();
  };
  function drawBrush(x1, y1, x2, y2) {
    const canvas = q('imgEditorCanvas');
    const ctx = canvas.getContext('2d');
    ctx.globalCompositeOperation = X.imgEditor.tool === 'eraser' ? 'destination-out' : 'source-over';
    ctx.strokeStyle = X.imgEditor.color;
    ctx.lineWidth = X.imgEditor.size;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(x1, y1);
    ctx.lineTo(x2, y2);
    ctx.stroke();
    ctx.globalCompositeOperation = 'source-over';
  }
  function drawMosaic(cx, cy) {
    const canvas = q('imgEditorCanvas');
    const ctx = canvas.getContext('2d');
    const size = X.imgEditor.size * 3;
    const x = Math.floor(cx / size) * size;
    const y = Math.floor(cy / size) * size;
    const imageData = ctx.getImageData(x, y, size, size);
    const data = imageData.data;
    let r = 0, g = 0, b = 0, count = 0;
    for (let i = 0; i < data.length; i += 4) { r += data[i]; g += data[i+1]; b += data[i+2]; count++; }
    if (count > 0) { r = Math.round(r/count); g = Math.round(g/count); b = Math.round(b/count); }
    for (let i = 0; i < data.length; i += 4) { data[i] = r; data[i+1] = g; data[i+2] = b; }
    ctx.putImageData(imageData, x, y);
  }
  function pushImageHistory() {
    if (!X.imgEditor) return;
    const canvas = q('imgEditorCanvas');
    // Remove redo states
    X.imgEditor.history = X.imgEditor.history.slice(0, X.imgEditor.historyIdx + 1);
    X.imgEditor.history.push(canvas.toDataURL('image/png'));
    X.imgEditor.historyIdx++;
    if (X.imgEditor.history.length > 30) { X.imgEditor.history.shift(); X.imgEditor.historyIdx--; }
  }
  window.undoImageEdit = function() {
    if (!X.imgEditor || X.imgEditor.historyIdx <= 0) return;
    X.imgEditor.historyIdx--;
    restoreImageHistory();
  };
  window.redoImageEdit = function() {
    if (!X.imgEditor || X.imgEditor.historyIdx >= X.imgEditor.history.length - 1) return;
    X.imgEditor.historyIdx++;
    restoreImageHistory();
  };
  function restoreImageHistory() {
    if (!X.imgEditor) return;
    const canvas = q('imgEditorCanvas');
    const ctx = canvas.getContext('2d');
    const img = new Image();
    img.onload = function() { ctx.clearRect(0, 0, canvas.width, canvas.height); ctx.drawImage(img, 0, 0); };
    img.src = X.imgEditor.history[X.imgEditor.historyIdx];
  }
  window.setImageTool = function(tool) {
    if (!X.imgEditor) return;
    if (X.imgEditor.textEditing) window.confirmImageText();
    X.imgEditor.tool = tool;
    const canvas = q('imgEditorCanvas');
    canvas.style.cursor = tool === 'text' ? 'text' : 'crosshair';
    ['brush','text','mosaic','eraser'].forEach(t => {
      const btn = q('ieTool' + t.charAt(0).toUpperCase() + t.slice(1));
      if (btn) btn.style.background = t === tool ? 'rgba(99,102,241,.5)' : 'rgba(255,255,255,.1)';
    });
  };
  window.setImageColor = function(color) {
    if (!X.imgEditor) return;
    X.imgEditor.color = color;
    const input = q('imgTextInput');
    if (input) { input.style.color = color; renderImageTextPreview(input.value); }
  };
  window.setImageSize = function(size) {
    if (!X.imgEditor) return;
    X.imgEditor.size = Number(size);
    const input = q('imgTextInput');
    const canvas = q('imgEditorCanvas');
    if (input && canvas) {
      input.style.fontSize = Math.max(14, (X.imgEditor.size * 3 + 12) * (canvas.getBoundingClientRect().height / canvas.height)) + 'px';
      renderImageTextPreview(input.value);
    }
  };
  window.closeImageEditor = function() {
    window.cancelImageText();
    const ov = q('imgEditorOv');
    if (ov) ov.classList.remove('show');
    X.imgEditor = null;
  };
  window.saveImageEdit = function() {
    const canvas = q('imgEditorCanvas');
    if (!canvas) return;
    canvas.toBlob(function(blob) {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'edited_' + Date.now() + '.png';
      a.click();
      URL.revokeObjectURL(url);
      toast('图片已保存');
    }, 'image/png');
  };

  // Cloud multi-select
  window.toggleCloudSelect = function(cb) {
    if (!X.cloud.selected) X.cloud.selected = new Set();
    if (cb.checked) X.cloud.selected.add(cb.dataset.id);
    else X.cloud.selected.delete(cb.dataset.id);
    renderCloudSection();
  };
  async function batchCloudOp(op) {
    const ids = Array.from(X.cloud.selected || []);
    if (!ids.length) return toast('请先选择文件', true);
    const target = (op === 'move' || op === 'copy') ? await window.showPrompt('请输入目标路径（如 /docs）：', '/') : null;
    if ((op === 'move' || op === 'copy') && !target) return;
    let done = 0, fail = 0;
    toast('正在处理 0/' + ids.length);
    for (const id of ids) {
      try {
        const body = { entryId: id };
        if (target) body.parentPath = target;
        await apiPost('/api/cloud/' + op, body);
        done++;
      } catch (e) { fail++; }
      toast('正在处理 ' + (done + fail) + '/' + ids.length + (fail ? ' (' + fail + '失败)' : ''));
    }
    X.cloud.selected.clear();
    toast(op === 'move' ? '移动完成' : op === 'delete' ? '已移入回收站' : '操作完成' + (fail ? ' (' + fail + '失败)' : ''));
    cloudRefresh();
  }
  window.batchCloudMove = function() { batchCloudOp('move'); };
  window.batchCloudCopy = function() { batchCloudOp('copy'); };
  window.batchCloudDelete = async function() {
    if (!(await window.showConfirm('确定删除选中的 ' + (X.cloud.selected ? X.cloud.selected.size : 0) + ' 项？'))) return;
    batchCloudOp('delete');
  };
  window.batchCloudCompress = async function() {
    if (!X.cloud.selected || X.cloud.selected.size === 0) { toast('请先选择文件', true); return; }
    try {
      var entryIds = Array.from(X.cloud.selected);
      await apiPost('/api/cloud/compress-batch', { entryIds: JSON.stringify(entryIds), zipName: '压缩包.zip' });
      X.cloud.selected.clear();
      X.cloud.section = 'tasks';
      toast('批量压缩任务已提交');
      cloudRefresh();
    } catch (e) { toast(e.message || '批量压缩失败', true); }
  };

  const oldBuildMomentEl = window.buildMomentEl;
  if (oldBuildMomentEl) {
    window.buildMomentEl = function (m) {
      const el = oldBuildMomentEl(m);
      const attachments = Array.isArray(m && m.attachments) ? m.attachments.filter(Boolean) : [];
      if (!el || !attachments.length) return el;
      const referenced = new Set();
      String(m.content || '').replace(/\[(?:media|file):([^\]|]+)(?:\|[^\]]*)?\]/g, function (_, path) {
        referenced.add(String(path || ''));
        return '';
      });
      const extra = attachments.filter(item => item.filePath && !referenced.has(String(item.filePath)));
      if (!extra.length) return el;
      const likeBtn = el.querySelector(`button[onclick="toggleLike('${m.id}')"]`);
      const actionRow = likeBtn ? likeBtn.parentElement : null;
      const wrap = document.createElement('div');
      wrap.className = 'moment-file-list moment-extra-attachments';
      extra.forEach(item => {
        const fileName = item.fileName || guessMomentFileName(item.filePath);
        const namedPath = withFileName(item.filePath, fileName);
        const prevType = previewType({ name: fileName, contentType: item.type || '' });
        const card = document.createElement('div');
        card.className = 'fcard';
        card.innerHTML = `<div class='ficon'>${fileIcon({ name: fileName, contentType: item.type || '', type: 'file' })}</div><div><div class='fname'>${esc(fileName)}</div><div class='fhint'>点击预览或下载</div></div>`;
        card.onclick = () => openPrev(prevType, namedPath, fileName);
        wrap.appendChild(card);
      });
      if (actionRow && actionRow.parentNode) actionRow.parentNode.insertBefore(wrap, actionRow);
      else el.appendChild(wrap);
      return el;
    };
  }

  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape') return;
    closeAnnouncements();
    closeMobileMore();
    if (X.builtinMiniApps.activeId) closeBuiltinMiniApp();
  });

  const BUILTIN_QR_ID = 'builtin-qr';
  const BUILTIN_QR_ROUTE = '/games?app=builtin-qr';

  function builtinMiniApp(id) {
    return window.BuiltinMiniApps.find(app => app.id === id) || null;
  }

  function setBuiltinMiniAppVisible(visible) {
    const directory = q('gamesDirectory');
    const detail = q('builtinMiniAppDetail');
    if (directory) directory.classList.toggle('hidden', !!visible);
    if (detail) detail.classList.toggle('hidden', !visible);
    if (visible && detail) detail.scrollTop = 0;
  }

  function resetQrWorkspace() {
    X.builtinMiniApps.qr.dataUrl = '';
    ['qrFileInput', 'qrCameraInput'].forEach(id => {
      const input = q(id);
      if (input) input.value = '';
    });
    const fileName = q('qrFileName');
    if (fileName) fileName.textContent = '';
    const preview = q('qrPreview');
    if (preview) preview.removeAttribute('src');
    ['qrPreviewWrap', 'qrDecodeBtn', 'qrResult', 'qrEncodeResult'].forEach(id => {
      const node = q(id);
      if (node) node.classList.add('hidden');
    });
    const decodeBtn = q('qrDecodeBtn');
    if (decodeBtn) {
      decodeBtn.disabled = false;
      decodeBtn.textContent = '识别二维码';
    }
    const encodeText = q('qrEncodeText');
    if (encodeText) encodeText.value = '';
  }

  window.openBuiltinMiniApp = function (id, navMode) {
    const app = builtinMiniApp(id);
    if (!app) return toast('该系统小程序不存在', true);
    if (typeof checkAndWarnFeatureBan === 'function' && checkAndWarnFeatureBan('games')) return;
    if (typeof poll !== 'undefined' && poll) clearInterval(poll);
    prevView = 'gamesView';
    sw('gamesView', 'c-games');
    X.builtinMiniApps.activeId = app.id;
    setBuiltinMiniAppVisible(true);
    updateRoute(BUILTIN_QR_ROUTE, navMode === undefined ? true : navMode);
    updateMobileNavActive();
  };

  window.closeBuiltinMiniApp = function () {
    X.builtinMiniApps.activeId = '';
    setBuiltinMiniAppVisible(false);
    resetQrWorkspace();
    updateRoute('/games', 'replace');
    updateMobileNavActive();
  };

  window.openMiniTools = function () {
    history.replaceState({ path: BUILTIN_QR_ROUTE }, '', BUILTIN_QR_ROUTE);
    return openBuiltinMiniApp(BUILTIN_QR_ID, false);
  };

  window.openFeatures = function (navMode) {
    if (poll) clearInterval(poll);
    prevView = 'featuresView';
    sw('featuresView', 'c-features');
    updateRoute('/features', navMode === undefined ? true : navMode);
  };

  function gameUpdatedText(latest) {
    return latest && latest.uploadTime ? fmtDate(latest.uploadTime) : '暂无版本';
  }

  function buildBuiltinGameListItemHtml(app) {
    return `<article class='gc game-row builtin-miniapp-card' data-source='builtin' onclick="openBuiltinMiniApp('${jsString(app.id)}')">
      <div class='mini-program-tile'>${featureIcon(app.iconKey, app.title)}</div>
      <div class='game-directory-copy'>
        <div class='game-row-title'>${esc(app.title)} <span class='builtin-source-badge'>系统内置</span><span class='mini-program-category'>${esc(app.category)}</span><span class='game-heat-badge'>随开随用</span></div>
        <div class='game-row-desc'>${esc(app.description)}</div>
        <div class='game-row-meta'>开发者：ChatServer · 使用说明：编码、图片识别与拍照识别 · 更新时间：随系统更新</div>
      </div>
      <div class='mini-program-actions'><button type='button' class='mini-program-open-btn' onclick="event.stopPropagation();openBuiltinMiniApp('${jsString(app.id)}')">打开</button></div>
    </article>`;
  }

  function buildBuiltinGameCardHtml(app) {
    return `<article class='game-card builtin-miniapp-card' data-source='builtin' onclick="openBuiltinMiniApp('${jsString(app.id)}')">
      <div class='game-card-cover builtin-miniapp-cover'>
        <div class='game-card-icon'>${featureIcon(app.iconKey, app.title)}</div>
        <span class='mini-program-category on-cover'>${esc(app.category)}</span>
        <div class='game-card-heat'>随开随用</div>
        <button type='button' class='game-card-play' onclick="event.stopPropagation();openBuiltinMiniApp('${jsString(app.id)}')">打开</button>
      </div>
      <div class='game-card-body'>
        <div class='game-card-title'><span>${esc(app.title)}</span><span class='builtin-source-badge'>系统内置</span></div>
        <div class='game-card-desc'>${esc(app.description)}</div>
        <div class='game-card-meta'><span>ChatServer · 编码 / 识别</span><span>随系统更新</span></div>
      </div>
    </article>`;
  }

  function buildUploadedGameListItemHtml(game, latest) {
    const heat = typeof gameHeatText === 'function' ? gameHeatText(game) : Number(game.heatDisplay || 0);
    const category = game.category || '游戏';
    const developerTag = game.developerIsDeveloper ? " <span class='developer-b'>开发者</span>" : '';
    const manage = game.canManage ? `<button class='tb-btn' onclick="event.stopPropagation();openGameMetaEditor('${jsString(game.id)}')">编辑资料</button><button class='tb-btn' onclick="event.stopPropagation();triggerGameUpdate('${jsString(game.id)}')">发布更新</button>` : '';
    return `<article class='gc game-row' data-source='uploaded' onclick="openGameVersions('${jsString(game.id)}')">
      <div class='mini-program-tile cat-${esc(category)}'>${miniProgramIcon(category)}</div>
      <div class='game-directory-copy'><div class='game-row-title'>${esc(game.title)} <span class='uploaded-source-badge'>用户上传</span><span class='mini-program-category'>${esc(category)}</span>${heat ? `<span class='game-heat-badge'>热度 ${heat}</span>` : ''}</div>
      <div class='game-row-desc'>${esc(game.desc || '暂无小程序介绍')}</div><div class='game-row-meta'>开发者：${esc(game.developerNickname || '未知开发者')}${developerTag} · 更新时间：${esc(gameUpdatedText(latest))}</div></div>
      <div class='mini-program-actions'>${manage}<button class='mini-program-open-btn' onclick="event.stopPropagation();openGameVersions('${jsString(game.id)}')">打开</button></div>
    </article>`;
  }

  function buildUploadedGameCardHtml(game, latest) {
    const heat = typeof gameHeatText === 'function' ? gameHeatText(game) : Number(game.heatDisplay || 0);
    const category = game.category || '游戏';
    const cover = game.coverPath ? `<img class='game-card-cover-image' src='${esc(game.coverPath)}' alt=''>` : '';
    const developerBadge = game.developerIsDeveloper ? " <span class='developer-b'>开发者</span>" : '';
    const manage = game.canManage ? `<div class='mini-program-manage'><button class='tb-btn' onclick="event.stopPropagation();openGameMetaEditor('${jsString(game.id)}')">编辑</button><button class='tb-btn' onclick="event.stopPropagation();triggerGameUpdate('${jsString(game.id)}')">更新</button></div>` : '';
    return `<article class='game-card' data-source='uploaded' onclick="openGameVersions('${jsString(game.id)}')"><div class='game-card-cover cat-${esc(category)}'>${cover}<div class='game-card-icon'>${miniProgramIcon(category)}</div><span class='mini-program-category on-cover'>${esc(category)}</span>${heat ? `<div class='game-card-heat'>热度 ${heat}</div>` : ''}<button class='game-card-play' onclick="event.stopPropagation();openGameVersions('${jsString(game.id)}')">打开</button></div><div class='game-card-body'><div class='game-card-title'><span>${esc(game.title)}</span><span class='uploaded-source-badge'>用户上传</span></div><div class='game-card-desc'>${esc(game.desc || '暂无小程序介绍')}</div><div class='game-card-meta'><span>${esc(game.developerNickname || '未知')}${developerBadge}</span><span>${esc(gameUpdatedText(latest))}</span></div>${manage}</div></article>`;
  }

  window.setGameSearchQuery = function (value) {
    window.gameSearchQuery = String(value || '').trim().toLowerCase();
    renderGamesList(window.gamesCache || []);
  };

  window.setGameCategoryFilter = function (category) {
    window.gameCategoryFilter = category || '全部';
    document.querySelectorAll('#gameCategoryFilters [data-category]').forEach(btn => btn.classList.toggle('active', btn.dataset.category === window.gameCategoryFilter));
    renderGamesList(window.gamesCache || []);
  };

  window.setGamesViewMode = function (mode) {
    window.gamesViewMode = mode === 'cards' ? 'cards' : 'list';
    try { localStorage.setItem('games_view_mode', window.gamesViewMode); } catch (e) {}
    syncGamesViewModeButtons();
    renderGamesList(window.gamesCache || []);
  };

  window.renderGamesList = function (serverGames) {
    const list = q('gamesList');
    if (!list) return;
    syncGamesViewModeButtons();
    const query = String(window.gameSearchQuery || '').trim().toLowerCase();
    const category = window.gameCategoryFilter || '全部';
    const entries = [];
    window.BuiltinMiniApps.forEach(app => entries.push({ type: 'builtin', app }));
    (Array.isArray(serverGames) ? serverGames : []).forEach(game => entries.push({ type: 'uploaded', game }));
    const visible = entries.filter(entry => {
      const item = entry.app || entry.game || {};
      if (category !== '全部' && (item.category || '游戏') !== category) return false;
      if (!query) return true;
      const developer = entry.type === 'builtin' ? 'ChatServer 系统内置' : (item.developerNickname || '');
      return [item.title, item.category, item.description, item.desc, developer].some(value => String(value || '').toLowerCase().includes(query));
    });
    visible.sort((a, b) => {
      if (a.type !== b.type) return a.type === 'builtin' ? -1 : 1;
      if (a.type === 'builtin') return String(a.app.title).localeCompare(String(b.app.title), 'zh-CN');
      const heat = (typeof gameHeatText === 'function' ? gameHeatText(b.game) - gameHeatText(a.game) : 0);
      if (heat) return heat;
      return Number((latestGameVersion(b.game) || {}).uploadTime || 0) - Number((latestGameVersion(a.game) || {}).uploadTime || 0);
    });
    if (!visible.length) {
      list.innerHTML = "<div class='mini-program-empty'>没有符合当前分类和搜索条件的小程序</div>";
      return;
    }
    list.innerHTML = visible.map(entry => {
      if (entry.type === 'builtin') return window.gamesViewMode === 'cards' ? buildBuiltinGameCardHtml(entry.app) : buildBuiltinGameListItemHtml(entry.app);
      const latest = latestGameVersion(entry.game);
      return window.gamesViewMode === 'cards' ? buildUploadedGameCardHtml(entry.game, latest) : buildUploadedGameListItemHtml(entry.game, latest);
    }).join('');
  };

  window.loadGamesList = async function () {
    const response = await fetch('/api/games');
    const data = await safeJson(response);
    if (!response.ok) throw new Error((data && data.error) || '小程序目录加载失败');
    const serverApps = (Array.isArray(data) ? data : []).filter(game => game && game.id !== BUILTIN_QR_ID && game.source !== 'builtin');
    window.gamesCache = serverApps;
    window.gameMap = {};
    serverApps.forEach(game => { window.gameMap[game.id] = game; });
    renderGamesList(serverApps);
    return serverApps;
  };

  const uploadedGameHandlers = {
    openVersions: window.openGameVersions,
    openMetaEditor: window.openGameMetaEditor,
    triggerUpdate: window.triggerGameUpdate,
    submitMeta: window.submitGameMeta,
    submitPublish: window.submitGamePublish
  };
  window.openGameVersions = function (gameId) {
    if (gameId === BUILTIN_QR_ID) return openBuiltinMiniApp(BUILTIN_QR_ID);
    return uploadedGameHandlers.openVersions && uploadedGameHandlers.openVersions(gameId);
  };
  window.openGameMetaEditor = function (gameId) {
    if (gameId === BUILTIN_QR_ID) return toast('系统内置小程序不可编辑', true);
    return uploadedGameHandlers.openMetaEditor && uploadedGameHandlers.openMetaEditor(gameId);
  };
  window.triggerGameUpdate = function (gameId) {
    if (gameId === BUILTIN_QR_ID) return toast('系统内置小程序不发布用户版本', true);
    return uploadedGameHandlers.triggerUpdate && uploadedGameHandlers.triggerUpdate(gameId);
  };
  window.submitGameMeta = function () {
    if (typeof selectedGameId !== 'undefined' && selectedGameId === BUILTIN_QR_ID) return toast('系统内置小程序不可编辑', true);
    return uploadedGameHandlers.submitMeta && uploadedGameHandlers.submitMeta();
  };
  window.submitGamePublish = function () {
    if (typeof gameUploadContext !== 'undefined' && gameUploadContext && gameUploadContext.gameId === BUILTIN_QR_ID) return toast('系统内置小程序不发布用户版本', true);
    return uploadedGameHandlers.submitPublish && uploadedGameHandlers.submitPublish();
  };

  window.openGames = function (navMode) {
    if (typeof checkAndWarnFeatureBan === 'function' && checkAndWarnFeatureBan('games')) return;
    if (typeof poll !== 'undefined' && poll) clearInterval(poll);
    prevView = 'gamesView';
    sw('gamesView', 'c-games');
    syncGamesViewModeButtons();
    const requestedApp = navMode !== undefined && location.pathname === '/games' ? new URLSearchParams(location.search).get('app') : '';
    if (requestedApp === BUILTIN_QR_ID) {
      X.builtinMiniApps.activeId = BUILTIN_QR_ID;
      setBuiltinMiniAppVisible(true);
      updateRoute(BUILTIN_QR_ROUTE, navMode);
    } else {
      X.builtinMiniApps.activeId = '';
      setBuiltinMiniAppVisible(false);
      updateRoute('/games', navMode === undefined ? true : navMode);
    }
    return loadGamesList().catch(error => {
      const list = q('gamesList');
      if (list) list.innerHTML = `<div class='mini-program-empty'>${esc(error.message || '小程序目录加载失败')}</div>`;
    });
  };

  window.handleQRFileSelect = function (input) {
    const file = input.files[0];
    if (!file) return;
    q('qrFileName').textContent = file.name;
    const reader = new FileReader();
    reader.onload = function (e) {
      const dataUrl = e.target.result;
      q('qrPreview').src = dataUrl;
      q('qrPreviewWrap').classList.remove('hidden');
      q('qrDecodeBtn').classList.remove('hidden');
      q('qrResult').classList.add('hidden');
      X.builtinMiniApps.qr.dataUrl = dataUrl;
    };
    reader.readAsDataURL(file);
  };

  window.decodeQR = async function () {
    const dataUrl = X.builtinMiniApps.qr.dataUrl;
    if (!dataUrl) return toast('请先选择二维码图片', true);
    const btn = q('qrDecodeBtn');
    btn.disabled = true;
    btn.textContent = '识别中...';
    try {
      const res = await apiPost('/api/tools/decode-qr', { image: dataUrl });
      const resultDiv = q('qrResult');
      resultDiv.classList.remove('hidden', 'qr-result-error');
      if (res.success === 'true' || (res.success === true)) {
        const text = res.text || '';
        resultDiv.classList.add('qr-result-success');
        resultDiv.innerHTML =
          `<strong>识别成功</strong><div class='qr-result-text'>${esc(text)}</div><button id='qrCopyResultBtn' type='button' class='tb-btn'>复制结果</button>`;
        const copyButton = q('qrCopyResultBtn');
        if (copyButton) copyButton.onclick = () => copyText(text, '已复制');
      } else {
        resultDiv.classList.remove('qr-result-success');
        resultDiv.classList.add('qr-result-error');
        resultDiv.textContent = res.error || '未识别到二维码';
      }
    } catch (e) {
      const resultDiv = q('qrResult');
      resultDiv.classList.remove('hidden', 'qr-result-success');
      resultDiv.classList.add('qr-result-error');
      resultDiv.textContent = `解码失败：${e.message || '未知错误'}`;
    } finally {
      btn.disabled = false;
      btn.textContent = '识别二维码';
    }
  };

  window.encodeQR = async function () {
    const text = (q('qrEncodeText') && q('qrEncodeText').value || '').trim();
    if (!text) return toast('请输入要编码的内容', true);
    const box = q('qrEncodeResult');
    if (!box) return;
    box.classList.remove('hidden', 'qr-result-error');
    box.classList.add('qr-result-pending');
    box.innerHTML = "<div class='x-sub'>生成中...</div>";
    try {
      const res = await apiPost('/api/tools/encode-qr', { text });
      if (!res || !res.image) throw new Error((res && res.error) || '生成失败');
      const safeImg = esc(res.image);
      box.classList.remove('qr-result-pending');
      box.innerHTML = `<div class='qr-output-image'><img src='${safeImg}' alt='生成的二维码'></div><div class='qr-output-actions'><button id='qrCopyContentBtn' type='button' class='tb-btn'>复制内容</button><a class='tb-btn' href='${safeImg}' download='qrcode.png'>保存图片</a></div>`;
      const copyButton = q('qrCopyContentBtn');
      if (copyButton) copyButton.onclick = () => copyText(text, '内容已复制');
    } catch (e) {
      box.classList.remove('qr-result-pending');
      box.classList.add('qr-result-error');
      box.textContent = e.message || '生成失败';
    }
  };

  function ensurePasswordGate() {
    let ov = q('passwordGate');
    if (ov) return ov;
    ov = document.createElement('div');
    ov.id = 'passwordGate';
    ov.className = 'x-gate-ov hidden';
    ov.innerHTML = `<div class='x-gate-card'><div class='x-gate-hero'><div class='x-gate-badge'>安全更新</div><h3>先更新密码，再继续使用</h3><p>为了保护账号，现在需要你把密码升级到 6 到 64 位。更新后即可继续聊天、AI、视频和小程序。</p></div><div class='x-gate-form'><label class='x-gate-label'>当前密码</label><input id='gateOldPassword' type='password' placeholder='请输入当前密码'><label class='x-gate-label'>新密码</label><input id='gateNewPassword' type='password' placeholder='6~64 位，建议字母和数字组合'><label class='x-gate-label'>确认新密码</label><input id='gateRepeatPassword' type='password' placeholder='再输一次新密码'></div><div id='gatePasswordHint' class='x-gate-hint'></div><div class='x-gate-actions'><button type='button' class='x-gate-primary' onclick='submitPasswordGate()'>更新并继续</button></div></div>`;
    document.body.appendChild(ov);
    return ov;
  }
  function maybeShowPasswordGate() {
    if (!window.ME) return;
    if (!/^\/chat(\/|$)/.test(location.pathname)) return;
    const ov = ensurePasswordGate();
    ov.classList.toggle('hidden', !ME.requirePasswordChange);
  }
  window.submitPasswordGate = async function () {
    const oldPassword = (q('gateOldPassword') && q('gateOldPassword').value) || '';
    const newPassword = (q('gateNewPassword') && q('gateNewPassword').value) || '';
    const repeat = (q('gateRepeatPassword') && q('gateRepeatPassword').value) || '';
    const hint = q('gatePasswordHint');
    const fail = msg => { if (hint) hint.textContent = msg; };
    if (newPassword.length < 6 || newPassword.length > 64) return fail('新密码长度必须为 6~64 位');
    if (newPassword !== repeat) return fail('两次输入的新密码不一致');
    try {
      if (!(await window.showConfirm('将登出所有设备，是否继续？'))) return;
      await apiPost('/api/profile/update', { type: 'password', oldPassword, newPassword });
      try { sessionStorage.removeItem('chat_pending_pwd_gate'); } catch (_) {}
      await window.showAlert('已登出，请重新登录');
      location.href = '/login';
    } catch (e) {
      fail(e.message || '更新失败');
    }
  };

  const tutorialSteps = [
    {
      title: '先从公共聊天室开始',
      body: '教程会自动切换到对应页面，并把真正可点击的按钮或输入框圈出来。公共聊天室是所有人都能看到的默认会话。',
      action: '打开公共聊天室',
      target: '#c-public',
      placement: 'right',
      prepare: () => { if (typeof openChat === 'function') openChat('public', '公共聊天室', featureIcon('chat', ''), '所有人可见', false); },
      run: () => tutorialClick('#c-public')
    },
    {
      title: '这里可以直接输入',
      body: '教程不会挡住输入框。你可以现在点击输入框试打一段话，也可以按下面的按钮帮你聚焦。',
      action: '聚焦输入框',
      target: '#msgInp',
      placement: 'top',
      prepare: () => { if (typeof openChat === 'function') openChat('public', '公共聊天室', featureIcon('chat', ''), '所有人可见', false); },
      run: () => tutorialFocus('#msgInp', '试着发送一句问候吧')
    },
    {
      title: '发送按钮也是真的',
      body: '输入内容后点这里就会发送。旁边的加号可以上传图片、文件或从云盘选择内容。',
      action: '尝试发送',
      target: '#sendBtn',
      placement: 'top',
      prepare: () => { if (typeof openChat === 'function') openChat('public', '公共聊天室', featureIcon('chat', ''), '所有人可见', false); },
      run: () => tutorialClick('#sendBtn')
    },
    {
      title: '添加好友从这里进',
      body: '发现好友页可以搜索用户、处理好友请求。教程已经自动跳到这里，左侧这个入口以后也能直接打开。',
      action: '打开发现好友',
      target: '#c-discover',
      placement: 'right',
      prepare: () => { tutorialCloseModal('searchModal'); if (typeof openDiscover === 'function') openDiscover(); },
      run: () => tutorialClick('#c-discover')
    },
    {
      title: '全局搜索可以直接打字',
      body: '搜索框支持找用户、群聊、聊天记录、音乐、视频和 AI 对话。这个输入框同样可以直接点击输入。',
      action: '聚焦搜索框',
      target: '#searchInput',
      placement: 'bottom',
      prepare: () => { if (typeof openSearch === 'function') openSearch(); },
      run: () => tutorialFocus('#searchInput', '输入关键词搜索')
    },
    {
      title: 'AI 助手会随等级解锁',
      body: 'AI 助手里可以聊天，图片和视频生成会随着等级达标出现。教程会自动跳到 AI 页面。',
      action: '打开 AI 助手',
      target: '#c-ai',
      placement: 'right',
      prepare: () => { tutorialCloseModal('searchModal'); if (typeof openAi === 'function') openAi(); },
      run: () => tutorialClick('#c-ai')
    },
    {
      title: '云盘用来长期保存文件',
      body: '聊天里的上传文件可以进入云盘管理，也可以分享、收藏或查看下载记录。',
      action: '打开云盘',
      target: '#c-cloud',
      placement: 'right',
      prepare: () => { tutorialCloseModal('searchModal'); if (typeof openCloud === 'function') openCloud(); },
      run: () => tutorialClick('#c-cloud')
    },
    {
      title: '个人中心和每日成长',
      body: '个人中心可以改头像、资料、气泡皮肤，也能查看自动签到天数与小程序积分进度。',
      action: '打开个人中心',
      target: '#topProfileBtn',
      placement: 'bottom',
      prepare: () => { tutorialCloseModal('searchModal'); if (typeof openProfile === 'function') openProfile(); },
      run: () => tutorialClick('#topProfileBtn')
    }
  ];
  let tutorialIndex = 0;
  let tutorialCompleting = false;
  let tutorialPositionTimer = 0;
  let tutorialBound = false;

  function tutorialHasRegisterMarker() {
    try { return sessionStorage.getItem('chat_new_user_tutorial') === '1'; } catch (_) { return false; }
  }
  function tutorialClearRegisterMarker() {
    try { sessionStorage.removeItem('chat_new_user_tutorial'); } catch (_) {}
  }
  function maybeStartNewUserTutorial() {
    if (!window.ME || ME.requirePasswordChange || q('newUserTutorial')) return;
    if (!tutorialHasRegisterMarker()) return;
    if (ME.tutorialCompleted === true) {
      tutorialClearRegisterMarker();
      return;
    }
    setTimeout(() => renderNewUserTutorial(0), 300);
  }
  function tutorialCloseModal(id) {
    const modal = q(id);
    if (!modal) return;
    if (typeof closeModal === 'function') closeModal(id);
    else modal.classList.remove('show');
  }
  function tutorialFind(selector) {
    if (!selector) return null;
    const parts = String(selector).split(',');
    for (const raw of parts) {
      const sel = raw.trim();
      if (!sel) continue;
      const el = document.querySelector(sel);
      if (!el) continue;
      const rect = el.getBoundingClientRect();
      const visible = rect.width > 0 && rect.height > 0 && getComputedStyle(el).visibility !== 'hidden';
      if (visible) return el;
    }
    return null;
  }
  function tutorialClick(selector) {
    const el = tutorialFind(selector);
    if (!el) return;
    el.click();
    scheduleTutorialPosition(180);
  }
  function tutorialFocus(selector, placeholder) {
    const el = tutorialFind(selector);
    if (!el) return;
    if (placeholder && 'placeholder' in el) el.placeholder = placeholder;
    el.focus();
    if (typeof el.select === 'function' && !el.value) {
      try { el.select(); } catch (_) {}
    }
    scheduleTutorialPosition(80);
  }
  function bindTutorialPositioning() {
    if (tutorialBound) return;
    tutorialBound = true;
    window.addEventListener('resize', scheduleTutorialPosition);
    window.addEventListener('orientationchange', scheduleTutorialPosition);
    window.addEventListener('scroll', scheduleTutorialPosition, true);
    document.addEventListener('focusin', scheduleTutorialPosition, true);
  }
  function unbindTutorialPositioning() {
    if (!tutorialBound) return;
    tutorialBound = false;
    window.removeEventListener('resize', scheduleTutorialPosition);
    window.removeEventListener('orientationchange', scheduleTutorialPosition);
    window.removeEventListener('scroll', scheduleTutorialPosition, true);
    document.removeEventListener('focusin', scheduleTutorialPosition, true);
  }
  function renderNewUserTutorial(index) {
    tutorialIndex = Math.max(0, Math.min(tutorialSteps.length - 1, index));
    const step = tutorialSteps[tutorialIndex];
    let ov = q('newUserTutorial');
    if (!ov) {
      ov = document.createElement('div');
      ov.id = 'newUserTutorial';
      ov.className = 'tutorial-ov';
      document.body.appendChild(ov);
    }
    bindTutorialPositioning();
    ov.innerHTML = `<div class='tutorial-mask tutorial-mask-top'></div><div class='tutorial-mask tutorial-mask-left'></div><div class='tutorial-mask tutorial-mask-right'></div><div class='tutorial-mask tutorial-mask-bottom'></div><div class='tutorial-hole' id='newUserTutorialHole'></div><div class='tutorial-pointer' id='newUserTutorialPointer'></div><div class='tutorial-card' id='newUserTutorialCard'><div class='tutorial-progress'><span style='width:${Math.round((tutorialIndex + 1) / tutorialSteps.length * 100)}%'></span></div><div class='tutorial-kicker'>新手教程 ${tutorialIndex + 1}/${tutorialSteps.length}</div><div class='tutorial-body'><h3>${esc(step.title)}</h3><p>${esc(step.body)}</p></div><div class='tutorial-actions'><button type='button' class='tutorial-ghost' onclick='tutorialSkip()'>跳过并不再显示</button><button type='button' onclick='tutorialPrev()' ${tutorialIndex === 0 ? 'disabled' : ''}>上一步</button><button type='button' class='tutorial-action' onclick='runTutorialStepAction()'>${esc(step.action || '试一下')}</button><button type='button' class='tutorial-primary' onclick='tutorialNext()'>${tutorialIndex === tutorialSteps.length - 1 ? '完成' : '下一步'}</button></div></div>`;
    try {
      const prepared = typeof step.prepare === 'function' ? step.prepare() : null;
      if (prepared && typeof prepared.then === 'function') prepared.finally(() => scheduleTutorialPosition(120));
    } catch (_) {}
    const target = tutorialFind(step.target);
    if (target) {
      try { target.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'center' }); } catch (_) { target.scrollIntoView(); }
    }
    scheduleTutorialPosition(260);
  }
  function scheduleTutorialPosition(delay) {
    if (!q('newUserTutorial')) return;
    clearTimeout(tutorialPositionTimer);
    tutorialPositionTimer = setTimeout(positionNewUserTutorial, typeof delay === 'number' ? delay : 30);
  }
  function setTutorialRect(el, left, top, width, height) {
    if (!el) return;
    el.style.left = Math.round(left) + 'px';
    el.style.top = Math.round(top) + 'px';
    el.style.width = Math.max(0, Math.round(width)) + 'px';
    el.style.height = Math.max(0, Math.round(height)) + 'px';
  }
  function clamp(n, min, max) { return Math.max(min, Math.min(max, n)); }
  function placeTutorialPointer(cardRect, targetRect) {
    const pointer = q('newUserTutorialPointer');
    if (!pointer || !cardRect || !targetRect) return;
    const tx = targetRect.left + targetRect.width / 2;
    const ty = targetRect.top + targetRect.height / 2;
    let sx = clamp(tx, cardRect.left, cardRect.right);
    let sy = clamp(ty, cardRect.top, cardRect.bottom);
    if (tx >= cardRect.left && tx <= cardRect.right) sy = ty < cardRect.top ? cardRect.top : cardRect.bottom;
    if (ty >= cardRect.top && ty <= cardRect.bottom) sx = tx < cardRect.left ? cardRect.left : cardRect.right;
    const dx = tx - sx;
    const dy = ty - sy;
    const len = Math.sqrt(dx * dx + dy * dy);
    if (len < 18) { pointer.style.display = 'none'; return; }
    pointer.style.display = '';
    pointer.style.left = Math.round(sx) + 'px';
    pointer.style.top = Math.round(sy) + 'px';
    pointer.style.width = Math.round(len) + 'px';
    pointer.style.transform = `rotate(${Math.atan2(dy, dx)}rad)`;
  }
  function positionNewUserTutorial() {
    const ov = q('newUserTutorial');
    const card = q('newUserTutorialCard');
    if (!ov || !card) return;
    const step = tutorialSteps[tutorialIndex] || {};
    const target = tutorialFind(step.target);
    const vw = window.innerWidth || document.documentElement.clientWidth || 0;
    const vh = window.innerHeight || document.documentElement.clientHeight || 0;
    const hole = q('newUserTutorialHole');
    const topMask = ov.querySelector('.tutorial-mask-top');
    const leftMask = ov.querySelector('.tutorial-mask-left');
    const rightMask = ov.querySelector('.tutorial-mask-right');
    const bottomMask = ov.querySelector('.tutorial-mask-bottom');
    card.style.visibility = 'hidden';
    card.style.left = '16px';
    card.style.top = '16px';
    const cardW = Math.min(390, Math.max(280, vw - 32));
    card.style.width = cardW + 'px';
    const cardH = card.offsetHeight || 260;
    let cardLeft = Math.max(16, (vw - cardW) / 2);
    let cardTop = Math.max(16, (vh - cardH) / 2);
    let targetRect = null;
    if (target) {
      const raw = target.getBoundingClientRect();
      const pad = 8;
      targetRect = {
        left: clamp(raw.left - pad, 8, Math.max(8, vw - 8)),
        top: clamp(raw.top - pad, 8, Math.max(8, vh - 8)),
        right: clamp(raw.right + pad, 8, Math.max(8, vw - 8)),
        bottom: clamp(raw.bottom + pad, 8, Math.max(8, vh - 8))
      };
      targetRect.width = Math.max(1, targetRect.right - targetRect.left);
      targetRect.height = Math.max(1, targetRect.bottom - targetRect.top);
      setTutorialRect(topMask, 0, 0, vw, targetRect.top);
      setTutorialRect(leftMask, 0, targetRect.top, targetRect.left, targetRect.height);
      setTutorialRect(rightMask, targetRect.right, targetRect.top, vw - targetRect.right, targetRect.height);
      setTutorialRect(bottomMask, 0, targetRect.bottom, vw, vh - targetRect.bottom);
      setTutorialRect(hole, targetRect.left, targetRect.top, targetRect.width, targetRect.height);
      if (hole) hole.style.display = '';
      const gap = 16;
      const placement = step.placement || 'auto';
      const canRight = targetRect.right + gap + cardW <= vw - 16;
      const canLeft = targetRect.left - gap - cardW >= 16;
      const canBottom = targetRect.bottom + gap + cardH <= vh - 16;
      const canTop = targetRect.top - gap - cardH >= 16;
      if ((placement === 'right' && canRight) || (placement === 'auto' && canRight)) {
        cardLeft = targetRect.right + gap;
        cardTop = clamp(targetRect.top + targetRect.height / 2 - cardH / 2, 16, Math.max(16, vh - cardH - 16));
      } else if ((placement === 'left' && canLeft) || (placement === 'auto' && canLeft)) {
        cardLeft = targetRect.left - gap - cardW;
        cardTop = clamp(targetRect.top + targetRect.height / 2 - cardH / 2, 16, Math.max(16, vh - cardH - 16));
      } else if ((placement === 'top' && canTop) || (!canBottom && canTop)) {
        cardLeft = clamp(targetRect.left + targetRect.width / 2 - cardW / 2, 16, Math.max(16, vw - cardW - 16));
        cardTop = targetRect.top - gap - cardH;
      } else {
        cardLeft = clamp(targetRect.left + targetRect.width / 2 - cardW / 2, 16, Math.max(16, vw - cardW - 16));
        cardTop = canBottom ? targetRect.bottom + gap : clamp(vh - cardH - 16, 16, Math.max(16, vh - cardH - 16));
      }
    } else {
      setTutorialRect(topMask, 0, 0, vw, vh);
      setTutorialRect(leftMask, 0, 0, 0, 0);
      setTutorialRect(rightMask, 0, 0, 0, 0);
      setTutorialRect(bottomMask, 0, 0, 0, 0);
      if (hole) hole.style.display = 'none';
      const pointer = q('newUserTutorialPointer');
      if (pointer) pointer.style.display = 'none';
    }
    card.style.left = Math.round(cardLeft) + 'px';
    card.style.top = Math.round(cardTop) + 'px';
    card.style.visibility = '';
    if (targetRect) placeTutorialPointer(card.getBoundingClientRect(), targetRect);
  }
  async function completeNewUserTutorial(message) {
    if (tutorialCompleting) return;
    tutorialCompleting = true;
    try {
      await apiPost('/api/tutorial/complete', {});
      ME.tutorialCompleted = true;
      tutorialClearRegisterMarker();
      clearTimeout(tutorialPositionTimer);
      unbindTutorialPositioning();
      const ov = q('newUserTutorial');
      if (ov) ov.remove();
      toast(message || '新手教程已完成');
    } catch (e) {
      tutorialCompleting = false;
      toast(e.message || '教程完成状态保存失败', true);
    }
  }
  window.tutorialPrev = function () { renderNewUserTutorial(tutorialIndex - 1); };
  window.tutorialNext = function () {
    if (tutorialIndex < tutorialSteps.length - 1) return renderNewUserTutorial(tutorialIndex + 1);
    return completeNewUserTutorial('新手教程已完成，以后登录不会再弹出');
  };
  window.tutorialSkip = function () { return completeNewUserTutorial('已跳过新手教程，以后登录不会再弹出'); };
  window.runTutorialStepAction = function () {
    const step = tutorialSteps[tutorialIndex];
    if (step && typeof step.run === 'function') step.run();
    else tutorialNext();
  };

  function jsString(v) {
    if (typeof eJ === 'function') return eJ(String(v == null ? '' : v));
    return String(v == null ? '' : v).replace(/\\/g, '\\\\').replace(/'/g, "\\'");
  }
  function ensureMediaSearchUI() {
    const input = q('searchInput');
    if (input) input.placeholder = '搜索用户、群聊、消息、音乐、视频...';
    const aiBtn = q('searchScopeAi');
    if (aiBtn && !q('searchScopeMusic')) {
      aiBtn.insertAdjacentHTML('afterend', '<button class="tb-btn" id="searchScopeMusic" onclick="setSearchScope(&quot;music&quot;)">音乐</button><button class="tb-btn" id="searchScopeVideo" onclick="setSearchScope(&quot;video&quot;)">视频</button>');
    }
  }
  function applySearchScopeButtonState(scope) {
    const scopes = { All: 'all', Users: 'users', Groups: 'groups', Messages: 'messages', Sent: 'sent', Ai: 'ai', Music: 'music', Video: 'video' };
    Object.keys(scopes).forEach(name => {
      const btn = q('searchScope' + name);
      if (!btn) return;
      const active = scopes[name] === scope;
      btn.style.background = active ? 'var(--ac)' : '';
      btn.style.color = active ? '#fff' : '';
    });
  }
  window.setSearchScope = function (scope) {
    X.globalSearchScope = scope || 'all';
    ensureMediaSearchUI();
    applySearchScopeButtonState(X.globalSearchScope);
  };
  window.openSearch = function () {
    ensureMediaSearchUI();
    const input = q('searchInput');
    const results = q('searchResults');
    if (input) input.value = '';
    if (results) results.innerHTML = '';
    openModal('searchModal');
    window.setSearchScope(X.globalSearchScope || 'all');
    if (input) input.focus();
  };
  function renderSearchSection(title, rows) {
    return rows && rows.length ? `<div style="font-size:13px;color:var(--muted);padding:8px 0 4px">${title}</div>${rows.join('')}` : '';
  }
  window.doSearch = async function () {
    ensureMediaSearchUI();
    const input = q('searchInput');
    const keyword = input ? input.value.trim() : '';
    if (!keyword) return;
    const box = q('searchResults');
    if (!box) return;
    box.innerHTML = '<div style="color:var(--muted);text-align:center;padding:20px">搜索中...</div>';
    try {
      const scope = X.globalSearchScope || 'all';
      let url = '/api/search?q=' + encodeURIComponent(keyword) + '&scope=' + encodeURIComponent(scope);
      if (typeof room !== 'undefined' && room && room !== 'public') url += '&room=' + encodeURIComponent(room);
      const d = await apiGet(url);
      let html = '';
      const users = Array.isArray(d.users) ? d.users : [];
      html += renderSearchSection('用户', users.map(u => {
        const badges = `${u.isPrimarySuperAdmin ? ' <span class="owner-b">服主</span>' : ''}${u.isSuperAdmin ? ' <span class="sa-b">超管</span>' : ''}${u.isDeveloper ? ' <span class="developer-b">开发者</span>' : ''}`;
        return `<div class="search-item" onclick="closeModal('searchModal');openUserProfile('${jsString(u.userId)}')"><div style="font-weight:700">${esc(u.nickname)}${badges} <span class="lv-badge lv-${esc((u.levelDisplay || 'Lv1').replace(/[^0-9]/g, ''))}">${esc(u.levelDisplay || 'Lv1')}</span></div></div>`;
      }));
      const groups = Array.isArray(d.groups) ? d.groups : [];
      html += renderSearchSection('群聊', groups.map(g => `<div class="search-item" onclick="closeModal('searchModal');joinAndOpenGroup('${jsString(g.groupId)}')"><div style="font-weight:700">${esc(g.groupName)}</div><div style="font-size:12px;color:var(--muted)">${Number(g.memberCount || 0)} 人</div></div>`));
      const messages = Array.isArray(d.messages) ? d.messages : [];
      html += renderSearchSection(scope === 'sent' ? '发送消息' : '聊天记录', messages.map(m => `<div class="search-item" onclick="closeModal('searchModal');scrollToMessage('${jsString(m.chatRoomId)}','${jsString(m.id)}')"><div style="font-size:12px;color:var(--muted)">${esc(m.fromNickname || '')}${m.isDeveloper ? ' <span class="developer-b">开发者</span>' : ''} · ${typeof formatChatTime === 'function' ? formatChatTime(m.timestamp) : fmtDate(m.timestamp)}</div><div style="font-size:13px">${esc(m.content || '')}</div></div>`));
      const aiConversations = Array.isArray(d.aiConversations) ? d.aiConversations : [];
      html += renderSearchSection('AI 对话', aiConversations.map(c => `<div class="search-item" onclick="closeModal('searchModal');openAi();setTimeout(function(){aiOpenConversation('${jsString(c.id)}');},100)"><div style="font-weight:700">${esc(c.title || '新标签')}</div><div style="font-size:12px;color:var(--muted)">${esc(c.type || 'chat')} · ${esc(c.modelId || '')}</div></div>`));
      const musicTracks = Array.isArray(d.musicTracks) ? d.musicTracks : [];
      html += renderSearchSection('音乐', musicTracks.map(t => `<div class="search-item" onclick="closeModal('searchModal');openMusic();setTimeout(function(){playTrackById('${jsString(t.id)}');},260)"><div style="font-weight:700">${esc(t.title || '未命名歌曲')}</div><div style="font-size:12px;color:var(--muted)">${esc(t.artist || '未知歌手')}${t.album ? ' · ' + esc(t.album) : ''} · ${Number(t.playCount || 0).toLocaleString('zh-CN')} 次播放</div></div>`));
      const videos = Array.isArray(d.videos) ? d.videos : [];
      html += renderSearchSection('视频', videos.map(v => `<div class="search-item" onclick="closeModal('searchModal');openVideos();setTimeout(function(){playVideoById('${jsString(v.id)}');},360)"><div style="font-weight:700">${esc(v.title || '未命名视频')}</div><div style="font-size:12px;color:var(--muted)">${esc(v.categoryName || '未分类')} · ${Number(v.playCount || 0).toLocaleString('zh-CN')} 次播放</div><div style="font-size:13px;margin-top:4px">${esc(v.description || '')}</div></div>`));
      box.innerHTML = html || '<div style="color:var(--muted);text-align:center;padding:20px">无结果</div>';
    } catch (e) {
      box.innerHTML = '<div style="color:#ef4444">搜索失败</div>';
    }
  };

  ensureExtraDom();
  maybeShowPasswordGate();
  maybeStartNewUserTutorial();
  refreshProfileExtras();
  setInterval(async () => {
    try {
      const d = await apiGet('/api/me');
      if (d) {
        syncMeFromSnapshot(d);
        if (activeViewId() === 'profileView' && typeof initProfileUI === 'function') initProfileUI();
      }
    } catch (e) {}
  }, 60000);
  const initAdaptiveShell = () => {
    applyMobileShell();
    window.addEventListener('resize', applyMobileShell);
    window.addEventListener('orientationchange', applyMobileShell);
  };
  if (document.readyState === 'complete') initAdaptiveShell();
  else window.addEventListener('load', initAdaptiveShell, { once: true });
})();

function setProfSection(sec) {
  var btns = document.querySelectorAll('.profile-tab-btn');
  btns.forEach(function(b) {
    if ((sec === 'all' && b.getAttribute('onclick').indexOf("'all'") !== -1) ||
        (sec === 'basic' && b.getAttribute('onclick').indexOf("'basic'") !== -1) ||
        (sec === 'appearance' && b.getAttribute('onclick').indexOf("'appearance'") !== -1) ||
        (sec === 'account' && b.getAttribute('onclick').indexOf("'account'") !== -1)) {
      b.classList.add('active');
    } else {
      b.classList.remove('active');
    }
  });
  var cards = document.querySelectorAll('.pc[data-pc-section]');
  cards.forEach(function(c) {
    var s = c.getAttribute('data-pc-section');
    if (sec === 'all' || s === sec) {
      c.style.display = '';
    } else {
      c.style.display = 'none';
    }
  });
}
window.setProfSection = setProfSection;

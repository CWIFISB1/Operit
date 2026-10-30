"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
// ============ 记忆成长 · hook 实现（独立模块）============
// 平台 resolveDurableFunctionRef 要求注册的函数必须带
//   __operit_toolpkg_module_path 与 __operit_toolpkg_export_name
// 这两个标记只由 require() 机制自动打上。入口 main.js 由平台直接执行，
// 里面的函数没有这两个标记，注册必抛
//   "function must be exported from a toolpkg module"
// 因此 hook 实现必须放在独立文件，由 main.js require 进来。

var store = require("./growth_store.js");
var engine = require("./growth_engine.js");
var evo = require("./evolution_memory.js");

var _inited = false;
var _lastBrief = null;
var _lastTrigger = null;
var _registered = null;
var _lastInput = null;
var _lastExtract = null;
// 合并候选队列：纯内存，随 hook_state 一起落盘（hook 内禁止 IO）
var _mergeCandidates = [];

// 候选队列上限。超出只保留最新的：旧候选对应的条目可能已被处理过，
// 留着只会在注入里刷屏。
var MERGE_QUEUE_MAX = 40;

// ===== 工具失败缓冲（纯内存，hook 专用）=====
// 为什么必须加：记忆库此前只注册了 4 个 hook（message / input / compose / finalize），
// 没有 tool_lifecycle。结果是「工具调用失败」这个最高价值的自进化信号源
// 从来没有进入过进化记忆 —— 只能靠模型自觉调 mem_lesson 手动上报，
// 而模型恰恰在失败时最容易忘记上报。
// 约束：hook 内绝对禁止 IO（否则递归触发工具调用 -> hook -> IO），
// 所以这里只落内存，真正写盘由 mem_evolution(flush_failures=1) 在工具链里完成。
var _failBuf = {};
var _failSeen = 0;
var _failWritten = 0;
var FAIL_BUF_MAX = 40;
function failFp(toolName, errMsg) {
    return String(toolName || "") + "|" + String(errMsg || "").slice(0, 60);
}
// 纯内存同步记录：不 await，不碰 Tools.*
function recordToolFailureSync(toolName, errMsg, extra) {
    try {
        var fp = failFp(toolName, errMsg);
        var now = Date.now();
        var rec = _failBuf[fp];
        if (rec) {
            rec.count = (rec.count || 1) + 1;
            rec.lastAt = now;
            rec.lastMsg = String(errMsg || "").slice(0, 240);
            // 重复失败必须刷新 extra：否则留在缓冲里的是几轮前的旧参数，
            // 落盘后写进进化记忆的「证据」就是假的。
            if (extra) rec.extra = extra;
        } else {
            var keys = Object.keys(_failBuf);
            if (keys.length >= FAIL_BUF_MAX) {
                var oldestK = keys[0];
                var oldestAt = Infinity;
                for (var i = 0; i < keys.length; i += 1) {
                    var t = (_failBuf[keys[i]] && _failBuf[keys[i]].firstAt) || 0;
                    if (t < oldestAt) { oldestAt = t; oldestK = keys[i]; }
                }
                delete _failBuf[oldestK];
            }
            _failBuf[fp] = {
                tool: String(toolName || "").slice(0, 80),
                msg: String(errMsg || "").slice(0, 240),
                count: 1,
                firstAt: now,
                lastAt: now,
                extra: extra || null
            };
        }
        _failSeen += 1;
        return true;
    } catch (_eRF) {
        return false;
    }
}
// 纯内存视图，供 mem_diag 显示「缓冲里还压着几条没落盘」
function getFailBuffer() {
    var out = [];
    try {
        var ks = Object.keys(_failBuf);
        for (var i = 0; i < ks.length; i += 1) {
            var r = _failBuf[ks[i]];
            if (!r) continue;
            out.push({ tool: r.tool, msg: r.msg, count: r.count, ageMs: Date.now() - (r.lastAt || 0) });
        }
        out.sort(function (a, b) { return b.count - a.count; });
    } catch (_eGB) { }
    return { pending: out.length, seen: _failSeen, written: _failWritten, items: out.slice(0, 8) };
}
// 取出并清空内存缓冲。纯内存操作，无 IO，可在工具里安全调用后再落盘。
function drainFailBuffer() {
    var out = [];
    try {
        var ks = Object.keys(_failBuf);
        for (var i = 0; i < ks.length; i += 1) {
            var r = _failBuf[ks[i]];
            if (!r) continue;
            out.push({ tool: r.tool, msg: r.msg, count: r.count, firstAt: r.firstAt, lastAt: r.lastAt, extra: r.extra });
        }
        _failBuf = {};
    } catch (_eDF) { }
    return out;
}
// 落盘成功后由工具回填计数（用于 mem_diag 显示闭环）
function markFailWritten(n) {
    _failWritten += (Number(n) || 0);
    return _failWritten;
}

// 统一入口：去重 + 封顶。
// 为什么必须有去重：实测同一句话被推了 55 次（queued_for_merge 50 -> 55），
// mergeCandidates 里 20 条全是同一个标题，唯一标题数 = 1。
// 原实现三处 push 都不查重，只要同一句反复出现在对话里就无限堆。
function pushMergeCandidate(c) {
    if (!c) return -1;
    var inc = String(c.incoming_title || "");
    var eid = String(c.existing_id || "");
    // 去重键：incoming_title + existing_id。两者都同 = 同一个待处理项。
    for (var i = 0; i < _mergeCandidates.length; i += 1) {
        var e = _mergeCandidates[i];
        if (!e) continue;
        if (String(e.incoming_title || "") === inc && String(e.existing_id || "") === eid) {
            // 已存在：只刷新时间与分数，不新增
            e.at = c.at || Date.now();
            if (typeof c.score === "number") e.score = c.score;
            return _mergeCandidates.length;
        }
    }
    _mergeCandidates.push(c);
    // 封顶：保留最新的 MERGE_QUEUE_MAX 条
    if (_mergeCandidates.length > MERGE_QUEUE_MAX) {
        _mergeCandidates = _mergeCandidates.slice(-MERGE_QUEUE_MAX);
    }
    return _mergeCandidates.length;
}

// 队列健康度（自检用）。纯计算，不碰 IO。
function mergeQueueHealth() {
    var n = _mergeCandidates.length;
    if (n === 0) return { total: 0, unique: 0, dupRatio: 0, saturated: false, healthy: true };
    var seen = {};
    var unique = 0;
    for (var i = 0; i < n; i += 1) {
        var e = _mergeCandidates[i];
        if (!e) continue;
        var k = String(e.incoming_title || "") + "|" + String(e.existing_id || "");
        if (!seen[k]) { seen[k] = 1; unique += 1; }
    }
    var dupRatio = n > 0 ? 1 - (unique / n) : 0;
    // 判据：去重后唯一率低于 60% = 去重失效（真实缺陷的直接证据）
    // saturated: 到达上限说明积压没被消费（模型长时间不调 mem_merge_review）
    return {
        total: n,
        unique: unique,
        dupRatio: Math.round(dupRatio * 1000) / 1000,
        saturated: n >= MERGE_QUEUE_MAX,
        healthy: dupRatio < 0.4
    };
}
// 审计状态：轮次 + 上次全量审计 + 上次结果摘要
var _auditState = { round: 0, lastFullAuditAt: 0, lastResult: null };
// evolution cache preload reentrancy lock (prevents hook recursion)
var _evoLoading = false;
var AUDIT_INTERVAL_ROUNDS = 10;

// 预载失败退避状态：连续失败 n 次，则跳过 2^n 轮再试，避免每轮都打盘。
var _evoFailStreak = 0;
var _evoSkipRounds = 0;

// ===== 健康基线内存副本 =====
// 为什么必须放内存：hook 内禁止 IO，而 degradation_detect 全是 async 读盘。
// 预载期读一次，注入期只读内存变量。
var _healthCache = null;
var _degMod = null;

function getDegMod() {
    if (_degMod) return _degMod;
    try { _degMod = require("./degradation_detect.js"); } catch (_e) { _degMod = null; }
    return _degMod;
}

async function preloadHealth() {
    try {
        var deg = getDegMod();
        if (!deg || typeof deg.detect !== "function") {
            _healthCache = { ok: false, err: "degradation_detect 不可用" };
            return;
        }
        // 先查读盘错误：读失败绝不能伪装成「空库=健康」。
        // 实测 S6：不加这层时 failAll 场景仍报 ok:true, samples:0。
        if (typeof deg.histError === "function") {
            var he = String(deg.histError() || "");
            if (he) {
                _healthCache = { ok: false, err: "健康历史读取失败：" + he.slice(0, 80) };
                return;
            }
        }
        // 用 all：判定基于全部 source 的合并样本（跨包聚合）。
        var det = await deg.detect("all", {});
        var samples = (typeof deg.readSamples === "function") ? await deg.readSamples("all") : [];
        var base = (typeof deg.emaBaseline === "function")
            ? deg.emaBaseline(samples, "issues", 0.9)
            : { ok: false };
        var cur = samples.length ? (Number(samples[samples.length - 1].issues) || 0) : 0;
        var anom = (typeof deg.detectAnomaly === "function")
            ? deg.detectAnomaly(cur, base, { higherIsBetter: false }) : null;
        var reg = (typeof deg.detectRegression === "function")
            ? deg.detectRegression(cur, (base && base.ok) ? base.mean : 0, {}) : null;
        _healthCache = {
            ok: true, at: Date.now(),
            level: String((det && det.level) || "ok"),
            samples: Number((det && det.samples) || 0),
            current: cur,
            best: (det && det.best != null) ? det.best : null,
            baselineOk: !!(base && base.ok),
            baselineMean: (base && base.ok) ? base.mean : null,
            anomaly: anom || null,
            regression: reg || null
        };
    } catch (e) {
        _healthCache = { ok: false, err: String((e && e.message) || e) };
    }
}

async function initOnce() {
    if (_inited) return;
    // 退避：还没到重试轮次就直接返回，省 IO。
    if (_evoSkipRounds > 0) { _evoSkipRounds -= 1; return; }
    try { await store.ensureDir(); } catch (_e0) { }
    // 健康基线预载（允许 IO 的窗口期）。失败不影响 initOnce 判定，
    // 由 _healthCache.ok=false 在注入块里降级为告警。
    try { await preloadHealth(); } catch (_eH) { }
    // 进化记忆预载：把失败/模板库读进内存，之后 hook 内只用内存副本（hook 禁同步 IO）
    // guarded preload - hook must never do unbounded IO.
    // Tools.Files.read here could fire tool-call event -> hook -> read ...
    // Reentrancy lock: skip if already loading; on failure cache stays unready
    // and buildEvolutionBlockFromCache returns "" (silent no-op, no retry).
    // 预载：失败必须可重试。
    // 旧实现 _inited = true 在 await 之前，一次失败=整个会话静默失效
    // （test_preload.js C2 实测：loadCalls 恒为 1）。
    if (!_evoLoading && typeof evo.loadCache === "function") {
        _evoLoading = true;
        var loadedOk = false;
        try {
            var r = await evo.loadCache(_evoFailStreak > 0);
            loadedOk = !(r && r.error);
        } catch (_eEvo) { loadedOk = false; } finally { _evoLoading = false; }
        if (loadedOk && (!evo.cacheLoaded || evo.cacheLoaded())) {
            _inited = true;
            _evoFailStreak = 0;
            _evoSkipRounds = 0;
        } else {
            // 没成就把 _inited 复位，下轮还能再试；并记失败次数做退避。
            _inited = false;
            _evoFailStreak += 1;
            _evoSkipRounds = Math.min(16, Math.pow(2, _evoFailStreak));
        }
    } else if (typeof evo.loadCache !== "function") {
        _inited = true;
    }
}

function reportError(tag, e) {
    var msg = String(tag || "err") + " " + String((e && e.message) || e);
    try { store.appendLog("hook_" + msg); } catch (_e1) { }
    try { console.log("[MEMGROWTH] " + msg); } catch (_e2) { }
    return msg;
}

function markRegistered(ok) {
    // lifecycle / input / message 必须一并落盘：此前只记 compose+finalize，
    // mem_diag 看不出 tool_lifecycle 到底注册成功没（注册失败会被 inspect 成「本来就没写」）。
    _registered = {
        at: Date.now(),
        compose: !!(ok && ok.compose),
        finalize: !!(ok && ok.finalize),
        input: !!(ok && ok.input),
        message: !!(ok && ok.message),
        lifecycle: !!(ok && ok.lifecycle)
    };
    try { store.appendLog("register compose=" + _registered.compose + " finalize=" + _registered.finalize + " input=" + _registered.input + " message=" + _registered.message + " lifecycle=" + _registered.lifecycle); } catch (_e3) { }
    try { saveState(); } catch (_e3b) { }
    return _registered;
}

function getRegistered() { return _registered; }

// -------- hook 状态落盘 --------
// 平台机制：debug_install_toolpkg 会重建 JS 模块，内存态全部清零。
// 所以 hook 的 last_* 必须落盘，否则 mem_diag 永远读到 null（假阴性）。
var STATE_FILE = "hook_state.json";

async function saveState() {
    try {
        var obj = {
            at: Date.now(),
            registered: _registered,
            lastInput: _lastInput,
            lastTrigger: _lastTrigger,
            lastBrief: _lastBrief,
            lastExtract: _lastExtract,
            mergeCandidates: _mergeCandidates.slice(-20),
            auditState: _auditState
        };
        await store.writeText(STATE_FILE, JSON.stringify(obj));
    } catch (_eS) { }
}

async function loadState() {
    try {
        var txt = await store.readText(STATE_FILE);
        if (!txt) return null;
        return JSON.parse(txt);
    } catch (_eL) { return null; }
}

var SKIP_EXACT = ["嗯", "哦", "喔", "好", "行", "可以", "继续", "是", "对", "不", "没有", "收到", "ok", "okay", "yes", "no", "嗯嗯", "好的", "行吧", "可以吧"];

var HINTS = ["记得", "之前", "上次", "以前", "曾经", "历史", "老规矩", "照旧", "记忆", "经验", "教训", "总结", "回顾", "remember", "recall", "previously"];

function shouldPrompt(text) {
    var t = String(text || "").trim();
    if (!t) return { yes: false, reason: "empty" };
    var bare = t.replace(/[。！？!?.,，~～\s]/g, "").toLowerCase();
    if (bare.length === 0) return { yes: false, reason: "punctuation_blank" };
    if (SKIP_EXACT.indexOf(bare) >= 0) return { yes: false, reason: "trivial" };
    var lowered = t.toLowerCase();
    for (var k = 0; k < HINTS.length; k += 1) {
        if (lowered.indexOf(HINTS[k]) >= 0) return { yes: true, reason: "explicit_hint" };
    }
    if (bare.length >= 4) return { yes: true, reason: "substantive" };
    return { yes: false, reason: "too_short" };
}

var _lastUserText = "";

function rememberUserText(t) {
    var s = String(t || "").trim();
    if (s) _lastUserText = s.slice(0, 2000);
    return _lastUserText;
}

function pickUserText(payload) {
    var raw = payload.rawInput || payload.raw_input || payload.userInput;
    if (raw && String(raw).trim()) return String(raw).trim();
    var hist = payload.chatHistory || payload.chat_history;
    if (hist && hist.length) {
        for (var p = hist.length - 1; p >= 0; p -= 1) {
            var item = hist[p];
            if (item) {
                var kind = String(item.kind || item.role || "").toUpperCase();
                if (kind === "USER" || kind === "HUMAN") {
                    var c = String(item.content || "").trim();
                    if (c) return c;
                }
            }
        }
    }
    var pi = payload.processedInput || payload.processed_input;
    if (pi && String(pi).trim()) return String(pi).trim();
    // 兜底：after_compose 阶段 rawInput 常为空，用上一轮记住的用户输入
    if (_lastUserText) return _lastUserText;
    return "";
}

// ============================================================
// 审计模式：<memory_audit> 注入块
// 轻量增量：_mergeCandidates 非空即报
// 全量审计：每 AUDIT_INTERVAL_ROUNDS 轮跑 auditScan
// 只追加文本，不调工具、不做 IO，保证 hook 不递归
// ============================================================
function buildAuditBlock(idx, forceFull) {
    var nl = String.fromCharCode(10);
    var L = [];
    var now = Date.now();

    var pending = _mergeCandidates.length;
    if (pending > 0) {
        // 队列健康度：先算出真实唯一数，再决定怎么报。
        // 实测缺陷：原实现直接报 "有 20 组"，其实 20 条全是同一个标题，
        // 模型看到 "20 组" 会以为积压很多，实际只有 1 件事要处理。
        var mq = mergeQueueHealth();
        var head = mq.unique === pending
            ? ("[增量] 有 " + pending + " 组疑似重复/冲突，等你判断：")
            : ("[增量] 有 " + mq.unique + " 件待处理（队列 " + pending + " 条含重复，去重后 " + mq.unique + " 件）：");
        L.push(head);
        // 去重后再展示，避免同一件事刷三行
        var seenKey = {};
        var uniqShown = [];
        for (var ui = _mergeCandidates.length - 1; ui >= 0 && uniqShown.length < 3; ui -= 1) {
            var uc = _mergeCandidates[ui];
            if (!uc) continue;
            var uk = String(uc.incoming_title || "") + "|" + String(uc.existing_id || "");
            if (seenKey[uk]) continue;
            seenKey[uk] = 1;
            uniqShown.push(uc);
        }
        for (var i = 0; i < uniqShown.length; i += 1) {
            var c = uniqShown[i];
            L.push("  · [" + c.reason + " " + c.score + "] " +
                String(c.existing_title || "").slice(0, 26) +
                "  <->  " + String(c.incoming_title || "").slice(0, 26));
        }
        if (mq.saturated) {
            L.push("  ！队列已满（" + pending + "/" + MERGE_QUEUE_MAX + "），旧的正在被挤掉。先清队列再继续写。");
        }
        if (!mq.healthy) {
            L.push("  ！去重失效（重复率 " + Math.round(mq.dupRatio * 100) + "%），这是引擎缺陷，应当记录。");
        }
        L.push("  处理：mem_merge_review 取全部候选 -> 你判断后调 mem_merge_apply 提交。");
        L.push("");
    }

    _auditState.round = (_auditState.round || 0) + 1;
    var dueFull = forceFull || (_auditState.round % AUDIT_INTERVAL_ROUNDS === 0) ||
        (now - (_auditState.lastFullAuditAt || 0) > 6 * 3600 * 1000 && _auditState.round > 2);

    if (dueFull && idx && idx.entries && idx.entries.length) {
        var r = null;
        try {
            // 传上次审计的 id 集合，让 auditScan 能算增量
            r = engine.auditScan(idx.entries, { prevIds: _auditState.lastIds || null });
        } catch (_eA) { r = null; }
        if (r) {
            _auditState.lastFullAuditAt = now;
            // 记下本次 id 集合，下次审计用来算增量
            _auditState.lastIds = (idx.entries || []).map(function (x) { return String((x && x.id) || ""); });
            _auditState.lastResult = {
                at: now, total: r.total,
                duplicates: r.duplicates.length, conflicts: r.conflicts.length,
                thin: r.thin.length, stale: r.stale.length,
                selfCheckHealthy: r.selfCheck ? r.selfCheck.healthy : null
            };
            var issues = r.duplicates.length + r.conflicts.length + r.thin.length + r.stale.length;
            // 自检行：先证明审计真跑了，再报问题。
            // 旧实现没问题时只输出"全部通过"，无法区分"真没问题"和"审计没跑"。
            if (r.selfCheck) {
                var sc = r.selfCheck;
                if (!sc.healthy) {
                    L.push("[全量审计] ⚠ 审计自检异常：" + sc.total + " 条但检查执行数=" + sc.checksRun + "，结果不可信，请手动 mem_audit");
                } else {
                    L.push("[全量审计] 已扫 " + sc.total + " 条，执行 " + sc.checksRun + " 次检查（配对 " +
                        sc.pairsCompared + " / 空壳 " + sc.thinChecked + " / 陈旧 " + sc.staleChecked +
                        "），耗时 " + sc.elapsedMs + "ms");
                }
            }
            // 增量：自上次审计以来又动了什么
            if (r.delta && (r.delta.addedCount > 0 || r.delta.removedCount > 0)) {
                L.push("  自上次审计：" + (r.delta.addedCount > 0 ? ("新增 " + r.delta.addedCount + " 条") : "") +
                    (r.delta.addedCount > 0 && r.delta.removedCount > 0 ? "，" : "") +
                    (r.delta.removedCount > 0 ? ("移除 " + r.delta.removedCount + " 条") : ""));
            }
            if (issues > 0) {
                L.push("[全量审计] 共 " + r.total + " 条，发现 " + issues + " 处待处理：");
                if (r.conflicts.length) {
                    L.push("  矛盾 " + r.conflicts.length + " 组（同主题但结论相反，必须处理）：");
                    for (var cf = 0; cf < Math.min(3, r.conflicts.length); cf += 1) {
                        L.push("    ! " + String(r.conflicts[cf].a_title).slice(0, 22) +
                            "  vs  " + String(r.conflicts[cf].b_title).slice(0, 22));
                    }
                }
                if (r.duplicates.length) {
                    L.push("  重复 " + r.duplicates.length + " 组（该合并成一条）：");
                    for (var dp = 0; dp < Math.min(3, r.duplicates.length); dp += 1) {
                        L.push("    · " + String(r.duplicates[dp].a_title).slice(0, 22) +
                            "  ~  " + String(r.duplicates[dp].b_title).slice(0, 22) +
                            "  (" + r.duplicates[dp].overlap + ")");
                    }
                }
                if (r.thin.length) {
                    L.push("  空壳 " + r.thin.length + " 条（只有口号没有具体做法）：");
                    for (var th = 0; th < Math.min(3, r.thin.length); th += 1) {
                        L.push("    - " + String(r.thin[th].title).slice(0, 28) + "（" + r.thin[th].why + "）");
                    }
                }
                if (r.stale.length) {
                    L.push("  陈旧 " + r.stale.length + " 条（长期未命中）：");
                    for (var st = 0; st < Math.min(2, r.stale.length); st += 1) {
                        L.push("    ~ " + String(r.stale[st].title).slice(0, 28) + "（" + r.stale[st].why + "）");
                    }
                }
                L.push("");
                L.push("  处理原则：矛盾优先（会误导下次）；重复合并；空壳补具体做法；陈旧考虑降层或归档。");
                L.push("  不要为了「有交代」而改对的话——同义不改。");
                // 回退出口：mem_rollback 此前不存在，history 只写不读。
                // 注入块不点名的话，模型改错了只会 mem_forget 删掉重写，版本史白留。
                L.push("  改错了可回退：mem_rollback title=<标题> steps=1（当前版会压回 history，可再前进）。");
                // 干预出口：forceAudit 此前零调用，模型发现问题只能干等轮次。
                L.push("  要立刻全量自查：mem_audit force=true（下一轮注入块必跑全量）。");
                // 观测出口：audit_state 此前不可见，「跑过全通过」与「根本没跑」长得一样。
                L.push("  看自检跑没跑：mem_diag audit=1 读 audit_state.last_result。");
            } else {
                L.push("[全量审计] " + r.total + " 条全部通过：无重复、无矛盾、无空壳、无陈旧。");
            }
        }
    }

    // 健康基线：只在有信号时注入，正常状态零开销。
    // 此前 degradation / statistical / antiHarmony 只存在于 mem_audit 返回体，
    // 模型不主动调就永远看不到退化结论（自检可见缺口）。
    try {
        if (_healthCache) {
            if (_healthCache.ok === false) {
                L.push("[健康基线] 预载失败：" + String(_healthCache.err || "").slice(0, 80) + "（本轮退化判定不可信）");
                L.push("");
            } else if (_healthCache.level && _healthCache.level !== "ok") {
                var hc = _healthCache;
                L.push("[健康基线] 检测到 " + hc.level + "：样本 " + hc.samples +
                    "，当前 issues " + hc.current +
                    (hc.best != null ? ("，历史最好 " + hc.best) : ""));
                if (hc.regression && hc.regression.regression) {
                    L.push("  回退：" + String(hc.regression.reason || "").slice(0, 90));
                }
                if (hc.anomaly && hc.anomaly.anomaly) {
                    L.push("  异常：" + String(hc.anomaly.reason || "") + "（" + String(hc.anomaly.severity || "") + "）");
                }
                L.push("  处理：先修掉新引入的问题再继续加功能，别让基线继续恶化。");
                L.push("");
            }
        }
    } catch (_eHC) { }

    if (!L.length) return "";
    return "<memory_audit>" + nl + L.join(nl) + nl + "</memory_audit>";
}

// ============ tool lifecycle hook：纯内存，同步，零 IO ============
// 平台下发结构（PackageManagerToolPkgFacade.buildBasePayload）：
//   { event, eventName, eventPayload, timestampMs, functionName, toolPkgId, ... }
// 业务数据在 eventPayload 里，不是 payload。
// 铁律：本函数体内不得出现 Tools. / await / require —— 否则工具调用 -> hook -> IO ->
// 工具调用 会形成递归链，平台会抛 IllegalStateException 并使全设备工具失效。
async function toolLifecycleHook(event) {
    try {
        var ev = "";
        try { ev = String((event && (event.event || event.eventName)) || ""); } catch (_e1) { ev = ""; }
        if (ev !== "tool_execution_error" && ev !== "tool_execution_result" && ev !== "tool_execution_finished") {
            return null;
        }
        var payload = null;
        try { payload = (event && (event.eventPayload || event.payload)) || null; } catch (_e2) { payload = null; }
        if (!payload) return null;
        var ok = payload.success;
        var errMsg = "";
        try { errMsg = String(payload.errorMessage || payload.reason || ""); } catch (_e3) { errMsg = ""; }
        // 只记失败：成功事件量太大，记下来只会把失败库冲成噪声。
        if (ok === true && !errMsg) return null;
        if (!errMsg && ok !== false) return null;
        var toolName = "";
        try { toolName = String(payload.toolName || ev); } catch (_e4) { toolName = ev; }
        // 真实证据必须抓：buildBasePayload 已把 parameters / resultText 放进 eventPayload，
        // 不抓就只剩一句 errorMessage，落盘后写进进化记忆的「证据」是空壳。
        var realParams = null;
        try { realParams = payload.parameters || null; } catch (_eP) { realParams = null; }
        var resultText = "";
        try { resultText = String(payload.resultText || "").slice(0, 400); } catch (_eR) { resultText = ""; }
        var extra = { params: realParams, result_text: resultText, event: ev };
        // 纯内存同步：不 await，不碰 Tools.*
        recordToolFailureSync(toolName, errMsg || "tool failed", extra);
        return null;
    } catch (e) {
        // hook 内任何异常都不许外泄，否则平台会判定为拦截
        return null;
    }
}
async function systemPromptHook(event) {
    try {
        var stage = event && (event.eventName || event.event);
        if (stage !== "after_compose_system_prompt") return null;
        await initOnce();
        var payload = (event && event.eventPayload) || {};
        var histLen = (payload.chatHistory && payload.chatHistory.length) ? payload.chatHistory.length : 0;
        var userText = pickUserText(payload);
        if (userText) rememberUserText(userText);
        var d = shouldPrompt(userText);
        _lastTrigger = { at: Date.now(), yes: d.yes, reason: d.reason, len: userText.length, hist: histLen, stage: String(stage) };
        if (!d.yes) {
            _lastBrief = { at: Date.now(), skipped: true, reason: d.reason };
            saveState();
            return null;
        }
        var idx = await store.readIndex();
        if (!idx.entries || idx.entries.length === 0) {
            _lastBrief = { at: Date.now(), skipped: true, reason: "empty_index" };
            return null;
        }
        var hint = engine.buildIndexHint(idx, userText);
        if (!hint) {
            _lastBrief = { at: Date.now(), skipped: true, reason: "no_hint" };
            return null;
        }
        var auditBlock = "";
        try { auditBlock = buildAuditBlock(idx, false); } catch (_eAudit) { auditBlock = ""; }
        // 进化记忆：本轮是否命中踩过的坑。纯内存读，不碰 fs。
        var evoBlock = "";
        try {
            if (typeof evo.buildEvolutionBlockFromCache === "function") {
                evoBlock = evo.buildEvolutionBlockFromCache(userText);
            }
        } catch (_eEvo2) { evoBlock = ""; }

        _lastBrief = {
            at: Date.now(), skipped: false, total: idx.entries.length,
            queryHint: userText.slice(0, 60), blockLen: hint.length,
            auditLen: auditBlock.length,
            evoLen: evoBlock.length,
            evoCacheErr: (typeof evo.cacheError === "function") ? evo.cacheError() : "",
            evoFailStreak: _evoFailStreak,
            // 健康基线进诊断：此前退化结论只在 mem_audit 里。
            healthLevel: (_healthCache && _healthCache.ok) ? String(_healthCache.level || "ok") : ((_healthCache && _healthCache.ok === false) ? "error" : null),
            mergeQueue: mergeQueueHealth()
        };
        saveState();
        var cur = String(payload.systemPrompt || "");
        var nl = String.fromCharCode(10);
        var merged = cur ? (cur + nl + nl + hint) : hint;
        if (auditBlock) merged = merged + nl + nl + auditBlock;
        if (evoBlock) merged = merged + nl + nl + evoBlock;
        try { await store.appendLog("hint total=" + idx.entries.length + " q=" + userText.slice(0, 30)); } catch (_e4) { }
        return { systemPrompt: merged };
    } catch (e) {
        reportError("compose_run_fail", e);
        _lastBrief = { at: Date.now(), skipped: true, reason: "hook_error", error: String((e && e.message) || e) };
        return null;
    }
}

// -------- prompt input hook --------
// after_compose_system_prompt 阶段的 PromptHookContext 里
// rawInput / processedInput / chatHistory 三者恒为空
// （SystemPromptConfig.kt:684 只填 stage/chatId/metadata）
// 所以必须用最早的 before_process 阶段把用户输入记下来。
async function inputHook(event) {
    try {
        var stage = event && (event.eventName || event.event);
        if (stage !== "before_process") return null;
        var payload = (event && event.eventPayload) || {};
        var t = payload.rawInput || payload.processedInput || "";
        var s = String(t || "").trim();
        if (s) {
            rememberUserText(s);
            _lastInput = { at: Date.now(), len: s.length, head: s.slice(0, 40) };
            saveState();
        }
        return null;
    } catch (e) {
        reportError("input_hook_fail", e);
        return null;
    }
}

// -------- 自动抽取 hook --------
// 平台挂载点：ToolPkg.registerChatMessageHook
// 触发时机：ToolPkgChatMessageHookBridge.dispatchMessagePersisted（每条消息落库后）
// payload: chatId / sender / roleName / content / timestamp ...
// 主人口气也要抽：主人的纠正（"不对，应该X"）是最高价值教训，
// 原来只抽 assistant 导致所有纠正全部漏掉。
// 现在两侧都抽，区分交给 engine 内部（isCorrectionSentence 只对 user 生效）。
async function messageHook(event) {
    try {
        var payload = (event && event.eventPayload) || {};
        var sender = String(payload.sender || "").toUpperCase();
        // 只跳过 system/tool 这类非对话消息，user 与 assistant 都要
        if (sender.indexOf("SYSTEM") >= 0 || sender.indexOf("TOOL") >= 0) return null;
        var text = String(payload.content || "");
        if (!text) return null;
        await initOnce();
        var items = engine.extractFromMessage(text, payload.roleName || payload.sender);
if (!items || items.length === 0) {
            _lastExtract = { at: Date.now(), saved: 0, reason: "no_pattern" };
            saveState();
            return null;
        }
        var idx = await store.readIndex();
        var saved = 0;
        var skipped = 0;
        for (var i = 0; i < items.length; i += 1) {
            var it = items[i];
            // 合并改造：不再丢弃，收进候选让模型判断（纯内存，无 IO）
            var queued = false;
            for (var j = 0; j < idx.entries.length; j += 1) {
                if (idx.entries[j].title === it.title) {
                    pushMergeCandidate({
                        reason: "exact_title", score: 1,
                        incoming_title: it.title, incoming_content: it.content, incoming_layer: it.layer,
                        existing_id: idx.entries[j].id || "",
                        existing_title: idx.entries[j].title || "",
                        existing_content: idx.entries[j].content || "",
                        existing_version: idx.entries[j].version || 1,
                        existing_layer: idx.entries[j].layer || "L2",
                        at: Date.now()
                    });
                    queued = true;
                    break;
                }
            }
            if (queued) { skipped += 1; continue; }

            var sims = (engine.findSimilarAll ? engine.findSimilarAll(idx.entries, it.content, 0.82, 5) : []);
            if (!sims.length) {
                var one = engine.findSimilar(idx.entries, it.content, 0.82);
                if (one) sims = [one];
            }
            if (sims.length) {
                for (var sj = 0; sj < sims.length; sj += 1) {
                    var sn = sims[sj];
                    pushMergeCandidate({
                        reason: "near_content", score: sn.score,
                        incoming_title: it.title, incoming_content: it.content, incoming_layer: it.layer,
                        existing_id: sn.entry.id || "",
                        existing_title: sn.entry.title || "",
                        existing_content: sn.entry.content || "",
                        existing_version: sn.entry.version || 1,
                        existing_layer: sn.entry.layer || "L2",
                        at: Date.now()
                    });
                }
                skipped += 1;
                continue;
            }

            var cf = engine.detectConflict(idx.entries, it.title, it.content);
            if (cf && cf.conflict) {
                pushMergeCandidate({
                    reason: "conflict_" + (cf.kind || "overlap"),
                    score: Math.round((cf.score || 0) * 1000) / 1000,
                    incoming_title: it.title, incoming_content: it.content, incoming_layer: it.layer,
                    existing_id: (cf.entry && cf.entry.id) || "",
                    existing_title: (cf.entry && cf.entry.title) || "",
                    existing_content: (cf.entry && cf.entry.content) || "",
                    existing_version: (cf.entry && cf.entry.version) || 1,
                    existing_layer: (cf.entry && cf.entry.layer) || "L2",
                    at: Date.now()
                });
                skipped += 1;
                continue;
            }
            var now = Date.now();
            try { await engine.writeNative(it.title, it.content, { layer: it.layer, tags: it.tags }); } catch (_eW) { }
            idx.entries.push({
                // id 必须有。自动抽取之前漏了这行，产出 3 条无 id 条目，
                // 导致 mem_merge_apply / mem_forget 按 id 定位全部失效。
                id: (engine.makeId ? engine.makeId() : ("mem_auto_" + Date.now() + "_" + saved)),
                title: it.title, layer: it.layer, tags: it.tags,
                importance: (engine.computeImportance ? engine.computeImportance(it.kind, it.content, it.layer) : 0.5),
                evidence: "auto-extract",
                content: it.content, version: 1,
                createdAt: now, updatedAt: now,
                lastUsedAt: 0, hitCount: 0, reviewCount: 0,
                nextReviewAt: now + engine.layerIntervalMs(it.layer, 0),
                history: []
            });
            saved += 1;
        }
        if (saved > 0) {
            await store.writeIndex(idx);
            try { await store.appendLog("auto_extract saved=" + saved + " skipped=" + skipped); } catch (_eL) { }
        }
        _lastExtract = {
            at: Date.now(), saved: saved, skipped: skipped,
            found: items.length, queued_for_merge: _mergeCandidates.length
        };
        saveState();
        return null;
    } catch (e) {
        reportError("message_hook_fail", e);
        return null;
    }
}

async function finalizeHook(event) {
    return null;
}

exports.shouldPrompt = shouldPrompt;
exports.pickUserText = pickUserText;
exports.rememberUserText = rememberUserText;
exports.systemPromptHook = systemPromptHook;
exports.inputHook = inputHook;
exports.messageHook = messageHook;
exports.finalizeHook = finalizeHook;
exports.reportError = reportError;
exports.markRegistered = markRegistered;
exports.getRegistered = getRegistered;
exports.getLastBrief = function () { return _lastBrief; };
// 健康基线快照，供 mem_diag 直接读（自检可见的第二出口）。
// 为什么需要第二出口：注入块只在「有信号」时才推，正常态零开销；
// 但诊断需要随时能查到「现在基线到底是什么状态」。
exports.getHealthCache = function () { return _healthCache; };
exports.preloadHealth = preloadHealth;
exports.getLastTrigger = function () { return _lastTrigger; };
exports.getLastInput = function () { return _lastInput; };
exports.getLastExtract = function () { return _lastExtract; };
exports.getMergeCandidates = function () { return _mergeCandidates.slice(); };
exports.pushMergeCandidate = pushMergeCandidate;
exports.mergeQueueHealth = mergeQueueHealth;
exports.MERGE_QUEUE_MAX = MERGE_QUEUE_MAX;
exports.toolLifecycleHook = toolLifecycleHook;
exports.recordToolFailureSync = recordToolFailureSync;
exports.getFailBuffer = getFailBuffer;
exports.drainFailBuffer = drainFailBuffer;
exports.markFailWritten = markFailWritten;
exports.getAuditState = function () { return _auditState; };
exports.forceAudit = function () { _auditState.round = AUDIT_INTERVAL_ROUNDS - 1; return true; };
exports.setMergeCandidates = function (list) { _mergeCandidates = list || []; return _mergeCandidates.length; };
exports.clearMergeCandidates = function (ids) {
    if (!ids || !ids.length) { _mergeCandidates = []; return 0; }
    var before = _mergeCandidates.length;
    _mergeCandidates = _mergeCandidates.filter(function (c) {
        return ids.indexOf(c.existing_id) < 0;
    });
    return before - _mergeCandidates.length;
};
exports.loadState = loadState;
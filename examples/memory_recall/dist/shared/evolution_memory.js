// 进化记忆层（Evolution Memory）—— 独立于任务记忆
// 参照 DSH-AGINT 第四章：进化记忆让系统记住「自己上次改了什么、效果如何、哪些坑别再踩」，
// 而不是只记「用户说过什么」。
//
// 关键修正（实测踩到的坑）：
//   ToolPkg 沙箱里 fs 是空对象，必须用 Tools.Files.*（异步）+ ToolPkg.getConfigDir()。
//   旧实现用 fs.readFileSync，在沙箱里静默失败被 try/catch 吞掉。
//
// 存储（与任务记忆物理隔离）：
//   evolution/log.jsonl        进化轨迹追加
//   evolution/failures.json    失败模式库（上限 100）
//   evolution/templates.json   成功模板库（上限 50）

var DIR_NAME = "evolution";
var LOG_FILE = "log.jsonl";
var FAIL_FILE = "failures.json";
var TPL_FILE = "templates.json";

var MAX_FAILURES = 100;
var MAX_TEMPLATES = 50;

// 共享目录解析。
// 背景：ToolPkg.getConfigDir() 在各包自己的上下文里返回各自目录，
// 所以世界书与记忆库的 evolution 从来不是同一份。
// 实测证据：记忆库 evolution/ 有 failures.json(4699B)+templates.json(1700B)，
//          世界书 evolution/ 只有 health_history.json(416B)，两个文件都没有。
// 又实测：ToolPkg 没有 getEnv（API 清单里没有），所以不能用环境变量。
//
// 方案：读一个固定位置的指针文件，内容 = 共享 evolution 目录绝对路径。
// Android 侧 /sdcard 各包都能读写，不受包隔离影响。
// 读不到指针文件 = 回退各包自己的目录（原行为），不影响单包使用。
var SHARED_POINTER = "/sdcard/Download/Operit/plugins/_shared/evolution_dir.txt";
var _resolvedBase = undefined;   // undefined=未解析, null=用默认, string=共享

function baseDir() {
    if (typeof _resolvedBase === "string" && _resolvedBase) return _resolvedBase;
    try { return ToolPkg.getConfigDir() + "/" + DIR_NAME; } catch (e) { return null; }
}

// 异步解析共享指针。只在首次调用时真正读盘，之后走内存。
async function resolveBaseDir() {
    if (_resolvedBase !== undefined) return baseDir();
    var picked = null;
    try {
        var r = await Tools.Files.read(SHARED_POINTER);
        var txt = String((r && r.content) || "").trim();
        if (txt && txt.length > 3 && txt.charAt(0) === "/") picked = txt;
    } catch (_ePtr) { picked = null; }
    _resolvedBase = picked;   // null 表示没配置，回退默认
    return baseDir();
}
function logPath() { return baseDir() + "/" + LOG_FILE; }
function failPath() { return baseDir() + "/" + FAIL_FILE; }
function tplPath() { return baseDir() + "/" + TPL_FILE; }

async function ensureDir() {
    try { await Tools.Files.mkdir(baseDir(), true); } catch (e) { }
}

// onErr 可选：把读取异常原文交回调用方，用于区分"文件不存在"与"读盘失败"。
// 不传时行为与旧版完全一致（返回 ""），所以 3 处老调用点零影响。
async function readRaw(path, onErr) {
    try {
        var r = await Tools.Files.read(path);
        return String((r && r.content) || "");
    } catch (e) {
        if (typeof onErr === "function") {
            try { onErr(String((e && e.message) || e || "read_fail")); } catch (_eOn) { }
        }
        return "";
    }
}

async function writeRaw(path, text) {
    try {
        await ensureDir();
        await Tools.Files.write(path, text, false);
        return true;
    } catch (e) { return false; }
}

// "文件不存在"= 合法空库；"读盘失败"= 必须上抛。
// 旧实现把两者合并成 []，导致 cacheLoaded() 把故障谎报成空库（实测 R2）。
var READ_MISS_PAT = /enoent|not found|no such file|does not exist|filenotfound/i;

async function readArr(path) {
    var errMsg = "";
    var raw = await readRaw(path, function (m) { errMsg = m; });
    var txt = String(raw || "").trim();
    if (!txt) {
        if (errMsg && !READ_MISS_PAT.test(errMsg)) {
            throw new Error(errMsg);      // 真失败：上抛
        }
        return [];                          // 不存在或空文件：合法空库
    }
    try {
        var v = JSON.parse(txt);
        return Array.isArray(v) ? v : [];
    } catch (e) { return []; }              // 内容坏掉不算读盘失败
}

// 降级读：调用方拿不到数据也不该崩，但错误必须留痕。
// 为什么需要：readArr 在非 ENOENT 错误时抛（Enh20b 的要求，
// 否则 loadCache 标不了错误态），但 listFailures / stats /
// recordFailure 这些工具入口不能因为磁盘错就整体失败。
// 实测回归：不加这层时 EIO 直接冒到调用方，5 个工具全崩。
// 留痕方式：写 _cacheErr，cacheError() 可读，注入块也会降级为告警态。
async function safeReadArr(path, fallback) {
    try {
        return await readArr(path);
    } catch (e) {
        _cacheErr = String((e && e.message) || e || "read_fail");
        return Array.isArray(fallback) ? fallback : [];
    }
}

// ---------- 内存缓存（hook 用，零 IO）----------
var _cacheFailures = null;
var _cacheTemplates = null;
// 读盘失败标记。null/"" = 无错误。
// 为什么需要：loadCache 原先在读失败时把两个缓存设为 []，
// 于是 cacheLoaded() 返回 true，把"读失败"伪装成"成功但空库"，
// 上层永远看不见问题（test_preload.js C4 实测）。
var _cacheErr = null;

function cacheLoaded() {
    if (_cacheErr) return false;
    return _cacheFailures !== null && _cacheTemplates !== null;
}

// 诊断入口：注入块/工具层可读取当前缓存错误，避免静默失效。
function cacheError() {
    return _cacheErr ? String(_cacheErr) : "";
}

async function loadCache(force) {
    await resolveBaseDir();
    // 已有错误态且未强制重试：直接返回失败，让上层知道还没好。
    if (_cacheErr && !force) {
        return { failures: 0, templates: 0, error: String(_cacheErr) };
    }
    // force 重试：先清错误，否则 readArr 里残留态会干扰判断。
    if (force) _cacheErr = null;
    try {
        var f = await readArr(failPath());
        var t = await readArr(tplPath());
        _cacheFailures = f;
        _cacheTemplates = t;
        _cacheErr = null;
        return { failures: f.length, templates: t.length };
    } catch (e) {
        // 读失败：缓存保持 null（=未就绪），并记下错误。
        // 绝不设成 []，否则 cacheLoaded() 会谎报就绪。
        _cacheFailures = null;
        _cacheTemplates = null;
        _cacheErr = String((e && e.message) || e || "read_fail");
        return { failures: 0, templates: 0, error: _cacheErr };
    }
}

// ---------- 失败模式库 ----------
function failureSig(kind, target) {
    return String(kind || "unknown") + "|" + String(target || "").slice(0, 40);
}

// 进化轨迹上限（此前是硬编码 300，抽成常量便于统一）
var MAX_TRACE_LINES = 300;

// 进化轨迹追加：失败与成功共用同一入口。
// 为什么必须抽出来：实测 log.jsonl 11 行全是 type=failure，
// recordTemplate 里 grep logPath 计数为 0 —— 成功路径从来没写过轨迹。
// 结果就是「进化轨迹」只记坏消息，无法回答「哪次改成功了、隔了多久又踩坑」。
async function appendTrace(entry) {
    try {
        var prev = await readRaw(logPath());
        var lines = prev ? prev.split(String.fromCharCode(10)).filter(function (x) { return !!x; }) : [];
        lines.push(JSON.stringify(entry));
        if (lines.length > MAX_TRACE_LINES) lines = lines.slice(lines.length - MAX_TRACE_LINES);
        await writeRaw(logPath(), lines.join(String.fromCharCode(10)));
        return true;
    } catch (e) { return false; }
}

// 读进化轨迹。此前 log 只能被 stats 数行数，模型永远看不到内容。
// type 可选：failure / template；不传则全类型。最新的排在前面。
async function listTimeline(limit, type) {
    await resolveBaseDir();
    var raw = await readRaw(logPath());
    var lines = raw ? raw.split(String.fromCharCode(10)).filter(function (x) { return !!x; }) : [];
    var out = [];
    for (var i = 0; i < lines.length; i += 1) {
        var o = null;
        try { o = JSON.parse(lines[i]); } catch (_e) { o = null; }
        if (!o) continue;
        if (type && String(o.type || "") !== String(type)) continue;
        out.push({
            ts: Number(o.ts) || 0,
            type: String(o.type || ""),
            kind: String(o.kind || ""),
            target: String(o.target || o.name || "").slice(0, 80),
            count: Number(o.count) || 0
        });
    }
    out.sort(function (a, b) { return b.ts - a.ts; });
    var lim = Number(limit) > 0 ? Number(limit) : 20;
    return out.slice(0, lim);
}

// count / extra 可选：hook 缓冲刷盘时必须带上「踩了几次」与真实参数，
// 否则一条踩了 20 次的坑写进库里 count=1，排序时会被排到最下面没人看。
async function recordFailure(kind, target, detail, evidence, count, extra) {
    await resolveBaseDir();
    var arr = await safeReadArr(failPath(), []);
    var sig = failureSig(kind, target);
    var now = Date.now();
    var hit = null;
    for (var i = 0; i < arr.length; i += 1) {
        if (arr[i] && arr[i].sig === sig) { hit = arr[i]; break; }
    }
    var addN = Number(count) > 0 ? Math.floor(Number(count)) : 1;
    if (hit) {
        hit.count = (Number(hit.count) || 0) + addN;
        hit.lastAt = now;
        if (detail) hit.detail = String(detail).slice(0, 500);
        if (evidence) hit.evidence = String(evidence).slice(0, 300);
    } else {
        arr.push({
            sig: sig,
            kind: String(kind || "unknown"),
            target: String(target || "").slice(0, 80),
            detail: String(detail || "").slice(0, 500),
            evidence: String(evidence || "").slice(0, 300),
            count: addN,
            firstAt: now,
            lastAt: now
        });
    }
    // extra 只写最后一条（最新证据）
    var lastRec = hit || arr[arr.length - 1];
    if (extra && lastRec) {
        try { lastRec.extra = extra; } catch (_eEx) { }
    }
    if (arr.length > MAX_FAILURES) {
        arr.sort(function (a, b) { return (Number(b.lastAt) || 0) - (Number(a.lastAt) || 0); });
        arr = arr.slice(0, MAX_FAILURES);
    }
    var ok = await writeRaw(failPath(), JSON.stringify(arr, null, 2));
    // 同步更新缓存，避免内存与磁盘脱节
    _cacheFailures = arr;
    // 追加轨迹（读-改-写，最多保留 300 行）
    // 追加轨迹（统一走 appendTrace，成功与失败同一入口）
    await appendTrace({ ts: now, type: "failure", kind: kind, target: target, sig: sig, count: addN, total: (Number((lastRec || {}).count) || addN) });
    return ok;
}

// ---------- 成功模板库 ----------
async function recordTemplate(name, pattern, note) {
    await resolveBaseDir();
    var key = String(name || "").slice(0, 60);
    if (!key) return false;
    var arr = await safeReadArr(tplPath(), []);
    var now = Date.now();
    var hit = null;
    for (var i = 0; i < arr.length; i += 1) {
        if (arr[i] && arr[i].name === key) { hit = arr[i]; break; }
    }
    if (hit) {
        hit.count = (Number(hit.count) || 0) + 1;
        hit.lastAt = now;
        if (pattern) hit.pattern = String(pattern).slice(0, 800);
    } else {
        arr.push({
            name: key,
            pattern: String(pattern || "").slice(0, 800),
            note: String(note || "").slice(0, 300),
            count: 1,
            firstAt: now,
            lastAt: now
        });
    }
    if (arr.length > MAX_TEMPLATES) {
        arr.sort(function (a, b) { return (Number(b.lastAt) || 0) - (Number(a.lastAt) || 0); });
        arr = arr.slice(0, MAX_TEMPLATES);
    }
    var ok = await writeRaw(tplPath(), JSON.stringify(arr, null, 2));
    _cacheTemplates = arr;
    // 成功路径也写轨迹。此前这里 0 处 logPath，导致轨迹里全是失败。
    await appendTrace({ ts: now, type: "template", name: key, target: key, count: (Number((hit || {}).count) || 1) });
    return ok;
}

// ---------- 定向读取（纯内存，hook 专用）----------
function matchTarget(tgt, q) {
    var t = String(tgt || "");
    var s = String(q || "");
    if (!t || !s) return false;
    for (var n = 2; n <= 6; n += 1) {
        for (var k = 0; k + n <= s.length; k += 1) {
            if (t.indexOf(s.slice(k, k + n)) >= 0) return true;
        }
    }
    return false;
}

// 严格匹配：最小子串长度可配，默认 4。
// 为什么模板不能用 matchTarget 的 2 字滑窗：
//   实测 query「记忆库状态覆盖怎么判」会命中 "ToolPkg 包开发流程" 模板，
//   因为长 pattern 里几乎必然含有 query 的某个 2 字子串。
//   失败模式的 target 只有 8 字左右，2 字滑窗是合理宽松度；
//   模板 pattern 有 200+ 字，同样的宽松度等于"全都命中"。
function matchTargetStrict(tgt, q, minLen) {
    var t = String(tgt || "");
    var s = String(q || "");
    if (!t || !s) return false;
    var start = Number(minLen) > 0 ? Number(minLen) : 4;
    var maxN = 8;
    if (maxN > s.length) maxN = s.length;
    for (var n = maxN; n >= start; n -= 1) {
        for (var k = 0; k + n <= s.length; k += 1) {
            if (t.indexOf(s.substr(k, n)) >= 0) return true;
        }
    }
    return false;
}

function preflightWarningsFromCache(text, limit) {
    if (!cacheLoaded()) return [];
    var lim = limit || 3;
    var q = String(text || "");
    if (!q) return [];
    var out = [];
    for (var i = 0; i < _cacheFailures.length; i += 1) {
        var f = _cacheFailures[i];
        if (!f || !f.target) continue;
        if (matchTarget(f.target, q)) out.push(f);
    }
    out.sort(function (a, b) { return (Number(b.count) || 0) - (Number(a.count) || 0); });
    return out.slice(0, lim);
}

async function preflightWarnings(text, limit) {
    if (!cacheLoaded()) await loadCache();
    return preflightWarningsFromCache(text, limit);
}

// 成功模板匹配（纯内存，hook 专用）。
// 为什么需要独立函数：原实现里 _cacheTemplates 只被取 .length 当成一个数字，
// pattern 正文从未进入注入块 —— templates.json 里那条完整的
// "ToolPkg 包开发流程"（含 gate.py -> zip -> cp -> 重载 的严格顺序）
// 一直躺着没人看见。
//
// 匹配策略与失败模式一致（matchTarget 的 2~6 字滑窗），
// 但模板额外用 name + note 一起参与匹配，命中率更高。
function preflightTemplatesFromCache(text, limit) {
    if (!cacheLoaded()) return [];
    var lim = limit || 2;
    var q = String(text || "");
    if (!q) return [];
    var out = [];
    for (var i = 0; i < _cacheTemplates.length; i += 1) {
        var t = _cacheTemplates[i];
        if (!t) continue;
        // 只用 name + note 这两个短字段匹配，pattern 只用于展示。
        // 若把长 pattern 也纳入匹配，任何 query 都会命中所有模板（实测确认）。
        // 同时最小子串长度提到 4，进一步压假阳性。
        var probe = String(t.name || "") + " " + String(t.note || "");
        if (matchTargetStrict(probe, q, 4)) {
            out.push(t);
        }
    }
    out.sort(function (a, b) { return (Number(b.count) || 0) - (Number(a.count) || 0); });
    return out.slice(0, lim);
}

// 供工具层查询（async 版本，会按需加载缓存）
async function preflightTemplates(text, limit) {
    if (!cacheLoaded()) await loadCache();
    return preflightTemplatesFromCache(text, limit);
}

// 智能截断：保住「现象 + 判据」，砍掉中间论证过程。
// 为什么不能硬截断：实测「ToolPkg 部署顺序」230 字被切到 200，
// 丢的正是尾部"md5 核对四处一致"这一步；「supersede-detect」264 字被切到 120，
// 丢的正是"无论如何都不要做自动 mark"这条禁令。
// 教训的写法天然是「现象 → 过程 → 判据」，判据在尾部，硬截断总是先砍它。
// 分级：禁用类禁令优先级最高（"无论如何都不要做 X"这类一旦丢失，
// 模型可能直接去做被明确禁止的事）。其次是判据/结论类。
var KEEP_TAIL_STRONG = /不要|禁止|绝不|无论如何|一律不|切勿|千万/;
var KEEP_TAIL_PAT = /判据|必须|顺序|否则|铁律|记住|禁止|不要|一律|切勿|千万|无论如何/;

function smartTruncate(s, max) {
    var t = String(s || "");
    var lim = Number(max) > 0 ? Number(max) : 200;
    if (t.length <= lim) return t;

    // 按句切分（中文句读 + 分号 + 换行）
    var parts = t.split(/(?<=[。；;！!？?\n])/).filter(function (x) {
        return String(x || "").trim();
    });
    if (parts.length <= 1) {
        // 无法切句：退化为硬截断，但保留尾部线索。
        // 用实际长度算预算（实测踩过：省略标记 5 字符不是 3，导致超 1 字）。
        var mark = "…(略)…";
        var tailN = Math.min(18, Math.floor(lim * 0.2));
        var headN = lim - mark.length - tailN;
        if (headN < 1) { headN = Math.max(1, lim - mark.length); tailN = 0; }
        var fallback = t.slice(0, headN) + mark + (tailN > 0 ? t.slice(-tailN) : "");
        return fallback.length > lim ? fallback.slice(0, lim) : fallback;
    }

    var head = parts[0];
    // 尾句选择：不取"第一个含标记的句子"，而取【权重最高】的那句。
    // 实测教训：supersede 那条里「语义歧义判断必须交回模型」含"必须"，
    // 但真正该保的是「无论如何都不要做自动 mark」—— 后者是禁令，权重更高。
    var tail = "";
    var bestScore = -1;
    for (var i = 1; i < parts.length; i += 1) {
        var seg = parts[i];
        var sc = 0;
        if (KEEP_TAIL_STRONG.test(seg)) sc += 10;   // 禁令类最高
        if (KEEP_TAIL_PAT.test(seg)) sc += 2;        // 判据类
        if (/http|实测|验证/.test(seg)) sc += 1;     // 有证据
        sc += (i / parts.length) * 1.5;              // 位置弱加成
        if (sc > bestScore) { bestScore = sc; tail = seg; }
    }
    if (!tail) tail = parts[parts.length - 1];

    var joiner = "…";
    var budget = lim - joiner.length;
    if (head.length + tail.length <= budget) {
        // 头尾都能放下：中间还有余量就再塞中间句
        var midRoom = budget - head.length - tail.length;
        var mid = "";
        for (var m = 1; m < parts.length - 1; m += 1) {
            if (mid.length + parts[m].length > midRoom) break;
            mid += parts[m];
        }
        return head + mid + (mid || midRoom > 4 ? joiner : "") + tail;
    }

    // 放不下：保头 + 保尾，各自按比例缩
    var tailKeep = Math.min(tail.length, Math.max(60, Math.floor(budget * 0.55)));
    var headKeep = Math.max(20, budget - tailKeep);
    if (headKeep + tailKeep > budget) headKeep = budget - tailKeep;
    var headPart = head.length > headKeep ? head.slice(0, headKeep) : head;
    var tailPart = tail.length > tailKeep ? tail.slice(tail.length - tailKeep) : tail;
    var out = headPart + joiner + tailPart;
    return out.length > lim ? out.slice(0, lim) : out;
}

// 纯内存版注入块，hook 专用
function buildEvolutionBlockFromCache(text) {
    if (!cacheLoaded()) {
        // 缓存未就绪：若已有明确错误，返回一行极短告警而不是空串。
        // 为什么：空串让模型以为「进化记忆本来就是空的」，
        // 于是不会去修；告警能让它当场知道是坏的。
        // 长度必须极短，注入块不能因为故障反而膨胀。
        var ce = (typeof cacheError === "function") ? cacheError() : "";
        if (ce) {
            return "<evolution_memory degraded=\"1\">进化记忆预载失败：" +
                String(ce).slice(0, 80) + "（本次不注入教训，别当成没有坑）</evolution_memory>";
        }
        return "";
    }
    var failN = _cacheFailures.length;
    var tplN = _cacheTemplates.length;
    if (failN === 0 && tplN === 0) return "";
    var warns = preflightWarningsFromCache(text, 3);
    var L = [];
    L.push('<evolution_memory source="evolution" failures="' + failN + '" templates="' + tplN + '">');
    if (warns.length > 0) {
        L.push("进化记忆：下面这些坑你踩过，动手前先看一眼");
        for (var i = 0; i < warns.length; i += 1) {
            var w = warns[i];
            L.push("  · [" + w.kind + "] " +
                smartTruncate(String(w.detail || w.target || ""), 200) +
                "（已踩 " + (Number(w.count) || 1) + " 次）");
        }
    }
    // 成功模板：与失败模式同样重要，此前从未注入（只报过数量）。
    // 模板告诉模型"上次这么做成了"，是正向可复用路径，对行动指导性更强。
    var tplHits = preflightTemplatesFromCache(text, 2);
    if (tplHits.length > 0) {
        L.push("成功模板：这条路你走通过，可以照着做");
        for (var ti = 0; ti < tplHits.length; ti += 1) {
            var tp = tplHits[ti];
            var body = smartTruncate(String(tp.pattern || tp.note || ""), 260);
            L.push("  + [" + String(tp.name || "").slice(0, 30) + "] " + body +
                "（成功 " + (Number(tp.count) || 1) + " 次）");
        }
    }
    if (warns.length === 0 && tplHits.length === 0) {
        L.push("进化记忆：已积累 " + failN + " 条失败模式、" + tplN + " 条成功模板（本轮未命中相关项）");
    }
    L.push("</evolution_memory>");
    return L.join("\n");
}

async function buildEvolutionBlock(text) {
    if (!cacheLoaded()) await loadCache();
    return buildEvolutionBlockFromCache(text);
}

// 教训全文查询（供工具层消费）。
// 为什么需要：detail 字段一直写进去了，但没有任何出口能读出来 ——
// mem_evolution 只返回数量，模型看得到"有 9 条坑"却看不到是哪 9 条。
// 参数 kind 过滤类型；limit 控制返回条数（默认 20，避免返回体膨胀）。
async function listFailures(kind, limit) {
    await resolveBaseDir();
    var arr = await safeReadArr(failPath(), []);
    var k = String(kind || "").trim().toLowerCase();
    if (k) {
        arr = arr.filter(function (x) {
            return String((x && x.kind) || "").toLowerCase() === k;
        });
    }
    arr = arr.slice().sort(function (a, b) {
        return (Number(b && b.count) || 0) - (Number(a && a.count) || 0);
    });
    var lim = Number(limit) > 0 ? Number(limit) : 20;
    return arr.slice(0, lim).map(function (x) {
        return {
            kind: String((x && x.kind) || ""),
            target: String((x && x.target) || ""),
            count: Number((x && x.count) || 1),
            detail: String((x && x.detail) || "").slice(0, 300),
            evidence: String((x && x.evidence) || "").slice(0, 160)
        };
    });
}

// 成功模板全文查询。pattern 是"可照抄的做法"，此前只能看到数量。
async function listTemplates(limit) {
    await resolveBaseDir();
    var arr = await safeReadArr(tplPath(), []);
    arr = arr.slice().sort(function (a, b) {
        return (Number(b && b.count) || 0) - (Number(a && a.count) || 0);
    });
    var lim = Number(limit) > 0 ? Number(limit) : 20;
    return arr.slice(0, lim).map(function (x) {
        return {
            name: String((x && x.name) || ""),
            note: String((x && x.note) || "").slice(0, 200),
            pattern: String((x && x.pattern) || "").slice(0, 600),
            count: Number((x && x.count) || 1)
        };
    });
}

async function stats() {
    await resolveBaseDir();
    var fails = await safeReadArr(failPath(), []);
    var tpls = await safeReadArr(tplPath(), []);
    var logLines = 0;
    try {
        var s = await readRaw(logPath());
        logLines = s.split("\n").filter(function (x) { return x.trim(); }).length;
    } catch (e) { logLines = -1; }
    return {
        dir: baseDir(),
        failures: fails.length,
        templates: tpls.length,
        logEntries: logLines,
        topFailures: fails.slice()
            .sort(function (a, b) { return (Number(b.count) || 0) - (Number(a.count) || 0); })
            .slice(0, 5)
            .map(function (x) { return { kind: x.kind, target: x.target, count: x.count }; })
    };
}

module.exports = {
    recordFailure: recordFailure,
    recordTemplate: recordTemplate,
    preflightWarnings: preflightWarnings,
    preflightWarningsFromCache: preflightWarningsFromCache,
    preflightTemplatesFromCache: preflightTemplatesFromCache,
    matchTargetStrict: matchTargetStrict,
    cacheError: cacheError,
    preflightTemplates: preflightTemplates,
    buildEvolutionBlock: buildEvolutionBlock,
    buildEvolutionBlockFromCache: buildEvolutionBlockFromCache,
    loadCache: loadCache,
    cacheLoaded: cacheLoaded,
    stats: stats,
    listFailures: listFailures,
    smartTruncate: smartTruncate,
    listTemplates: listTemplates,
    listTimeline: listTimeline,
    appendTrace: appendTrace,
    baseDir: baseDir,
    resolveBaseDir: resolveBaseDir,
    SHARED_POINTER: SHARED_POINTER,
};
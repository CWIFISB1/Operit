"use strict";
Object.defineProperty(exports, "__esModule", { value: true });

var LAYERS = ["L1", "L2", "L3"];
var BASE_INTERVAL_DAYS = { L1: 2, L2: 7, L3: 21 };
var GROWTH_FACTOR = 2.1;
var STALE_DAYS = 45;
var STALE_MAX_HITS = 1;

function clampInt(v, min, max, dft) {
    var n = Number(v);
    if (!isFinite(n)) return dft;
    return Math.max(min, Math.min(max, Math.round(n)));
}
function clampNum(v, min, max, dft) {
    var n = Number(v);
    if (!isFinite(n)) return dft;
    return Math.max(min, Math.min(max, n));
}
function normalizeLayer(raw, dft) {
    var s = String(raw || "").trim().toUpperCase();
    if (LAYERS.indexOf(s) >= 0) return s;
    var f = String(dft || "L1").toUpperCase();
    return LAYERS.indexOf(f) >= 0 ? f : "L1";
}
function layerIntervalMs(layer, reviewCount) {
    var base = BASE_INTERVAL_DAYS[normalizeLayer(layer)] || 2;
    var n = Math.max(0, Number(reviewCount) || 0);
    var days = base * Math.pow(GROWTH_FACTOR, Math.min(n, 6));
    return Math.round(days * 86400000);
}
function isStale(entry, nowMs) {
    if (!entry) return false;
    var now = nowMs || Date.now();
    var last = Number(entry.lastUsedAt) || Number(entry.createdAt) || 0;
    if (!last) return false;
    var ageDays = (now - last) / 86400000;
    var hits = Number(entry.hitCount) || 0;
    if (entry.layer === "L3") return ageDays > STALE_DAYS * 3 && hits === 0;
    return ageDays > STALE_DAYS && hits <= STALE_MAX_HITS;
}
function tokenize(text) {
    var s = String(text || "").toLowerCase();
    var out = [];
    var words = s.match(/[a-z0-9_]+/g) || [];
    for (var i = 0; i < words.length; i += 1) { if (words[i].length > 1) out.push(words[i]); }
    var cjk = s.replace(/[^\u4e00-\u9fa5]/g, " ");
    var segs = cjk.split(/\s+/);
    for (var j = 0; j < segs.length; j += 1) {
        for (var k = 0; k < segs[j].length - 1; k += 1) out.push(segs[j].substr(k, 2));
    }
    return out;
}
function jaccard(a, b) {
    var A = {};
    var B = {};
    var ta = tokenize(a);
    var tb = tokenize(b);
    for (var i = 0; i < ta.length; i += 1) A[ta[i]] = 1;
    for (var j = 0; j < tb.length; j += 1) B[tb[j]] = 1;
    var ka = Object.keys(A);
    var kb = Object.keys(B);
    if (ka.length === 0 || kb.length === 0) return 0;
    var inter = 0;
    for (var k = 0; k < ka.length; k += 1) { if (B[ka[k]]) inter += 1; }
    var union = ka.length + kb.length - inter;
    return union > 0 ? inter / union : 0;
}
function findSimilar(entries, content, threshold) {
    var best = null;
    var list = entries || [];
    for (var i = 0; i < list.length; i += 1) {
        var e = list[i];
        var hay = String(e.title || "") + " " + String(e.tags || "") + " " + String(e.content || "");
        var s = jaccard(content, hay);
        if (s >= threshold && (!best || s > best.score)) best = { entry: e, score: s };
    }
    return best;
}
// 返回全部相似候选（供模型挑选，引擎不拍板）
function findSimilarAll(entries, content, threshold, limit) {
    var list = entries || [];
    var max = limit || 5;
    var out = [];
    for (var i = 0; i < list.length; i += 1) {
        var e = list[i];
        var hay = String(e.title || "") + " " + String(e.tags || "") + " " + String(e.content || "");
        var s = jaccard(content, hay);
        if (s >= threshold) out.push({ entry: e, score: Math.round(s * 1000) / 1000 });
    }
    out.sort(function (a, b) { return b.score - a.score; });
    return out.slice(0, max);
}
exports.findSimilarAll = findSimilarAll;

// ============================================================
// 审计扫描器（纯计算，无 IO）
// 参考 MintMory 的 dreaming pass：定期全量扫，挑出需要判断的组合
// ============================================================

function topicOverlap(a, b) {
    var ta = String(a.title || "") + " " + String(a.tags || "");
    var tb = String(b.title || "") + " " + String(b.tags || "");
    var s = jaccard(ta, tb);
    var sc = jaccard(String(a.content || ""), String(b.content || ""));
    return Math.max(s, (s * 0.4 + sc * 0.6));
}

function looksContradictory(a, b) {
    var A = String(a.content || "").toLowerCase();
    var B = String(b.content || "").toLowerCase();
    var negA = hasAny(A, NEG_CUES[0]);
    var posA = hasAny(A, NEG_CUES[1]);
    var negB = hasAny(B, NEG_CUES[0]);
    var posB = hasAny(B, NEG_CUES[1]);
    return (negA && posB && !posA) || (posA && negB && !negA);
}

function looksThin(e) {
    var c = String(e.content || "").trim();
    if (!c) return "内容为空";

    var len = c.length;

    // 快通道：够长的直接合格（实测 13 条里 11 条 >= 60 字，全是好条目）
    if (len >= 80) return null;

    // 短条目看结构要素
    var hasObject =
        /[a-zA-Z_][a-zA-Z0-9_\.\-]{2,}/.test(c) ||
        /`[^`]+`/.test(c) ||
        /\/[a-zA-Z0-9_\-\/]{2,}/.test(c);

    var hasAction =
        /(做|改|读|写|查|跑|用|设|加|删|判|断|校验|验证|测|试|建|装|卸|清|备份|复制|替换|注入|合并|拆|分|取|放|传|调|执行|启动|停止|开关|禁|允|限)/.test(c);

    var clauses = (c.match(/[，,。；;：:！!？?]/g) || []).length;

    if (len < 30 && !hasObject && !hasAction) return "过短且无具体对象与操作（<30 字）";
    if (len < 50 && !hasObject) return "缺具体对象（无工具名/文件名/参数）";
    if (len < 50 && !hasAction) return "缺可执行动作";
    if (len < 60 && clauses === 0) return "单句口号，无展开（<60 字且无分句）";

    // 其余一律合格：宁可漏判，也不误杀
    return null;
}

function looksStale(e, now) {
    var last = Number(e.lastUsedAt || 0) || Number(e.updatedAt || 0) || Number(e.createdAt || 0);
    if (!last) return null;
    var days = (now - last) / 86400000;
    var hits = Number(e.hitCount || 0);
    if (days > 45 && hits === 0) return "45 天未命中";
    if (days > 90) return "90 天未更新";
    return null;
}

// 生成记忆 id。格式 mem_ + 12 位十六进制，与历史数据一致。
// 纯函数，不碰 IO，hook 里也能调。
// 之前 memWrite 根本没写 id 字段，导致新条目全靠 title 做键——
// mem_merge_apply 按 id 定位，碰到无 id 条目就匹配不到。
function makeId() {
    var hex = "0123456789abcdef";
    var out = "mem_";
    for (var i = 0; i < 12; i += 1) {
        out += hex.charAt(Math.floor(Math.random() * 16));
    }
    return out;
}
function auditScan(entries, opts) {
    var o = opts || {};
    var list = entries || [];
    var now = Date.now();
    var scanStartMs = now;
    var dupThreshold = o.dupThreshold || 0.55;
    var maxPairs = o.maxPairs || 12;

    // 自检计数器：让"真跑了"和"抛异常被吞"能区分开。
    // 旧实现 auditScan 抛错时被调用方 try/catch 吃掉，表现和"全部通过"完全一样。
    var checks = { pairsCompared: 0, thinChecked: 0, staleChecked: 0, idsSeen: 0 };
    var errors = [];

    var duplicates = [], conflicts = [], thin = [], stale = [];
    // 状态覆盖统计：扫出"该覆盖但没覆盖"的存量冲突，以及已被覆盖的条数。
    var supersededList = [];
    var supersedePending = [];

    for (var i = 0; i < list.length; i += 1) {
        var a = list[i];
        if (!a) continue;
        for (var j = i + 1; j < list.length; j += 1) {
            var b = list[j];
            if (!b) continue;
            checks.pairsCompared += 1;
            var ov = topicOverlap(a, b);
            if (ov < dupThreshold) continue;
            var pair = {
                a_id: a.id || "", a_title: a.title || "", a_layer: a.layer || "", a_content: String(a.content || "").slice(0, 400),
                b_id: b.id || "", b_title: b.title || "", b_layer: b.layer || "", b_content: String(b.content || "").slice(0, 400),
                overlap: Math.round(ov * 1000) / 1000
            };
            if (looksContradictory(a, b)) {
                pair.kind = "conflict";
                conflicts.push(pair);
                // 冲突对里两边都没被覆盖 = 真实存量问题（新旧并列注入）。
                if (!a.superseded_by && !b.superseded_by) {
                    supersedePending.push({ a_title: String(a.title || "").slice(0, 40), b_title: String(b.title || "").slice(0, 40) });
                }
            }
            else { pair.kind = "duplicate"; duplicates.push(pair); }
            if (duplicates.length + conflicts.length >= maxPairs) break;
        }
        if (duplicates.length + conflicts.length >= maxPairs) break;
    }

    for (var k = 0; k < list.length; k += 1) {
        var e = list[k];
        if (!e) continue;
        checks.idsSeen += 1;
        checks.thinChecked += 1;
        var thinWhy = looksThin(e);
        if (thinWhy) thin.push({ id: e.id || "", title: e.title || "", layer: e.layer || "", why: thinWhy });
        checks.staleChecked += 1;
        var staleWhy = looksStale(e, now);
        if (staleWhy) stale.push({ id: e.id || "", title: e.title || "", layer: e.layer || "", why: staleWhy, hits: Number(e.hitCount || 0) });
        if (e.superseded_by) {
            supersededList.push({ id: e.id || "", title: String(e.title || "").slice(0, 40), by: String(e.superseded_by).slice(0, 40) });
        }
    }

    // 自检块：这些数字是"审计确实执行了"的证据。
    // 如果 total>0 但 checksRun 全是 0，说明审计体根本没进循环（被吞或提前 return）。
    var checksRun = checks.pairsCompared + checks.thinChecked + checks.staleChecked;
    var selfCheck = {
        total: list.length,
        checksRun: checksRun,
        pairsCompared: checks.pairsCompared,
        thinChecked: checks.thinChecked,
        staleChecked: checks.staleChecked,
        elapsedMs: Date.now() - scanStartMs,
        // 健康判定：扫到条目了却一次检查都没跑 = 异常
        healthy: list.length === 0 ? true : (checksRun > 0),
        errors: errors
    };

    // 增量：对比上次审计的 id 集合，报新增/移除。
    // 旧实现只报当前状态，看不出"自上次以来又脏了什么"。
    var delta = null;
    if (o.prevIds && Array.isArray(o.prevIds)) {
        var prevSet = {};
        for (var pi = 0; pi < o.prevIds.length; pi += 1) prevSet[String(o.prevIds[pi])] = true;
        var curSet = {};
        for (var ci = 0; ci < list.length; ci += 1) curSet[String(list[ci].id || "")] = true;
        var added = [];
        for (var ak in curSet) {
            if (Object.prototype.hasOwnProperty.call(curSet, ak) && !prevSet[ak]) added.push(ak);
        }
        var removed = [];
        for (var rk in prevSet) {
            if (Object.prototype.hasOwnProperty.call(prevSet, rk) && !curSet[rk]) removed.push(rk);
        }
        delta = {
            prevCount: o.prevIds.length,
            curCount: list.length,
            addedCount: added.length,
            removedCount: removed.length,
            added: added.slice(0, 5),
            removed: removed.slice(0, 5)
        };
    }

    return {
        duplicates: duplicates, conflicts: conflicts,
        thin: thin.slice(0, 10), stale: stale.slice(0, 10),
        superseded: supersededList.slice(0, 10),
        supersedePending: supersedePending.slice(0, 10),
        total: list.length, scannedAt: now,
        selfCheck: selfCheck,
        delta: delta
    };
}
exports.makeId = makeId;
exports.auditScan = auditScan;
exports.topicOverlap = topicOverlap;
exports.looksThin = looksThin;
exports.looksLikeCode = looksLikeCode;


function lexicalScore(query, text) {
    var q = tokenize(query);
    if (q.length === 0) return 0;
    var hay = String(text || "").toLowerCase();
    var hit = 0;
    for (var i = 0; i < q.length; i += 1) { if (hay.indexOf(q[i]) >= 0) hit += 1; }
    return hit / q.length;
}
// -------- BM25 检索（替代原二字分切命中率） --------
// 原 lexicalScore = 命中数/词数，无 IDF、无长度归一、无词频饱和，
// 中文二字分切后极易被高频词拉高，实测 0.14 分不可用。
// BM25：tf 饱和(k1) + 长度归一(b) + IDF 稀有词加权。
var BM25_K1 = 1.2;
var BM25_B = 0.75;

function termFreq(tokens) {
    var tf = {};
    for (var i = 0; i < tokens.length; i += 1) {
        var t = tokens[i];
        tf[t] = (tf[t] || 0) + 1;
    }
    return tf;
}

// docs: [{ tf, len }]；返回每条 doc 对 query 的 BM25 分（已归一化到 0..1）
function bm25Score(query, docs) {
    var qTokens = tokenize(query);
    if (!docs || docs.length === 0) return [];
    if (qTokens.length === 0) return docs.map(function () { return 0; });
    var N = docs.length;
    var totalLen = 0;
    for (var i = 0; i < N; i += 1) totalLen += docs[i].len || 0;
    var avgLen = N > 0 ? (totalLen / N) : 1;
    if (avgLen <= 0) avgLen = 1;
    // df
    var df = {};
    var seen = {};
    for (var q = 0; q < qTokens.length; q += 1) {
        var term = qTokens[q];
        if (seen[term]) continue;
        seen[term] = 1;
        var c = 0;
        for (var d0 = 0; d0 < N; d0 += 1) { if (docs[d0].tf[term]) c += 1; }
        df[term] = c;
    }
    var raw = [];
    for (var j = 0; j < N; j += 1) {
        var doc = docs[j];
        var dl = doc.len || 0;
        var s = 0;
        for (var k = 0; k < qTokens.length; k += 1) {
            var tk = qTokens[k];
            var f = doc.tf[tk] || 0;
            if (f === 0) continue;
            var n = df[tk] || 0;
            var idf = Math.log(1 + (N - n + 0.5) / (n + 0.5));
            var denom = f + BM25_K1 * (1 - BM25_B + BM25_B * (dl / avgLen));
            s += idf * (f * (BM25_K1 + 1)) / (denom || 1);
        }
        raw.push(s);
    }
    // 归一化：除以最大可能分（全部 query 词满命中）
    var norm = 0;
    for (var m = 0; m < qTokens.length; m += 1) {
        var t2 = qTokens[m];
        var n2 = df[t2] || 0;
        norm += Math.log(1 + (N - n2 + 0.5) / (n2 + 0.5)) * (BM25_K1 + 1) / (BM25_K1);
    }
    if (norm <= 0) norm = 1;
    var out = [];
    for (var x = 0; x < raw.length; x += 1) out.push(Math.min(1, raw[x] / norm));
    return out;
}

// 把一条记忆构建成 BM25 doc
function toDoc(entry) {
    var hay = String(entry.title || "") + " " + String(entry.tags || "") + " " +
        String(entry.content || entry.prevContent || "");
    var tk = tokenize(hay);
    return { tf: termFreq(tk), len: tk.length };
}

// -------- 矛盾检测（对齐 mem0 的 UPDATE 决策） --------
// 只做语义层面的否定/数值冲突判定，不引入外部模型。
// 返回 { conflict, kind, reason, counterpart }
var NEG_CUES = [
    ["不要", "禁止", "禁用", "别", "不能", "禁止使用", "must not", "never", "do not"],
    ["必须", "务必", "一定要", "总是", "must", "always", "required"]
];

function hasAny(text, list) {
    for (var i = 0; i < list.length; i += 1) {
        if (text.indexOf(list[i]) >= 0) return true;
    }
    return false;
}

function numbersIn(text) {
    var m = String(text || "").match(/\d+(?:\.\d+)?/g) || [];
    var out = [];
    for (var i = 0; i < m.length; i += 1) {
        var v = Number(m[i]);
        if (!isNaN(v)) out.push(v);
    }
    return out;
}

// ===== 状态覆盖 (State Overwriting) =====
// 参照 kushbhardwaj0/llm-cognitive-memory-engine 的批评：
//   "Standard RAG stores outdated text blocks side-by-side with new ones
//    (retrieving both <old conclusion> and <new conclusion>)"
// 本引擎旧状态：detectConflict 只检测、auditScan 只报告，从不做替换动作，
// 新旧结论并列注入，模型可能拿旧的当依据。这里补上标记 + 降权 + 注入提示。
//
// 三步用法：
//   write 前   findSupersedeCandidates(list, title, content) 看有没有该覆盖的旧条目
//   write 后   markSuperseded(list, oldId, newId, cause)      给旧条目打标记
//   恢复时     clearSuperseded(entry)                        清除标记
//
// 全部纯函数，不碰 IO，hook 里也能调。

// 给旧条目打上"已被覆盖"标记。oldIdOrTitle 可传 id 或 title。
// 返回被标记的条目数。
function markSuperseded(list, oldIdOrTitle, newIdOrTitle, cause) {
    var arr = list || [];
    var key = String(oldIdOrTitle || "").trim();
    if (!key) return 0;
    var nk = String(newIdOrTitle || "").trim();
    var when = Date.now();
    var why = String(cause || "").slice(0, 120);
    var n = 0;
    for (var i = 0; i < arr.length; i += 1) {
        var e = arr[i];
        if (!e) continue;
        if (String(e.id || "") !== key && String(e.title || "") !== key) continue;
        e.superseded_by = nk;
        e.superseded_at = when;
        e.superseded_cause = why;
        n += 1;
    }
    return n;
}

// 清除单条的覆盖标记（结论被推翻需要恢复时用）。
function clearSuperseded(entry) {
    if (!entry || typeof entry !== "object") return false;
    var had = !!(entry.superseded_by || entry.superseded_at);
    try {
        delete entry.superseded_by;
        delete entry.superseded_at;
        delete entry.superseded_cause;
    } catch (_eDel) {
        entry.superseded_by = "";
        entry.superseded_at = 0;
        entry.superseded_cause = "";
    }
    return had;
}

// -------- 共享锚点检测（supersede 第二路） --------
// 为什么需要这第二路：实测 "数据库用 PostgreSQL" -> "数据库改用 ScyllaDB"
// 的 topicOverlap 只有 0.286，远低于 0.5 阈值 —— 检测不到。
// 而这恰恰是最该覆盖的场景（结论被完全推翻）。
// 根因：结论被推翻时措辞差异最大，纯相似度天然漏检。
//
// 补法：主体相同（锚点重合）+ 结论不同。
//   "数据库选型" 是锚点（两版都提），"PostgreSQL"/"ScyllaDB" 是结论（不同）。
// 负例（"数据库用 PostgreSQL" vs "今天天气不错"）锚点为 0，不会误报。

// 语法填充与高频无信息词，不算锚点。
var SUP_STOP = {};
(function () {
    var arr = ["必须", "不能", "可以", "需要", "因为", "所以", "如果", "就是", "这个", "那个",
        "整个", "否则", "不然", "决定", "支持", "问题", "内容", "失败", "成功", "出现",
        "列表", "定义", "数组", "键名", "已经", "没有", "还有", "他们", "我们", "你们",
        "什么", "怎么", "这样", "那样", "之后", "之前", "现在", "以前", "然后", "但是",
        "而且", "或者", "虽然", "不过", "由于", "根据", "通过", "对于", "关于", "一样"];
    for (var i = 0; i < arr.length; i += 1) SUP_STOP[arr[i]] = 1;
})();

// 门禁1：领域通用词。这些词几乎每条记忆都有，不能当锚点。
// 事故证据：三条无关条目（"ToolPkg 工具必须 return Promise" /
// "数组的 obj.entries..." / "OLD 里缺少注释"）被误判为同主题，
// 全靠 "toolpkg" + "必须" + "工具" 这类词过关。
var SUP_DOMAIN_STOP = {};
(function () {
    var arr = ["toolpkg", "operit", "sandbox", "worldbook", "memory",
        "世界书", "记忆库", "沙盒包", "沙盒", "插件", "工具包", "子包",
        "参数", "工具", "写入", "读取", "调用", "方法", "函数", "字段",
        "文件", "路径", "目录", "配置", "设置", "选项", "流程", "步骤",
        "问题", "情况", "时候", "地方", "东西", "事情", "方式", "办法",
        "注意", "记得", "检查", "确认", "处理", "执行", "返回", "报错"];
    for (var i = 0; i < arr.length; i += 1) SUP_DOMAIN_STOP[arr[i]] = 1;
})();

// 门禁3：虚词字表。锚点必须至少含 1 个非虚词字，否则是"虚词滑窗"。
// 事故证据：A2 残留误报锚点是 ["必须用","不是"] —— 纯虚词组合，
// 没有任何实义内容，却因为长度达标 + 出现在标题里而通过全部判据。
// 滑窗天然会产出大量这类组合，它们是中文句子的语法骨架，不是主题。
var SUP_FUNC_CHARS = {};
(function () {
    var s = "必须用不是的了在有和与或及就都也还又再很太更最" +
        "这那什么怎样如何因为所以但而且虽然如果则却只把被让" +
        "给对从到向于由之其者地得过着你我他她它们上下里外" +
        "一二三四五六七八九十个些点种次时候现在已经可能会要" +
        "能可需要应该别勿莫未无没非否唯仅只已曾";
    for (var i = 0; i < s.length; i += 1) SUP_FUNC_CHARS[s.charAt(i)] = 1;
})();

// 门禁4：纯 ASCII 泛词。这些英文/符号词在技术记忆里到处都是，
// 单独重合说明不了主题相同。
// 实测："现在必须用 new 参数写入" 与 "OLD 里缺少注释" 靠 "old" 重合被误判。
var SUP_ASCII_GENERIC = {};
(function () {
    var arr = ["old", "new", "the", "and", "for", "not", "you", "are", "this", "that",
        "with", "from", "have", "will", "can", "may", "should", "must", "use", "used",
        "true", "false", "null", "undefined", "string", "number", "object", "array",
        "var", "let", "const", "function", "return", "async", "await", "then", "catch",
        "error", "warn", "info", "debug", "test", "main", "index", "data", "file",
        "size", "name", "type", "value", "key", "list", "item", "props", "args",
        "params", "param", "options", "config", "result", "response", "request", "ok"];
    for (var i = 0; i < arr.length; i += 1) SUP_ASCII_GENERIC[arr[i]] = 1;
})();

// 锚点是否含实义内容（至少 1 个非虚词字）。
function supersedeIsMeaningful(word) {
    var w = String(word || "");
    if (!w) return false;
    // 纯英文/数字词视为有意义（parameters / redis 等）
    if (!/[\u4e00-\u9fa5]/.test(w)) return w.length >= 2;
    for (var i = 0; i < w.length; i += 1) {
        if (!SUP_FUNC_CHARS[w.charAt(i)]) return true;
    }
    return false;
}

// 抽取锚点集合：英文词（>=2 字符）+ 中文 2~4 字滑窗。
function supersedeAnchors(text) {
    var t = String(text || "").toLowerCase();
    var out = {};
    var en = t.match(/[a-z][a-z0-9_\.\-]{1,}/g) || [];
    for (var i = 0; i < en.length; i += 1) out[en[i]] = 1;
    var cn = t.match(/[\u4e00-\u9fa5]{2,}/g) || [];
    for (var j = 0; j < cn.length; j += 1) {
        var s = cn[j];
        var lim = s.length < 4 ? s.length : 4;
        for (var k = 2; k <= lim; k += 1) {
            for (var p = 0; p + k <= s.length; p += 1) out[s.substr(p, k)] = 1;
        }
    }
    return out;
}

// 两段文本的共享锚点（去掉停用词，长词优先，去子串）。
function supersedeSharedAnchors(a, b) {
    var A = supersedeAnchors(a);
    var B = supersedeAnchors(b);
    var shared = [];
    for (var k in A) {
        if (!Object.prototype.hasOwnProperty.call(A, k)) continue;
        if (SUP_STOP[k]) continue;
        if (SUP_DOMAIN_STOP[k]) continue;      // 门禁1：领域通用词不算锚点
        if (k.length < 2) continue;
        // 滑窗产出的部分重合词同样要排除（"具包参"、"包参数" 这类）
        var domHit = false;
        for (var dk in SUP_DOMAIN_STOP) {
            if (!Object.prototype.hasOwnProperty.call(SUP_DOMAIN_STOP, dk)) continue;
            if (k.indexOf(dk) >= 0 && k.length <= dk.length + 1) { domHit = true; break; }
        }
        if (domHit) continue;
        if (!supersedeIsMeaningful(k)) continue;   // 门禁3：纯虚词滑窗不算锚点
        if (SUP_ASCII_GENERIC[k]) continue;        // 门禁4：纯 ASCII 泛词不算锚点
        if (B[k]) shared.push(k);
    }
    shared.sort(function (x, y) { return y.length - x.length; });
    var kept = [];
    for (var i = 0; i < shared.length; i += 1) {
        var s = shared[i];
        var isSub = false;
        for (var j = 0; j < kept.length; j += 1) {
            if (kept[j].indexOf(s) >= 0) { isSub = true; break; }
        }
        if (!isSub) kept.push(s);
    }
    return kept;
}

// 锚点路由：返回 {hit, anchors, why}
// hit 判据（三条同时成立才认）：
//   1. 共享锚点 >= 2 个（单个词重合太容易巧合）
//   2. 至少一个锚点 >= 3 字（排除纯二字偶然重合）
//   3. 至少一个锚点出现在任一方标题里（标题是条目身份，比正文可靠）
function supersedeAnchorHit(oldEntry, newTitle, newContent) {
    var o = oldEntry || {};
    var oldAll = String(o.title || "") + " " + String(o.tags || "") + " " + String(o.content || "");
    var newAll = String(newTitle || "") + " " + String(newContent || "");
    var shared = supersedeSharedAnchors(oldAll, newAll);

    // titleOk 必须单独算「两方标题的共享锚点」。
    // 原因：实测 "数据库用 PostgreSQL" vs "数据库改用 ScyllaDB" 时，
    // 正文层的 4 字滑窗锚点是 "数据库选"，并不出现在标题里；
    // 而标题真正共享的是 "数据库"(3字)，它在 shared 里被去子串逻辑吞掉了。
    // 用 shared 去判 titleOk 会漏掉这一类。
    var oldTitleLow = String(o.title || "").toLowerCase();
    var newTitleLow = String(newTitle || "").toLowerCase();
    var titleShared = supersedeSharedAnchors(String(o.title || ""), String(newTitle || ""));
    var titleOk = false;
    for (var ti = 0; ti < titleShared.length; ti += 1) {
        if (titleShared[ti].length >= 2) { titleOk = true; break; }
    }
    // 保底：shared 里若有锚点直接出现在任一方标题内，也算 titleOk
    if (!titleOk) {
        for (var i = 0; i < shared.length; i += 1) {
            if (oldTitleLow.indexOf(shared[i]) >= 0 || newTitleLow.indexOf(shared[i]) >= 0) { titleOk = true; break; }
        }
    }

    // longOk 收紧：需要 >=4 字的中文实义锚点（完整词），或 >=6 字符的英文专名。
    // 原因：3 字滑窗碎片（"件时"就是 2 字、"参数键"是 3 字）区分不开词与碎片，
    // 只有 4 字以上的中文串才基本能保证是完整词。
    // 实测漏网：3 字锚点 "必须用"、"参数键" 都会放行无关条目。
    var longOk = false;
    for (var li = 0; li < shared.length; li += 1) {
        var w = shared[li];
        if (/[\u4e00-\u9fa5]/.test(w)) {
            if (w.length >= 4) { longOk = true; break; }
        } else {
            if (w.length >= 6) { longOk = true; break; }
        }
    }
    // 标题共享锚点本身就是强信号（标题是条目身份），放宽到 >=3 字
    if (!longOk && titleOk) {
        for (var tj = 0; tj < titleShared.length; tj += 1) {
            var tw = titleShared[tj];
            if (/[\u4e00-\u9fa5]/.test(tw)) {
                if (tw.length >= 3) { longOk = true; break; }
            } else if (tw.length >= 6) { longOk = true; break; }
        }
    }

    // 门禁2：内容高度重合说明是"同一段话的重复"（duplicate），
    // 不是"结论被推翻"（supersede）。这种事交给 mem_merge_apply 处理。
    // 真正该替代的场景重合度一般在 0.2~0.7（措辞差异明显但主体相同）。
    var contentJac = 0;
    try {
        contentJac = jaccard(String(o.content || ""), String(newContent || ""));
    } catch (_eJc) { contentJac = 0; }
    var tooSimilar = contentJac > 0.85;

    var hit = (shared.length >= 2 && longOk && titleOk && !tooSimilar);
    var why = "";
    if (hit) {
        why = "主体相同（" + shared.slice(0, 3).join("、") + "）但结论不同";
    }
    return {
        hit: hit,
        anchors: shared.slice(0, 6),
        title_anchors: titleShared.slice(0, 4),
        content_jaccard: Math.round(contentJac * 1000) / 1000,
        too_similar: tooSimilar,
        why: why
    };
}

// 找该被新条目覆盖的旧条目（写入前调用）。
// 判据与 detectConflict 一致（topicOverlap >= thr），但返回的是可执行列表而非报告，
// 每个候选带 reason，供 mem_write 直接决定覆盖谁。
function findSupersedeCandidates(list, newTitle, newContent, opts) {
    var o = opts || {};
    var arr = list || [];
    var thr = typeof o.threshold === "number" ? o.threshold : 0.5;
    var limit = typeof o.limit === "number" ? o.limit : 3;
    var nt = String(newTitle || "");
    var nc = String(newContent || "");
    var probe = { title: nt, content: nc, tags: o.tags || "" };
    var out = [];
    for (var i = 0; i < arr.length; i += 1) {
        var e = arr[i];
        if (!e) continue;
        if (nt && String(e.title || "") === nt) continue;   // 同名是更新不是覆盖
        if (e.archived) continue;
        if (e.superseded_by) continue;                       // 已覆盖的不重复标
        var ov = 0;
        try {
            ov = typeof topicOverlap === "function" ? topicOverlap(probe, e) : 0;
        } catch (_eOv) { ov = 0; }
        // 两路检测，任一命中即入选。
        // 第一路：topicOverlap >= thr（措辞接近的改写）
        // 第二路：共享锚点（措辞差异大但主体相同的结论推翻）——
        //   实测 "数据库用 PostgreSQL" -> "数据库改用 ScyllaDB" overlap 仅 0.286，
        //   只靠第一路会完全漏掉。这一路正是补这个缺口。
        var anchor = null;
        try {
            anchor = typeof supersedeAnchorHit === "function"
                ? supersedeAnchorHit(e, nt, nc) : null;
        } catch (_eAn) { anchor = null; }
        var byOverlap = (ov >= thr);
        var byAnchor = !!(anchor && anchor.hit);
        if (!byOverlap && !byAnchor) continue;

        // 方向判定：只有 overlap 路才采信 looksContradictory 的粗判。
        // 锚点路不采信 —— 它本来就是"措辞差异大"的场景，否定/肯定词的出现
        // 往往是无关的（"不要用长块 str.replace" 不是"结论相反"的证据）。
        // 实测：锚点路配上 looksContradictory 会把 "OLD 里缺少注释" 标成"结论相反"。
        var contradiction = false;
        if (byOverlap) {
            try {
                contradiction = typeof looksContradictory === "function" ? looksContradictory(probe, e) : false;
            } catch (_eLc) { contradiction = false; }
        }

        var hitPath = byOverlap && byAnchor ? "both" : (byOverlap ? "overlap" : "anchor");
        // 置信度分级：overlap 路是"措辞接近"，基本可确定是同一结论的改写（high）。
        // 纯 anchor 路是"主体相同但结论不同"，可能是取代也可能是并列
        // （实测边界：groups.json 与 hidden.json 共享"世界书"锚点，但属并列），
        // 所以标 low，把最终判断交回模型 —— 引擎只给证据，不替模型下结论。
        var conf = byOverlap ? "high" : "low";
        var rs = "";
        if (contradiction) rs = "同主题结论相反";
        else if (byAnchor && !byOverlap) {
            rs = "主体相同但结论不同（" + String((anchor.anchors || []).slice(0, 2).join("、")) +
                "），可能是取代也可能是并列，需你判断";
        } else rs = "同主题被新结论取代";
        out.push({
            id: e.id || "",
            title: String(e.title || ""),
            layer: String(e.layer || ""),
            overlap: Math.round(ov * 1000) / 1000,
            anchors: anchor && anchor.anchors ? anchor.anchors : [],
            title_anchors: anchor && anchor.title_anchors ? anchor.title_anchors : [],
            // 判据数值必须带出去：此前 supersedeAnchorHit 算了 content_jaccard / too_similar，
            // 但候选结果只取了 hit / anchors / why，两个数值字段算完就丢。
            // 模型看到「主体相同但结论不同」时无法判断这判定有多接近阈值，
            // 也就无法分辨「确定取代」与「勉强压线」。
            content_jaccard: (anchor && typeof anchor.content_jaccard === "number") ? anchor.content_jaccard : null,
            too_similar: !!(anchor && anchor.too_similar),
            path: hitPath,
            confidence: conf,
            contradiction: contradiction,
            reason: rs
        });
    }
    // high 置信优先；同置信内按 overlap 排。
    // 不按 overlap 单独排的原因：anchor 路命中的 overlap 天然低，
    // 单纯比 overlap 会让它们永远沉底，等于白做这第二路。
    out.sort(function (a, b) {
        if (a.confidence !== b.confidence) return a.confidence === "high" ? -1 : 1;
        return b.overlap - a.overlap;
    });
    return out.slice(0, limit);
}

function detectConflict(entries, title, content) {
    var list = entries || [];
    var newText = String(content || "").toLowerCase();
    var newNums = numbersIn(newText);
    var best = null;
    for (var i = 0; i < list.length; i += 1) {
        var e = list[i];
        if (!e || e.title === title) continue;
        var hay = (String(e.title || "") + " " + String(e.tags || "") + " " +
            String(e.content || e.prevContent || "")).toLowerCase();
        var sim = lexicalScore(content, hay);
        if (sim < 0.45) continue;
        // 同类主题：否定/肯定对冲
        var newNeg = hasAny(newText, NEG_CUES[0]);
        var newPos = hasAny(newText, NEG_CUES[1]);
        var oldNeg = hasAny(hay, NEG_CUES[0]);
        var oldPos = hasAny(hay, NEG_CUES[1]);
        var polarityFlip = (newNeg && oldPos && !newPos) || (newPos && oldNeg && !newNeg);
        // 同类主题：数值互斥（同一位置不同数字）
        var oldNums = numbersIn(hay);
        var numClash = false;
        for (var a = 0; a < newNums.length && !numClash; a += 1) {
            for (var b = 0; b < oldNums.length; b += 1) {
                if (newNums[a] !== oldNums[b]) { numClash = true; break; }
            }
        }
        var score = sim + (polarityFlip ? 0.35 : 0) + (numClash ? 0.2 : 0);
        var kind = polarityFlip ? "polarity" : (numClash ? "numeric" : "overlap");
        var hit = (polarityFlip || numClash);
        if (hit && (!best || score > best.score)) {
            best = {
                conflict: true, kind: kind, score: Math.round(score * 1000) / 1000,
                reason: polarityFlip ? "否定/肯定方向相反" : "关键数值互斥",
                counterpart: { title: e.title, layer: e.layer, version: e.version || 1 }
            };
        }
    }
    if (!best) return { conflict: false };
    return best;
}

// -------- 自动抽取（消息持久化时触发） --------
// 平台挂载点：ToolPkg.registerChatMessageHook → message_persisted
// payload 含 sender / roleName / content / chatId。
// 抽取规则全部本地启发式，不依赖外部模型调用，保证 hook 内不阻塞。
// ============================================================
// 抽取规则 —— 修复「把思考过程当结论存」
// ------------------------------------------------------------
// 实测产出的垃圾条目：
//   「关键发现」「关键发现：缓存被回滚了」「现在改成不区分大小写的模糊匹配」
// 全是我思考过程中的中间句，不是结论，对以后没用。
//
// 原因：
//   1. 模式里的「发现」「改成」太宽，「关键发现」「现在改成」都会命中
//   2. 没排除思考标记（think 块）
//   3. 没排除未闭合的 markdown 强调
//   4. 没要求句子本身必须是「陈述结论」形式
//
// 新策略：
//   模式只留「被动形式」结论标记，不留动作词；
//   另加一道 isConclusionSentence 强校验。
// ============================================================
// 纠正型模式：主人指出错误/提出要求时说的话（最高价值信号）
var CORRECTION_PATTERNS = [
    { re: /(不对|错了|搞错|弄错|理解错|说错|写错|算错|不是这样|不是这个)/, layer: "L3", kind: "correction" },
    { re: /(应该是|应当|该用|改用|改成|换成|要这样|得这样)/, layer: "L3", kind: "correction" },
    { re: /(别|不要|别再|不用|无需|没必要|禁止|不准|不许)/, layer: "L3", kind: "forbid" },
    { re: /(以后|今后|往后|从此|后续都|一律|统一)/, layer: "L2", kind: "decision" },
    { re: /(记住|记好|别忘了|注意|重点是|关键是|问题在于|根本原因)/, layer: "L3", kind: "principle" },
    { re: /(漏了|缺了|少了|忘了|没做|没加|没改|还没|仍然|还是有问题|依然)/, layer: "L2", kind: "finding" },
    { re: /(补充|追加|而不是新建|不要新建|合并|归并|同类)/, layer: "L2", kind: "decision" }
];

var EXTRACT_PATTERNS = [
    // L3 铁律：只认「必须/禁止/铁律」这类硬词
    { re: /(必须|禁止|绝不|务必|一律|永远|铁律|底线|不得|原则是)/, layer: "L3", kind: "principle" },
    // L2 约定：只认「以后都/统一用/固定用」这类已定下的
    { re: /(以后都|统一用|固定用|一律用|规定用|约定好)/, layer: "L2", kind: "decision" },
    // L2 根因：只认「根因是/原因是/本质是」，不要单独的「发现」
    { re: /(根因是|原因是|本质是|实际上是|问题在于)/, layer: "L2", kind: "finding" },
    // L2 因果：「导致 / 会导致 / 因此 / 所以」引出后果
    { re: /(导致|会导致|从而导致|因此|所以必须|否则)/, layer: "L2", kind: "cause" },
    // L3 操作禁令：「绝对不能 / 不要 / 不得 / 禁止」
    { re: /(绝对不能|绝不能|不要再|不得|禁止直接|不要每次)/, layer: "L3", kind: "forbid" },
    // L2 统一做法：「统一 / 以后都 / 固定 / 一律」
    { re: /(统一|以后都|固定用|一律|都要用)/, layer: "L2", kind: "decision" },
    // L3 反复缺陷：同一问题每次重复发生（这类才是真教训，必须常驻）
    { re: /(每次都会|每次都要|每装一次|反复出现|又触发|总是会|屡次)/, layer: "L3", kind: "repeat" },
    // L2 机制说明：陈述「这样做就能/会让/作用是」——这是可复用的机制知识
    { re: /(就能|就会|会让|作用是|效果是|等于|等价于|原理是)/, layer: "L2", kind: "mechanism" },
    // L1 事件：只认带报错原文的
    { re: /(报错信息是|错误信息是|Error:|Exception:)/, layer: "L1", kind: "event" }
];

// 思考过程的典型开头，看到就丢弃
var PROCESS_LEAD = [
    /^关键发现/, /^发现了/, /^现在改成/, /^现在看/,
    /^接下来/, /^先看/, /^先查/, /^好的/, /^行，/,
    /^嗯/, /^对了/, /^下一步/, /^第一步/, /^首先/, /^然后/,
    /^我需要/, /^我先/, /^我来/, /^我发现/, /^我觉得/, /^我现在/
];

// 代码片段识别：用于丢弃被拆句后混进来的源码行
// 判据是「代码语法特征累计命中数」，单条特征不算（避免误杀正常的斜杠/花括号说明）
function looksLikeCode(t) {
    var s = String(t || "");

    // ===== 强特征：任一命中即判代码 =====
    if (/\{[^}]*\bre\s*:/.test(s)) return true;                   // { re: /.../ }
    if (/\{[^}]*\w+\s*:/.test(s) && /[{}]/.test(s)) return true;   // { key: value }
    if (/=>/.test(s)) return true;                                 // 箭头函数
    if (/\/[\(\[\\^$.*+?(){}\[\]|]/.test(s)) return true;         // /( /[ /\ /^
    if (/\/[gimsuy]{1,5}[,;)\s]/.test(s)) return true;              // /xxx/g
    if (/\b(var|let|const|function|return|typeof|await|async|exports|require)\b/.test(s)) return true;
    if (/^\s*\/\/|^\s*\/\*|^\s*\*\s/.test(s)) return true;           // 注释行
    if (/\\[nrt]/.test(s)) return true;                            // 转义残片
    if (/\[\s*\]/.test(s)) return true;                            // 空数组
    if (/\+\s*["']|["']\s*\+/.test(s)) return true;                // 字符串拼接
    if (/[=!<>]=?[^=]/.test(s) && /[a-zA-Z_]\s*[=!<>]/.test(s)) return true;

    // ===== 弱特征：累计 >=2 =====
    var hits = 0;
    if (/[{}[\]]/.test(s)) hits += 1;
    if (/;\s*$/.test(s)) hits += 1;
    if (/\bif\b|\bfor\b|\bwhile\b|\btry\b|\bcatch\b/.test(s)) hits += 1;
    if (/^\s{2,}/.test(s)) hits += 1;
    var cjk = (s.match(/[\u4e00-\u9fa5]/g) || []).length;
    var nonCjk = s.replace(/[\u4e00-\u9fa5\s]/g, "").length;
    if (s.length > 20 && cjk > 0 && nonCjk / (cjk + nonCjk) > 0.55) hits += 1;
    if (cjk < 3 && /[{}()\[\];=<>\/\\]/.test(s)) hits += 1;
    // ===== 强特征补充：序号 + 驼峰标识符 的枚举行残片 =====
    // 例："(2) 必须过 isConclusionSentence"
    var hasCamel = /[a-z]{3,}[A-Z][a-z]{2,}/.test(s);
    var startsEnum = /^[\u2460-\u2473]/.test(s.trim()) || /^\s*[0-9]{1,2}[.\u3001)]/.test(s);
    if (hasCamel && (startsEnum || cjk < 8)) return true;

    return hits >= 2;
}

// 元描述/引用行识别：这类是「关于内容的话」，不是结论本身
// 典型：索引行 "- [L3] xxx"、摘录行 "- 「xxx」"、元描述 "原文应该是..." 
function isMetaLine(t) {
    var s = String(t || "").trim();
    if (!s) return true;
    // 1) 列表符号起头（索引行/摘录行）
    if (/^[-*·•]\s*/.test(s)) return true;
    // 2) 层级前缀 [L1]/[L2]/[L3]
    if (/\[L[123]\]/.test(s)) return true;
    // 3) 注入块名（索引摘要自身被当内容）
    if (s.indexOf("known_lessons_index") >= 0) return true;
    if (s.indexOf("已沉淀教训索引") >= 0) return true;
    if (s.indexOf("memory_growth") >= 0) return true;
    if (s.indexOf("self_review_discipline") >= 0) return true;
    if (s.indexOf("pending_self_review") >= 0) return true;
    // 4) 元描述起头（在说「上一条/这次对话」而不是在陈述结论）
    if (/^(原文应该是|原文是|本次对话|上面这条|刚才那条|上一条|这条的|这一条|注：|备注：|说明：)/.test(s)) return true;
    // 5) 整句是纯引用（首尾都被引号包住）
    if (/^[`「『"'].*[`」』"']$/.test(s) && s.length > 4) return true;
    // 6) 以「--」或「→」纯箭头连接的两段摘录
    if (/^\S{0,6}\s*→\s*\S{0,40}$/.test(s) && s.length < 60) return true;
    // 7) 序号起头的步骤描述（"1. xxx" "2、xxx" "• xxx"）
    if (/^\d{1,2}\s*[.、)）]\s*/.test(s)) return true;
    // 8) 犹豫/推测/自问起头（"等等，" "可能是" "也许是" "让我想想" "会不会是"）
    if (/^(等等|也许|可能还|让我|我想想|会不会|是不是|难道|或者说|另外|顺便|补充一下|顺带)/.test(s)) return true;
    // 9) 第一人称动作描述（"我去看" "我查了" "我来试" "我试了"）
    if (/^我(去|来|先|再|又|也|已|刚|将|要|想|查|看|试|改|写|读|跑|建|做)/.test(s)) return true;
    // 10) 命令行/终端输出起头（===== / --- / ###）
    if (/^[=\-#*]{2,}/.test(s)) return true;
    // 11) 原文复述 / 过程记录起头
    if (/^(原文|我写的是|我原本|之前我|刚才|现在我|现在改成|接下来我|这可能是|关键区别|关键发现|实际测试|实测发现)/.test(s)) return true;
    return false;
}

// 是否是一个可存的结论句
// 纠正句：主人指出问题时说的话本身就是结论，不走 PROCESS_LEAD 过滤
function isCorrectionSentence(s) {
    var t = String(s || "").trim();
    if (!t) return false;
    if (t.length < 8) return false;
    if (t.length > 400) return false;
    if (looksLikeCode(t)) return false;
    if (/\u2026$/.test(t)) return false;
    var stars = (t.match(/\*\*/g) || []).length;
    if (stars % 2 === 1) return false;
    var bt = (t.match(/`/g) || []).length;
    if (bt % 2 === 1) return false;
    var hit = false;
    for (var i = 0; i < CORRECTION_PATTERNS.length; i += 1) {
        if (CORRECTION_PATTERNS[i].re.test(t)) { hit = true; break; }
    }
    if (!hit) return false;
    var cjk = (t.match(/[\u4e00-\u9fa5]/g) || []).length;
    var en = (t.match(/[a-zA-Z]{3,}/g) || []).length;
    if (cjk + en * 3 < 6) return false;
    return true;
}

function isConclusionSentence(s) {
    var t = String(s || "").trim();
    if (!t) return false;

    // 1) 含思考标记 -> 丢
    if (t.indexOf("<think>") >= 0 || t.indexOf("</think>") >= 0) return false;

    // 2) 以过程性措辞开头 -> 丢
    for (var i = 0; i < PROCESS_LEAD.length; i += 1) {
        if (PROCESS_LEAD[i].test(t)) return false;
    }

    // 3) markdown 强调未闭合 -> 丢
    var stars = (t.match(/\*\*/g) || []).length;
    if (stars % 2 === 1) return false;

    // 4) 代码围栏未闭合 -> 丢
    var ticks = (t.match(/```/g) || []).length;
    if (ticks % 2 === 1) return false;

    // 5) 行内反引号奇数 -> 丢
    var bt = (t.match(/`/g) || []).length;
    if (bt % 2 === 1) return false;

    // 6) 以省略号/破折号结尾 -> 说明被截断
    if (/…$/.test(t)) return false;
    // 8) 代码片段 -> 丢（它不是知识，是源码行）
    if (looksLikeCode(t)) return false;
    // 9) 元描述/引用行 -> 丢（索引行、摘录行、"原文应该是"这类）
    if (isMetaLine(t)) return false;

    // 7) 必须有足够实词
    var cjk = (t.match(/[\u4e00-\u9fa5]/g) || []).length;
    var en = (t.match(/[a-zA-Z]{3,}/g) || []).length;
    if (cjk + en * 3 < 10) return false;

    return true;
}


var EXTRACT_SKIP = [
    "好的", "收到", "嗯", "ok", "continue", "继续"
];

// -------- 句子切分 --------
// 关键修正：原实现用 text.match(re) 在整个文本里找第一个匹配，
// 匹配点常落在句子中间（如「…发现了新版 v3.3.0…」从「了」后切断），
// 于是产出 len=7 / len=9 的残句垃圾。
// 现在改为：先按句读切成完整句，只在句子内部匹配，抽出的必然是完整句。
var SENT_SPLIT = /[\n\r。！？!?；;]+/;

function splitSentences(text) {
    var parts = String(text || "").split(SENT_SPLIT);
    var out = [];
    for (var i = 0; i < parts.length; i += 1) {
        var s = String(parts[i] || "").replace(/\s+/g, " ").trim();
        if (s) out.push(s);
    }
    return out;
}

// 整行代码闸门：在分句之前先按行剔除含代码的行。
// 为什么必须在分句之前：SENT_SPLIT 含分号，"if (!hasVerbish) return false; 这里必须有动词标记"
// 会被切成 "if (" / "hasVerbish) return false" / "这里必须有动词标记才算完整句" 三段。
// 前两段各自 looksLikeCode 命中被丢，尾段是纯中文、含动词、括号配对，
// 逐条判据全部放行 —— 于是代码行后面的中文注释残片被当成独立结论抽进记忆库。
// 实测：整句 looksLikeCode=true，但拆开后尾段 false，提取出 1 条污染条目。
// 行级先判一次，代码行连同它后面的说明一起丢弃，从源头断掉这种残片。
function stripCodeLines(text) {
    var raw = String(text || "");
    if (!raw) return "";
    var lines = raw.split(/[\n\r]+/);
    var kept = [];
    for (var i = 0; i < lines.length; i += 1) {
        var line = String(lines[i] || "");
        if (!line.trim()) continue;
        if (looksLikeCode(line)) continue;
        kept.push(line);
    }
    return kept.join(String.fromCharCode(10));
}

// 猜测/不确定语气：真教训是确定陈述，出现这些词说明是过程推测，整句拒绝
var HEDGE_RE = /(可能|也许|大概|或许|似乎|恐怕|按理说|说不定|不确定|估计|应该会|或者说|大致上)/;

// 质量门槛：挡住无头无尾的从句与纯符号
// 纯连接词/助词：任何长度开头都是残片（句子不可能用连词起头）
var BAD_HEAD_HARD = ["的", "了", "和", "与", "及", "或", "就", "都", "也", "还", "但", "而", "于", "因", "由", "才", "又", "再", "却", "即", "并且", "而且"];
// 可作动词/介词的字符：短句开头多为残片，但长句常是正常祈使句（如「把配置初值设成启用」）
var BAD_HEAD_WEAK = ["是", "在", "被", "把", "让", "给", "从", "对", "向", "为", "将"];

// 从句/状语的典型起头：以这些结构开头说明是残片
var BAD_LEAD_PATTERNS = [
    /^以便/, /^从而/, /^使得/, /^用于/, /^用来/, /^为了/, /^因为/, /^所以/,
    /^如果/, /^虽然/, /^尽管/, /^随之/, /^进而/, /^并且/, /^而且/,
    // 过程记录 / 原文复述 / 无主语引导（不是结论，是对话过程残片）
    /^原文/, /^我写的是/, /^我原本/, /^之前我/, /^刚才/, /^现在我/,
    /^现在改成/, /^接下来我/, /^关键区别/, /^关键发现/, /^实际测试/, /^实测发现/,
    /^这可能是/, /^那可能是/, /^这也许/, /^重要的是/
];

// 判定是否「独立可读的完整句」
// -------- 计划句识别（抽取门禁）--------
// 为什么需要：实测自动抽取把 ko 的行动计划当知识存了。
//   "上轮列的三条遗留必须逐条核实，同时找出真正还没做的缺口"
// 这句含动词「逐条核实」、无代词悬空、无猜测词、括号配对、长度达标，
// 过了 isUsableSentence 全部现有门禁 —— 因为现有门禁全在查"是不是残片"，
// 没有一类查"是不是计划"。
//
// 计划句 vs 结论句的区别：
//   计划句：说"接下来要做什么"，不可复用
//   结论句：说"什么是真的/怎么才对"，可复用
//
// 判据（三条同时成立才判计划句）：
//   1. 含意图或任务标记
//   2. 不含结论标记
//   3. 不含具体可复用标识符（代码 / 路径 / API 名）

// 意图 / 任务编排标记
var PLAN_INTENT = /(上轮|下轮|本轮|这轮|上一轮|下一轮|接下来|下一步|待办|遗留|逐条|逐项|一一|继续增强|准备|打算|计划中|先做|再改|然后改|需要核实|需要处理|需要验证|待处理|待核实)/;
// 结论标记（出现即说明是在陈述事实，不是计划）
var PLAN_CONCLUSION = /(实测|验证过|已验证|结论|因为|所以|说明|原因是|原因在|导致|结果是|等于|意味着|证明|证实|复现了|跑出来)/;
// 具体标识符（代码 / 路径 / API）
var PLAN_CONCRETE = /`[^`]{2,}`|\/sdcard\/|\/[a-z_]+\/[a-z_]+\.|\.[a-z]{2,4}\b|[a-z]{3,}_[a-z]{3,}|[A-Za-z]+\.[A-Za-z]+/;

// 返回 true = 这是计划句，不该当记忆存。
function looksLikePlanSentence(s) {
    var t = String(s || "").trim();
    if (!t) return false;
    if (!PLAN_INTENT.test(t)) return false;            // 没有意图标记 = 不像计划
    if (PLAN_CONCLUSION.test(t)) return false;          // 有结论标记 = 是在陈述事实
    if (PLAN_CONCRETE.test(t)) return false;            // 有具体标识符 = 有可复用内容
    return true;
}

function isUsableSentence(s) {
    var t = String(s || "").trim();
    if (t.length < 12) return false;
    if (t.length > 300) return false;
    // 不能以连接/助词起头（那是从句残片）
    var head = t.charAt(0);
    // 纯连接词起头 -> 一定是残片
    for (var i = 0; i < BAD_HEAD_HARD.length; i += 1) {
        if (head === BAD_HEAD_HARD[i]) return false;
    }
    // 可作动词/介词的字符：只有很短时才判为残片
    if (t.length < 18) {
        for (var i2 = 0; i2 < BAD_HEAD_WEAK.length; i2 += 1) {
            if (head === BAD_HEAD_WEAK[i2]) return false;
        }
    }
    for (var j = 0; j < BAD_LEAD_PATTERNS.length; j += 1) {
        if (BAD_LEAD_PATTERNS[j].test(t)) return false;
    }
    // 必须含中文实词或足够英文词
    var cjk = (t.match(/[\u4e00-\u9fa5]/g) || []).length;
    var en = (t.match(/[a-zA-Z]{3,}/g) || []).length;
    if (cjk + en * 3 < 8) return false;
    if (!/[\u4e00-\u9fa5a-zA-Z]/.test(t)) return false;
    // 目的/结果状语：句中含「以便/从而/进而/使得」，且主干部分过短 -> 残片
    var midCue = t.match(/(以便|从而|进而|使得)/);
    if (midCue) {
        var cueAt = t.indexOf(midCue[1]);
        if (cueAt >= 0 && cueAt < 10) return false;
    }
    // 必须有动词性标记，否则是名词短语碎片
    var hasVerbish = /(是|要|需|应|可|会|能|必须|禁止|不要|判|做|用|走|改|写|读|取|加|减|设|查|跑|建|拆|解|验|测|记|删|增|让|使|把|被)/.test(t);
    if (!hasVerbish) return false;
    // 括号必须配对：未闭合说明是从句被切断的残片
    var _pairs = [["\u300c", "\u300d"], ["\u300e", "\u300f"], ["\uff08", "\uff09"], ["(", ")"], ["\u300a", "\u300b"], ["[", "]"]];
    for (var pi2 = 0; pi2 < _pairs.length; pi2 += 1) {
        var lo = t.split(_pairs[pi2][0]).length - 1;
        var ro = t.split(_pairs[pi2][1]).length - 1;
        if (lo !== ro) return false;
    }
    // 猜测/不确定语气 -> 不是结论
    if (HEDGE_RE.test(t)) return false;
    // 无主语引导句（"原因是X""关键区别在于Y"）-> 脱离上下文读不懂，是残片
    if (/^(原因是|是因为|这是|那是|这就是|也就是|而不是|而在于|关键区别|关键发现)/.test(t)) return false;
    // 代词悬空：句首或前 6 字内用「这些/那些/这个/那个/它们/上述/以上/该/此」
    // 但没有具体对象锚定 -> 脱上下文读不懂，是抽取残片。
    // 实测漏网：「这些永远不会被注入（除非关键词命中）」——无代码特征、含动词、括号配对，
    // 过了全部现有门槛，但主语「这些」指向的是上一段表格，单独存下来毫无意义。
    // 豁免：后面紧跟具体名词（如「这个函数」「该参数」「此接口」）算有锚定。
    var PRO_ANCHOR = /^(这些|那些|这个|那个|它们|上述|以上|该|此)(?=[一-龥]{2,8}(?:函数|方法|字段|参数|接口|文件|目录|命令|工具|包|类|值|配置|规则|条目|库|模块|变量|路径|日志|错误|异常|流程|步骤|接口))/;
    var PRO_BARE = /^(这些|那些|这个|那个|它们|上述|以上)(?![一-龥]{2,8}(?:函数|方法|字段|参数|接口|文件|目录|命令|工具|包|类|值|配置|规则|条目|库|模块|变量|路径|日志|错误|异常|流程|步骤))/;
    if (PRO_BARE.test(t) && !PRO_ANCHOR.test(t)) {
        // 再看句中是否出现了足够具体的英文标识符或引号包裹的名字
        var hasConcrete = /`[^`]{3,}`/.test(t) || /[a-z]{4,}[A-Z][a-z]{2,}/.test(t) || /[A-Za-z_]{5,}/.test(t);
        if (!hasConcrete) return false;
    }
    // 代词 + 副词/助动词直接相接 = 代词悬空。
    // 「这个会导致…」「这些都会…」里代词本该修饰名词却直接挂动词，脱离上文无从指代。
    // 豁免「这个X函数」这种已被 PRO_ANCHOR 覆盖的形式。
    if (/^(这些|那些|这个|那个|它们|上述|以上|该|此)(会|能|要|将|都|也|才|就|已|不|没|被|把|让|使|可|需|应|是|有|无|全部|全|均|皆)/.test(t)) {
        var hasConcrete2 = /`[^`]{3,}`/.test(t) || /[a-z]{4,}[A-Z][a-z]{2,}/.test(t);
        if (!hasConcrete2) return false;
    }
    // markdown 强调符号残留 -> 命令输出/思考过程残片
    if (t.indexOf("**") >= 0) return false;
    // 纯摘录箭头（"A \u2192 B"）无解释
    if (/^.{0,12}\s*\u2192\s*.{0,60}$/.test(t)) return false;
    // 计划/意图句：说"接下来要做什么"，不是知识。
    // 实测污染："上轮列的三条遗留必须逐条核实，同时找出真正还没做的缺口"
    // 这类句子过了上面全部 20+ 道门禁（有动词、无代词、括号配对、长度达标）。
    if (looksLikePlanSentence(t)) return false;
    return true;
}

// 在单句内匹配模式；返回要写入的正文（整句，而不是括号捕获片段）
function matchInSentence(sentence, pat) {
    if (!pat || !pat.re) return null;
    var m = sentence.match(pat.re);
    if (!m) return null;
    // 用整句做正文：段落完整、脱离上下文也能读懂
    var body = String(sentence).trim();
    if (!isUsableSentence(body)) return null;
    return body;
}

function extractFromMessage(content, sender) {
    var src = String(sender || "").toUpperCase();
    var isUser = src.indexOf("USER") >= 0 || src.indexOf("HUMAN") >= 0;
    return _extractImpl(content, sender, isUser);
}

function _extractImpl(content, sender, isUser) {
    var text = String(content || "").trim();
    if (!text || text.length < 12) return [];
    if (text.length > 8000) text = text.slice(0, 8000);
    for (var s = 0; s < EXTRACT_SKIP.length; s += 1) {
        if (text === EXTRACT_SKIP[s]) return [];
    }
    // 先按行剔除代码行，再分句。
    // 顺序不能反：SENT_SPLIT 含分号，代码行会被切碎，中文注释残片
    // 单独过 looksLikeCode 判 false，于是被当成独立结论抽走（实测污染过记忆库）。
    var cleaned = stripCodeLines(text);
    if (!cleaned || cleaned.trim().length < 12) return [];
    var sentences = splitSentences(cleaned);
    if (sentences.length === 0) return [];
    var out = [];
    var seen = {};
    for (var si = 0; si < sentences.length; si += 1) {
        var sent = sentences[si];

        // 主人发言：纠正句是最高价值信号，优先抽
        if (isUser && isCorrectionSentence(sent)) {
            var ckey = "C:" + sent.slice(0, 40);
            if (seen[ckey]) continue;
            seen[ckey] = 1;
            var cpat = null;
            for (var ci = 0; ci < CORRECTION_PATTERNS.length; ci += 1) {
                if (CORRECTION_PATTERNS[ci].re.test(sent)) { cpat = CORRECTION_PATTERNS[ci]; break; }
            }
            out.push({
                layer: (cpat && cpat.layer) || "L3",
                kind: (cpat && cpat.kind) || "correction",
                title: buildTitle("correction", sent),
                content: String(sent).trim(),
                tags: "auto,correction,from_user",
                source: String(sender || "user"),
                from_user: true
            });
            if (out.length >= 5) break;
            continue;
        }

        if (!isUsableSentence(sent)) continue;
        // 【关键】必须是「结论句」，把思考过程挡在门外
        // isUsableSentence 只判语法完整性，判不了「这是结论还是过程」。
        // 实测放进来的是「关键发现」「现在改成不区分大小写的模糊匹配」这类中间句。
        if (!isConclusionSentence(sent)) continue;
        for (var pi = 0; pi < EXTRACT_PATTERNS.length; pi += 1) {
            var pat = EXTRACT_PATTERNS[pi];
            var body = matchInSentence(sent, pat);
            if (!body) continue;
            var key = body.slice(0, 40);
            if (seen[key]) continue;
            seen[key] = 1;
            out.push({
                layer: pat.layer,
                kind: pat.kind,
                title: buildTitle(pat.kind, body),
                content: body,
                tags: "auto," + pat.kind,
                source: String(sender || "")
            });
            break; // 一句只归一类，避免同一句被多个模式重复写入
        }
        if (out.length >= 5) break;
    }
    return out;
}

// ============================================================
// 标题生成 —— 修复「残句标题」
// ------------------------------------------------------------
// 旧实现的错误：
//   if (t.length > 28) t = t.slice(0, 28);
//   return kind + ":" + t;
// 两个问题：
//   a) slice(0,28) 按字符数硬切，切在半句上，产出「了新版 v3.3.0…」
//   b) 加 kind 前缀，产出「event:」「finding:」「decision:」，这些前缀对检索毫无帮助，
//      反而让标题看不出主题。
//
// 新策略（对齐 SillyTavern 的 comment 理念：标注要可读，不是正文切片）：
//   1. 不加任何 kind 前缀
//   2. 先按句读（。！？；）取第一个完整句
//   3. 过长再按冒号取主旨部分（「…修复点：…」）
//   4. 还过长按逗号取前分句
//   5. 必须截断时带省略号，说明还有下文
// ============================================================
var TITLE_MAX = 32;

// ============================================================
// \u52a8\u6001\u8bc4\u4f30\u8bb0\u5fc6\u91cd\u8981\u5ea6 \u2014\u2014 \u4fee\u590d\u300c\u5168\u90e8\u5199\u6b7b 0.45\u300d
// ------------------------------------------------------------
// \u65e7\u5b9e\u73b0\uff1aimportance: 0.45 \u5199\u6b7b\u5728 hook_impl \u91cc\u3002
// \u540e\u679c\uff1a\u771f\u6b63\u91cd\u8981\u7684 L3 \u94c1\u5f8b\u548c\u4e00\u53e5\u65e0\u804a\u65e5\u5fd7\uff0c\u6743\u91cd\u5b8c\u5168\u4e00\u6837\uff0c
//       \u6392\u5e8f\u65f6\u65e0\u6cd5\u533a\u5206\uff0c\u91cd\u8981\u7684\u88ab\u6df9\u6ca1\u3002
//
// \u4f9d\u636e mem0 \u7684 memory scoring \u601d\u8def\uff1a\u91cd\u8981\u5ea6\u5e94\u8be5\u7531\u5185\u5bb9\u7279\u5f81\u51b3\u5b9a\uff0c
// \u4e0d\u662f\u4e00\u4e2a\u56fa\u5b9a\u5e38\u91cf\u3002\u8fd9\u91cc\u7528\u4e94\u4e2a\u53ef\u89c2\u6d4b\u4fe1\u53f7\uff1a
//   1. \u5c42\u7ea7\uff1aL3 \u94c1\u5f8b > L2 \u4e0d\u53ef\u53d8\u8bba\u65ad
//   2. \u542b\u786c\u6027\u8bcd\uff08\u5fc5\u987b/\u7981\u6b62/\u7edd\u4e0d/\u94c1\u5f8b\uff09\u2192 \u52a0\u5206
//   3. \u542b\u5177\u4f53\u6280\u672f\u540d\uff08\u82f1\u6587\u6807\u8bc6\u7b26/\u6587\u4ef6\u540d/\u5de5\u5177\u540d\uff09\u2192 \u52a0\u5206
//   4. \u542b\u53ef\u6267\u884c\u52a8\u4f5c\uff08\u8c03/\u7528/\u6539/\u5199/\u67e5/\u9a8c\uff09\u2192 \u52a0\u5206
//   5. \u5b57\u6570\u8fc7\u77ed\uff08<20\uff09\u2192 \u51cf\u5206
// ============================================================
function computeImportance(kind, body, layer) {
    var t = String(body || "");
    var score = 0.5;
    var l = String(layer || "").toUpperCase();
    if (l === "L3") score = 0.85;
    else if (l === "L2") score = 0.65;

    // \u786c\u6027\u8bcd
    if (/(\u5fc5\u987b|\u7981\u6b62|\u7edd\u4e0d|\u52a1\u5fc5|\u4e00\u5f8b|\u6c38\u8fdc|\u4e0d\u8981|\u94c1\u5f8b|\u539f\u5219\u662f|\u5e95\u7ebf|\u4e0d\u5f97)/.test(t)) score += 0.1;

    // \u5177\u4f53\u6280\u672f\u540d\uff08\u82f1\u6587\u6807\u8bc6\u7b26\u30015+\u4f4d\uff09
    var techs = t.match(/[A-Za-z][A-Za-z0-9_.\-]{4,}/g) || [];
    if (techs.length >= 1) score += 0.05;
    if (techs.length >= 3) score += 0.05;

    // \u53ef\u6267\u884c\u52a8\u4f5c
    if (/(\u8c03\u7528|\u6539\u5199|\u6539\u6210|\u7528\u4e86|\u5199\u5165|\u67e5\u8be2|\u9a8c\u8bc1|\u5b9a\u4f4d|\u56de\u8bfb|\u590d\u73b0|\u547d\u4ee4|\u53c2\u6570)/.test(t)) score += 0.05;

    // \u5b57\u6570\u8fc7\u77ed\u51cf\u5206
    if (t.length < 20) score -= 0.15;
    if (t.length < 15) score -= 0.15;

    // \u542b\u62a5\u9519\u539f\u6587\u7684\uff08\u5e26\u5f15\u53f7/\u4ee3\u7801\uff09\u52a0\u5206
    if (/[`""]/.test(t)) score += 0.03;

    if (score < 0.2) score = 0.2;
    if (score > 1.0) score = 1.0;
    return Math.round(score * 100) / 100;
}

function buildTitle(kind, body) {
    var t = String(body || "").replace(/\s+/g, " ").trim();
    if (!t) return "";

    // 2) 按句读取第一个完整句
    var seps = ["。", "！", "？", "；", "! ", "? ", "; "];
    var cut = t.length;
    for (var s = 0; s < seps.length; s += 1) {
        var at = t.indexOf(seps[s]);
        if (at > 0 && at < cut) cut = at;
    }
    var title = t.slice(0, cut).trim();
    if (!title) title = t;

    // 3) 冒号处理：冒号前是引导句（过短）就取冒号后的正文，否则取冒号前主旨
    var _colon = title.indexOf("：");
    if (_colon < 0) _colon = title.indexOf(":");
    if (_colon >= 0) {
        var _head = title.slice(0, _colon).trim();
        var _tail = title.slice(_colon + 1).trim();
        // 引导句特征：含"在于/是/如下/说明"且很短 -> 用后半段
        if (_head.length < 10 && /(在于|是|如下|说明|问题|区别)/.test(_head) && _tail.length >= 6) {
            title = _tail;
        } else if (title.length > TITLE_MAX && _colon >= 6 && _colon <= TITLE_MAX) {
            title = _head;
        }
    }

    // 4) 还过长：按逗号取前分句
    if (title.length > TITLE_MAX) {
        var comma = title.indexOf("，");
        if (comma < 0) comma = title.indexOf(",");
        if (comma >= 8) title = title.slice(0, comma).trim();
    }

    // 5) 最后手段：截断必须带省略号
    if (title.length > TITLE_MAX) {
        title = title.slice(0, TITLE_MAX - 2).trim() + "…";
    }
    return title;
}
function preview(entry, len) {
    if (!entry) return "";
    var src = String(entry.prevContent || entry.evidence || "");
    var n = Number(len) || 80;
    return src.length > n ? src.slice(0, n) + "..." : src;
}
function isDueReview(entry, nowMs) {
    var now = nowMs || Date.now();
    return (Number(entry.nextReviewAt) || 0) <= now;
}
function extractMemoryList(r) {
    if (!r) return [];
    if (Array.isArray(r)) return r;
    if (Array.isArray(r.data)) return r.data;
    if (Array.isArray(r.results)) return r.results;
    if (Array.isArray(r.memories)) return r.memories;
    if (r.data && Array.isArray(r.data.results)) return r.data.results;
    if (r.data && Array.isArray(r.data.memories)) return r.data.memories;
    return [];
}
async function writeNative(title, content, options) {
    var o = options || {};
    try {
        if (typeof Tools !== "undefined" && Tools.Memory && typeof Tools.Memory.create === "function") {
            var r = await Tools.Memory.create({ title: title, content: content, contentType: "text", source: "memory_growth" });
            return { ok: true, raw: r };
        }
    } catch (e) {
        return { ok: false, error: String((e && e.message) || e) };
    }
    try {
        var r2 = await toolCall("create_memory", { title: title, content: content });
        return { ok: true, raw: r2 };
    } catch (e2) {
        return { ok: false, error: String((e2 && e2.message) || e2) };
    }
}
async function updateNative(title, content) {
    try {
        if (typeof Tools !== "undefined" && Tools.Memory && typeof Tools.Memory.update === "function") {
            var r = await Tools.Memory.update(title, { content: content });
            return { ok: true, raw: r };
        }
    } catch (e) {
        return { ok: false, error: String((e && e.message) || e) };
    }
    try {
        var r2 = await toolCall("update_memory", { old_title: title, content: content });
        return { ok: true, raw: r2 };
    } catch (e2) {
        return { ok: false, error: String((e2 && e2.message) || e2) };
    }
}
async function deleteNative(title) {
    var t = String(title || "").trim();
    if (!t) return { ok: false, error: "empty title" };
    try {
        if (typeof Tools !== "undefined" && Tools.Memory && typeof Tools.Memory.remove === "function") {
            var r = await Tools.Memory.remove(t);
            return { ok: true, raw: r };
        }
    } catch (e) {
        return { ok: false, error: String((e && e.message) || e) };
    }
    try {
        if (typeof Tools !== "undefined" && Tools.Memory && typeof Tools.Memory.delete === "function") {
            var r2 = await Tools.Memory.delete(t);
            return { ok: true, raw: r2 };
        }
    } catch (e2) {
        return { ok: false, error: String((e2 && e2.message) || e2) };
    }
    try {
        var r3 = await toolCall("delete_memory", { title: t });
        return { ok: true, raw: r3 };
    } catch (e3) {
        return { ok: false, error: String((e3 && e3.message) || e3) };
    }
}

// 从人类可读的 disclosure 里抽触发词。
// 例：「当用户提到部署或打包时」-> ["部署", "打包"]
// 只做保守抽取：去掉虚词，保留 2 字以上实词段。
function extractDiscloseTerms(text) {
    var s = String(text || "");
    if (!s) return [];
    s = s.replace(/^(当|如果|若|一旦)/, "");
    s = s.replace(/(的时候|时|的话)?[。，,\s]*$/, "");
    s = s.replace(/(用户|主人|我|你|对方)?(提到|说到|说起|谈及|问起|询问|要求|需要|要做|做)/g, " ");
    var parts = s.split(/[或、,，\/|和与\s]+/);
    var out = [];
    for (var i = 0; i < parts.length; i += 1) {
        var t = String(parts[i] || "").trim();
        if (t.length >= 2 && t.length <= 12) out.push(t);
    }
    return out;
}


// ============================================================
// dependency invalidation (see mnemosyne)
// ============================================================
// Changed A => B depending on A must be marked stale, otherwise the system
// keeps teaching old conclusions. Mark only; never rewrite content.
function findDependents(list, changedIds, changedTitles) {
    var ids = {};
    var titles = {};
    for (var i = 0; i < (changedIds || []).length; i += 1) ids[String(changedIds[i])] = 1;
    for (var j = 0; j < (changedTitles || []).length; j += 1) titles[String(changedTitles[j])] = 1;
    var out = [];
    for (var k = 0; k < list.length; k += 1) {
        var e = list[k];
        if (!e) continue;
        var deps = Array.isArray(e.dependsOn) ? e.dependsOn : [];
        if (deps.length === 0) continue;
        for (var d = 0; d < deps.length; d += 1) {
            var dv = String(deps[d]);
            if (ids[dv] || titles[dv]) { out.push(e); break; }
        }
    }
    return out;
}

function invalidateDependents(list, changedIds, changedTitles, reason) {
    var hit = findDependents(list, changedIds, changedTitles);
    var names = [];
    var now = Date.now();
    var why = String(reason || "");
    if (!why && (changedTitles || []).length) why = String(changedTitles[0]);
    for (var i = 0; i < hit.length; i += 1) {
        var e = hit[i];
        e.stale_by = why.slice(0, 60);
        e.stale_at = now;
        names.push(String(e.title || ""));
    }
    return { count: names.length, names: names };
}

function clearStale(entry) {
    if (!entry) return false;
    if (entry.stale_by !== undefined) delete entry.stale_by;
    if (entry.stale_at !== undefined) delete entry.stale_at;
    return true;
}


// ============================================================
// cross-entry concept linking (see Nocturne Memory "memory islands")
// ============================================================
// A concept shared by several entries stays invisible unless the query
// happens to hit all of them. Extract recurring terms, expose shared ones.
// df = document frequency. Concept when 2 <= df <= 40% of corpus.
function buildConceptIndex(list) {
    var N = (list || []).length;
    if (N < 2) return { terms: {}, N: N };
    var df = {};
    for (var i = 0; i < N; i += 1) {
        var e = list[i];
        if (!e) continue;
        var text = String(e.title || "") + " " + String(e.tags || "") + " " + String(e.content || "");
        var toks = tokenize(text);
        var seen = {};
        for (var t = 0; t < toks.length; t += 1) {
            var k = toks[t];
            if (k.length < 2) continue;
            if (seen[k]) continue;
            seen[k] = 1;
            df[k] = (df[k] || 0) + 1;
        }
    }
    var cap = Math.max(2, Math.floor(N * 0.4));
    var terms = {};
    for (var kk in df) {
        var d = df[kk];
        if (d >= 2 && d <= cap) terms[kk] = d;
    }
    return { terms: terms, N: N, cap: cap };
}

function findRelated(entry, list, conceptIdx, limit) {
    var lim = limit || 3;
    if (!entry || !list || !conceptIdx || !conceptIdx.terms) return [];
    var idx = conceptIdx.terms;
    var myText = String(entry.title || "") + " " + String(entry.tags || "") + " " + String(entry.content || "");
    var myToks = tokenize(myText);
    var myConcepts = {};
    for (var i = 0; i < myToks.length; i += 1) {
        if (idx[myToks[i]]) myConcepts[myToks[i]] = 1;
    }
    if (Object.keys(myConcepts).length === 0) return [];
    var out = [];
    for (var j = 0; j < list.length; j += 1) {
        var o = list[j];
        if (!o || o === entry) continue;
        if (String(o.title || "") === String(entry.title || "")) continue;
        var oText = String(o.title || "") + " " + String(o.tags || "") + " " + String(o.content || "");
        var oToks = tokenize(oText);
        var shared = [];
        var seenO = {};
        for (var m = 0; m < oToks.length; m += 1) {
            var tk = oToks[m];
            if (seenO[tk]) continue;
            seenO[tk] = 1;
            if (myConcepts[tk]) shared.push(tk);
        }
        if (shared.length >= 2) {
            out.push({
                title: String(o.title || ""),
                layer: String(o.layer || ""),
                shared: shared.slice(0, 4),
                score: shared.length
            });
        }
    }
    out.sort(function (a, b) { return b.score - a.score; });
    return out.slice(0, lim);
}


// ============================================================
// injection budget guard (tiered compression)
// ============================================================
// Compression order (drop first = lowest value):
//   1. related-concept links
//   2. stale review list
//   3. consolidate hints
//   4. entry list from 8 down to 4
// Never dropped: index line, rules section.
var INJECT_BUDGET = 1600;

function compressHintBlock(lines, budget) {
    var lim = budget || INJECT_BUDGET;
    var total = 0;
    for (var i = 0; i < lines.length; i += 1) total += String(lines[i] || "").length + 1;
    if (total <= lim) return { lines: lines, dropped: 0, total: total, compressed: false };

    var out = lines.slice();
    var dropped = 0;

    // pass 1: related-concept lines
    var i2 = out.length - 1;
    while (i2 >= 0 && total > lim) {
        var s2 = String(out[i2] || "");
        if (s2.indexOf("关联（与") >= 0 || s2.indexOf("  ~ ") === 0) {
            total -= s2.length + 1; out.splice(i2, 1); dropped += 1;
        }
        i2 -= 1;
    }

    // pass 2: stale review block
    i2 = out.length - 1;
    while (i2 >= 0 && total > lim) {
        var s3 = String(out[i2] || "");
        var prevIsStale = out[i2 - 1] && String(out[i2 - 1]).indexOf("待复核") >= 0;
        if (s3.indexOf("待复核") >= 0 || prevIsStale ||
            s3.indexOf("确认后用 mem_evolve") >= 0) {
            total -= s3.length + 1; out.splice(i2, 1); dropped += 1;
        }
        i2 -= 1;
    }

    // pass 3: consolidate hints
    i2 = out.length - 1;
    while (i2 >= 0 && total > lim) {
        var s4 = String(out[i2] || "");
        if (s4.indexOf("可提炼：") >= 0) {
            total -= s4.length + 1; out.splice(i2, 1); dropped += 1;
        }
        i2 -= 1;
    }

    // pass 4: trim entry list tail, keep at least 4
    i2 = out.length - 1;
    var entryKept = 0;
    for (var e1 = 0; e1 < out.length; e1 += 1) if (String(out[e1]).indexOf("  - [L") === 0) entryKept += 1;
    while (i2 >= 0 && total > lim && entryKept > 4) {
        var s5 = String(out[i2] || "");
        if (s5.indexOf("  - [L") === 0) {
            total -= s5.length + 1; out.splice(i2, 1); dropped += 1; entryKept -= 1;
        }
        i2 -= 1;
    }

    return { lines: out, dropped: dropped, total: total, compressed: true };
}

function buildIndexHint(idx, text) {
    var list = (idx && idx.entries) ? idx.entries : [];
    var byLayer = { L1: 0, L2: 0, L3: 0 };
    var now = Date.now();
    var due = 0;
    var stale = 0;
    for (var i = 0; i < list.length; i += 1) {
        var e = list[i];
        byLayer[e.layer] = (byLayer[e.layer] || 0) + 1;
        if (isDueReview(e, now)) due += 1;
        if (isStale(e, now)) stale += 1;
    }
    // ===== 多信号融合检索（参照 mem0：BM25 x 实体 x 时序 三路融合）=====
    // 旧实现三个缺陷：①用 lexicalScore 而不是已写好的 bm25Score（无 IDF/无长度归一）
    // ②只喂 title+tags，正文 content 从不参与排序 ③没有任何时间维度。
    var scored = [];
    var q = String(text || "");
    var docs = [];
    for (var d1 = 0; d1 < list.length; d1 += 1) {
        var de = list[d1];
        var full = String(de.title || "") + " " + String(de.tags || "") + " " + String(de.content || "");
        var toks = tokenize(full);
        docs.push({ tf: termFreq(toks), len: toks.length });
    }
    var bm25 = q ? bm25Score(q, docs) : [];
    var qTokens = q ? tokenize(q) : [];
    var nowMs = Date.now();
    var HALF_LIFE_DAYS = 30;
    for (var j = 0; j < list.length; j += 1) {
        var ent = list[j];
        // S1 词法：BM25（IDF + tf 饱和 + 长度归一）
        var s1 = q ? (bm25[j] || 0) : 0;
        // S2 实体：查询词命中标题算强信号（标题是条目身份，比正文命中更可靠）
        var s2 = 0;
        if (qTokens.length > 0) {
            var titleLow = String(ent.title || "").toLowerCase();
            var tagLow = String(ent.tags || "").toLowerCase();
            var hitT = 0, hitG = 0;
            for (var t1 = 0; t1 < qTokens.length; t1 += 1) {
                var tk1 = qTokens[t1];
                if (titleLow.indexOf(tk1) >= 0) hitT += 1;
                if (tagLow.indexOf(tk1) >= 0) hitG += 1;
            }
            s2 = (hitT / qTokens.length) * 0.75 + (hitG / qTokens.length) * 0.25;
        }
        // S3 时序：越新越重，命中次数补一点（用过说明有价值）
        var lastTs = Number(ent.updatedAt || ent.createdAt || 0);
        var days = lastTs > 0 ? ((nowMs - lastTs) / 86400000) : 999;
        if (days < 0) days = 0;
        var recency = 1 / (1 + days / HALF_LIFE_DAYS);
        var hitBoost = Math.min(0.25, (Number(ent.hitCount) || 0) * 0.03);
        var s3 = 0.7 * recency + 0.3 * hitBoost;
        // 查询为空时只用时序（没法算相关性，按新旧排行最合理）
        var fused = q ? (0.55 * s1 + 0.30 * s2 + 0.15 * s3) : s3;

        // S4 条件触发（disclosure）：参照 Nocturne Memory 的 Disclosure Routing。
        // 纯 BM25 是「盲盒检索」——靠词面巧合抽取，无法表达「当 X 发生时读我」。
        var discloseHit = 0;
        if (q && typeof ent.disclosure === "string" && ent.disclosure) {
            var dTerms = Array.isArray(ent.disclose_on) ? ent.disclose_on : [];
            if (dTerms.length === 0) dTerms = extractDiscloseTerms(ent.disclosure);
            var dh = 0;
            for (var dt = 0; dt < dTerms.length; dt += 1) {
                if (q.indexOf(dTerms[dt]) >= 0) dh += 1;
            }
            if (dTerms.length > 0) discloseHit = dh / dTerms.length;
            if (dh > 0) {
                // 条件命中：强加成，压过普通条目
                fused = Math.min(1.0, fused + 0.45 * discloseHit);
            } else {
                // 声明了条件但未命中：降权，让位给真正相关的
                fused = fused * 0.55;
            }
        }

        // stale demote: a dependency changed, this entry may be outdated.
        // Demote but never hide - hiding loses the evidence.
        if (ent && ent.stale_by) fused = fused * 0.5;
        // superseded demote: 状态已被新结论覆盖。比 stale 更狠（0.25 vs 0.5），
        // 因为 stale 只是"前提可能变了"，superseded 是"已经有了明确的新结论"。
        // 同样只降权不隐藏——旧结论的演化过程本身是证据。
        if (ent && ent.superseded_by) fused = fused * 0.25;
        scored.push({ e: ent, s: fused, sig: { lex: s1, entity: s2, temporal: s3, disclose: discloseHit } });
    }
    scored.sort(function (a, b) { return b.s - a.s; });
    var picked = [];
    for (var k = 0; k < scored.length && picked.length < 8; k += 1) {
        if (scored[k].s > 0.08 || list.length <= 12) picked.push(scored[k].e);
    }
    if (picked.length === 0) picked = list.slice(0, 8);
    // 即时体检：只做可线性判定的项（空壳 / 缺 id），不做 O(n²) 相似度。
    // 原因：旧版本这里只报 due / stale，thin 和 duplicates 要等每 10 轮的全量审计，
    // 中间 9 轮库里有空壳也看不见。
    var thinCount = 0, noIdCount = 0, thinTitles = [];
    for (var t1 = 0; t1 < list.length; t1 += 1) {
        var te = list[t1];
        if (!te) continue;
        if (typeof looksThin === "function" && looksThin(te)) {
            thinCount += 1;
            if (thinTitles.length < 3) thinTitles.push(String(te.title || "").slice(0, 22));
        }
        if (!te.id) noIdCount += 1;
    }
    // 提炼候选扫描：把「同类攒够 3 条」的簇找出来，主动提示 mem_consolidate。
    // 旧实现只有索引块末尾那句静态规则「4. 同类 L1 攒到 3 条以上，主动 mem_consolidate」,
    // 完全依赖模型自己记得去数,实测从不触发。这里改成系统主动统计并列出候选。
    var consolidateClusters = [];
    try {
        var buckets = {};
        for (var c1 = 0; c1 < list.length; c1 += 1) {
            var ce = list[c1];
            if (!ce) continue;
            if (ce.archived) continue;                      // 已归档的不再参与
            var cl = String(ce.layer || "");
            if (cl !== "L1" && cl !== "L2") continue;       // L3 是终点,不再往上提
            var tagKey = "";
            var tagRaw = String(ce.tags || "").trim();
            if (tagRaw) {
                tagKey = tagRaw.split(/[,\s]+/)[0];
            }
            var bk;
            if (tagKey) {
                bk = cl + "|T|" + tagKey;
                if (!buckets[bk]) buckets[bk] = { layer: cl, key: tagKey, items: [], repTitle: "" };
            } else {
                // 无 tags：不能硬切「标题前 N 字」——「沙盒包参数/沙盒包解析/沙盒包安装」
                // 前 4 字各不相同（沙盒包参/沙盒包解/沙盒包安），硬切会永远聚不成一簇。
                // 改为与已有无 tag 桶比较公共前缀，>= 3 字视为同簇。
                var tt = String(ce.title || "");
                if (!tt) continue;
                var found = null;
                for (var bp in buckets) {
                    var b2 = buckets[bp];
                    if (b2.layer !== cl || b2.repTitle === undefined || !b2.repTitle) continue;
                    var pfx = 0;
                    var lim = Math.min(tt.length, b2.repTitle.length);
                    while (pfx < lim && tt.charAt(pfx) === b2.repTitle.charAt(pfx)) pfx += 1;
                    if (pfx >= 3) { found = bp; break; }
                }
                if (found) {
                    bk = found;
                } else {
                    bk = cl + "|P|" + tt;
                    buckets[bk] = { layer: cl, key: tt.slice(0, 6), items: [], repTitle: tt };
                }
            }
            buckets[bk].items.push(ce);
        }
        for (var bk2 in buckets) {
            var gg = buckets[bk2];
            if (gg.items.length >= 3) consolidateClusters.push(gg);
        }
        consolidateClusters.sort(function (a, b) { return b.items.length - a.items.length; });
        consolidateClusters = consolidateClusters.slice(0, 2);   // 最多提示 2 簇,避免噪音
    } catch (ecc) {
        consolidateClusters = [];   // 扫描失败不影响索引主流程
    }

    var L = [];
    L.push('<memory_growth source="memory_growth" total="' + list.length + '">');
    L.push("记忆索引：L1 事件 " + byLayer.L1 + " / L2 经验 " + byLayer.L2 + " / L3 原则 " + byLayer.L3 + "｜待复习 " + due + "｜陈旧 " + stale);
    if (thinCount > 0 || noIdCount > 0) {
        var warn = [];
        if (thinCount > 0) warn.push("空壳 " + thinCount + " 条");
        if (noIdCount > 0) warn.push("缺 id " + noIdCount + " 条");
        L.push("体检：" + warn.join(" / ") + "（用 mem_audit 看明细，mem_evolve 补内容）");
        if (thinTitles.length) L.push("  空壳示例：" + thinTitles.join("、"));
    }
    if (consolidateClusters.length > 0) {
        for (var cc = 0; cc < consolidateClusters.length; cc += 1) {
            var cg = consolidateClusters[cc];
            var names = [];
            for (var cn = 0; cn < cg.items.length && names.length < 3; cn += 1) {
                names.push(String(cg.items[cn].title || "").slice(0, 24));
            }
            L.push("可提炼：" + cg.layer + " 有 " + cg.items.length + " 条同簇（" + cg.key + "）→ 用 mem_consolidate 提炼成 " +
                (cg.layer === "L1" ? "L2" : "L3"));
            L.push("  " + names.join("、"));
        }
    }
    L.push("");
    // stale reminder: list explicitly, do not rely on the model noticing
    var staleList = [];
    for (var sc = 0; sc < list.length; sc += 1) {
        var se = list[sc];
        if (se && se.stale_by && staleList.length < 5) {
            staleList.push(String(se.title || "").slice(0, 26) + " (因 " + String(se.stale_by).slice(0, 20) + " 已变)");
        }
    }
    if (staleList.length > 0) {
        L.push("**待复核**：" + staleList.length + " 条依赖的前提已变，结论可能过时");
        for (var sl = 0; sl < staleList.length; sl += 1) L.push("  - " + staleList[sl]);
        L.push("  处理：确认后用 mem_evolve 改写，或 mem_review 标记已复核。");
    }
    // superseded reminder: 状态覆盖后必须让模型看见，否则等于没覆盖。
    var supList = [];
    for (var ssc = 0; ssc < list.length; ssc += 1) {
        var sse = list[ssc];
        if (sse && sse.superseded_by && supList.length < 5) {
            supList.push(String(sse.title || "").slice(0, 26) +
                " → 已被「" + String(sse.superseded_by).slice(0, 26) + "」取代");
        }
    }
    if (supList.length > 0) {
        L.push("**已过时**：" + supList.length + " 条状态已被新结论覆盖，不要再当依据");
        for (var ssl = 0; ssl < supList.length; ssl += 1) L.push("  x " + supList[ssl]);
        L.push("  处理：以新条目为准；旧条目的历史价值用 mem_search 回查，不要再写重复条目。");
    }
    L.push("可用条目（只有标题，内容需你主动读）：");
    // cross-entry linking: surface entries sharing concepts with the top pick.
    try {
        if (typeof buildConceptIndex === "function" && picked.length > 0) {
            var cIdx = buildConceptIndex(list);
            var rel = findRelated(picked[0], list, cIdx, 3);
            if (rel.length > 0) {
                L.push("关联（与「" + String(picked[0].title || "").slice(0, 20) + "」共享概念，可一并参考）：");
                for (var rl = 0; rl < rel.length; rl += 1) {
                    L.push("  ~ [" + rel[rl].layer + "] " + String(rel[rl].title || "").slice(0, 26) +
                        "（共享：" + rel[rl].shared.join("、") + "）");
                }
            }
        }
    } catch (_eRel) { }
    for (var m = 0; m < picked.length; m += 1) {
        var pe = picked[m];
        L.push("  - [" + pe.layer + "] " + pe.title + "（命中 " + (Number(pe.hitCount) || 0) + "，v" + (Number(pe.version) || 1) + "）");
    }
    L.push("");
    L.push("必须遵守：");
    L.push("1. 上面只是索引。要内容用 get_memory_by_title 读全文，禁止凭空假设。");
    L.push("2. 真用上了立刻调 mem_mark_used 记命中，否则系统不知道它有用。");
    L.push("3. 本轮学到值得留的，主动调 mem_write 写入，layer 自己判断：L1=事件 L2=经验 L3=原则。");
    L.push("4. 同类 L1 攒到 3 条以上，主动 mem_consolidate 提炼成 L2；L2 够了再提 L3。");
    L.push("5. 发现已有记忆不准，用 mem_evolve 更新（保留旧版），别另建重复条目。");
    L.push("6. 没值得记的就什么都不做，不要为了填而写。");
    L.push("</memory_growth>");

    // tiered compression before handing the block over
    try {
        var packed = compressHintBlock(L, INJECT_BUDGET);
        if (packed && packed.compressed) {
            var pl = packed.lines.slice();
            pl.splice(pl.length - 1, 0,
                "（已按 " + INJECT_BUDGET + " 字预算压缩，删 " + packed.dropped +
                " 行；完整内容用 mem_audit / mem_search 取）");
            return pl.join(String.fromCharCode(10));
        }
    } catch (_eCmp) { }

    return L.join(String.fromCharCode(10));
}

exports.LAYERS = LAYERS;
exports.clampInt = clampInt;
exports.clampNum = clampNum;
exports.normalizeLayer = normalizeLayer;
exports.layerIntervalMs = layerIntervalMs;
exports.isStale = isStale;
exports.isDueReview = isDueReview;
exports.tokenize = tokenize;
exports.jaccard = jaccard;
exports.findSimilar = findSimilar;
exports.lexicalScore = lexicalScore;
exports.bm25Score = bm25Score;
exports.detectConflict = detectConflict;
exports.markSuperseded = markSuperseded;
exports.clearSuperseded = clearSuperseded;
exports.findSupersedeCandidates = findSupersedeCandidates;
exports.supersedeAnchors = supersedeAnchors;
exports.supersedeSharedAnchors = supersedeSharedAnchors;
exports.supersedeAnchorHit = supersedeAnchorHit;
exports.SUP_DOMAIN_STOP = SUP_DOMAIN_STOP;
exports.SUP_FUNC_CHARS = SUP_FUNC_CHARS;
exports.supersedeIsMeaningful = supersedeIsMeaningful;
exports.SUP_ASCII_GENERIC = SUP_ASCII_GENERIC;
exports.extractFromMessage = extractFromMessage;
exports.splitSentences = splitSentences;
exports.isUsableSentence = isUsableSentence;
exports.looksLikePlanSentence = looksLikePlanSentence;
exports.isCorrectionSentence = isCorrectionSentence;
exports.CORRECTION_PATTERNS = CORRECTION_PATTERNS;
exports.buildTitle = buildTitle;
exports.toDoc = toDoc;
exports.termFreq = termFreq;
exports.preview = preview;
exports.extractMemoryList = extractMemoryList;
exports.writeNative = writeNative;
exports.updateNative = updateNative;
exports.deleteNative = deleteNative;
exports.buildIndexHint = buildIndexHint;
exports.compressHintBlock = compressHintBlock;
exports.INJECT_BUDGET = INJECT_BUDGET;
exports.buildConceptIndex = buildConceptIndex;
exports.findRelated = findRelated;
exports.findDependents = findDependents;
exports.invalidateDependents = invalidateDependents;
exports.clearStale = clearStale;
exports.extractDiscloseTerms = extractDiscloseTerms;
exports.computeImportance = computeImportance;
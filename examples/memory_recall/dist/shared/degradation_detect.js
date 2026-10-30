// 退化与停滞探测（Degradation & Stagnation Detection）
// 参照 DSH-AGINT 第六章：停滞 / 回归 / 同质化 三种信号。
//
// 关键修正（实测踩到的坑）：
//   ToolPkg 沙箱里 fs 是空对象（Object.keys(fs) 返回空），
//   必须用 Tools.Files.*（异步）+ ToolPkg.getConfigDir()。
//   旧实现用 fs.readFileSync，在沙箱里静默失败被 try/catch 吞掉，
//   表现为「recordSnapshot 调了但历史一直是 0 条」。

var DIR_NAME = "evolution";
var HIST_FILE = "health_history.json";
var MAX_HISTORY = 60;

// 健康历史读取错误。与 evolution_memory 的 _cacheErr 同一套语义：
// 读盘失败绝不能伪装成「成功但空库」，否则上层判定永远健康。
var _histErr = null;
var READ_MISS_PAT = /enoent|not found|no such file|does not exist|filenotfound/i;

// 共享目录解析（与 evolution_memory.js 同一套语义）。
// 为什么必须共享：实测磁盘上两个包各写一份 health_history.json
// （记忆库 1784B / 世界书 416B），降级检测只看得到自己那一份，
// 跨包「退化/停滞」判定基于残缺样本。
var SHARED_POINTER = "/sdcard/Download/Operit/plugins/_shared/evolution_dir.txt";
var _resolvedBase = undefined;   // undefined=未解析, null=用默认, string=共享

function baseDir() {
    if (typeof _resolvedBase === "string" && _resolvedBase) return _resolvedBase;
    try {
        return ToolPkg.getConfigDir() + "/" + DIR_NAME;
    } catch (e) {
        return null;
    }
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
    _resolvedBase = picked;
    return baseDir();
}

function histPath() { return baseDir() + "/" + HIST_FILE; }

async function ensureDir() {
    try { await Tools.Files.mkdir(baseDir(), true); } catch (e) { }
}

async function readRaw(path) {
    try {
        var r = await Tools.Files.read(path);
        return String((r && r.content) || "");
    } catch (e) { return ""; }
}

// 带错误回传的读：区分「文件不存在」与「读盘失败」。
// 原 readRaw 一律返回 ""，无法区分两者，是 S6 失败的直接原因。
async function readRawE(path) {
    try {
        var r = await Tools.Files.read(path);
        return { text: String((r && r.content) || ""), err: null };
    } catch (e) {
        return { text: "", err: String((e && e.message) || e || "read_fail") };
    }
}

async function writeRaw(path, text) {
    try {
        await ensureDir();
        await Tools.Files.write(path, text, false);
        return true;
    } catch (e) { return false; }
}

async function readHist() {
    await resolveBaseDir();
    _histErr = null;
    var got = await readRawE(histPath());
    var txt = String(got.text || "").trim();
    if (got.err) {
        // 文件不存在 = 合法空库；其它错误 = 真失败，记下来并上抛。
        if (READ_MISS_PAT.test(got.err)) return {};
        _histErr = String(got.err);
        throw new Error(got.err);
    }
    if (!txt) return {};
    try {
        var obj = JSON.parse(txt);
        if (!obj || typeof obj !== "object" || Array.isArray(obj)) return {};
        return obj;
    } catch (e) { return {}; }
}

// 记录一次健康快照。source = "worldbook" | "memory"
async function recordSnapshot(source, metrics) {
    await resolveBaseDir();
    var h = await readHist();
    var arr = h[source];
    if (!Array.isArray(arr)) arr = [];
    arr.push({
        ts: Date.now(),
        issues: Number(metrics && metrics.issues) || 0,
        total: Number(metrics && metrics.total) || 0,
        extra: (metrics && metrics.extra) || null
    });
    if (arr.length > MAX_HISTORY) arr = arr.slice(arr.length - MAX_HISTORY);
    h[source] = arr;
    return await writeRaw(histPath(), JSON.stringify(h, null, 2));
}

// 纯计算：从历史数组判定退化。抽出来方便单测，不碰 IO。
function detectFromArray(arr, opts) {
    var K = (opts && opts.K) || 5;
    var EPS = (opts && opts.epsilon) || 0.5;
    if (!Array.isArray(arr) || arr.length < 2) {
        return {
            level: "ok", signals: [],
            summary: "history insufficient (" + ((arr && arr.length) || 0) + ")",
            samples: (arr && arr.length) || 0
        };
    }
    var signals = [];

    // 1. 停滞：最近 K 次 issues 没有实质下降
    if (arr.length >= K) {
        var recent = arr.slice(arr.length - K);
        var first = Number(recent[0].issues) || 0;
        var last = Number(recent[recent.length - 1].issues) || 0;
        if (first > 0 && (first - last) < EPS) {
            signals.push({
                type: "stagnation",
                detail: "recent " + K + " audits issues did not drop (" + first + " -> " + last + ")"
            });
        }
    }

    // 2. 回归：当前比历史最好值差
    var best = Infinity;
    for (var i = 0; i < arr.length; i += 1) {
        var v = Number(arr[i].issues);
        if (!isNaN(v) && v < best) best = v;
    }
    var cur = Number(arr[arr.length - 1].issues) || 0;
    if (isFinite(best) && cur > best && (cur - best) >= 1) {
        signals.push({
            type: "regression",
            detail: "issues regressed from best " + best + " to " + cur + " (+" + (cur - best) + ")"
        });
    }

    // 3. 同质化：最近 K 次 issues 完全一致且 > 0
    if (arr.length >= K) {
        var seg = arr.slice(arr.length - K);
        var allSame = true;
        for (var j = 1; j < seg.length; j += 1) {
            if (Number(seg[j].issues) !== Number(seg[0].issues)) { allSame = false; break; }
        }
        if (allSame && Number(seg[0].issues) > 0) {
            signals.push({
                type: "homogeneous",
                detail: "recent " + K + " audits identical (" + seg[0].issues + "), spinning"
            });
        }
    }

    var level = "ok";
    if (signals.length > 0) {
        var order = { regression: 3, stagnation: 2, homogeneous: 1 };
        level = signals[0].type;
        for (var s = 1; s < signals.length; s += 1) {
            if ((order[signals[s].type] || 0) > (order[level] || 0)) level = signals[s].type;
        }
    }

    return {
        level: level,
        signals: signals,
        summary: signals.length === 0
            ? "ok (" + arr.length + " samples, current issues " + cur + ")"
            : signals.map(function (x) { return x.detail; }).join("; "),
        samples: arr.length,
        current: cur,
        best: isFinite(best) ? best : null
    };
}

async function detect(source, opts) {
    await resolveBaseDir();
    var h = await readHist();
    var key = String(source || "");
    if (!key || key === "all") return detectFromArray(collectAll(h).samples, opts);
    return detectFromArray(h[key], opts);
}

// 暴露原始样本数组，供统计基线（EMA / z-score）使用。
// 为什么不从 detect() 的返回里取：detect 只返回聚合值（samples/current/best），
// 拿不到逐次样本，算不了加权均值与标准差。
// 读盘错误快照。null/"" = 无错误。供 hook 预载判断「健康数据是否真的读到了」。
function histError() {
    return _histErr ? String(_histErr) : "";
}

// 合并全部 source 的样本。
// 为什么需要：存储层已共享 health_history.json，但消费层各读各的 key，
// 导致世界书的 5 处问题对记忆库完全不可见（反之亦然），
// 跨包整体恶化在任一包内都判不出来。
// 返回 { samples: 合并后按 ts 升序, bySource: {memory:N, worldbook:M} }。
function collectAll(histObj) {
    var h = histObj || {};
    var out = [];
    var bySource = {};
    var keys = Object.keys(h || {});
    for (var i = 0; i < keys.length; i += 1) {
        var k = keys[i];
        var arr = h[k];
        if (!Array.isArray(arr)) continue;
        bySource[k] = arr.length;
        for (var j = 0; j < arr.length; j += 1) {
            var x = arr[j];
            if (!x || typeof x !== "object") continue;
            out.push(x);
        }
    }
    out.sort(function (a, b) { return (Number(a.ts) || 0) - (Number(b.ts) || 0); });
    return { samples: out, bySource: bySource };
}

async function readSamples(source) {
    await resolveBaseDir();
    var h = await readHist();
    var key = String(source || "");
    // source="all" 或空：合并全部 source（跨包聚合）。
    if (!key || key === "all") return collectAll(h).samples;
    var arr = (h && h[key]) || [];
    return Array.isArray(arr) ? arr : [];
}

// 同步版：从已读出的历史对象里判定（供 hook 用，零 IO）
function detectFromHistory(histObj, source, opts) {
    var arr = (histObj && histObj[source]) || [];
    return detectFromArray(arr, opts);
}

async function stats(source) {
    await resolveBaseDir();
    var h = await readHist();
    var out = {};
    // source="all" = 聚合视图；空 = 逐 source 明细（保持原语义）。
    var sk = String(source || "");
    if (sk === "all") {
        var agg = collectAll(h);
        out.all = { samples: agg.samples.length, bySource: agg.bySource, detect: detectFromArray(agg.samples) };
        return out;
    }
    var keys = sk ? [sk] : Object.keys(h || {});
    for (var i = 0; i < keys.length; i += 1) {
        var k = keys[i];
        var arr = (h && h[k]) || [];
        out[k] = { samples: Array.isArray(arr) ? arr.length : 0, detect: detectFromArray(arr) };
    }
    return out;
}

// ===== 统计基线 + Z-score 异常检测 =====
// 参照 blackwell-systems/claudewatch:
//   internal/analyzer/anomaly.go 的 ComputeProjectBaseline + DetectAnomalies
//   internal/analyzer/regression.go 的 ComputeRegressionStatus
//
// 为什么需要：旧实现只做 current vs best 的二点比较
//   "issues regressed from best 0 to 1 (+1)"
// 二点比较三个问题：
//   1. 无统计意义：1 条 issues 在 20 条库里是噪声，在 200 条库里是信号
//   2. 无近期权重：三个月前的干净记录与昨天的同权
//   3. 无严重度分级：所有回退报同一句话
//
// 全部纯函数，零 IO。

// EMA 衰减加权：近期样本权重更高。
// decay=0.9 是 claudewatch 的取值。最老的样本权重 decay^(n-1)，最新的为 1。
function emaWeights(n, decay) {
    var d = Number(decay);
    if (!(d > 0 && d <= 1)) d = 1.0;
    var w = [];
    for (var i = 0; i < n; i += 1) {
        // 数组按"旧 -> 新"排列，所以最老的是 index 0，权重 decay^(n-1)
        w.push(Math.pow(d, n - 1 - i));
    }
    return w;
}

function weightedMean(vals, weights) {
    var sw = 0, sv = 0;
    for (var i = 0; i < vals.length; i += 1) {
        var w = Number(weights[i]) || 0;
        var v = Number(vals[i]);
        if (isNaN(v)) continue;
        sv += v * w;
        sw += w;
    }
    return sw > 0 ? sv / sw : 0;
}

function weightedStddev(vals, weights, mean) {
    var sw = 0, sv = 0;
    for (var i = 0; i < vals.length; i += 1) {
        var w = Number(weights[i]) || 0;
        var v = Number(vals[i]);
        if (isNaN(v)) continue;
        var d = v - mean;
        sv += d * d * w;
        sw += w;
    }
    if (sw <= 0) return 0;
    return Math.sqrt(sv / sw);
}

// 计算某个指标的 EMA 基线。
// samples: 样本数组（旧的在前）。key: 取哪个字段。
// 返回 { n, mean, stddev, weights } ；样本不足 3 个时 ok=false（照 claudewatch）。
function emaBaseline(samples, key, decay) {
    var arr = samples || [];
    var vals = [];
    for (var i = 0; i < arr.length; i += 1) {
        var s = arr[i];
        if (!s) continue;
        var v = Number(s[key]);
        if (isNaN(v)) continue;
        vals.push(v);
    }
    var n = vals.length;
    if (n < 3) {
        return { ok: false, n: n, mean: 0, stddev: 0, reason: "insufficient samples (need >= 3)" };
    }
    var w = emaWeights(n, decay);
    var mean = weightedMean(vals, w);
    var sd = weightedStddev(vals, w, mean);
    return { ok: true, n: n, mean: Math.round(mean * 1000) / 1000, stddev: Math.round(sd * 1000) / 1000, weights: w };
}

// 标准分。stddev 为 0 时返回 0（无波动 = 无从判断偏离）。
function zScore(value, mean, stddev) {
    var sd = Number(stddev);
    if (!(sd > 0)) return 0;
    return (Number(value) - Number(mean)) / sd;
}

// 异常判定。threshold 默认 2.0（claudewatch 取值）。
// |z| > threshold = warning；|z| >= 3 = critical。
// 只对"变坏"方向报警（higherIsBetter 决定哪边算坏）。
function detectAnomaly(value, baseline, opts) {
    var o = opts || {};
    var thr = Number(o.threshold);
    if (!(thr > 0)) thr = 2.0;
    var higherIsBetter = o.higherIsBetter === true;
    if (!baseline || !baseline.ok) {
        return { anomaly: false, severity: "none", z: 0, reason: "no baseline" };
    }
    var z = zScore(value, baseline.mean, baseline.stddev);
    var absZ = Math.abs(z);
    if (absZ <= thr) {
        return { anomaly: false, severity: "normal", z: Math.round(z * 1000) / 1000, threshold: thr };
    }
    // 方向判断：higherIsBetter=true 时低值是坏；否则高值是坏
    var worse = higherIsBetter ? (z < 0) : (z > 0);
    if (!worse) {
        return { anomaly: false, severity: "normal", z: Math.round(z * 1000) / 1000, threshold: thr, note: "偏离但在好的一侧" };
    }
    var sev = absZ >= 3 ? "critical" : "warning";
    return {
        anomaly: true, severity: sev, z: Math.round(z * 1000) / 1000, threshold: thr,
        value: Number(value), mean: baseline.mean, stddev: baseline.stddev,
        reason: (higherIsBetter ? "低于" : "高于") + "基线 " + absZ.toFixed(1) + " 个标准差"
    };
}

// 回归判定（照 claudewatch：current > threshold * baseline）。
// threshold 默认 1.5。
function detectRegression(current, baselineValue, opts) {
    var o = opts || {};
    var thr = Number(o.threshold);
    if (!(thr > 1)) thr = 1.5;
    var cur = Number(current);
    var base = Number(baselineValue);
    if (!(base > 0)) {
        return { regression: false, current: cur, baseline: base, threshold: thr, reason: "baseline is 0, cannot judge" };
    }
    var regressed = cur > thr * base;
    return {
        regression: regressed,
        current: cur, baseline: base, threshold: thr,
        ratio: Math.round((cur / base) * 100) / 100,
        reason: regressed
            ? ("回退：" + cur + " 超过基线 " + base + " 的 " + thr + " 倍（实际 " + (Math.round((cur / base) * 100) / 100) + "x）")
            : ("正常：" + cur + " 在基线 " + base + " 的 " + thr + " 倍以内")
    };
}

module.exports = {
    emaBaseline: emaBaseline,
    zScore: zScore,
    detectAnomaly: detectAnomaly,
    detectRegression: detectRegression,
    emaWeights: emaWeights,
    weightedMean: weightedMean,
    weightedStddev: weightedStddev,
    recordSnapshot: recordSnapshot,
    detect: detect,
    readSamples: readSamples,
    collectAll: collectAll,
    detectFromArray: detectFromArray,
    detectFromHistory: detectFromHistory,
    stats: stats,
    readHist: readHist,
    histError: histError,
    baseDir: baseDir,
    resolveBaseDir: resolveBaseDir,
    SHARED_POINTER: SHARED_POINTER,
    histPath: histPath
};
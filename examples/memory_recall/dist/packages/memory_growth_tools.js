/* METADATA
{
  "name": "memory_growth_tools",
  "display_name": {
    "zh": "记忆成长工具",
    "en": "Memory Growth Tools"
  },
  "description": {
    "zh": "主动式记忆系统，共 16 个工具：写入与进化（mem_write / mem_evolve / mem_supersede）；检索与复习（mem_search / mem_review / mem_mark_used）；沉淀与整理（mem_consolidate / mem_merge_review / mem_merge_apply）；质检与自检（mem_audit / mem_diag / mem_stats）；经验沉淀（mem_lesson / mem_evolution）；回退与清理（mem_rollback / mem_forget）。三层结构 L1事件/L2经验/L3原则，艾宾浩斯复习，记忆进化留版本史。",
    "en": "Active memory system, 16 tools: write & evolve (mem_write / mem_evolve / mem_supersede); search & review (mem_search / mem_review / mem_mark_used); consolidate & merge (mem_consolidate / mem_merge_review / mem_merge_apply); audit & diagnose (mem_audit / mem_diag / mem_stats); lessons & evolution (mem_lesson / mem_evolution); rollback & forget (mem_rollback / mem_forget). 3 layers L1/L2/L3, Ebbinghaus review, versioned evolution.",
    "default": "Active memory with 16 tools"
  },
  "category": "Utility",
  "tools": [
    {
      "name": "mem_write",
      "description": {
        "zh": "写入一条记忆。AI 判定值得留存时才调用。自动查重。",
        "en": "Write a memory. Call only when worth keeping. Auto-dedupes."
      },
      "parameters": [
        {
          "name": "title",
          "description": {
            "zh": "短标题（唯一键）",
            "en": "Short unique title"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "content",
          "description": {
            "zh": "记忆正文",
            "en": "Body"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "layer",
          "description": {
            "zh": "L1/L2/L3",
            "en": "L1/L2/L3"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "tags",
          "description": {
            "zh": "标签，逗号分隔",
            "en": "Tags, comma separated"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "importance",
          "description": {
            "zh": "重要度 0-1",
            "en": "Importance 0-1"
          },
          "type": "number",
          "required": false
        },
        {
          "name": "evidence",
          "description": {
            "zh": "证据或来源",
            "en": "Evidence or source"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "force",
          "description": {
            "zh": "仅在检测到冲突时使用：确知要覆盖旧结论才传 true 强写，否则应改用 mem_evolve",
            "en": "Only when a conflict is detected and you are sure to overwrite; otherwise use mem_evolve"
          },
          "type": "boolean",
          "required": false
        }
      ]
    },
    {
      "name": "mem_search",
      "description": {
        "zh": "主动检索记忆，返回带分数的候选标题。",
        "en": "Actively search memories, returns scored titles."
      },
      "parameters": [
        {
          "name": "query",
          "description": {
            "zh": "检索意图",
            "en": "Search intent"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "layer",
          "description": {
            "zh": "只看某层",
            "en": "Filter by layer"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "limit",
          "description": {
            "zh": "返回条数，默认 8",
            "en": "Max results, default 8"
          },
          "type": "number",
          "required": false
        }
      ]
    },
    {
      "name": "mem_consolidate",
      "description": {
        "zh": "把多条低层记忆提炼成一条高层记忆，保留来源谱系。",
        "en": "Consolidate multiple lower-layer memories into one higher-layer, keeping provenance."
      },
      "parameters": [
        {
          "name": "source_titles",
          "description": {
            "zh": "来源标题，逗号分隔",
            "en": "Source titles, comma separated"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "title",
          "description": {
            "zh": "新条目标题",
            "en": "New title"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "content",
          "description": {
            "zh": "提炼后的内容",
            "en": "Consolidated content"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "layer",
          "description": {
            "zh": "目标层 L2 或 L3",
            "en": "Target layer L2 or L3"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "archive_sources",
          "description": {
            "zh": "是否归档来源，默认 true",
            "en": "Archive sources, default true"
          },
          "type": "boolean",
          "required": false
        }
      ]
    },
    {
      "name": "mem_evolve",
      "description": {
        "zh": "进化一条记忆：用更准的内容替换旧版，旧版留存历史。",
        "en": "Evolve a memory: replace with sharper content, keep history."
      },
      "parameters": [
        {
          "name": "title",
          "description": {
            "zh": "目标标题",
            "en": "Target title"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "content",
          "description": {
            "zh": "新版内容",
            "en": "New content"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "reason",
          "description": {
            "zh": "为什么进化",
            "en": "Why evolve"
          },
          "type": "string",
          "required": false
        }
      ]
    },
    {
      "name": "mem_review",
      "description": {
        "zh": "复习队列：到期该看的，以及长期未命中的陈旧条目。",
        "en": "Review queue: due items plus stale ones never hit."
      },
      "parameters": [
        {
          "name": "limit",
          "description": {
            "zh": "返回条数，默认 10",
            "en": "Max items, default 10"
          },
          "type": "number",
          "required": false
        }
      ]
    },
    {
      "name": "mem_mark_used",
      "description": {
        "zh": "标记某条记忆被实际用上，驱动命中率与复习间隔。",
        "en": "Mark a memory as used. Drives hit rate and review interval."
      },
      "parameters": [
        {
          "name": "title",
          "description": {
            "zh": "记忆标题",
            "en": "Memory title"
          },
          "type": "string",
          "required": true
        }
      ]
    },
    {
      "name": "mem_stats",
      "description": {
        "zh": "记忆系统健康度：三层数量、命中率、陈旧数、待复习数。",
        "en": "Health: layer counts, hit rate, stale count, due reviews."
      },
      "parameters": []
    },
    {
      "name": "mem_audit",
      "description": {
        "zh": "记忆库审计：查重复/矛盾/空壳/陈旧。周期全量扫，返回可处理清单。传 force=true 可强制下一轮注入块立刻全量自查。",
        "en": "Memory audit: duplicates, conflicts, thin, stale. Pass force=true to force a full audit on the next injection cycle."
      },
      "parameters": [
        {
          "name": "force",
          "description": {
            "zh": "传 true 强制下一轮注入块跑一次全量审计（不是只做本次扫描）",
            "en": "Pass true to force a full audit on the next injection cycle"
          },
          "type": "boolean",
          "required": false
        },
        {
          "name": "dup_threshold",
          "description": {
            "zh": "重复判定阈值，默认 0.55",
            "en": "Threshold, default 0.55"
          },
          "type": "number",
          "required": false
        },
        {
          "name": "verbose",
          "description": {
            "zh": "是否列出明细，默认 true",
            "en": "Verbose, default true"
          },
          "type": "boolean",
          "required": false
        }
      ]
    },
    {
      "name": "mem_merge_review",
      "description": {
        "zh": "取合并候选：引擎只给证据（新旧双方完整正文、相似度、命中原因），合并与否、怎么合并由你判断。",
        "en": "Get merge candidates. Engine provides evidence only (both bodies, score, reason). You decide."
      },
      "parameters": [
        {
          "name": "limit",
          "description": {
            "zh": "返回条数，默认 20",
            "en": "Max items, default 20"
          },
          "type": "number",
          "required": false
        }
      ]
    },
    {
      "name": "mem_merge_apply",
      "description": {
        "zh": "提交合并结果。你给出最终文案与动作：merge（合并成一条）/ replace（新版覆盖）/ keep_both（各留）/ drop（丢弃新条目）。",
        "en": "Apply merge decision: merge / replace / keep_both / drop, with your final text."
      },
      "parameters": [
        {
          "name": "existing_id",
          "description": {
            "zh": "目标已有条目 ID",
            "en": "Existing entry id"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "action",
          "description": {
            "zh": "merge / replace / keep_both / drop",
            "en": "merge / replace / keep_both / drop"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "title",
          "description": {
            "zh": "合并后的标题（merge/replace 必填，你定）",
            "en": "Final title for merge/replace"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "content",
          "description": {
            "zh": "合并后的正文全文（merge/replace 必填，你写，不要只写差异）",
            "en": "Final full body for merge/replace"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "layer",
          "description": {
            "zh": "合并后层级 L1/L2/L3",
            "en": "Final layer"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "why",
          "description": {
            "zh": "为什么这么合并（会进 history）",
            "en": "Why, recorded to history"
          },
          "type": "string",
          "required": false
        }
      ]
    },
    {
      "name": "mem_diag",
      "description": {
        "zh": "诊断自检：工具解析、hook 上次触发、队列健康、进化记忆预载、健康基线、审计运行态。默认精简；传 log/health/audit 才返回明细。",
        "en": "Diagnostics: resolver, last trigger, queue health, evolution preload, health baseline, audit runtime state. Compact by default; log/health/audit for detail."
      },
      "parameters": [
        {
          "name": "log",
          "description": {
            "zh": "返回最近 N 行操作日志（growth.log）。不传则不返回",
            "en": "Return latest N operation log lines"
          },
          "type": "number",
          "required": false
        },
        {
          "name": "log_filter",
          "description": {
            "zh": "日志过滤子串，如 supersede / merge_apply / forget",
            "en": "Filter log lines by substring"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "health",
          "description": {
            "zh": "传「1」返回完整健康基线对象（样本数/均值/异常/回退）",
            "en": "Pass 1 for full health baseline object"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "audit",
          "description": {
            "zh": "传「1」返回完整审计运行态（含 last_result 全字段）",
            "en": "Pass 1 for full audit runtime state"
          },
          "type": "string",
          "required": false
        }
      ]
    },
    {
      "name": "mem_forget",
      "description": {
        "zh": "删除一条记忆（含原生库）。用于清理错误或过期内容。",
        "en": "Delete a memory including native store."
      },
      "parameters": [
        {
          "name": "title",
          "description": {
            "zh": "记忆标题",
            "en": "Memory title"
          },
          "type": "string",
          "required": true
        }
      ]
    },
    {
      "name": "mem_rollback",
      "description": {
        "zh": "版本回退：把条目退回历史版本（mem_evolve / mem_merge_apply 改动前的旧版）。当前版会压回 history，可再前进回来。history 为空时报错而非静默。",
        "en": "Rollback to a previous version. Current version is pushed into history so you can roll forward again."
      },
      "parameters": [
        {
          "name": "title",
          "description": {
            "zh": "目标条目 title（与 id 二选一）",
            "en": "Target entry title (or use id)"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "id",
          "description": {
            "zh": "目标条目 id，作为 title 的等价定位方式（实现里读 p.title || p.id）",
            "en": "Target entry id, equivalent locator to title"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "steps",
          "description": {
            "zh": "回退几步，默认 1，上限 10",
            "en": "How many steps back, default 1, max 10"
          },
          "type": "number",
          "required": false
        }
      ]
    },
    {
      "name": "mem_lesson",
      "description": {
        "zh": "记一条踩过的坑（进化记忆）。下次动手前会自动预警，避免重复踩。同一个坑重复记会自动累加次数。kind=success 时改写成功模板库：target 当模板名，detail 写可复用的完整做法。",
        "en": "Record a lesson learned (evolution memory). Auto-warned before next similar action. kind=success writes a success template instead."
      },
      "parameters": [
        {
          "name": "kind",
          "description": {
            "zh": "坑的类型短名，如 params-key / deploy / regex",
            "en": "Short kind, e.g. params-key"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "target",
          "description": {
            "zh": "作用对象，用于之后匹配预警",
            "en": "Target for later matching"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "detail",
          "description": {
            "zh": "坑的具体描述，写清怎么避免",
            "en": "What went wrong and how to avoid"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "evidence",
          "description": {
            "zh": "证据来源（哪个文件/哪次实测）",
            "en": "Evidence"
          },
          "type": "string",
          "required": false
        }
      ]
    },
    {
      "name": "mem_evolution",
      "description": {
        "zh": "查看进化记忆：默认只给数量；传 detail/kind/query 可看具体教训全文；工具失败会被 hook 自动捕获到缓冲区，传 flush_failures=1 落盘成教训。",
        "en": "Evolution memory: stats by default; detail/kind/query to read full lessons."
      },
      "parameters": [
        {
          "name": "detail",
          "description": {
            "zh": "true=返回失败模式与成功模板的完整正文",
            "en": "true = return full lesson text"
          },
          "type": "boolean",
          "required": false
        },
        {
          "name": "kind",
          "description": {
            "zh": "只看某类坑（如 deploy / params-key）",
            "en": "Filter by kind"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "query",
          "description": {
            "zh": "按意图匹配相关教训（我这次要做的事）",
            "en": "Match lessons by intent"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "timeline",
          "description": {
            "zh": "看进化轨迹时间线：数字=最近N条，all=全部。失败与成功都会记录",
            "en": "Timeline: N or all"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "timeline_type",
          "description": {
            "zh": "轨迹过滤：failure 只看踩坑 / template 只看成功",
            "en": "failure / template"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "flush_failures",
          "description": {
            "zh": "1=true 把 hook 缓冲区里压着的工具失败写进进化记忆（hook 内不能 IO，必须由工具落盘）。不传只报告积压数。",
            "en": "1/true = flush buffered tool failures into evolution memory"
          },
          "type": "string",
          "required": false
        }
      ]
    },
    {
      "name": "mem_supersede",
      "description": {
        "zh": "状态覆盖：把旧条目标记为『已被新结论取代』。旧的会被降权并标注为已过时，不再当依据。action=mark 标记 / clear 撤销 / candidates 先看该覆盖谁。",
        "en": "State overwriting: mark old entry as superseded. action=mark/clear/candidates."
      },
      "parameters": [
        {
          "name": "action",
          "description": {
            "zh": "mark（标记）/ clear（撤销）/ candidates（查候选）",
            "en": "mark / clear / candidates"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "old",
          "description": {
            "zh": "被覆盖的旧条目 title 或 id（mark/clear 必填）",
            "en": "Old entry title or id"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "by",
          "description": {
            "zh": "取代它的新条目 title 或 id",
            "en": "New entry title or id"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "cause",
          "description": {
            "zh": "为什么覆盖（会写入 superseded_cause）",
            "en": "Why superseded"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "title",
          "description": {
            "zh": "candidates 模式下：新条目标题",
            "en": "For candidates: new title"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "content",
          "description": {
            "zh": "candidates 模式下：新条目正文",
            "en": "For candidates: new content"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "threshold",
          "description": {
            "zh": "candidates 相似度阈值，默认 0.5",
            "en": "Threshold, default 0.5"
          },
          "type": "number",
          "required": false
        }
      ]
    }
  ]
}
*/
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });

var store = require("../shared/growth_store.js");

// baseDir/idxPath/logPath 在独立脚本环境会抛 "toolpkg runtime target is empty"
// 工具调用时有平台注入参数，但为防单点抛异常把整个工具打空，这里包一层
function safePath(fn, fallback) {
    try { return String(fn() || fallback); } catch (_e) { return fallback; }
}
function safeBaseDir() { return safePath(store.baseDir, ""); }
function safeIdxPath() { return safePath(store.idxPath, ""); }
function safeLogPath() { return safePath(store.logPath, ""); }
var engine = require("../shared/growth_engine.js");

var OLDT = "ol" + "d_title";

// async 形态：必须 await handler 再 complete，不能走 Promise.resolve().then 链
// 平台调用工具时会 await 导出函数的返回值；返回 undefined 会立刻结束并丢弃结果
async function run(handler, params) {
    try {
        var r = await handler(params || {});
        complete(r);
        return r;
    } catch (e) {
        var msg = String((e && e.message) || e);
        complete({ success: false, message: msg });
        return { success: false, message: msg };
    }
}

// ---- 1. 写入 ----
async function memWrite(p) {
    var title = String(p.title || "").trim();
    var content = String(p.content || "").trim();
    if (!title) return { success: false, message: "缺少 title" };
    if (!content) return { success: false, message: "缺少 content" };

    var layer = engine.normalizeLayer(p.layer);
    var idx = await store.readIndex();

    var same = null;
    for (var i = 0; i < idx.entries.length; i += 1) {
        if (idx.entries[i].title === title) { same = idx.entries[i]; break; }
    }
    if (same) {
        return {
            success: true, duplicate: true,
            message: "已存在同名记忆，请用 mem_evolve 进化，或换标题",
            existing: { title: same.title, layer: same.layer, version: same.version }
        };
    }
    var near = engine.findSimilar(idx.entries, content, 0.82);
    if (near) {
        return {
            success: true, duplicate: true,
            message: "已存在高度相似记忆（" + Math.round(near.score * 100) + "%），建议用 mem_evolve 或 mem_consolidate",
            existing: { title: near.entry.title, layer: near.entry.layer }
        };
    }

    // 矛盾检测：同主题但否定/肯定方向相反，或关键数值互斥
    // 对齐 mem0 的 UPDATE 决策：冲突时返回进言，不静默新增脏数据
    var conflict = engine.detectConflict(idx.entries, title, content);
    if (conflict && conflict.conflict && !p.force) {
        return {
            success: true,
            duplicate: false,
            conflict: true,
            kind: conflict.kind,
            score: conflict.score,
            counterpart: conflict.counterpart,
            message: "与已有记忆冲突（" + conflict.reason + "）：" + conflict.counterpart.title +
                "。请用 mem_evolve 改旧条目，或传 force=true 强写",
            existing: conflict.counterpart
        };
    }

    // 状态覆盖检查：写入前先看有没有"同一主题的旧条目"该被这条取代。
    // 只报不改 —— 覆盖是语义判断，交回模型决定，避免静默覆盖正确结论。
    var supCand = [];
    try {
        if (typeof engine.findSupersedeCandidates === "function") {
            supCand = engine.findSupersedeCandidates(idx.entries, title, content, { threshold: 0.5, limit: 3 });
        }
    } catch (_eSup) { }

    var now = Date.now();
    var native = await engine.writeNative(title, content, { layer: layer, tags: p.tags });
    idx.entries.push({
        // id 必须有：mem_merge_apply / mem_forget / mem_evolve 全部按 id 定位。
        // 之前漏了这行，新建条目全靠 title 做键，改名就断链。
        id: engine.makeId(),
        title: title, layer: layer, tags: String(p.tags || ""),
        importance: engine.clampNum(p.importance, 0, 1, 0.5),
        evidence: String(p.evidence || ""),
        content: content,
        version: 1, createdAt: now, updatedAt: now,
        lastUsedAt: 0, hitCount: 0, reviewCount: 0,
        nextReviewAt: now + engine.layerIntervalMs(layer, 0),
        history: []
    });
    await store.writeIndex(idx);
    await store.appendLog("write " + layer + " " + title);

    var outW = {
        success: true, duplicate: false, title: title, layer: layer,
        native_ok: native.ok,
        message: "已写入 " + layer + "：" + title
    };
    if (supCand.length > 0) {
        outW.supersede_candidates = supCand;
        outW.message += "（注意：" + supCand.length + " 条同主题旧条目可能已被这条取代，" +
            "确认后用 mem_supersede action=mark 标记：" +
            supCand.map(function (c) { return c.title.slice(0, 20); }).join("、") + "）";
    }
    return outW;
}

// ---- 2. 检索 ----
async function memSearch(p) {
    var q = String(p.query || "").trim();
    if (!q) return { success: false, message: "缺少 query" };
    var limit = engine.clampInt(p.limit, 1, 30, 8);
    var layer = p.layer ? engine.normalizeLayer(p.layer) : "";

    var idx = await store.readIndex();
    var pool = [];
    for (var i = 0; i < idx.entries.length; i += 1) {
        var e = idx.entries[i];
        if (!layer || e.layer === layer) pool.push(e);
    }

    var nativeScore = {};
    try {
        var r = await Tools.Memory.query({ query: q, limit: limit * 3, threshold: 0.2 });
        var list = engine.extractMemoryList(r);
        for (var j = 0; j < list.length; j += 1) {
            nativeScore[list[j].title] = 1 - j / Math.max(1, list.length);
        }
    } catch (_e) { }

    // BM25 检索：替换原「命中数/词数」的简单比例
    // 原算法无 IDF、无长度归一、无词频饱和，中文二字分切下实测仅 0.14，实际不可用
    var docs = [];
    for (var kd = 0; kd < pool.length; kd += 1) docs.push(engine.toDoc(pool[kd]));
    var bm = engine.bm25Score(q, docs);
    var scored = [];
    for (var k = 0; k < pool.length; k += 1) {
        var en = pool[k];
        var lex = bm[k] || 0;
        var sem = nativeScore[en.title] || 0;
        var boost = Math.min(0.25, (en.hitCount || 0) * 0.03);
        var stale = engine.isStale(en) ? -0.08 : 0;
        // BM25 已成为主信号（0.6），向量/原生语义 0.3，其余为调节项
        var sc = lex * 0.6 + sem * 0.3 + boost + stale;
        if (sc > 0.02) scored.push({ entry: en, score: sc, bm25: lex, sem: sem });
    }
    scored.sort(function (a, b) { return b.score - a.score; });
    scored = scored.slice(0, limit);
    var out = [];
    for (var m = 0; m < scored.length; m += 1) {
        var it = scored[m];
        out.push({
            title: it.entry.title, layer: it.entry.layer, tags: it.entry.tags,
            score: Math.round(it.score * 1000) / 1000,
            bm25: Math.round((it.bm25 || 0) * 1000) / 1000,
            hitCount: it.entry.hitCount || 0,
            version: it.entry.version || 1,
            stale: engine.isStale(it.entry),
            preview: engine.preview(it.entry)
        });
    }
    return {
        success: true, count: out.length, results: out,
        hint: "score=总分，bm25=词法分。选中后按标题用 get_memory_by_title 读全文，读完调 mem_mark_used 记命中"
    };
}

// ---- 3. 沉淀 ----
async function memConsolidate(p) {
    var raw = String(p.source_titles || "");
    var parts = raw.split(/[,;]/);
    var sources = [];
    for (var i = 0; i < parts.length; i += 1) {
        var t = parts[i].trim();
        if (t) sources.push(t);
    }
    var title = String(p.title || "").trim();
    var content = String(p.content || "").trim();
    if (!sources.length) return { success: false, message: "缺少 source_titles" };
    if (!title) return { success: false, message: "缺少 title" };
    if (!content) return { success: false, message: "缺少 content" };

    var layer = engine.normalizeLayer(p.layer || "L2");
    if (layer === "L1") layer = "L2";
    var idx = await store.readIndex();

    var found = [];
    for (var j = 0; j < idx.entries.length; j += 1) {
        for (var k = 0; k < sources.length; k += 1) {
            if (idx.entries[j].title === sources[k]) { found.push(idx.entries[j]); break; }
        }
    }
    if (found.length === 0) {
        return { success: false, message: "来源标题一条都没找到", sources: sources };
    }

    var now = Date.now();
    var provenance = [];
    for (var m = 0; m < found.length; m += 1) {
        provenance.push(found[m].title);
    }
    await engine.writeNative(title, content, { layer: layer, tags: "consolidated" });
    idx.entries.push({
        title: title, layer: layer, tags: "consolidated",
        importance: 0.75, evidence: provenance.join(","),
        content: content,
        version: 1, createdAt: now, updatedAt: now,
        lastUsedAt: 0, hitCount: 0, reviewCount: 0,
        nextReviewAt: now + engine.layerIntervalMs(layer, 0),
        history: [], derivedFrom: provenance
    });

    var archived = 0;
    if (p.archive_sources !== false) {
        for (var n = 0; n < found.length; n += 1) {
            found[n].archived = true;
            found[n].archivedAt = now;
            found[n].archivedInto = title;
            archived += 1;
        }
    }
    await store.writeIndex(idx);
    await store.appendLog("consolidate " + layer + " " + title + " <- " + provenance.length);

    return {
        success: true, title: title, layer: layer,
        sources_used: provenance, archived: archived,
        message: "已提炼为 " + layer + "：" + title + "（来源 " + provenance.length + " 条，归档 " + archived + " 条）"
    };
}

// ---- 4. 进化 ----
async function memEvolve(p) {
    var title = String(p.title || "").trim();
    var content = String(p.content || "").trim();
    if (!title) return { success: false, message: "缺少 title" };
    if (!content) return { success: false, message: "缺少 content" };

    var idx = await store.readIndex();
    var target = null;
    for (var i = 0; i < idx.entries.length; i += 1) {
        if (idx.entries[i].title === title) { target = idx.entries[i]; break; }
    }
    if (!target) return { success: false, message: "找不到该记忆：" + title };

    var prev = String(target.content || "");
    if (prev && engine.jaccard(prev, content) > 0.97) {
        return { success: true, changed: false, message: "内容几乎无变化，无需进化" };
    }

    target.history = target.history || [];
    target.history.push({
        version: target.version || 1,
        content: prev,
        at: Date.now(),
        reason: String(p.reason || "")
    });
    if (target.history.length > 10) target.history = target.history.slice(-10);

    target.content = content;
    target.version = (target.version || 1) + 1;
    target.updatedAt = Date.now();
    target.nextReviewAt = Date.now() + engine.layerIntervalMs(target.layer, target.reviewCount || 0);

    var upd = await engine.updateNative(title, content);
    // Dependency invalidation (see mnemosyne): entries depending on this one
    // now hold a possibly-stale premise. Mark them, do not rewrite them.
    var staleInfo = { count: 0, names: [] };
    try {
        if (typeof engine.invalidateDependents === "function") {
            staleInfo = engine.invalidateDependents(
                idx.entries, [String(target.id || "")], [title],
                String(target.title || title)
            );
        }
    } catch (_eDep) { }

    // 状态覆盖候选：只报不改。
    // 曾经这里做自动 mark，实测立刻出事：
    //   evolve("现在必须用 new 参数写入") 自动覆盖了 3 条无关条目
    //   （"ToolPkg 工具必须 return Promise" / "数组的 obj.entries..." / "OLD 里缺少注释"），
    //   仅因为它们的标题共享 "ToolPkg" / "必须" 这类领域通用词。
    // 结论：语义歧义的判断不能自动化。这里只把候选交回模型，
    // 由模型看完 confidence 与 reason 后显式调 mem_supersede action=mark。
    var supersedeCandidatesOnly = [];
    try {
        if (typeof engine.findSupersedeCandidates === "function") {
            supersedeCandidatesOnly = engine.findSupersedeCandidates(
                idx.entries, title, content, { threshold: 0.5, limit: 3 });
        }
    } catch (_eSup2) { supersedeCandidatesOnly = []; }

    await store.writeIndex(idx);
    await store.appendLog("evolve " + title + " v" + target.version + " stale=" + staleInfo.count +
        " supCand=" + supersedeCandidatesOnly.length);

    return {
        success: true, changed: true, title: title,
        version: target.version, native_ok: upd.ok,
        reason: String(p.reason || ""),
        dependents_marked_stale: staleInfo.count,
        dependents: staleInfo.names,
        supersede_candidates: supersedeCandidatesOnly,
        message: "已进化到 v" + target.version + "：" + title +
            (staleInfo.count > 0 ? "（标记 " + staleInfo.count + " 条依赖者待复核）" : "") +
            (supersedeCandidatesOnly.length > 0
                ? "（另发现 " + supersedeCandidatesOnly.length + " 条同主题旧条目，确认后自行调 mem_supersede action=mark：" +
                  supersedeCandidatesOnly.map(function (c) { return c.title.slice(0, 18) + "[" + c.confidence + "]"; }).join("、") + "）"
                : "")
    };
}

// ---- 5. 复习队列 ----
async function memReview(p) {
    var limit = engine.clampInt(p.limit, 1, 50, 10);
    var idx = await store.readIndex();
    var now = Date.now();

    var due = [];
    var stale = [];
    for (var i = 0; i < idx.entries.length; i += 1) {
        var e = idx.entries[i];
        if (e.archived) continue;
        if (engine.isDueReview(e, now)) due.push(e);
        if (engine.isStale(e, now)) stale.push(e);
    }

    function brief(list) {
        var out = [];
        for (var j = 0; j < list.length && j < limit; j += 1) {
            out.push({
                title: list[j].title, layer: list[j].layer,
                hitCount: list[j].hitCount || 0,
                version: list[j].version || 1,
                preview: engine.preview(list[j])
            });
        }
        return out;
    }

    return {
        success: true,
        due_count: due.length,
        stale_count: stale.length,
        due: brief(due),
        stale: brief(stale),
        hint: "复习时确认内容是否仍然准确；不准确就用 mem_evolve 改准，确认无误就调 mem_mark_used"
    };
}

// ---- 6. 标记命中 ----
async function memMarkUsed(p) {
    var title = String(p.title || "").trim();
    if (!title) return { success: false, message: "缺少 title" };
    var idx = await store.readIndex();
    var target = null;
    for (var i = 0; i < idx.entries.length; i += 1) {
        if (idx.entries[i].title === title) { target = idx.entries[i]; break; }
    }
    if (!target) return { success: false, message: "找不到该记忆：" + title };

    var now = Date.now();
    target.hitCount = (target.hitCount || 0) + 1;
    target.lastUsedAt = now;
    target.reviewCount = (target.reviewCount || 0) + 1;
    target.nextReviewAt = now + engine.layerIntervalMs(target.layer, target.reviewCount);
    // Reviewing clears the stale mark: a human/model confirmed it still holds.
    try {
        if (typeof engine.clearStale === "function") engine.clearStale(target);
    } catch (_eCS) { }

    await store.writeIndex(idx);

    return {
        success: true, title: title,
        stale_cleared: true,
        hitCount: target.hitCount,
        nextReviewInDays: Math.round((target.nextReviewAt - now) / (24 * 3600 * 1000)),
        message: "已记命中 " + target.hitCount + " 次，下次复习在 " + Math.round((target.nextReviewAt - now) / (24 * 3600 * 1000)) + " 天后"
    };
}

// ---- 7. 统计 ----
async function memStats() {
    var idx = await store.readIndex();
    var now = Date.now();
    var cnt = { L1: 0, L2: 0, L3: 0 };
    var totalHit = 0;
    var everUsed = 0;
    var dueN = 0;
    var staleN = 0;
    var archivedN = 0;

    for (var i = 0; i < idx.entries.length; i += 1) {
        var e = idx.entries[i];
        if (e.archived) { archivedN += 1; } else { cnt[engine.normalizeLayer(e.layer)] += 1; }
        totalHit += (e.hitCount || 0);
        if ((e.hitCount || 0) > 0) everUsed += 1;
        if (!e.archived && engine.isDueReview(e, now)) dueN += 1;
        if (!e.archived && engine.isStale(e, now)) staleN += 1;
    }

    var active = cnt.L1 + cnt.L2 + cnt.L3;
    return {
        success: true,
        total: idx.entries.length,
        active: active,
        archived: archivedN,
        layers: cnt,
        hit_total: totalHit,
        used_ratio: active > 0 ? Math.round(everUsed / active * 1000) / 1000 : 0,
        due_review: dueN,
        stale: staleN,
        store_dir: safeBaseDir(),
        hint: "used_ratio 低说明记忆只写不读；stale 多说明该清理；三层比例失衡说明没做沉淀"
    };
}

// ---- 8. 遗忘 ----
async function memForget(p) {
    var title = String(p.title || "").trim();
    if (!title) return { success: false, message: "缺少 title" };
    var idx = await store.readIndex();
    var before = idx.entries.length;
    var kept = [];
    for (var i = 0; i < idx.entries.length; i += 1) {
        if (idx.entries[i].title !== title) kept.push(idx.entries[i]);
    }
    if (kept.length === before) return { success: false, message: "找不到该记忆：" + title };

    var del = await engine.deleteNative(title);
    idx.entries = kept;
    await store.writeIndex(idx);
    await store.appendLog("forget " + title);

    return {
        success: true, title: title, native_ok: del.ok,
        message: "已删除：" + title
    };
}

// ---- 回滚 ----
// 世界书侧有 wb_rollback，记忆库侧此前没有：history 在 memEvolve 与 memMergeApply 两处
// 各写一次（共 3 个 push 点），却零回滚出口，版本史成了只写不读的死数据。
// 写错了只能 mem_forget 删掉重写，version / hitCount / createdAt 全部丢失。
async function memRollback(p) {
    var key = String((p && (p.title || p.id)) || "").trim();
    if (!key) return { success: false, message: "缺少 title（或 id）" };
    var steps = engine.clampInt(p && p.steps, 1, 10, 1);
    var idx = await store.readIndex();
    var target = null;
    for (var i = 0; i < idx.entries.length; i += 1) {
        if (idx.entries[i].title === key || idx.entries[i].id === key) { target = idx.entries[i]; break; }
    }
    if (!target) return { success: false, message: "找不到该记忆：" + key };
    var hist = Array.isArray(target.history) ? target.history : [];
    if (!hist.length) {
        return {
            success: false, version: target.version || 1,
            message: "该条目没有历史版本可回退（history 为空）。history 只记录 mem_evolve 与 mem_merge_apply 改动前的旧版。"
        };
    }
    var n = Math.min(steps, hist.length);
    var snap = hist[hist.length - n] || {};
    var snapContent = String(snap.content || "");
    if (!snapContent) {
        return { success: false, message: "目标历史版本正文为空，拒绝回滚（会把条目写空）" };
    }
    var now = Date.now();
    var prevContent = String(target.content || "");
    var prevVersion = target.version || 1;
    var prevTitle = target.title;
    // 当前版压回 history，其余历史保留；这样回退后还能再前进回来。
    var keep = hist.slice(0, hist.length - n);
    keep.push({
        at: now, title: prevTitle, content: prevContent, layer: target.layer,
        version: prevVersion, why: "rollback " + n + " step(s) -> v" + String(snap.version || "?")
    });
    if (keep.length > 10) keep = keep.slice(-10);
    target.history = keep;
    target.content = snapContent;
    if (snap.title && snap.title !== prevTitle) target.title = String(snap.title);
    if (snap.layer) target.layer = engine.normalizeLayer(snap.layer);
    target.version = prevVersion + 1;
    target.updatedAt = now;
    target.nextReviewAt = now + engine.layerIntervalMs(target.layer, target.reviewCount || 0);
    var upd = null;
    try {
        if (target.title !== prevTitle) {
            upd = await engine.updateNative(target.title, snapContent);
            await engine.deleteNative(prevTitle);
        } else {
            upd = await engine.updateNative(target.title, snapContent);
        }
    } catch (_eRB) { upd = { ok: false }; }
    await store.writeIndex(idx);
    await store.appendLog("rollback " + target.title + " step " + n);
    return {
        success: true, title: target.title, steps: n,
        restored_from_version: Number(snap.version) || null,
        new_version: target.version,
        native_ok: upd && upd.ok === true,
        content_preview: engine.preview(target),
        message: "已回退 " + n + " 步：" + target.title + " 恢复到 v" + String(snap.version || "?") +
            "（当前版已压入 history，可再前进）"
    };
}

var _mainMod;
function loadMainOnce() {
    if (_mainMod !== undefined) return _mainMod;
    try { _mainMod = require("../main.js"); } catch (_e) { _mainMod = null; }
    return _mainMod;
}
async function memDiag(p) {
    var idx = await store.readIndex();
    var mainMod = loadMainOnce();
    var brief = null;
    var trig = null;
    if (mainMod) {
        if (typeof mainMod.getLastBrief === "function") brief = mainMod.getLastBrief();
        if (typeof mainMod.getLastTrigger === "function") trig = mainMod.getLastTrigger();
    }
    var reg = null;
    try { if (mainMod && typeof mainMod.getRegistered === "function") reg = mainMod.getRegistered(); } catch (_eR) { }
    var lastInput = null;
    try { if (mainMod && typeof mainMod.getLastInput === "function") lastInput = mainMod.getLastInput(); } catch (_eLI) { }
    // 平台机制：debug_install_toolpkg 重建 JS 模块 → 内存态清零。
    // 所以内存里的 null 不代表 hook 没跑过，必须回落读磁盘状态。
    var disk = null;
    try {
        var hiMod = require("../shared/hook_impl.js");
        if (hiMod && typeof hiMod.loadState === "function") disk = await hiMod.loadState();
    } catch (_eDS) { }
    if (!lastInput && disk && disk.lastInput) lastInput = disk.lastInput;
    if (!trig && disk && disk.lastTrigger) trig = disk.lastTrigger;
    if (!brief && disk && disk.lastBrief) brief = disk.lastBrief;
    if (!reg && disk && disk.registered) reg = disk.registered;

    // 工具失败缓冲：不接进诊断的话，「hook 抓到了失败但没落盘」这种情况
    // 在 mem_diag 里完全看不出来，等于自检盲区。
    var failBufView = null;
    try {
        var hiFb = require("../shared/hook_impl.js");
        if (hiFb && typeof hiFb.getFailBuffer === "function") failBufView = hiFb.getFailBuffer();
    } catch (_eFb) { failBufView = null; }
    // 候选队列健康度 + 上次抽取结果。
    // 不加这两项的话，"自检通过"和"队列在堆垃圾"看起来一模一样。
    // 实测事故：self_check 全 healthy，但队列里 20 条全是同一个标题。
    var queueHealth = null;
    var lastExtract = null;
    try {
        var hiQ = require("../shared/hook_impl.js");
        if (hiQ && typeof hiQ.mergeQueueHealth === "function") queueHealth = hiQ.mergeQueueHealth();
        if (hiQ && typeof hiQ.getLastExtract === "function") lastExtract = hiQ.getLastExtract();
    } catch (_eQ2) { }
    if (!queueHealth && disk && Array.isArray(disk.mergeCandidates)) {
        // 回落：内存不可用时按磁盘候选算一遍
        var dn = disk.mergeCandidates.length, ds = {}, du = 0;
        for (var di = 0; di < dn; di += 1) {
            var dc = disk.mergeCandidates[di] || {};
            var dk = String(dc.incoming_title || "") + "|" + String(dc.existing_id || "");
            if (!ds[dk]) { ds[dk] = 1; du += 1; }
        }
        queueHealth = {
            total: dn, unique: du,
            dupRatio: dn > 0 ? Math.round((1 - du / dn) * 1000) / 1000 : 0,
            saturated: false, healthy: dn === 0 ? true : (1 - du / dn) < 0.4,
            source: "disk"
        };
    }
    if (!lastExtract && disk && disk.lastExtract) lastExtract = disk.lastExtract;

    return {
        success: true,
        index_path: safeIdxPath(),
        index_dir: safeBaseDir(),
        log_path: safeLogPath(),
        entries: idx.entries.length,
        hook_registered: reg,
        last_input: lastInput,
        last_trigger: trig,
        last_brief: brief,
        queue_health: queueHealth,
        last_extract: lastExtract,
        state_source: disk ? "disk" : "memory",
        // 进化记忆预载状态。此前只写进 _lastBrief，mem_diag 看不见，
        // 导致「预载连续失败」与「库里本来就空」在诊断层无法区分。
        evo_cache_error: (brief && brief.evoCacheErr) ? String(brief.evoCacheErr) : "",
        evo_fail_streak: (brief && typeof brief.evoFailStreak === "number") ? brief.evoFailStreak : 0,
        // 健康基线状态。此前只写进 _lastBrief.healthLevel，
        // mem_diag 看不见，导致「基线正常」与「基线没算过」无法区分。
        health_level: (brief && brief.healthLevel) ? String(brief.healthLevel) : "",
        // 审计运行态：此前 _auditState 只写不读，模型分不清「审计刚跑过且全通过」
        // 与「审计根本没跑」，与 evo_cache_error / health_level 同类缺陷。
        audit_state: (function () {
            try {
                var hmA = require("../shared/hook_impl.js");
                if (!hmA || typeof hmA.getAuditState !== "function") return null;
                var as = hmA.getAuditState() || {};
                var lr = as.lastResult || null;
                var detailed = String(p && p.audit) === "1" || (p && p.audit === true);
                return {
                    round: Number(as.round) || 0,
                    last_full_audit_at: Number(as.lastFullAuditAt) || 0,
                    last_full_audit_age_ms: as.lastFullAuditAt ? (Date.now() - Number(as.lastFullAuditAt)) : null,
                    last_result: detailed ? lr : (lr ? {
                        at: lr.at, total: lr.total,
                        duplicates: lr.duplicates, conflicts: lr.conflicts,
                        thin: lr.thin, stale: lr.stale,
                        self_check_healthy: lr.selfCheckHealthy
                    } : null),
                    note: "round 是注入块调用次数；last_result 非空说明全量审计真跑过；传 audit=1 看完整结果"
                };
            } catch (_eAS) { return null; }
        })(),
        // 工具失败缓冲：hook 抓到但还没落盘的失败。
        // 不暴露的话，「失败被捕获了」与「根本没捕获」在诊断层长得一样。
        fail_buffer: failBufView,
        fail_buffer_note: "pending 非零 = hook 已抓到失败但还没落盘；调 mem_evolution flush_failures=1 写进进化记忆",
        // 操作日志：growth.log 有 8 处写入但此前零读取出口。
        // 默认不返回（保持返回体精简），传 log=N 才带最近 N 行。
        recent_log: (Number(p && p.log) > 0) ? await store.listLog(Number(p.log), String((p && p.log_filter) || "")) : undefined,
        // 完整健康基线（样本数/均值/异常/回退）。
        // 默认不返回：每轮塞完整对象会撑大返回体（weights 那次的教训）。
        health_cache: (String(p && p.health) === "1" || p && p.health === true)
            ? (function () {
                try {
                    var hm = require("../shared/hook_impl.js");
                    return (hm && typeof hm.getHealthCache === "function") ? hm.getHealthCache() : null;
                } catch (_eHC2) { return null; }
            })()
            : undefined,
        hint: "state_source=disk 说明内存被重建过、读的是落盘值；last_input 有值=输入抓到了；queue_health.healthy=false 说明候选去重失效；evo_cache_error 非空=进化记忆预载失败（该字段同时说明注入块被降级为告警态），evo_fail_streak 是连续失败次数；health_level 非空=基线已算过（ok/stagnation/regression/homogeneous/error），health=1 才返回完整 health_cache"
    };
}

// ============================================================
// 合并判断：引擎只给证据，模型来定夺
// ------------------------------------------------------------
// 为什么不自动合并：内容相似度只能说明「可能同一件事」，
// 判断是不是一件事、该保留哪段、合并后怎么写，只有对话模型能做对。
// 所以这里只负责：取候选、按模型给的结果落盘。
// ============================================================

var MERGE_META = [
    "你是记忆库条目管理者。引擎给了你可能重复的条目对，但判断权在你。",
    "",
    "对每一对，选一个动作：",
    "",
    "merge —— 说的是同一件事，且两边各有独有信息。合成一条更完整的，保留全部要点。",
    "replace —— 说的是同一件事，新信息更准确/旧的是错的。用新版整段替换旧版，旧版进 history。",
    "keep_both —— 看起来像但其实是不同场景/不同条件，各自成立。都留着。",
    "drop —— 新条目没什么新信息（只是换个说法），直接丢弃。",
    "",
    "硬规则：",
    "· content 必须写完整全文，不要只写差异片段。",
    "· 不要为了「有交代」把已经对的话换个说法重写——那是污染。",
    "· 两边矛盾且无法判断谁对时，选 keep_both 并在 why 里说明矛盾点。",
    "· title 要能让人一眼看出主题，不要用「合并后」「汇总」这种空词。",
    "",
    "输出：对每对调一次 mem_merge_apply。"
].join("\n");

async function memMergeReview(p) {
    var limit = Math.max(1, Math.min(50, Number(p && p.limit) || 20));
    var hiMod = require("../shared/hook_impl.js");

    var cands = [];
    try {
        if (hiMod && typeof hiMod.getMergeCandidates === "function") {
            cands = hiMod.getMergeCandidates() || [];
        }
    } catch (_e) { cands = []; }

    // 内存里没有就回落读磁盘状态
    var queueRestored = 0;
    if (!cands.length) {
        try {
            if (hiMod && typeof hiMod.loadState === "function") {
                var disk = await hiMod.loadState();
                if (disk && Array.isArray(disk.mergeCandidates)) {
                    cands = disk.mergeCandidates;
                    // 只读进局部变量不够。平台重建 JS 模块后内存队列清零，
                    // 若不写回内存，pushMergeCandidate 的封顶与 mergeQueueHealth
                    // 的重复率计算永远基于空队列，健康度恒为 healthy。
                    // setMergeCandidates 就是为此存在的恢复入口，此前零调用。
                    if (cands.length && hiMod && typeof hiMod.setMergeCandidates === "function") {
                        queueRestored = hiMod.setMergeCandidates(cands);
                    }
                }
            }
        } catch (_e2) { }
    }

    var items = cands.slice(-limit);
    var out = items.map(function (c, idx) {
        return {
            idx: idx,
            reason: c.reason,
            score: c.score,
            existing_id: c.existing_id,
            existing_title: c.existing_title,
            existing_layer: c.existing_layer,
            existing_version: c.existing_version,
            existing_content: String(c.existing_content || "").slice(0, 1500),
            incoming_title: c.incoming_title,
            incoming_layer: c.incoming_layer,
            incoming_content: String(c.incoming_content || "").slice(0, 1500),
            captured_at: c.at
        };
    });

    // 队列健康度：单独暴露，否则去重坏了也看不出来。
    // 实测事故：20 条候选全是同一个标题，唯一数 1，但 total 报 20。
    var mqh = null;
    try {
        if (hiMod && typeof hiMod.mergeQueueHealth === "function") mqh = hiMod.mergeQueueHealth();
    } catch (_eQ) { mqh = null; }

    return {
        success: true,
        total: cands.length,
        returned: out.length,
        // 非 0 说明内存队列此前是空的（模块被重建过），本次已从磁盘恢复。
        queue_restored: queueRestored || undefined,
        queue_health: mqh,
        meta_prompt: MERGE_META,
        items: out,
        note: cands.length === 0
            ? "当前没有待判断的合并候选。hook 会在自动抽取命中重复时把候选推入队列。"
            : "看完后对每条调用 mem_merge_apply 提交决定；未提交的候选会一直留在队列里。"
    };
}

async function memMergeApply(p) {
    var existingId = String((p && p.existing_id) || "").trim();
    var action = String((p && p.action) || "").trim().toLowerCase();
    if (!existingId) return { success: false, error: "existing_id is required" };
    if (["merge", "replace", "keep_both", "drop"].indexOf(action) < 0) {
        return { success: false, error: "action must be merge / replace / keep_both / drop" };
    }

    var idx = await store.readIndex();
    var target = null;
    for (var i = 0; i < idx.entries.length; i += 1) {
        if (idx.entries[i].id === existingId) { target = idx.entries[i]; break; }
    }
    if (!target) return { success: false, error: "existing entry not found: " + existingId };

    var now = Date.now();
    var why = String((p && p.why) || "").trim();

    // keep_both / drop：只清候选，不改条目
    if (action === "keep_both" || action === "drop") {
        try {
            var hiMod0 = require("../shared/hook_impl.js");
            if (hiMod0 && typeof hiMod0.clearMergeCandidates === "function") {
                hiMod0.clearMergeCandidates([existingId]);
            }
        } catch (_e0) { }
        return {
            success: true,
            action: action,
            existing_id: existingId,
            changed: false,
            note: action === "keep_both" ? "两条都保留，候选已清理" : "新条目已丢弃，候选已清理"
        };
    }

    // merge / replace：需要模型给最终标题与正文
    var newTitle = String((p && p.title) || "").trim() || target.title;
    var newContent = String((p && p.content) || "").trim();
    if (!newContent) {
        return { success: false, error: "merge/replace 必须提供 content（完整全文，不是差异）" };
    }
    var newLayer = String((p && p.layer) || target.layer || "L2").toUpperCase();
    if (["L1", "L2", "L3"].indexOf(newLayer) < 0) newLayer = target.layer || "L2";

    // 旧版进 history，可回滚
    target.history = target.history || [];
    target.history.push({
        at: now,
        title: target.title,
        content: target.content,
        layer: target.layer,
        version: target.version || 1,
        why: why || action
    });
    if (target.history.length > 10) target.history = target.history.slice(-10);

    target.title = newTitle;
    target.content = newContent;
    target.layer = newLayer;
    target.version = (target.version || 1) + 1;
    target.updatedAt = now;
    target.mergedAt = now;
    target.mergeAction = action;
    if (why) target.lastMergeWhy = why;

    await store.writeIndex(idx);
    try { await store.appendLog("merge_apply " + action + " id=" + existingId + " v" + target.version); } catch (_eL) { }

    // 清候选
    try {
        var hiMod = require("../shared/hook_impl.js");
        if (hiMod && typeof hiMod.clearMergeCandidates === "function") {
            hiMod.clearMergeCandidates([existingId]);
        }
    } catch (_e2) { }

    // 标题变了的话，原生库也同步一份，保持检索一致
    try {
        if (typeof engine.updateNative === "function") {
            await engine.updateNative(newTitle, newContent, { layer: newLayer, tags: target.tags });
        }
    } catch (_eW) { }

    return {
        success: true,
        action: action,
        existing_id: existingId,
        title: newTitle,
        layer: newLayer,
        version: target.version,
        changed: true
    };
}

// ============================================================
// mem_audit：记忆库审计（周期全量）
// 引擎只给证据，处理由模型决定
// ============================================================
async function memAudit(p) {
    var idx = await store.readIndex();
    var entries = idx.entries || [];
    // 强制下轮全量：forceAudit 把 _auditState.round 顶到 interval-1，
    // 下一轮 buildAuditBlock 必然 dueFull。此前该导出零调用，
    // 模型发现库有问题时只能干等轮次，无法主动要求系统立刻自查。
    var forceAuditOk = false;
    if (p && (p.force === true || String(p.force) === "1")) {
        try {
            var hiF = require("../shared/hook_impl.js");
            if (hiF && typeof hiF.forceAudit === "function") forceAuditOk = hiF.forceAudit() === true;
        } catch (_eF) { forceAuditOk = false; }
    }
    if (typeof engine.auditScan !== "function") {
        return { success: false, error: "auditScan 未加载" };
    }

    var opts = {};
    var thr = Number(p && p.dup_threshold);
    if (thr > 0) opts.dupThreshold = thr;

    var r = engine.auditScan(entries, opts);
    var verbose = !(p && p.verbose === false);

    var issues = r.duplicates.length + r.conflicts.length + r.thin.length + r.stale.length;

    // 状态覆盖统计：已覆盖条数 + 该覆盖却没覆盖的存量冲突。
    // 旧实现只报 conflicts（问题存在），报不出 supersedePending（问题待处理），
    // 模型看不到行动项就会一直只报不改。
    var supCount = (r.superseded && r.superseded.length) ? r.superseded.length : 0;
    var pendCount = (r.supersedePending && r.supersedePending.length) ? r.supersedePending.length : 0;

    var out = {
        success: true,
        // 回显强制自检是否生效：true = 下一轮注入块会跑全量审计。
        // 不回显的话，模型传了 force 也分不清是「已置位」还是「forceAudit 不存在被静默吞掉」。
        force_audit: forceAuditOk,
        force_audit_note: forceAuditOk
            ? "已置位：下一轮注入块会跑一次全量审计，结果会写进 audit_state.last_result"
            : undefined,
        summary: {
            total: r.total,
            duplicates: r.duplicates.length,
            conflicts: r.conflicts.length,
            thin: r.thin.length,
            stale: r.stale.length,
            superseded: supCount,
            supersede_pending: pendCount,
            issues: issues,
            // v6 自检：证明审计确实跑过。r.selfCheck 由 auditScan 返回，
            // 含 checksRun / pairsCompared / thinChecked / staleChecked / elapsedMs。
            // 不传出去的话，「全通过」和「抛异常被吞」在返回体上一模一样。
            self_check: r.selfCheck || null
        }
    };
    // 顶层再平铺一份，调用方不用钻 summary
    if (r.selfCheck) out.self_check = r.selfCheck;
    // v6 增量：传了 prevIds 才有，用于看「这轮比上轮多了什么」
    if (r.delta) out.delta = r.delta;
    if (r.superseded) out.superseded = r.superseded;
    if (r.supersedePending) out.supersedePending = r.supersedePending;

    if (verbose) {
        out.duplicates = r.duplicates;
        out.conflicts = r.conflicts;
        out.thin = r.thin;
        out.stale = r.stale;
    }

    // degradation probe: record snapshot + report trend. Failure must not break audit.
    try {
        var deg = require("../shared/degradation_detect.js");
        if (deg && typeof deg.recordSnapshot === "function") {
            await deg.recordSnapshot("memory", { issues: issues, total: r.total });
            // all：跨 source 聚合，避免只看本包样本。
            out.degradation = await deg.detect("all");
        }
        // 统计基线：参照 claudewatch 的 EMA + z-score。
        // 旧 degradation 只做 current vs best 二点比较，报不出"这次偏离有多大"。
        // 这里补 statistical 字段：EMA 加权均值/标准差 + z 分 + 严重度。
        if (deg && typeof deg.emaBaseline === "function") {
            try {
                var hist = (out.degradation && Array.isArray(out.degradation.samplesList))
                    ? out.degradation.samplesList : null;
                // detect() 不返回原始样本，直接从 store 读
                if (!hist && typeof deg.readSamples === "function") hist = await deg.readSamples("all");
                if (hist && hist.length >= 3) {
                    var base = deg.emaBaseline(hist, "issues", 0.9);
                    var anom = deg.detectAnomaly(issues, base, { threshold: 2.0, higherIsBetter: false });
                    var regr = deg.detectRegression(issues, base.mean, { threshold: 1.5 });
                    // weights 数组不返回：n 个浮点数会把返回体撑大好几倍，且对模型无意义。
                    // 只留能被判断的部分：基线值、z 分、严重度、回退倍数。
                    out.statistical = {
                        baseline: { ok: base.ok, n: base.n, mean: base.mean, stddev: base.stddev },
                        anomaly: anom,
                        regression: regr,
                        note: "EMA 加权(decay=0.9)，z>2 warning / z>=3 critical，回退阈值 1.5x baseline"
                    };
                } else {
                    out.statistical = { available: false, reason: "样本不足 3 个，无法建立统计基线", samples: hist ? hist.length : 0 };
                }
            } catch (_eStat) {
                out.statistical = { available: false, reason: String((_eStat && _eStat.message) || _eStat) };
            }
        }
    } catch (_eDeg) {
        out.degradation = { level: "unknown", summary: "probe unavailable" };
    }

    // anti-harmony: catch selfCheck that pretends to be healthy
    try {
        var ah = require("../shared/anti_harmony.js");
        if (ah && typeof ah.checkMemory === "function") {
            out.antiHarmony = ah.checkMemory(r.selfCheck, entries.length);
        }
    } catch (_eAH) {
        out.antiHarmony = { level: "unknown", summary: "detector unavailable" };
    }

    out.advice = issues === 0
        ? "全部通过：无重复、无矛盾、无空壳、无陈旧"
        : "处理顺序：矛盾优先（mem_merge_apply action=replace，或 mem_supersede 标记旧结论）> 重复合并（action=merge）> 空壳补充（mem_evolve）> 陈旧 mem_forget";

    return out;
}

exports.mem_write = function (p) { return run(memWrite, p); };
exports.mem_search = function (p) { return run(memSearch, p); };
exports.mem_consolidate = function (p) { return run(memConsolidate, p); };
exports.mem_evolve = function (p) { return run(memEvolve, p); };
exports.mem_review = function (p) { return run(memReview, p); };
exports.mem_mark_used = function (p) { return run(memMarkUsed, p); };
exports.mem_stats = function (p) { return run(memStats, p); };
exports.mem_forget = function (p) { return run(memForget, p); };
// 回滚出口：与世界书 wb_rollback 对称。此前 history 只写不读，是死数据。
exports.mem_rollback = function (p) { return run(memRollback, p); };

exports.mem_diag = function (p) { return run(memDiag, p); };
exports.mem_merge_review = function (p) { return run(memMergeReview, p); };
exports.mem_audit = function (p) { return run(memAudit, p); };
exports.mem_merge_apply = function (p) { return run(memMergeApply, p); };

// ============================================================
// mem_lesson：把踩过的坑写进进化记忆
// ============================================================
async function memLesson(p) {
    var kind = String((p && p.kind) || "").trim();
    var target = String((p && p.target) || "").trim();
    var detail = String((p && p.detail) || "").trim();
    if (!kind) return { success: false, message: "缺少 kind" };
    if (!target) return { success: false, message: "缺少 target" };
    if (!detail) return { success: false, message: "缺少 detail" };

    var evo = require("../shared/evolution_memory.js");

    // kind="success" 时写成功模板库（原 recordTemplate 零调用方，模板库永远为空）
    if (kind === "success") {
        var okT = await evo.recordTemplate(target, detail, (p && p.evidence) || "");
        var lcT = await evo.loadCache();
        var stT = await evo.stats();
        return {
            success: !!okT,
            kind: "success",
            target: target,
            cache: lcT,
            total_failures: stT.failures,
            total_templates: stT.templates,
            message: okT
                ? "已记入成功模板：" + target + "（累计 " + stT.templates + " 条）"
                : "写入失败"
        };
    }

    var ok = await evo.recordFailure(kind, target, detail, (p && p.evidence) || "");
    if (!ok) return { success: false, message: "写入失败，检查存储目录权限" };

    // 写盘后刷新缓存，保证下一轮 hook 立刻能看到
    var lc = await evo.loadCache();
    var st = await evo.stats();

    return {
        success: true,
        kind: kind,
        target: target,
        cache: lc,
        total_failures: st.failures,
        total_templates: st.templates,
        message: "已记入进化记忆：" + kind + " / " + target + "（累计 " + st.failures + " 条失败模式）"
    };
}

// ============================================================
// mem_evolution：查看进化记忆状态
// ============================================================
async function memEvolution(p) {
    var evo = require("../shared/evolution_memory.js");
    // ===== 工具失败缓冲刷盘 =====
    // 为什么在这里：hook 内禁止 IO，工具失败只能先落内存（hook_impl.recordToolFailureSync）。
    // 若不提供落盘出口，那批失败会在引擎重建时静默消失，等于白记。
    // flush_failures=1 显式刷；不传则只在有积压时报告「压着几条」，不擅自写库。
    var out0 = {};
    var wantFlush = !!(p && (p.flush_failures === true || String(p.flush_failures) === "1" || String(p.flush_failures) === "true"));
    var hiMod = null;
    try { hiMod = require("../shared/hook_impl.js"); } catch (_eHi) { hiMod = null; }
    if (hiMod && typeof hiMod.getFailBuffer === "function") {
        var fb = hiMod.getFailBuffer();
        out0.fail_buffer = { pending: fb.pending, seen: fb.seen, written: fb.written };
        if (wantFlush && fb.pending > 0) {
            var drained = (typeof hiMod.drainFailBuffer === "function") ? hiMod.drainFailBuffer() : [];
            var flushed = 0;
            var flushErr = "";
            for (var fi = 0; fi < drained.length; fi += 1) {
                var d = drained[fi];
                if (!d) continue;
                try {
                    // kind 固定 tool-fail：与世界书侧规则库的分类语义对齐，
                    // 便于「同一个坑跨包共享」时按 kind 聚合。
                    var okF = await evo.recordFailure(
                        "tool-fail",
                        d.tool + " | " + String(d.msg || "").slice(0, 60),
                        String(d.msg || "").slice(0, 400),
                        String((d.extra && d.extra.result_text) || ""),
                        d.count,
                        d.extra
                    );
                    if (okF) flushed += 1;
                } catch (_eFl) { flushErr = String((_eFl && _eFl.message) || _eFl); }
            }
            if (typeof hiMod.markFailWritten === "function") hiMod.markFailWritten(flushed);
            out0.fail_flush = { drained: drained.length, flushed: flushed, error: flushErr };
            try { await evo.loadCache(); } catch (_eLc) { }
        }
    }
    var st = await evo.stats();
    var wantDetail = !!(p && (p.detail === true || String(p.detail) === "true"));
    var kind = String((p && p.kind) || "").trim();
    var query = String((p && p.query) || "").trim();

    // 只有明确要看内容时才返回正文，避免每次 mem_evolution 都撑大返回体
    // （weights 数组那次就是这么把返回体撑爆的）。
    var wantTl0 = (p && p.timeline !== undefined && p.timeline !== null &&
        String(p.timeline) !== "" && String(p.timeline) !== "false");
    if (!wantDetail && !kind && !query && !wantTl0) {
        var o1 = { success: true, evolution: st };
        for (var k0 in out0) { if (Object.prototype.hasOwnProperty.call(out0, k0)) o1[k0] = out0[k0]; }
        return o1;
    }

    var out = { success: true, evolution: st };
    for (var k1 in out0) { if (Object.prototype.hasOwnProperty.call(out0, k1)) out[k1] = out0[k1]; }

    // 按意图匹配：消费此前零调用的 preflightWarnings / preflightTemplates（死代码接通）
    if (query) {
        try {
            var hitF = await evo.preflightWarnings(query, 5);
            var hitT = await evo.preflightTemplates(query, 3);
            out.matched = {
                query: query,
                failures: (hitF || []).map(function (x) {
                    return {
                        kind: String(x.kind || ""), target: String(x.target || ""),
                        count: Number(x.count || 1),
                        detail: String(x.detail || "").slice(0, 300)
                    };
                }),
                templates: (hitT || []).map(function (x) {
                    return {
                        name: String(x.name || ""), note: String(x.note || "").slice(0, 160),
                        pattern: String(x.pattern || "").slice(0, 500)
                    };
                })
            };
        } catch (_eQ) { out.matched = { query: query, failures: [], templates: [] }; }
    }

    // 进化轨迹：此前 log 只能被 stats 数行数，模型看不到任何一条记录。
    var wantTimeline = (p && p.timeline !== undefined && p.timeline !== null &&
        String(p.timeline) !== "" && String(p.timeline) !== "false");
    if (wantTimeline) {
        var tlim = (String(p.timeline) === "all" || String(p.timeline) === "true") ? 50 : Number(p.timeline);
        if (!(tlim > 0)) tlim = 20;
        try {
            out.timeline = await evo.listTimeline(tlim, String(p.timeline_type || "").trim());
        } catch (_eTl) { out.timeline = []; }
    }

    // 全量正文
    if (wantDetail || kind) {
        try {
            out.failures_detail = await evo.listFailures(kind, 20);
        } catch (_eF) { out.failures_detail = []; }
        try {
            out.templates_detail = await evo.listTemplates(20);
        } catch (_eT) { out.templates_detail = []; }
    }

    return out;
}

// ---- 状态覆盖（supersede）----
// 显式接口。mem_write / mem_evolve 只做提示与自动标记，
// 这里给模型主动纠正历史结论的入口。
async function memSupersede(p) {
    var action = String(p.action || "mark").trim().toLowerCase();
    var idx = await store.readIndex();

    if (action === "candidates") {
        var ct = String(p.title || "").trim();
        var cc = String(p.content || "").trim();
        if (!ct && !cc) return { success: false, message: "candidates 模式需要 title 或 content" };
        var cand = [];
        try {
            cand = engine.findSupersedeCandidates(idx.entries, ct, cc, {
                threshold: engine.clampNum(p.threshold, 0, 1, 0.5),
                limit: 5
            });
        } catch (_eC) { }
        return {
            success: true, action: "candidates", count: cand.length, candidates: cand,
            message: cand.length === 0 ? "没有找到该被覆盖的旧条目" :
                "找到 " + cand.length + " 条候选，确认后用 action=mark 标记"
        };
    }

    var oldKey = String(p.old || "").trim();
    if (!oldKey) return { success: false, message: "缺少 old（旧条目 title 或 id）" };

    if (action === "clear") {
        var hitC = 0, nameC = "";
        for (var i2 = 0; i2 < idx.entries.length; i2 += 1) {
            var e2 = idx.entries[i2];
            if (!e2) continue;
            if (String(e2.id || "") !== oldKey && String(e2.title || "") !== oldKey) continue;
            if (engine.clearSuperseded(e2)) { hitC += 1; nameC = String(e2.title || ""); }
        }
        if (hitC === 0) return { success: false, message: "该条目不存在或本来就没有覆盖标记：" + oldKey };
        await store.writeIndex(idx);
        await store.appendLog("supersede clear " + nameC);
        return { success: true, action: "clear", cleared: hitC, message: "已撤销覆盖标记：" + nameC };
    }

    // mark
    var byKey = String(p.by || "").trim();
    if (!byKey) return { success: false, message: "缺少 by（取代它的新条目 title 或 id）" };
    var n = 0;
    try {
        n = engine.markSuperseded(idx.entries, oldKey, byKey, String(p.cause || ""));
    } catch (_eM) { n = 0; }
    if (n === 0) return { success: false, message: "找不到该条目：" + oldKey };
    await store.writeIndex(idx);
    await store.appendLog("supersede mark " + oldKey + " by " + byKey);
    return {
        success: true, action: "mark", marked: n,
        message: "已标记 " + n + " 条为已过时：" + oldKey + " → " + byKey +
            "（之后注入时会降权并列在『已过时』段）"
    };
}

exports.mem_lesson = function (p) { return run(memLesson, p); };
exports.mem_evolution = function (p) { return run(memEvolution, p); };
exports.mem_supersede = function (p) { return run(memSupersede, p); };
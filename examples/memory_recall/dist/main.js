"use strict";
Object.defineProperty(exports, "__esModule", { value: true });

// ============ 主动式记忆成长 · 主入口 ============
// 旧做法：每轮自动检索 + 把记忆正文塞进 systemPrompt（AI 全程无感，被动被喂）
// 新做法：只注入「索引 + 任务指令」，AI 自己决定查不查、查哪条、写不写、要不要沉淀
// 参考：mem0（只增不覆盖）/ MemOS（L1-L3 三层）/ EverOS（后台整合）/ deep-memory（艾宾浩斯）

var store = require("./shared/growth_store.js");
var engine = require("./shared/growth_engine.js");

var _inited = false;
var _lastBrief = null;
var _lastTrigger = null;

async function initOnce() {
    if (_inited) return;
    _inited = true;
    try { await store.ensureDir(); } catch (_e) { }
}

var SKIP_EXACT = ["嗯","哦","喔","好","行","可以","继续","是","对","不","没有","收到","ok","okay","yes","no","嗯嗯","好的","行吧","可以吧","继续吧"];
var HINTS = ["记得","之前","上次","以前","曾经","历史","老规矩","照旧","记忆","经验","教训","总结","回顾","remember","recall","previously"];

function shouldPrompt(text) {
    var t = String(text || "").trim();
    if (!t) return { yes: false, reason: "empty" };
    var bare = t.replace(/[。！？!?.,，~～\s]/g, "").toLowerCase();
    if (bare.length === 0) return { yes: false, reason: "punctuation_only" };
    if (SKIP_EXACT.indexOf(bare) >= 0) return { yes: false, reason: "trivial" };
    var lower = t.toLowerCase();
    for (var i = 0; i < HINTS.length; i += 1) {
        if (lower.indexOf(HINTS[i]) >= 0) return { yes: true, reason: "explicit_hint" };
    }
    if (bare.length >= 8) return { yes: true, reason: "substantive" };
    return { yes: false, reason: "too_short" };
}

function pickUserText(payload) {
    var raw = payload.rawInput || payload.raw_input || payload.userInput;
    if (raw && String(raw).trim()) return String(raw).trim();
    var hist = payload.chatHistory || payload.chat_history;
    if (hist && hist.length) {
        for (var i = hist.length - 1; i >= 0; i -= 1) {
            var t = hist[i];
            if (!t) continue;
            var kind = String(t.kind || t.role || "").toUpperCase();
            if (kind === "USER" || kind === "HUMAN") {
                var c = String(t.content || "").trim();
                if (c) return c;
            }
        }
    }
    var pi = payload.processedInput || payload.processed_input;
    if (pi && String(pi).trim()) return String(pi).trim();
    return "";
}

async function systemPromptHook(event) {
    try {
        var stage = event && (event.eventName || event.event);
        if (stage !== "after_compose_system_prompt") return null;
        await initOnce();

        var payload = (event && event.eventPayload) || {};
        var userText = pickUserText(payload);
        var d = shouldPrompt(userText);
        _lastTrigger = { at: Date.now(), yes: d.yes, reason: d.reason, len: userText.length };

        if (!d.yes) {
            _lastBrief = { at: Date.now(), skipped: true, reason: d.reason };
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

        _lastBrief = { at: Date.now(), skipped: false, total: idx.entries.length, queryHint: userText.slice(0, 60), blockLen: hint.length };

        var cur = String(payload.systemPrompt || "");
        var nl = String.fromCharCode(10);
        var merged = cur ? (cur + nl + nl + hint) : hint;
        try { await store.appendLog("hint total=" + idx.entries.length + " q=" + userText.slice(0, 30)); } catch (_e3) { }
        return { systemPrompt: merged };
    } catch (e) {
        _lastBrief = { at: Date.now(), skipped: true, reason: "hook_error", error: String((e && e.message) || e) };
        try { await store.appendLog("hook_error " + String((e && e.message) || e)); } catch (_e2) { }
        return null;
    }
}

async function finalizeHook(event) {
    try {
        var stage = event && (event.eventName || event.event);
        if (stage !== "before_finalize_prompt") return null;
        return null;
    } catch (_e) {
        return null;
    }
}

// ============ 注册 ============
// 关键：注册的函数必须由 require() 加载，才会带
//   __operit_toolpkg_module_path / __operit_toolpkg_export_name
// 入口 main.js 自身的函数没有这两个标记，直接传会抛
//   "function must be exported from a toolpkg module"
// 所以 hook 实现放在 shared/hook_impl.js，这里只取它的导出引用。
var hookImpl = null;
try {
    hookImpl = require("./shared/hook_impl.js");
} catch (eLoad) {
    try { store.appendLog("hook_impl_require_fail " + String((eLoad && eLoad.message) || eLoad)); } catch (_eL) { }
    hookImpl = null;
}

function registerToolPkg() {
    var okCompose = false;
    var okFinalize0 = false;
    var okFinalize = false;
    if (!hookImpl) {
        try { store.appendLog("register_skipped hook_impl_missing"); } catch (_eA) { }
        return false;
    }
    var okMessage = false;
    try {
        ToolPkg.registerChatMessageHook({
            id: "memory_growth_message",
            function: hookImpl.messageHook
        });
        okMessage = true;
    } catch (eMsg) {
        try { store.appendLog("register_message_fail " + String((eMsg && eMsg.message) || eMsg)); } catch (_eM) { }
    }
    var okInput = false;
    try {
        ToolPkg.registerPromptInputHook({
            id: "memory_growth_input",
            function: hookImpl.inputHook
        });
        okInput = true;
    } catch (eInput) {
        try { store.appendLog("register_input_fail " + String((eInput && eInput.message) || eInput)); } catch (_eI) { }
    }
    try {
        ToolPkg.registerSystemPromptComposeHook({
            id: "memory_growth_sys_prompt",
            function: hookImpl.systemPromptHook
        });
        okCompose = true;
    } catch (eCompose) {
        try { store.appendLog("register_compose_fail " + String((eCompose && eCompose.message) || eCompose)); } catch (_eB) { }
    }
    try {
        ToolPkg.registerPromptFinalizeHook({
            id: "memory_growth_finalize",
            function: hookImpl.finalizeHook
        });
        okFinalize = true;
    } catch (eFin) {
        try { store.appendLog("register_finalize_fail " + String((eFin && eFin.message) || eFin)); } catch (_eC) { }
    }
    // 工具失败自动捕获：此前记忆库只注册 4 个 hook，没有 tool_lifecycle，
    // 导致「工具调用失败」这个最高价值信号源从未进入进化记忆，
    // 只能靠模型自觉调 mem_lesson —— 而模型在失败时最容易忘记上报。
    var okLifecycle = false;
    try {
        ToolPkg.registerToolLifecycleHook({
            id: "memory_growth_lifecycle",
            function: hookImpl.toolLifecycleHook
        });
        okLifecycle = true;
    } catch (eLife) {
        try { store.appendLog("register_lifecycle_fail " + String((eLife && eLife.message) || eLife)); } catch (_eLi) { }
    }
    try { hookImpl.markRegistered({ input: okInput, message: okMessage, compose: okCompose, finalize: okFinalize, lifecycle: okLifecycle }); } catch (_eD) { }
    try { console.log("[MEMGROWTH] register input=" + okInput + " message=" + okMessage + " compose=" + okCompose + " finalize=" + okFinalize + " lifecycle=" + okLifecycle); } catch (_eE) { }
    return okCompose;
}

function getLastBrief() {
    try { return hookImpl ? hookImpl.getLastBrief() : _lastBrief; } catch (_eF) { return _lastBrief; }
}
function getLastTrigger() {
    try { return hookImpl ? hookImpl.getLastTrigger() : _lastTrigger; } catch (_eG) { return _lastTrigger; }
}
function getRegistered() {
    try { return hookImpl ? hookImpl.getRegistered() : null; } catch (_eH) { return null; }
}

var _tmp = null;
exports.registerToolPkg = registerToolPkg;
exports.systemPromptHook = systemPromptHook;
exports.finalizeHook = finalizeHook;
function getLastInput() {
    try { return hookImpl ? hookImpl.getLastInput() : null; } catch (_eI2) { return null; }
}
function getLastExtract() {
    try { return hookImpl ? hookImpl.getLastExtract() : null; } catch (_eX) { return null; }
}
exports.getLastExtract = getLastExtract;
exports.getLastInput = getLastInput;
exports.getLastBrief = getLastBrief;
exports.getLastTrigger = getLastTrigger;
exports.shouldPrompt = shouldPrompt;
exports.getRegistered = getRegistered;

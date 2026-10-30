"use strict";
Object.defineProperty(exports, "__esModule", { value: true });

var DIR_NAME = "memory_growth";
var IDX_FILE = "index.json";
var LOG_FILE = "growth.log";

function baseDir() {
    return ToolPkg.getConfigDir() + "/" + DIR_NAME;
}
function idxPath() { return baseDir() + "/" + IDX_FILE; }
function logPath() { return baseDir() + "/" + LOG_FILE; }

async function ensureDir() {
    try {
        await Tools.Files.mkdir(baseDir(), true);
    } catch (_e) { }
}
async function readRaw(path) {
    try {
        var r = await Tools.Files.read(path);
        return String((r && r.content) || "");
    } catch (_e) {
        return "";
    }
}
async function writeRaw(path, text) {
    try {
        await ensureDir();
        await Tools.Files.write(path, text, false);
        return true;
    } catch (_e) {
        return false;
    }
}
async function readIndex() {
    var raw = await readRaw(idxPath());
    var txt = String(raw || "").trim();
    if (!txt) return { version: 1, entries: [] };
    try {
        var obj = JSON.parse(txt);
        if (!obj || typeof obj !== "object") return { version: 1, entries: [] };
        if (!Array.isArray(obj.entries)) obj.entries = [];
        return obj;
    } catch (_e) {
        return { version: 1, entries: [] };
    }
}
async function writeIndex(idx) {
    var payload = {
        version: 1,
        updatedAt: Date.now(),
        entries: (idx && idx.entries) ? idx.entries : []
    };
    return writeRaw(idxPath(), JSON.stringify(payload, null, 2));
}
async function appendLog(line) {
    try {
        var prev = await readRaw(logPath());
        var lines = prev ? prev.split(String.fromCharCode(10)).filter(function (x) { return !!x; }) : [];
        lines.push(new Date().toISOString() + " " + String(line));
        if (lines.length > 300) lines = lines.slice(lines.length - 300);
        await writeRaw(logPath(), lines.join(String.fromCharCode(10)));
    } catch (_e) { }
}

exports.DIR_NAME = DIR_NAME;
exports.baseDir = baseDir;
exports.idxPath = idxPath;
exports.logPath = logPath;
exports.ensureDir = ensureDir;
exports.readIndex = readIndex;
exports.writeIndex = writeIndex;
exports.appendLog = appendLog;

// 通用文本读写（hook 状态落盘用）
async function writeText(name, text) {
    await ensureDir();
    await writeRaw(baseDir() + "/" + name, String(text == null ? "" : text));
    return true;
}

async function readText(name) {
    try {
        var r = await readRaw(baseDir() + "/" + name);
        return r == null ? "" : String(r);
    } catch (_e) { return ""; }
}

// 读操作日志。此前 growth.log 只有写（8 处调用）没有任何读出口，
// 模型看不到「这个库历史上被改过什么」—— merge/supersede 的审计链断在磁盘上。
// filter 可选：按子串过滤（如 "supersede" / "merge_apply" / "forget"）。
// 返回倒序（最新在前），每条拆成 ts + text 两段。
async function listLog(limit, filter) {
    var raw = await readRaw(logPath());
    var lines = raw ? raw.split(String.fromCharCode(10)).filter(function (x) { return !!x; }) : [];
    var f = String(filter || "").trim().toLowerCase();
    if (f) {
        lines = lines.filter(function (x) { return String(x).toLowerCase().indexOf(f) >= 0; });
    }
    var out = [];
    for (var i = lines.length - 1; i >= 0; i -= 1) {
        var ln = String(lines[i]);
        var sp = ln.indexOf(" ");
        out.push({
            ts: sp > 0 ? ln.slice(0, sp) : "",
            text: sp > 0 ? ln.slice(sp + 1) : ln
        });
        if (out.length >= (Number(limit) > 0 ? Number(limit) : 20)) break;
    }
    return out;
}

exports.writeText = writeText;
exports.listLog = listLog;
exports.readText = readText;

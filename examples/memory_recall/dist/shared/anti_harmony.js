// 反和谐检测器（Anti-Harmony Detector）
// 参照 DSH-AGINT 第 3.4 节「反和谐检测器」：定义「伪和谐模式」，命中即惩罚或告警。
//
// 本系统的伪和谐模式（自检假装健康）：
//   A1 空转自检：selfCheck.healthy=true 但 checksRun=0
//        → 审计根本没跑，却报「健康」。这是最危险的假阳性。
//   A2 扫描不全：healthy=true 但 totalScanned < 实际条目数
//        → 扫了一半就说健康。
//   A3 全零假象：所有指标为 0 且条目数 > 0
//        → 可能是审计函数静默 early-return。
//   A4 超时伪装：elapsedMs 极小（< 1ms）却声称扫了几百次匹配
//        → 计算量对不上，实际没做。
//   A5 错误吞噬：selfCheck.errors 非空但 healthy 仍为 true
//        → 有错误却判健康。
//
// 输出：{ level: "ok"|"suspicious"|"fake", patterns[], summary }
//   fake       = 明确在骗（A1/A5）
//   suspicious = 可能有问题（A2/A3/A4）

function checkMemory(selfCheck, actualTotal) {
    var pats = [];
    if (!selfCheck || typeof selfCheck !== "object") {
        return { level: "fake", patterns: ["A0 缺失自检块：无法证明审计跑过"], summary: "no selfCheck" };
    }
    var healthy = selfCheck.healthy === true;
    var checksRun = Number(selfCheck.checksRun) || 0;
    var total = Number(selfCheck.total) || 0;
    var pairs = Number(selfCheck.pairsCompared) || 0;
    var elapsed = Number(selfCheck.elapsedMs) || 0;
    var errors = selfCheck.errors;

    // A1 空转自检
    if (healthy && checksRun === 0 && (actualTotal || total) > 0) {
        pats.push("A1 空转自检：healthy=true 但 checksRun=0（审计没跑却报健康）");
    }
    // A2 扫描不全
    if (healthy && actualTotal > 0 && total > 0 && total < actualTotal) {
        pats.push("A2 扫描不全：healthy=true 但 total(" + total + ") < 实际(" + actualTotal + ")");
    }
    // A3 全零假象
    if (healthy && (actualTotal || total) > 0 && checksRun === 0 && pairs === 0) {
        pats.push("A3 全零假象：有 " + (actualTotal || total) + " 条却 checksRun=0 且 pairsCompared=0");
    }
    // A4 超时伪装
    if (pairs > 100 && elapsed > 0 && elapsed < 1) {
        pats.push("A4 超时伪装：pairsCompared=" + pairs + " 却只花 " + elapsed + "ms，计算量对不上");
    }
    // A5 错误吞噬
    if (errors && errors.length && healthy) {
        pats.push("A5 错误吞噬：selfCheck.errors 有 " + errors.length + " 条却仍报 healthy=true");
    }

    return mkResult(pats);
}

function checkWorldbook(selfCheck, actualTotal) {
    var pats = [];
    if (!selfCheck || typeof selfCheck !== "object") {
        return { level: "fake", patterns: ["A0 缺失自检块：无法证明审计跑过"], summary: "no selfCheck" };
    }
    var healthy = selfCheck.healthy === true;
    var checksRun = Number(selfCheck.checksRun) || 0;
    var totalScanned = Number(selfCheck.totalScanned) || 0;
    var enabled = Number(selfCheck.enabledScanned) || 0;
    var disabled = Number(selfCheck.disabledScanned) || 0;

    // A1 空转自检
    if (healthy && checksRun === 0 && (actualTotal || totalScanned) > 0) {
        pats.push("A1 空转自检：healthy=true 但 checksRun=0");
    }
    // A2 扫描不全（enabled+disabled 应等于 totalScanned）
    if (healthy && (enabled + disabled) !== totalScanned && totalScanned > 0) {
        pats.push("A2 计数不平：enabled(" + enabled + ")+disabled(" + disabled + ") != totalScanned(" + totalScanned + ")");
    }
    // A2b 与实际总数不符
    if (healthy && actualTotal > 0 && totalScanned > 0 && totalScanned < actualTotal) {
        pats.push("A2b 扫描不全：totalScanned(" + totalScanned + ") < 实际(" + actualTotal + ")");
    }
    // A3 全零假象
    if (healthy && (actualTotal || totalScanned) > 0 && totalScanned === 0) {
        pats.push("A3 全零假象：有条目却 totalScanned=0");
    }

    return mkResult(pats);
}

function mkResult(pats) {
    var fakeKeys = ["A0", "A1", "A5"];
    var level = "ok";
    if (pats.length > 0) {
        level = "suspicious";
        for (var i = 0; i < pats.length; i += 1) {
            for (var j = 0; j < fakeKeys.length; j += 1) {
                if (pats[i].indexOf(fakeKeys[j]) === 0) { level = "fake"; break; }
            }
            if (level === "fake") break;
        }
    }
    return {
        level: level,
        patterns: pats,
        count: pats.length,
        summary: pats.length === 0 ? "no fake-harmony pattern detected" : pats.join(" | ")
    };
}

module.exports = {
    checkMemory: checkMemory,
    checkWorldbook: checkWorldbook
};
export function describeCompletion(result) {
  const limited = result?.completion
    ? result.completion === "LIMIT_REACHED"
    : ["TOOL_LIMIT", "DEADLINE"].includes(result?.exitReason);
  const hasMatches = Boolean(result?.matches?.length);
  const message = limited
    ? hasMatches
      ? "已找到这些片段，还有候选未检查。"
      : result?.exitReason === "DEADLINE"
        ? "这次查找超时了，还没有确认到符合描述的片段。"
        : "这次检查次数已用完，还没有确认到符合描述的片段。"
    : hasMatches
      ? "已返回通过原画面核验的片段。"
      : "这次没找到符合描述的片段。";
  return { limited, message };
}

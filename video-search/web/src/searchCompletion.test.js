import { test } from "node:test";
import assert from "node:assert/strict";
import { describeCompletion } from "./searchCompletion.js";

test("normal no-answer does not claim unfinished work", () => {
  assert.equal(
    describeCompletion({ completion: "COMPLETED", matches: [] }).limited,
    false,
  );
  assert.equal(
    describeCompletion({ exitReason: "PLANNER_FINISHED", matches: [] }).message,
    "这次没找到符合描述的片段。",
  );
});
test("old and new limits remain distinguishable from a normal no-answer", () => {
  for (const result of [
    { exitReason: "TOOL_LIMIT" },
    { completion: "LIMIT_REACHED", exitReason: "TOOL_LIMIT" },
  ]) {
    assert.equal(describeCompletion(result).limited, true);
    assert.match(describeCompletion(result).message, /检查次数已用完/);
  }
  assert.match(describeCompletion({ exitReason: "DEADLINE" }).message, /超时/);
});
test("verified partial matches remain usable with an incomplete notice", () => {
  assert.equal(
    describeCompletion({ completion: "LIMIT_REACHED", matches: [{}] }).message,
    "已找到这些片段，还有候选未检查。",
  );
  assert.equal(describeCompletion({ matches: [{}] }).limited, false);
});

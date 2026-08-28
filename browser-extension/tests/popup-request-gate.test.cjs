const assert = require("node:assert/strict");
const test = require("node:test");

const requestGate = require("../src/popup-request-gate.js");

test("a newer resume selection invalidates an older response", () => {
  const gate = requestGate.create();
  const first = gate.begin(7);
  const second = gate.begin(8);

  assert.equal(gate.isCurrent(first, 7), false);
  assert.equal(gate.isCurrent(second, 8), true);
});

test("cancelling invalidates pending polling", () => {
  const gate = requestGate.create();
  const token = gate.begin(7);
  gate.cancel();

  assert.equal(gate.isCurrent(token, 7), false);
});

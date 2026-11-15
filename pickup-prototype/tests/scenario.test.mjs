import test from "node:test";
import assert from "node:assert/strict";
import {
  initialState,
  scenarioReducer,
  isSharing,
} from "../src/state/scenario.ts";

test("boarding requires a started session and explicit stationary confirmation", () => {
  assert.equal(scenarioReducer(initialState, "board"), initialState);
  let state = initialState;
  for (const action of [
    "invite",
    "accept",
    "start",
    "delay",
    "wait",
    "ready",
    "approach",
  ])
    state = scenarioReducer(state, action);
  assert.equal(state.stage, 7);
  assert.equal(isSharing(state), true);
  assert.equal(scenarioReducer(state, "board"), state);
  state = scenarioReducer(state, "arrive");
  assert.equal(state.parked, true);
  state = scenarioReducer(state, "board");
  assert.equal(state.stage, 8);
  assert.equal(isSharing(state), false);
});

test("out-of-order and duplicate actions cannot advance the scenario", () => {
  assert.equal(scenarioReducer(initialState, "ready"), initialState);
  const invited = scenarioReducer(initialState, "invite");
  assert.equal(scenarioReducer(invited, "invite"), invited);
  assert.equal(isSharing(invited), false);
});

test("reset clears the shared session and previous event history", () => {
  const invited = scenarioReducer(initialState, "invite");
  assert.deepEqual(scenarioReducer(invited, "reset"), initialState);
});

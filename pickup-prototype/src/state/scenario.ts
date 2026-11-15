export type Stage = 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8;
export type Action =
  | "invite"
  | "accept"
  | "start"
  | "delay"
  | "wait"
  | "ready"
  | "approach"
  | "arrive"
  | "board"
  | "reset";
export type ScenarioState = {
  stage: Stage;
  parked: boolean;
  history: string[];
};
export const initialState: ScenarioState = {
  stage: 0,
  parked: false,
  history: [],
};

const transitions: Partial<
  Record<Action, { from: Stage; to: Stage; message: string }>
> = {
  invite: { from: 0, to: 1, message: "아버지가 픽업 초대 링크를 만들었어요." },
  accept: {
    from: 1,
    to: 2,
    message: "지우가 장소와 시간을 확인하고 약속을 수락했어요.",
  },
  start: {
    from: 2,
    to: 3,
    message: "픽업을 시작했어요. 차량 위치·ETA 공유 중.",
  },
  delay: {
    from: 3,
    to: 4,
    message: "지우: 엘리베이터 대기 중 · 약 5분 후 출발.",
  },
  wait: { from: 4, to: 5, message: "아버지가 동측 주차장 대기를 선택했어요." },
  ready: { from: 5, to: 6, message: "지우가 2번 출구로 이동 중이에요." },
  approach: { from: 6, to: 7, message: "승하차 구역으로 재접근을 시작했어요." },
  board: {
    from: 7,
    to: 8,
    message: "탑승 완료를 확인했어요. 위치 공유가 종료됐어요.",
  },
};

export function scenarioReducer(
  state: ScenarioState,
  action: Action,
): ScenarioState {
  if (action === "reset") return { ...initialState, history: [] };
  if (action === "arrive") {
    if (state.stage !== 7 || state.parked) return state;
    return {
      ...state,
      parked: true,
      history: [...state.history, "아버지가 정차 완료를 확인했어요."],
    };
  }
  if (action === "board" && !state.parked) return state;
  const transition = transitions[action];
  if (!transition || state.stage !== transition.from) return state;
  return {
    ...state,
    stage: transition.to,
    parked: false,
    history: [...state.history, transition.message],
  };
}

export function isSharing(state: ScenarioState): boolean {
  return state.stage >= 3 && state.stage < 8;
}

export function voiceAnswer(state: ScenarioState): string {
  if (state.stage < 3)
    return "아직 픽업을 시작하지 않았어요. 출발하면 위치 공유와 경로 안내를 함께 시작합니다.";
  if (state.stage === 3)
    return "지우는 역에 도착했어요. 아직 준비 상태를 보내지 않았습니다. 차량은 약속 장소까지 약 3분 남았어요.";
  if (state.stage === 4)
    return "지우가 엘리베이터를 기다리고 있어요. 본인이 입력한 출발 예정은 약 5분 후입니다. 승하차 구역 대신 동측 주차장에서 대기할까요?";
  if (state.stage === 5)
    return "지우는 아직 준비 중이에요. 현재는 동측 주차장에서 대기 중입니다. 이동을 시작하면 알려드릴게요.";
  if (state.stage === 6)
    return "지우가 2번 출구로 이동 중이에요. 도보 약 3분, 차량 접근도 약 3분입니다. 픽업 장소로 다시 안내할까요?";
  if (state.stage === 7)
    return state.parked
      ? "정차 완료를 지우에게 알렸어요. 흰색 아이오닉 5, 12가 3456 차량을 확인하도록 안내했습니다."
      : "지우는 약속 장소로 이동 중이에요. 아직 차량이 접근 중이므로 정차 완료 전에는 탑승을 안내하지 않습니다.";
  return "지우의 탑승 확인이 끝났어요. 이번 픽업의 위치 공유를 종료했습니다.";
}

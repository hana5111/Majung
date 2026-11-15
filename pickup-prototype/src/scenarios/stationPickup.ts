export const steps = [
  {
    title: "픽업 약속",
    short: "약속",
    time: "18:40",
    phase: 0,
    instruction: "아버지 화면에서 초대 링크를 만들어 보세요.",
    description:
      "오랜만에 본가에 오는 딸. 아버지가 기차역으로 마중을 나갑니다.",
  },
  {
    title: "초대 수락",
    short: "연결",
    time: "18:41",
    phase: 0,
    instruction: "딸의 휴대폰에서 약속을 확인하고 수락하세요.",
    description: "같은 이름의 장소보다, 같은 출구와 승하차 구역을 약속합니다.",
  },
  {
    title: "픽업 출발",
    short: "출발",
    time: "18:45",
    phase: 1,
    instruction: "아버지 화면에서 픽업을 시작하세요.",
    description:
      "차량에서 약속을 이어받고, 출발과 함께 위치 공유를 시작합니다.",
  },
  {
    title: "예상 밖 지연",
    short: "지연",
    time: "19:02",
    phase: 1,
    instruction: "딸의 휴대폰에서 준비 지연을 알려 보세요.",
    description:
      "차량은 곧 도착하지만, 딸은 캐리어를 들고 엘리베이터를 기다립니다.",
  },
  {
    title: "대기 장소 선택",
    short: "대기",
    time: "19:03",
    phase: 2,
    instruction: "아버지 화면에서 안내를 확인하고 주차장 대기를 선택하세요.",
    description:
      "승하차 구역에서 기다리는 대신, 주차장에서 다음 이동을 준비합니다.",
  },
  {
    title: "다시 맞추는 시간",
    short: "준비",
    time: "19:07",
    phase: 2,
    instruction: "딸의 휴대폰에서 나오는 중이라고 알려 주세요.",
    description: "딸의 이동 상태가 바뀌면, 아버지에게 재접근을 제안합니다.",
  },
  {
    title: "픽업 지점 재접근",
    short: "접근",
    time: "19:08",
    phase: 2,
    instruction: "아버지 화면에서 승하차 구역으로 다시 출발하세요.",
    description: "딸의 도보 시간과 차량 접근 시간을 함께 확인합니다.",
  },
  {
    title: "만남과 탑승",
    short: "탑승",
    time: "19:11",
    phase: 3,
    instruction: "정차를 확인한 뒤, 딸의 휴대폰에서 탑승을 완료하세요.",
    description: "근처에 있다는 것과, 탈 준비가 됐다는 것은 다릅니다.",
  },
  {
    title: "마중 완료",
    short: "완료",
    time: "19:12",
    phase: 3,
    instruction: "픽업이 끝났습니다. 처음부터 다시 체험할 수 있어요.",
    description: "잘 왔어. 이제 함께 집으로 갑니다.",
  },
] as const;

export const phases = ["운행 전", "운행 중", "만나기 직전", "탑승·종료"];
export const pickup = {
  station: "다온역",
  place: "2번 출구 승하차 구역",
  meetingTime: "19:05",
  parking: "다온역 동측 주차장",
  daughter: "지우",
  father: "아버지",
  car: "아이오닉 5 · 화이트",
  plate: "12가 3456",
};

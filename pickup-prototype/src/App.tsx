import { useReducer, useState } from "react";
import type { ReactNode } from "react";
import PickupMap from "./components/PickupMap";
import { steps, phases, pickup } from "./scenarios/stationPickup";
import {
  initialState,
  scenarioReducer,
  isSharing,
  voiceAnswer,
} from "./state/scenario";
import type { Action } from "./state/scenario";

function Icon({ name, size = 20 }: { name: string; size?: number }) {
  const paths: Record<string, ReactNode> = {
    arrow: <path d="M5 12h14m-5-5 5 5-5 5" />,
    pin: (
      <>
        <path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z" />
        <circle cx="12" cy="10" r="2" />
      </>
    ),
    car: (
      <>
        <path d="m4 10 2-6h12l2 6v9H4Zm0 0h16M7 19v2m10-2v2M7 14h2m6 0h2" />
      </>
    ),
    link: (
      <>
        <path
          d="m10 14 4-4m-6 6-1 1a4 4 0 0 1-6-6l5-5a4 4 0 0 1 6 0m0 2 1-1a4 4 0 0 1 6 6l-5 5a4 4 0 0 1-6 0"
          transform="translate(2 0)"
        />
      </>
    ),
    clock: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="M12 6v6l4 2" />
      </>
    ),
    reset: (
      <>
        <path d="M4 10a8 8 0 1 1 1 8M4 4v6h6" />
      </>
    ),
    check: <path d="m5 12 4 4L19 6" />,
    volume: (
      <>
        <path d="M4 9h4l5-4v14l-5-4H4Zm13-1a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14" />
      </>
    ),
    phone: (
      <>
        <rect x="6" y="2" width="12" height="20" rx="3" />
        <path d="M10 18h4" />
      </>
    ),
    train: (
      <>
        <rect x="5" y="3" width="14" height="15" rx="4" />
        <path d="M5 10h14M12 3v7M8 18l-3 4m11-4 3 4M8 14h1m6 0h1" />
      </>
    ),
    pause: (
      <>
        <path d="M9 5v14M15 5v14" />
      </>
    ),
    menu: (
      <>
        <path d="M4 6h16M4 12h16M4 18h16" />
      </>
    ),
    shield: (
      <>
        <path d="m12 2 8 3v6c0 5-8 11-8 11S4 16 4 11V5Z" />
        <path d="m8 11 3 3 5-5" />
      </>
    ),
    info: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="M12 11v6m0-10v1" />
      </>
    ),
  };
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.65"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {paths[name] ?? paths.pin}
    </svg>
  );
}

export default function App() {
  const [state, dispatch] = useReducer(scenarioReducer, initialState);
  const [view, setView] = useState("both");
  const [voice, setVoice] = useState("");
  const [modal, setModal] = useState<"invite" | "scenario" | null>(null);
  const [toast, setToast] = useState("");
  const step = steps[state.stage];
  const sharing = isSharing(state);
  const notify = (message: string) => {
    setToast(message);
    window.setTimeout(() => setToast(""), 3500);
  };
  const act = (action: Action) => {
    dispatch(action);
    setVoice("");
  };
  const invitation = `아빠가 마중 갈게요. ${pickup.station} ${pickup.place}, 오늘 ${pickup.meetingTime}에 만나요. [프로토타입 초대 예시]`;
  const copyInvite = async () => {
    try {
      await navigator.clipboard.writeText(invitation);
      notify(
        "초대 문구를 복사했어요. 실제 초대 링크 전송은 연결하지 않았어요.",
      );
    } catch {
      notify(
        "자동 복사를 사용할 수 없어요. 초대창의 문구를 선택해서 복사해 주세요.",
      );
    }
  };
  const reset = () => {
    act("reset");
    setModal(null);
    notify("시나리오를 처음부터 시작합니다.");
  };
  const eta =
    state.stage <= 2
      ? "19:05"
      : state.stage <= 4
        ? "19:05"
        : state.stage <= 6
          ? "19:11"
          : state.parked
            ? "도착"
            : "19:11";
  const statusText =
    state.stage < 3
      ? "공유 시작 전"
      : state.stage === 8
        ? "공유 종료"
        : "위치 공유 중";
  const driverAction: Partial<
    Record<number, { text: string; action: Action; icon: string }>
  > = {
    0: { text: "픽업 초대 만들기", action: "invite", icon: "link" },
    2: { text: "픽업 시작 · 경로 안내", action: "start", icon: "arrow" },
    4: { text: "동측 주차장에서 대기", action: "wait", icon: "pause" },
    6: { text: "픽업 장소로 다시 출발", action: "approach", icon: "arrow" },
  };
  const action = driverAction[state.stage];
  const passengerTitle =
    state.stage < 2
      ? "아빠가 마중 올게요"
      : state.stage === 2
        ? "약속이 정해졌어요"
        : state.stage < 4
          ? "아빠가 오고 있어요"
          : state.stage < 6
            ? "서두르지 않아도 괜찮아요"
            : state.stage < 8
              ? "이제 만날 시간이에요"
              : "잘 왔어, 지우야";

  return (
    <div className="app-shell">
      <header className="site-header">
        <a
          className="brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            reset();
          }}
          aria-label="마중 처음으로"
        >
          <span className="brand-symbol">
            m<span>↗</span>
          </span>
          <strong>
            마중<span>MEETWAY</span>
          </strong>
        </a>
        <div className="header-center">
          <span className="live-dot" />
          INTERACTIVE PROTOTYPE <span className="header-sep">/</span> SCENARIO
          01
        </div>
        <button className="text-button" onClick={() => setModal("scenario")}>
          시나리오 살펴보기 <Icon name="arrow" size={16} />
        </button>
      </header>

      <main>
        <section className="intro">
          <div>
            <div className="eyebrow">서로의 시간을 맞추는 픽업</div>
            <h1>
              오랜만에 오는 딸,
              <br className="mobile-break" /> 오늘은 <span>엇갈림 없이.</span>
            </h1>
            <p>
              금요일 저녁, 다온역. 아버지와 지우의 만남을 함께 만들어 보세요.
            </p>
          </div>
          <div className="scenario-people">
            <div className="avatar father">아</div>
            <span className="people-line">
              ···
              <Icon name="car" size={20} />
              ···
            </span>
            <div className="avatar daughter">지</div>
            <span className="people-label">
              아버지 <span>↔</span> 딸 지우
            </span>
          </div>
        </section>

        <section className="journey" aria-label="시나리오 진행 단계">
          {phases.map((phase, i) => (
            <div
              className={`phase ${step.phase === i ? "active" : step.phase > i ? "done" : ""}`}
              key={phase}
            >
              <span>
                {step.phase > i ? <Icon name="check" size={14} /> : `0${i + 1}`}
              </span>
              <div>
                {phase}
                <small>
                  {
                    [
                      "약속을 정하고",
                      "서로를 확인하고",
                      "시간을 맞추고",
                      "함께 출발해요",
                    ][i]
                  }
                </small>
              </div>
              {i < 3 && <b>→</b>}
            </div>
          ))}
        </section>

        <section className="experience-heading">
          <div>
            <span className="section-index">LIVE DEMO</span>
            <h2>하나의 약속, 두 개의 화면</h2>
          </div>
          <div className="view-switch" role="group" aria-label="화면 보기 선택">
            {[
              ["both", "함께 보기"],
              ["driver", "운전자"],
              ["passenger", "탑승자"],
            ].map(([key, label]) => (
              <button
                key={key}
                aria-pressed={view === key}
                className={view === key ? "selected" : ""}
                onClick={() => setView(key)}
              >
                {label}
              </button>
            ))}
          </div>
        </section>

        <div className={`devices view-${view}`}>
          <section className="driver-area" aria-label="아버지 차량 화면">
            <div className="device-label">
              <span>
                <Icon name="car" size={17} /> 아버지의 차량
              </span>
              <small>PLEOS CONNECT CONCEPT</small>
            </div>
            <div className="cockpit">
              <div className="cockpit-top">
                <span className="cockpit-wordmark">
                  마중<span>픽업 어시스턴트</span>
                </span>
                <div>
                  <span className={`sharing ${sharing ? "on" : ""}`}>
                    <i />
                    {statusText}
                  </span>
                  <strong>{step.time}</strong>
                </div>
              </div>
              <div className="cockpit-content">
                <aside className="trip-panel">
                  <div className="trip-eyebrow">오늘의 픽업</div>
                  <h3>
                    딸 마중 가기 <span>↗</span>
                  </h3>
                  <div className="trip-person">
                    <span className="mini-avatar">지</span>
                    <div>
                      지우
                      <small>
                        {state.stage < 2
                          ? "약속 확인 대기"
                          : state.stage < 4
                            ? "위치 공유 전"
                            : state.stage < 6
                              ? "준비 중 · 5분 후 출발"
                              : state.stage < 8
                                ? "2번 출구로 이동 중"
                                : "탑승 완료"}
                      </small>
                    </div>
                  </div>
                  <div className="destination">
                    <Icon
                      name={state.stage === 5 ? "pause" : "pin"}
                      size={19}
                    />
                    <div>
                      <small>
                        {state.stage === 5
                          ? "선택한 대기 장소"
                          : "약속한 만남 지점"}
                      </small>
                      <b>
                        {state.stage === 5 ? "동측 주차장" : "다온역 2번 출구"}
                      </b>
                      <span>
                        {state.stage === 5
                          ? "픽업 준비를 기다리고 있어요"
                          : "지정 승하차 구역"}
                      </span>
                    </div>
                  </div>
                  <div className="eta-block">
                    <small>
                      {state.stage === 8
                        ? "마중 완료"
                        : state.stage === 5
                          ? "주차장 대기"
                          : "픽업 도착 예상"}
                    </small>
                    <strong>
                      {state.stage === 8
                        ? "함께 귀가"
                        : state.stage === 5
                          ? "잠시 쉬어가요"
                          : eta}
                      <span>{state.stage < 7 ? "예상" : ""}</span>
                    </strong>
                    <p>
                      {state.stage === 8
                        ? "위치 공유를 종료했어요"
                        : state.stage === 4
                          ? "지우의 준비 시간과 차이가 있어요"
                          : state.stage === 6
                            ? "지우 도보 3분 · 차량 3분"
                            : state.stage === 5
                              ? "지우가 나오면 알려드릴게요"
                              : "약속 시간 19:05"}
                    </p>
                  </div>
                  <div className="trip-bottom">
                    <span className="tiny-status" />
                    {state.stage === 8
                      ? "픽업 종료"
                      : state.parked
                        ? "운전자 정차 확인"
                        : state.stage === 5 ||
                            state.stage === 6 ||
                            state.stage < 3
                          ? "정차 시나리오"
                          : "이동 시나리오"}
                  </div>
                </aside>
                <div className="map-area">
                  <PickupMap state={state} />
                  <div className="map-top-pill">
                    <span className="live-dot" />
                    {state.stage === 8
                      ? "만남 완료"
                      : state.stage === 5
                        ? "대기 경로 안내"
                        : "약속 장소로 경로 안내"}
                    <span className="sim-label">SIM</span>
                  </div>
                  {state.stage === 8 && (
                    <div className="complete-map">
                      <div className="complete-check">
                        <Icon name="check" size={28} />
                      </div>
                      <h3>반가운 만남, 마중 완료.</h3>
                      <p>두 사람의 위치 공유가 끝났어요.</p>
                    </div>
                  )}
                  <div className="map-legend">
                    <span>
                      <i className="car-dot" />
                      아버지 차량
                    </span>
                    <span>
                      <i className="person-dot" />
                      지우{state.stage < 4 ? " · 공유 전" : ""}
                    </span>
                  </div>
                </div>
              </div>
              <div className="voice-area">
                <span className="gleo-icon">
                  <i />
                  <i />
                  <i />
                  <i />
                  <i />
                </span>
                <div>
                  <b>
                    Gleo <span>음성 응답 시뮬레이션</span>
                  </b>
                  <p>
                    {voice ||
                      (state.stage === 4 || state.stage === 6
                        ? voiceAnswer(state)
                        : state.stage === 8
                          ? "잘 왔어요. 이제 함께 집으로 돌아가세요."
                          : "“지우 어디 있어?”를 눌러 픽업 상황을 확인해 보세요.")}
                  </p>
                </div>
                <button
                  className="voice-query"
                  aria-label="지우 어디 있어?"
                  onClick={() => setVoice(voiceAnswer(state))}
                >
                  <Icon name="volume" size={16} />
                  <span>지우 어디 있어?</span>
                </button>
              </div>
              <div className="driver-actions">
                {action ? (
                  <button
                    className="button lime"
                    onClick={() => {
                      act(action.action);
                      if (action.action === "invite") setModal("invite");
                    }}
                  >
                    {action.text}
                    <Icon name={action.icon} size={17} />
                  </button>
                ) : state.stage === 7 ? (
                  <button
                    className="button lime"
                    disabled={state.parked}
                    onClick={() => act("arrive")}
                  >
                    {state.parked
                      ? "지우에게 정차 완료 전달됨"
                      : "정차 완료 알리기"}
                    <Icon name="check" size={17} />
                  </button>
                ) : state.stage === 8 ? (
                  <button className="button lime" onClick={reset}>
                    다시 체험하기
                    <Icon name="reset" size={17} />
                  </button>
                ) : (
                  <div className="waiting-action">
                    <span className="pulse-dot" />
                    {state.stage === 1
                      ? "지우의 약속 수락을 기다려요"
                      : state.stage === 3
                        ? "지우의 준비 상태를 기다려요"
                        : "지우가 나오기 시작하면 알려드려요"}
                  </div>
                )}
                <button
                  className="icon-button"
                  onClick={() =>
                    notify(
                      "이 데모는 실제 내비게이션·차량 제어에 연결되지 않았어요.",
                    )
                  }
                  aria-label="시뮬레이션 안내"
                >
                  <Icon name="info" size={19} />
                </button>
              </div>
            </div>
            <div className="device-caption">
              <Icon name="shield" size={14} /> 실제 운전 없이 체험하는
              시뮬레이션입니다. 모든 위치·시간·차량 상태는 가상 데이터입니다.
            </div>
          </section>

          <section className="passenger-area" aria-label="딸의 휴대폰 화면">
            <div className="device-label">
              <span>
                <Icon name="phone" size={17} /> 지우의 휴대폰
              </span>
              <small>모바일 웹</small>
            </div>
            <div className="phone">
              <div className="phone-status">
                <strong>{step.time}</strong>
                <div className="island" />
                <span>▮▮▮ ▰</span>
              </div>
              <div className="phone-content">
                <div className="phone-brand">
                  마중<span>나의 픽업</span>
                </div>
                <span
                  className={`phone-pill ${state.stage === 8 ? "ended" : ""}`}
                >
                  <span className="live-dot" />
                  {state.stage < 2 ? "아빠의 초대" : statusText}
                </span>
                <h3>{passengerTitle}</h3>
                <p className="phone-subtitle">
                  {state.stage < 2
                    ? "오랜만에 오는 길, 편하게 와."
                    : state.stage === 8
                      ? "이번 약속의 위치 공유가 종료됐어요."
                      : state.stage < 4
                        ? "캐리어 잘 챙기고, 조심히 나와."
                        : state.stage === 4
                          ? "아빠에게 준비 상태를 전달했어요."
                          : state.stage < 6
                            ? "아빠는 가까운 주차장에서 기다릴게."
                            : "약속한 승하차 구역에서 만나요."}
                </p>
                <div className="phone-map">
                  <PickupMap state={state} compact />
                  {state.stage < 3 && (
                    <span className="phone-map-note">
                      출발하면 차량 위치가 표시돼요
                    </span>
                  )}
                </div>
                <div className="phone-meeting">
                  <Icon name="pin" size={17} />
                  <div>
                    <b>다온역 2번 출구</b>
                    <span>지정 승하차 구역 · 약속 19:05</span>
                  </div>
                </div>
                <div className="phone-progress">
                  <span>
                    {state.stage === 8
                      ? "픽업 완료"
                      : state.parked
                        ? "아빠가 정차했어요"
                        : state.stage === 5
                          ? "아빠는 주차장에서 대기 중"
                          : state.stage >= 3
                            ? "아빠 도착 예상"
                            : "약속한 시간"}
                  </span>
                  <strong>
                    {state.stage === 8
                      ? "✓"
                      : state.stage === 5
                        ? "대기 중"
                        : eta}
                  </strong>
                </div>
                {state.stage === 4 || state.stage === 5 ? (
                  <div className="phone-note">
                    <Icon name="pause" size={16} />
                    <p>
                      준비 중이라고 알려드렸어요.
                      <br />
                      출발할 때 ‘나오는 중’을 눌러 주세요.
                    </p>
                  </div>
                ) : state.stage >= 6 && state.stage < 8 ? (
                  <div className="car-info">
                    <Icon name="car" size={22} />
                    <div>
                      <b>{pickup.plate}</b>
                      <span>{pickup.car}</span>
                    </div>
                  </div>
                ) : (
                  <div className="phone-note">
                    <Icon name="shield" size={16} />
                    <p>
                      {state.stage === 8
                        ? "픽업 링크가 만료됐어요. 위치 정보는 더 이상 공유하지 않아요."
                        : "이 약속에 참여한 사람끼리만 위치를 공유해요."}
                    </p>
                  </div>
                )}
                <div className="passenger-actions">
                  {state.stage === 0 && (
                    <button className="button phone-button" disabled>
                      아빠가 초대를 준비 중이에요
                    </button>
                  )}
                  {state.stage === 1 && (
                    <>
                      <button
                        className="button phone-button"
                        onClick={() => act("accept")}
                      >
                        약속 확인 · 초대 수락
                        <Icon name="check" size={17} />
                      </button>
                      <p className="consent">
                        데모 수락입니다. 실제 위치 권한은 요청하지 않아요.
                      </p>
                    </>
                  )}
                  {state.stage === 2 && (
                    <button className="button phone-button" disabled>
                      아빠의 출발을 기다려요
                    </button>
                  )}
                  {state.stage === 3 && (
                    <button
                      className="button phone-button"
                      onClick={() => act("delay")}
                    >
                      준비 중 · 5분 후 출발
                      <Icon name="clock" size={17} />
                    </button>
                  )}
                  {state.stage === 4 && (
                    <button className="button phone-button" disabled>
                      아빠가 대기 장소를 확인 중이에요
                    </button>
                  )}
                  {state.stage === 5 && (
                    <button
                      className="button phone-button"
                      onClick={() => act("ready")}
                    >
                      이제 나오는 중이에요
                      <Icon name="arrow" size={17} />
                    </button>
                  )}
                  {state.stage === 6 && (
                    <button className="button phone-button" disabled>
                      아빠에게 이동 상태를 알렸어요
                    </button>
                  )}
                  {state.stage === 7 && (
                    <button
                      className="button phone-button"
                      disabled={!state.parked}
                      onClick={() => act("board")}
                    >
                      {state.parked
                        ? "차량 확인 · 탑승 완료"
                        : "차량이 아직 접근 중이에요"}
                      <Icon name="check" size={17} />
                    </button>
                  )}
                  {state.stage === 8 && (
                    <button className="button phone-button" onClick={reset}>
                      다시 체험하기
                      <Icon name="reset" size={17} />
                    </button>
                  )}
                </div>
                <div className="phone-footer">
                  초대 링크로 참여 · 앱 설치 없이
                </div>
              </div>
              <div className="home-indicator" />
            </div>
          </section>
        </div>

        <section className="demo-guide" aria-live="polite">
          <div className="guide-number">
            {String(state.stage + 1).padStart(2, "0")}
            <span>/09</span>
          </div>
          <div className="guide-copy">
            <span>{step.title}</span>
            <h3>{step.instruction}</h3>
            <p>{step.description}</p>
          </div>
          <button
            className="reset-button"
            aria-label="처음으로"
            onClick={reset}
          >
            <Icon name="reset" size={16} />
            <span>처음으로</span>
          </button>
        </section>
        <section className="event-log">
          <div>
            <span className="section-index">SESSION LOG</span>
            <h3>함께 맞춰가는 기록</h3>
          </div>
          <div aria-live="polite">
            {state.history.length ? (
              state.history
                .slice(-3)
                .reverse()
                .map((entry, i) => (
                  <p className={i === 0 ? "latest" : ""} key={entry}>
                    <span className="log-dot" />
                    {entry}
                  </p>
                ))
            ) : (
              <p className="empty-log">
                첫 초대를 만들면, 두 사람의 픽업 기록이 여기에 쌓여요.
              </p>
            )}
          </div>
        </section>
      </main>
      <footer className="site-footer">
        <span>
          마중 <b>MEETWAY</b>
        </span>
        <p>픽업의 마지막 순간까지, 서로의 시간을 맞춥니다.</p>
        <small>CONCEPT PROTOTYPE · 실제 Pleos / Gleo 연동 전</small>
      </footer>
      {toast && (
        <div className="toast" role="status">
          <Icon name="info" size={17} />
          {toast}
        </div>
      )}
      {modal && (
        <div className="modal-backdrop" onClick={() => setModal(null)}>
          <section
            role="dialog"
            aria-modal="true"
            aria-labelledby="modal-title"
            className="modal"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              autoFocus
              className="modal-close"
              aria-label="닫기"
              onClick={() => setModal(null)}
            >
              ×
            </button>
            <span className="eyebrow">
              {modal === "invite" ? "PICKUP INVITATION" : "SCENARIO 01"}
            </span>
            <h2 id="modal-title">
              {modal === "invite"
                ? "지우에게 보낼 마중 초대"
                : "오랜만에 본가에 오는 딸"}
            </h2>
            {modal === "invite" ? (
              <>
                <p>장소와 시간을 함께 확인하는 한 번의 픽업 약속이에요.</p>
                <div className="invite-card">
                  <Icon name="train" size={27} />
                  <h3>다온역에서 만나요</h3>
                  <p>2번 출구 지정 승하차 구역</p>
                  <strong>오늘 저녁 7:05</strong>
                  <div className="fake-link">meetway.demo / pickup / jiu</div>
                </div>
                <p className="modal-note">
                  초대 예시입니다. 아래 버튼은 문구만 복사하며 실제 서버
                  세션이나 외부 초대를 만들지 않아요.
                </p>
                <button className="button phone-button" onClick={copyInvite}>
                  초대 문구 복사
                  <Icon name="link" size={17} />
                </button>
                <button
                  className="text-button modal-continue"
                  onClick={() => setModal(null)}
                >
                  딸의 휴대폰에서 수락하기 →
                </button>
              </>
            ) : (
              <>
                <p>
                  금요일 저녁 7시. 캐리어를 든 지우와 차로 마중 나온 아버지.
                  준비 시간이 어긋나도 전화 대신 상태를 공유하고, 주차장 대기와
                  재접근으로 만나요.
                </p>
                <ol className="scenario-list">
                  {[
                    "초대 링크로 장소·시간 확인",
                    "차량에서 출발 · 위치 공유 시작",
                    "딸의 엘리베이터 대기 지연",
                    "아버지의 주차장 대기 선택",
                    "딸의 출발에 맞춰 재접근",
                    "정차 확인 · 탑승 · 공유 종료",
                  ].map((x) => (
                    <li key={x}>{x}</li>
                  ))}
                </ol>
                <p className="modal-note">
                  가상 역·시간을 사용하는 단일 브라우저 데모입니다. 두 화면은
                  로컬 상태를 공유하며, 실제 GPS·메시지·차량 제어·음성 인식은
                  연결되지 않았어요.
                </p>
                <button
                  className="button phone-button"
                  onClick={() => setModal(null)}
                >
                  체험 계속하기
                  <Icon name="arrow" size={17} />
                </button>
              </>
            )}
          </section>
        </div>
      )}
    </div>
  );
}

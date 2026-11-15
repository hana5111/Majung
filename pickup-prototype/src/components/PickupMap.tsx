import { useId } from "react";
import type { ScenarioState } from "../state/scenario";

export default function PickupMap({
  state,
  compact = false,
}: {
  state: ScenarioState;
  compact?: boolean;
}) {
  const id = useId().replace(/:/g, "");
  const waiting = state.stage === 5 || state.stage === 6;
  const car =
    state.stage < 3
      ? [145, 340]
      : state.stage <= 4
        ? [285, 285]
        : waiting
          ? [175, 200]
          : [455, 220];
  const person =
    state.stage < 6 ? [530, 110] : state.stage === 6 ? [525, 160] : [493, 220];
  return (
    <svg
      className={`pickup-map ${compact ? "compact" : ""}`}
      viewBox="0 0 680 440"
      role="img"
      aria-label={`가상 다온역 지도. ${waiting ? "차량은 동측 주차장에서 대기 중" : "차량은 약속한 승하차 구역으로 이동"}`}
    >
      <defs>
        <pattern
          id={`grid-${id}`}
          width="34"
          height="34"
          patternUnits="userSpaceOnUse"
        >
          <path d="M34 0H0V34" fill="none" stroke="#263d3e" strokeWidth=".5" />
        </pattern>
        <filter id={`shadow-${id}`}>
          <feDropShadow dx="0" dy="3" stdDeviation="6" floodOpacity=".35" />
        </filter>
      </defs>
      <rect width="680" height="440" fill="#172f31" />
      <rect width="680" height="440" fill={`url(#grid-${id})`} />
      <g fill="#203b3b" stroke="#2b4848" strokeWidth="1">
        <rect x="32" y="36" width="115" height="88" rx="12" />
        <rect x="177" y="35" width="135" height="88" rx="12" />
        <rect x="34" y="159" width="83" height="100" rx="12" />
        <rect x="340" y="40" width="64" height="91" rx="12" />
        <rect x="349" y="286" width="102" height="108" rx="12" />
        <rect x="485" y="286" width="135" height="107" rx="12" />
        <rect x="35" y="300" width="67" height="91" rx="12" />
        <rect x="633" y="36" width="80" height="166" rx="12" />
      </g>
      <path
        d="M-20 281H302L349 240H700M145 460V153H349V-20M630 460V260M324 460V280"
        stroke="#0e2528"
        strokeWidth="30"
        fill="none"
        strokeLinejoin="round"
      />
      <path
        d="M-20 281H302L349 240H700M145 460V153H349V-20M630 460V260M324 460V280"
        stroke="#3d5352"
        strokeWidth="20"
        fill="none"
        strokeLinejoin="round"
      />
      <path
        d="M-20 281H302L349 240H700M145 460V153H349V-20M630 460V260M324 460V280"
        stroke="#526563"
        strokeWidth="1"
        strokeDasharray="7 9"
        fill="none"
      />
      <rect
        x="440"
        y="48"
        width="163"
        height="125"
        rx="14"
        fill="#344947"
        stroke="#62716a"
      />
      <path
        d="M451 78H591M451 87H591M451 96H591"
        stroke="#63746c"
        strokeWidth="2"
      />
      <text
        x="521"
        y="131"
        textAnchor="middle"
        fill="#d8e0d8"
        fontSize="20"
        fontWeight="700"
      >
        다온역
      </text>
      <text
        x="521"
        y="151"
        textAnchor="middle"
        fill="#a8bbb1"
        fontSize="10"
        letterSpacing="2"
      >
        DAON STATION
      </text>
      <rect
        x="164"
        y="168"
        width="104"
        height="73"
        rx="12"
        fill="#30483f"
        stroke={waiting ? "#d4efa7" : "#52695a"}
        strokeWidth="1.5"
      />
      <text x="218" y="193" textAnchor="middle" fill="#b6c9b2" fontSize="13">
        P 동측 주차장
      </text>
      <text x="218" y="215" textAnchor="middle" fill="#879e90" fontSize="10">
        대기 장소
      </text>
      <rect
        x="421"
        y="210"
        width="148"
        height="48"
        rx="10"
        fill="#1c6159"
        stroke="#8cd7b7"
        strokeDasharray="4 4"
      />
      <text x="495" y="244" textAnchor="middle" fill="#d3f4df" fontSize="11">
        지정 승하차 구역
      </text>
      <text x="575" y="195" fill="#c1d3cc" fontSize="11">
        2번 출구
      </text>
      <path
        className="route-line"
        d={
          waiting
            ? "M285 281H145V201H175"
            : "M145 340V281H302L350 240H405L430 220H455"
        }
        fill="none"
        stroke="#bde58b"
        strokeWidth="5"
        strokeLinecap="round"
        strokeLinejoin="round"
        opacity={state.stage === 8 ? ".25" : "1"}
      />
      {state.stage >= 4 && state.stage < 8 && (
        <path
          d={`M${person[0]} ${person[1]}L540 190L493 220`}
          stroke="#9cd8f4"
          strokeWidth="2.5"
          strokeDasharray="4 7"
          fill="none"
        />
      )}
      <text x="46" y="146" fill="#688582" fontSize="11">
        역전공원
      </text>
      <text x="354" y="337" fill="#708984" fontSize="11">
        다온문화센터
      </text>
      <text x="165" y="368" fill="#859b95" fontSize="11">
        역전로
      </text>
      <text x="61" y="420" fill="#6f8b86" fontSize="10">
        가상 지도 · 실제 도로 안내 아님
      </text>
      {state.stage < 8 && (
        <g
          className="map-marker"
          style={{ transform: `translate(${car[0]}px, ${car[1]}px)` }}
          filter={`url(#shadow-${id})`}
        >
          <circle r="28" fill="#c8eb9a" opacity=".12" />
          <circle r="19" fill="#d0efa7" />
          <path d="M-8 6V-5L-5-10H5L8-5V6Z" fill="#20453f" />
          <path d="M-5-4H5" stroke="#d0efa7" strokeWidth="2" />
          <circle cx="-5" cy="8" r="2" fill="#20453f" />
          <circle cx="5" cy="8" r="2" fill="#20453f" />
        </g>
      )}
      {state.stage >= 4 && state.stage < 8 && (
        <g
          className="map-marker"
          style={{ transform: `translate(${person[0]}px, ${person[1]}px)` }}
        >
          <circle r="19" fill="#9ed3ed" />
          <circle cy="-5" r="4" fill="#254c60" />
          <path d="M-7 8A7 7 0 0 1 7 8" fill="#254c60" />
          <rect x="-35" y="-44" width="70" height="19" rx="9" fill="#d9edf7" />
          <text
            x="0"
            y="-31"
            textAnchor="middle"
            fill="#254c60"
            fontSize="10"
            fontWeight="700"
          >
            지우
          </text>
        </g>
      )}
      <g transform="translate(642 382)">
        <circle r="19" fill="#132a2c" stroke="#405955" />
        <path d="M0-11L6 7L0 3L-6 7Z" fill="#b9c9bf" />
        <text y="-26" textAnchor="middle" fill="#97aea4" fontSize="9">
          N
        </text>
      </g>
    </svg>
  );
}

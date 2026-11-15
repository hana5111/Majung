export function pickupEta(a, now = Date.now()) {
  if (a.pickupMode !== 'NOW') {
    const date = new Date(a.meetingAt);
    if (!Number.isFinite(date.getTime())) return { title: '시간 확인 중', detail: '' };
    return { title: new Intl.DateTimeFormat('ko-KR', { hour: 'numeric', minute: '2-digit', timeZone: 'Asia/Seoul' }).format(date),
      detail: new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric', weekday: 'long', timeZone: 'Asia/Seoul' }).format(date) };
  }
  const t = a.trip ?? {};
  if (t.state === 'ARRIVED') return { title: '목적지 도착', detail: '운전자가 도착했어요. 차량과 주변을 확인해 주세요.' };
  if (t.state === 'STOPPED') return { title: '길 안내 종료', detail: '차량의 도착 시간을 현재 확인할 수 없어요.' };
  if (!t.departedAt) return { title: '출발 준비 중', detail: '길 안내가 시작되면 도착 예상 시간을 알려드려요.' };
  const arrival = Date.parse(t.arrivalAt);
  if (t.state !== 'EN_ROUTE' || !t.etaFresh || !Number.isFinite(t.updatedAt) || now - t.updatedAt >= 45000 ||
      now - t.updatedAt < -30000 || !Number.isFinite(arrival))
    return { title: '시간 확인 중', detail: '최신 내비 정보를 기다리고 있어요. 이전 시간을 현재 값으로 표시하지 않아요.' };
  const minutes = Math.ceil(Math.max(0, arrival - now) / 60000);
  return { title: minutes > 0 ? `약 ${minutes}분 후 도착` : '곧 도착 예정',
    detail: `${new Intl.DateTimeFormat('ko-KR', { hour: 'numeric', minute: '2-digit', timeZone: 'Asia/Seoul' }).format(new Date(arrival))} 도착 예상 · 차량 내비 기준` };
}

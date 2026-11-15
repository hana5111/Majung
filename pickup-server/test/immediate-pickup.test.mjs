import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { createStore } from '../src/store.mjs';
import { makeServer } from '../src/server.mjs';
import { pickupEta } from '../../pickup-web/eta.js';

const target = { latitude: 37.265, longitude: 127.001, name: '테스트역', address: '테스트 목적지',
  displayLatitude: null, displayLongitude: null, poiId: 'test', poiSubId: '', poiProvider: 'test' };
function fixture() {
  let clock = Date.parse('2026-11-15T00:00:00Z');
  const store = createStore(null, () => clock);
  const driver = store.register({ displayName: '테스트 운전자' }).accessToken;
  const make = () => store.create(driver, { passengerName: '지우', place: '테스트역', pickupMode: 'NOW', pickupLocation: target, clientRequestId: randomUUID() });
  const publish = (a, extra = {}) => store.updateTrip(driver, a.id, { state: 'EN_ROUTE', pickupLocation: target, observedAt: clock, remainingSeconds: 720, ...extra });
  return { store, driver, make, publish, time: () => clock, advance: ms => { clock += ms; } };
}
test('탑승자와 목적지만 생성; 출발 전에는 수락할 수 없고 날짜를 임의 생성하지 않음', () => {
  const f = fixture(); const a = f.make();
  assert.equal(a.meetingAt, null); assert.equal(a.meetingPoint, ''); assert.equal(a.pickupMode, 'NOW');
  assert.equal(a.trip.state, 'NOT_STARTED'); assert.equal(a.trip.arrivalAt, null);
  assert.throws(() => f.store.respond(a.inviteToken, 'accept', a.version), { status: 409 });
});
test('실제 목적지의 ETA만 초대에 반영; 도착 시간 갱신 중에도 탑승자 수락 버전 유지', () => {
  const f = fixture(); const a = f.make();
  const started = f.publish(a);
  assert.equal(started.trip.arrivalAt, '2026-11-15T00:12:00.000Z');
  assert.equal(started.version, a.version); assert.equal(started.trip.etaFresh, true);
  f.advance(10000); f.publish(a, { remainingSeconds: 900 });
  const accepted = f.store.respond(a.inviteToken, 'accept', a.version);
  assert.equal(accepted.status, 'CONFIRMED');
  assert.equal(accepted.trip.arrivalAt, '2026-11-15T00:15:10.000Z');
  assert.equal(accepted.inviteToken, undefined); assert.equal(accepted.passengerContact, undefined);
});
test('갱신이 끊기면 서버와 탑승자 화면 모두 오래된 ETA를 감춤', () => {
  const f = fixture(); const a = f.make(); f.publish(a);
  const cached = f.store.getInvitation(a.inviteToken);
  assert.match(pickupEta(cached, f.time()).title, /12분/);
  f.advance(45000);
  const stale = f.store.getInvitation(a.inviteToken);
  assert.equal(stale.trip.etaFresh, false); assert.equal(stale.trip.arrivalAt, null);
  assert.equal(pickupEta(cached, f.time()).title, '시간 확인 중');
  assert.equal(pickupEta(stale, f.time()).title, '시간 확인 중');
});
test('다른 차량·탑승자 토큰·잘못된 좌표·오래된 측정·잘못된 시간은 거부', () => {
  const f = fixture(); const a = f.make();
  const payload = { state: 'EN_ROUTE', pickupLocation: target, observedAt: f.time(), remainingSeconds: 720 };
  const other = f.store.register({}).accessToken;
  assert.throws(() => f.store.updateTrip(other, a.id, payload), { status: 404 });
  assert.throws(() => f.store.updateTrip(a.inviteToken, a.id, payload), { status: 401 });
  for (const invalid of [{ pickupLocation: { ...target, longitude: 128 } }, { observedAt: f.time() - 31000 },
    { remainingSeconds: -1 }, { remainingSeconds: 86401 }, { remainingSeconds: '720' }, { state: 'FAKE' }])
    assert.throws(() => f.publish(a, invalid));
});
test('늦게 도착한 측정으로 새 ETA를 덮어쓰지 않음; 목적지 변경 시 공유를 초기화', () => {
  const f = fixture(); const a = f.make(); f.publish(a); f.advance(10000);
  const latest = f.publish(a, { remainingSeconds: 600 });
  const delayed = f.publish(a, { observedAt: f.time() - 5000, remainingSeconds: 999 });
  assert.equal(delayed.trip.arrivalAt, latest.trip.arrivalAt);
  const changed = f.store.setPickupLocation(f.driver, a.id, { expectedVersion: a.version, pickupLocation: { ...target, longitude: 127.01 } });
  assert.equal(changed.trip.state, 'NOT_STARTED'); assert.equal(changed.trip.arrivalAt, null);
  assert.throws(() => f.publish(a), { status: 409 });
});
test('내비 종료·도착 시 ETA 제거; 취소된 픽업의 갱신 거부', () => {
  const f = fixture(); const a = f.make(); f.publish(a); f.advance(1000);
  const arrived = f.publish(a, { state: 'ARRIVED' });
  assert.equal(arrived.trip.arrivalAt, null); assert.equal(pickupEta(arrived, f.time()).title, '목적지 도착');
  f.store.cancel(f.driver, a.id);
  assert.throws(() => f.publish(a), { status: 409 });
});
test('실제 HTTP 경로로 출발·ETA 갱신·탑승자 조회·수락', async t => {
  const server = makeServer(); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => new Promise(r => { server.close(r); server.closeAllConnections(); }));
  const api = async (method, path, key, data) => {
    const r = await fetch(`http://127.0.0.1:${server.address().port}${path}`, { method,
      headers: { Authorization: `Bearer ${key ?? ''}`, 'Content-Type': 'application/json' }, body: data ? JSON.stringify(data) : undefined });
    assert.ok(r.ok, `${path}: ${r.status}`); return r.json();
  };
  const driver = (await api('POST', '/api/drivers', null, {})).accessToken;
  const a = await api('POST', '/api/appointments', driver, { passengerName: '지우', place: '테스트역', pickupLocation: target });
  await api('POST', `/api/appointments/${a.id}/trip`, driver, { pickupLocation: target, state: 'EN_ROUTE', observedAt: Date.now(), remainingSeconds: 720 });
  const passenger = await api('GET', '/api/invitation', a.inviteToken);
  assert.equal(passenger.trip.etaFresh, true); assert.equal(passenger.meetingAt, null);
  assert.equal((await api('POST', '/api/invitation/respond', a.inviteToken, { decision: 'accept', expectedVersion: passenger.version })).status, 'CONFIRMED');
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import { createStore } from '../src/store.mjs';
import { makeServer } from '../src/server.mjs';

const details = () => ({ passengerName: '지우', passengerContact: '010-0000-0000', place: '테스트역', meetingPoint: '동측 승차장', meetingAt: new Date(Date.now() + 3600000).toISOString(), note: '기능 검증용 약속' });
const selectedLocation = () => ({ latitude: 37.265, longitude: 127.001, displayLatitude: null, displayLongitude: null,
  name: '테스트역 동측 승차장', address: '검증용 주소', poiId: 'test-poi', poiSubId: '0', poiProvider: 'test-provider' });
async function fixture(t) {
  const server = makeServer(); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  const base = `http://127.0.0.1:${server.address().port}`;
  const api = async (method, route, key, data) => {
    const r = await fetch(base + route, { method, headers: { ...(key ? { Authorization: `Bearer ${key}` } : {}), ...(data ? { 'Content-Type': 'application/json' } : {}) }, ...(data ? { body: JSON.stringify(data) } : {}) });
    return { code: r.status, data: await r.json() };
  };
  const driver = (await api('POST', '/api/drivers', null, { displayName: '아빠' })).data.accessToken;
  return { base, api, driver };
}

test('차량 생성 → 별도 탑승자 수락 → 차량 목록 확정; 중복 수락은 같은 결과', async t => {
  const { api, driver } = await fixture(t);
  const made = await api('POST', '/api/appointments', driver, details());
  assert.equal(made.code, 201); assert.equal(made.data.status, 'PENDING');
  const invitation = await api('GET', '/api/invitation', made.data.inviteToken);
  assert.equal(invitation.data.driverName, '아빠');
  assert.equal(invitation.data.inviteToken, undefined);
  assert.equal(invitation.data.passengerContact, undefined);
  assert.equal(invitation.data.driverId, undefined);
  const accepted = await api('POST', '/api/invitation/respond', made.data.inviteToken, { decision: 'accept' });
  assert.equal(accepted.code, 200); assert.equal(accepted.data.status, 'CONFIRMED');
  const twice = await api('POST', '/api/invitation/respond', made.data.inviteToken, { decision: 'accept' });
  assert.equal(twice.data.version, accepted.data.version);
  const list = await api('GET', '/api/appointments', driver);
  assert.equal(list.data.appointments[0].status, 'CONFIRMED');
});

test('차량 권한과 탑승자 권한 분리; 다른 차량은 목록·취소 접근 불가', async t => {
  const { api, driver } = await fixture(t);
  const a = (await api('POST', '/api/appointments', driver, details())).data;
  const other = (await api('POST', '/api/drivers', null, { displayName: '다른 운전자' })).data.accessToken;
  assert.equal((await api('GET', '/api/appointments')).code, 401);
  assert.equal((await api('GET', '/api/appointments', a.inviteToken)).code, 401);
  assert.equal((await api('GET', '/api/invitation', driver)).code, 404);
  assert.deepEqual((await api('GET', '/api/appointments', other)).data.appointments, []);
  assert.equal((await api('POST', `/api/appointments/${a.id}/cancel`, other, {})).code, 404);
});

test('취소한 링크로 수락 불가; 탑승자 화면에서도 취소 확인', async t => {
  const { api, driver } = await fixture(t);
  const a = (await api('POST', '/api/appointments', driver, details())).data;
  assert.equal((await api('POST', `/api/appointments/${a.id}/cancel`, driver, {})).data.status, 'CANCELLED');
  assert.equal((await api('POST', '/api/invitation/respond', a.inviteToken, { decision: 'accept' })).code, 409);
  assert.equal((await api('GET', '/api/invitation', a.inviteToken)).data.status, 'CANCELLED');
});

test('거절 이후 수락으로 변경 불가', async t => {
  const { api, driver } = await fixture(t);
  const a = (await api('POST', '/api/appointments', driver, details())).data;
  assert.equal((await api('POST', '/api/invitation/respond', a.inviteToken, { decision: 'decline' })).data.status, 'DECLINED');
  assert.equal((await api('POST', '/api/invitation/respond', a.inviteToken, { decision: 'accept' })).code, 409);
});

test('잘못된 약속·과거 시간·잘못된 연락처는 저장하지 않음', async t => {
  const { api, driver } = await fixture(t);
  for (const change of [{ passengerName: ' ' }, { place: '' }, { meetingPoint: '' }, { meetingAt: 'invalid' }, { meetingAt: new Date(Date.now() - 1000).toISOString() }, { passengerContact: '<script>' }]) {
    assert.equal((await api('POST', '/api/appointments', driver, { ...details(), ...change })).code, 400);
  }
  assert.deepEqual((await api('GET', '/api/appointments', driver)).data.appointments, []);
});

test('약속 시간 + 1시간 후 만료; 이미 확정된 약속도 만료', () => {
  let now = Date.now();
  const store = createStore(null, () => now);
  const d = store.register({}).accessToken;
  const a = store.create(d, details()); const b = store.create(d, details());
  store.respond(b.inviteToken, 'accept'); now = a.expiresAt + 1;
  assert.equal(store.getInvitation(a.inviteToken).status, 'EXPIRED');
  assert.equal(store.getInvitation(b.inviteToken).status, 'EXPIRED');
  assert.throws(() => store.respond(a.inviteToken, 'accept'), /종료된 약속/);
});

test('서버 저장소를 다시 열어도 차량 연결·약속·수락 상태 유지', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'ddakta-test-'));
  try {
    const file = path.join(dir, 'appointments.json');
    const first = createStore(file); const d = first.register({}).accessToken;
    const a = first.create(d, details()); first.respond(a.inviteToken, 'accept');
    const second = createStore(file);
    assert.equal(second.list(d)[0].status, 'CONFIRMED');
    assert.equal(second.getInvitation(a.inviteToken).id, a.id);
  } finally { rmSync(dir, { recursive: true }); }
});

test('탑승자 페이지 자산 제공; 토큰 없는 요청과 임의 파일 접근 제한', async t => {
  const { base, api } = await fixture(t);
  const page = await fetch(base + '/invite');
  assert.match(await page.text(), /약속 수락하기/);
  assert.match(page.headers.get('content-security-policy'), /frame-ancestors 'none'/);
  assert.equal(page.headers.get('cache-control'), 'no-store');
  assert.equal((await api('GET', '/api/invitation')).code, 404);
  assert.equal((await fetch(base + '/src/store.mjs')).status, 404);
});

test('같은 생성 요청 재전송은 같은 약속·링크를 반환; 다른 내용은 거부', async t => {
  const { api, driver } = await fixture(t);
  const payload = { ...details(), clientRequestId: randomUUID() };
  const first = (await api('POST', '/api/appointments', driver, payload)).data;
  const again = (await api('POST', '/api/appointments', driver, payload)).data;
  assert.equal(again.id, first.id); assert.equal(again.inviteToken, first.inviteToken);
  assert.equal((await api('GET', '/api/appointments', driver)).data.appointments.length, 1);
  assert.equal((await api('POST', '/api/appointments', driver, { ...payload, place: '다른역' })).code, 409);
});

test('생성 요청 키는 차량별로 구분하며 형식이 잘못되면 저장하지 않음', async t => {
  const { api, driver } = await fixture(t);
  const payload = { ...details(), clientRequestId: randomUUID() };
  const first = (await api('POST', '/api/appointments', driver, payload)).data;
  const other = (await api('POST', '/api/drivers', null, { displayName: '다른 운전자' })).data.accessToken;
  const second = (await api('POST', '/api/appointments', other, payload)).data;
  assert.notEqual(first.id, second.id);
  assert.equal((await api('POST', '/api/appointments', driver, { ...details(), clientRequestId: 'bad-key' })).code, 400);
});

test('서버 재시작과 약속 시간 경과 후에도 같은 요청으로 중복 생성하지 않음', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'ddakta-retry-'));
  let now = Date.now();
  try {
    const file = path.join(dir, 'appointments.json');
    const first = createStore(file, () => now); const driver = first.register({}).accessToken;
    const payload = { ...details(), clientRequestId: randomUUID() };
    const made = first.create(driver, payload);
    now = made.expiresAt + 1;
    const second = createStore(file, () => now);
    assert.equal(second.create(driver, payload).id, made.id);
    assert.equal(second.list(driver).length, 1);
    assert.equal(second.list(driver)[0].status, 'EXPIRED');
  } finally { rmSync(dir, { recursive: true }); }
});

test('선택한 장소를 약속 생성·조회·탑승자 화면에 동일하게 제공', async t => {
  const { api, driver } = await fixture(t);
  const target = selectedLocation();
  const made = await api('POST', '/api/appointments', driver, { ...details(), pickupLocation: target });
  assert.equal(made.code, 201);
  assert.deepEqual(made.data.pickupLocation, target);
  assert.equal(made.data.place, target.name);
  assert.deepEqual((await api('GET', '/api/appointments', driver)).data.appointments[0].pickupLocation, target);
  const view = (await api('GET', '/api/invitation', made.data.inviteToken)).data;
  assert.deepEqual(view.pickupLocation, target);
  assert.equal(view.place, target.name);
  assert.equal(view.driverId, undefined); assert.equal(view.inviteToken, undefined);
  assert.equal((await api('POST', '/api/invitation/respond', made.data.inviteToken, { decision: 'accept', expectedVersion: view.version })).data.status, 'CONFIRMED');
});

test('잘못된 좌표·문자형 좌표·불완전한 표시 좌표는 저장하지 않음', async t => {
  const { api, driver } = await fixture(t);
  for (const change of [{ latitude: 91 }, { longitude: -181 }, { latitude: '37.265' }, { longitude: null },
    { displayLatitude: 37.265 }, { name: '' }, { name: 'place\nother' }, { poiProvider: {} }]) {
    assert.equal((await api('POST', '/api/appointments', driver, { ...details(), pickupLocation: { ...selectedLocation(), ...change } })).code, 400);
  }
  assert.deepEqual((await api('GET', '/api/appointments', driver)).data.appointments, []);
});

test('좌표 수정은 소유 차량과 최신 약속 버전을 확인; 무관한 요청은 원본 유지', async t => {
  const { api, driver } = await fixture(t);
  const a = (await api('POST', '/api/appointments', driver, details())).data;
  const other = (await api('POST', '/api/drivers', null, {})).data.accessToken;
  const route = `/api/appointments/${a.id}/location`;
  const input = { expectedVersion: a.version, pickupLocation: selectedLocation() };
  assert.equal((await api('POST', route, other, input)).code, 404);
  assert.equal((await api('POST', route, a.inviteToken, input)).code, 401);
  assert.equal((await api('POST', route, driver, { ...input, expectedVersion: 0 })).code, 409);
  assert.equal((await api('POST', route, driver, { ...input, pickupLocation: null })).code, 400);
  assert.equal((await api('GET', '/api/invitation', a.inviteToken)).data.pickupLocation, null);
  const saved = await api('POST', route, driver, input);
  assert.equal(saved.code, 200); assert.equal(saved.data.version, a.version + 1);
  assert.deepEqual(saved.data.pickupLocation, selectedLocation());
  assert.equal(saved.data.place, selectedLocation().name);
  assert.equal((await api('GET', '/api/invitation', a.inviteToken)).data.place, selectedLocation().name);
});

test('확정 후 위치가 바뀌면 재수락 필요; 오래된 화면으로 수락하거나 덮어쓰기 불가', async t => {
  const { api, driver } = await fixture(t);
  const a = (await api('POST', '/api/appointments', driver, { ...details(), pickupLocation: selectedLocation() })).data;
  const accepted = (await api('POST', '/api/invitation/respond', a.inviteToken, { decision: 'accept', expectedVersion: a.version })).data;
  const route = `/api/appointments/${a.id}/location`;
  const changed = (await api('POST', route, driver, { expectedVersion: accepted.version, pickupLocation: { ...selectedLocation(), latitude: 37.266 } })).data;
  assert.equal(changed.status, 'PENDING'); assert.equal(changed.respondedAt, null);
  assert.equal((await api('POST', '/api/invitation/respond', a.inviteToken, { decision: 'accept', expectedVersion: a.version })).code, 409);
  assert.equal((await api('POST', '/api/invitation/respond', a.inviteToken, { decision: 'accept' })).code, 409);
  assert.equal((await api('POST', route, driver, { expectedVersion: accepted.version, pickupLocation: selectedLocation() })).code, 409);
  const confirmed = (await api('POST', '/api/invitation/respond', a.inviteToken, { decision: 'accept', expectedVersion: changed.version })).data;
  assert.equal(confirmed.status, 'CONFIRMED');
  const unchanged = (await api('POST', route, driver, { expectedVersion: confirmed.version, pickupLocation: changed.pickupLocation })).data;
  assert.equal(unchanged.status, 'CONFIRMED'); assert.equal(unchanged.version, confirmed.version);
});

test('좌표는 서버 재시작 뒤에도 유지되고 종료된 약속에는 추가 저장 불가', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'majung-location-'));
  try {
    const file = path.join(dir, 'appointments.json');
    const first = createStore(file); const driver = first.register({}).accessToken;
    const a = first.create(driver, details());
    const saved = first.setPickupLocation(driver, a.id, { expectedVersion: a.version, pickupLocation: selectedLocation() });
    const second = createStore(file);
    assert.deepEqual(second.list(driver)[0].pickupLocation, selectedLocation());
    const cancelled = second.cancel(driver, a.id);
    assert.throws(() => second.setPickupLocation(driver, a.id, { expectedVersion: cancelled.version, pickupLocation: selectedLocation() }), /종료된 약속/);
    assert.equal(saved.version, 2);
  } finally { rmSync(dir, { recursive: true }); }
});

test('같은 생성 요청 키로 다른 좌표를 생성할 수 없음', async t => {
  const { api, driver } = await fixture(t);
  const payload = { ...details(), clientRequestId: randomUUID(), pickupLocation: selectedLocation() };
  const first = (await api('POST', '/api/appointments', driver, payload)).data;
  assert.equal((await api('POST', '/api/appointments', driver, payload)).data.id, first.id);
  assert.equal((await api('POST', '/api/appointments', driver, { ...payload, pickupLocation: { ...selectedLocation(), longitude: 127.002 } })).code, 409);
});

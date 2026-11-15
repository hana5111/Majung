import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync, renameSync, existsSync } from 'node:fs';
import { dirname } from 'node:path';

export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
const hash = (value) => createHash('sha256').update(value).digest('hex');
const token = () => randomBytes(32).toString('base64url');
const clean = (value, label, limit, optional = false) => {
  if (optional && (value == null || value === '')) return '';
  if (typeof value !== 'string' || !value.trim() || value.trim().length > limit)
    throw new ApiError(400, `${label}을(를) 확인해 주세요.`);
  return value.trim();
};

function pickupLocation(value) {
  if (value == null) return null;
  if (typeof value !== 'object' || Array.isArray(value)) throw new ApiError(400, '선택한 장소를 확인해 주세요.');
  const coordinate = (v, limit) => typeof v === 'number' && Number.isFinite(v) && Math.abs(v) <= limit;
  if (!coordinate(value.latitude, 90) || !coordinate(value.longitude, 180)) throw new ApiError(400, '선택한 장소의 좌표를 확인해 주세요.');
  const displayLatitude = value.displayLatitude ?? null;
  const displayLongitude = value.displayLongitude ?? null;
  if ((displayLatitude !== null || displayLongitude !== null) && (!coordinate(displayLatitude, 90) || !coordinate(displayLongitude, 180)))
    throw new ApiError(400, '선택한 장소의 지도 좌표를 확인해 주세요.');
  const text = (key, limit, optional = true) => {
    const result = clean(value[key], '선택한 장소 정보', limit, optional);
    if (/[\u0000-\u001f\u007f]/.test(result)) throw new ApiError(400, '선택한 장소 정보를 확인해 주세요.');
    return result;
  };
  return { latitude: value.latitude, longitude: value.longitude, displayLatitude, displayLongitude,
    name: text('name', 100, false), address: text('address', 240), poiId: text('poiId', 160),
    poiSubId: text('poiSubId', 80), poiProvider: text('poiProvider', 80) };
}

export function createStore(file, now = Date.now) {
  let db = { drivers: [], appointments: [] };
  if (file && existsSync(file)) db = JSON.parse(readFileSync(file, 'utf8'));
  function save() {
    if (!file) return;
    mkdirSync(dirname(file), { recursive: true, mode: 0o700 });
    writeFileSync(`${file}.tmp`, JSON.stringify(db), { mode: 0o600 });
    renameSync(`${file}.tmp`, file);
  }
  const getDriver = (secret) => {
    const driver = secret && db.drivers.find(d => d.tokenHash === hash(secret));
    if (!driver) throw new ApiError(401, '차량 연결 정보가 유효하지 않습니다. 다시 연결해 주세요.');
    return driver;
  };
  const status = (a) => ['PENDING', 'CONFIRMED'].includes(a.status) && now() >= a.expiresAt ? 'EXPIRED' : a.status;
  const tripView = (a) => {
    const t = a.trip ?? { state: 'NOT_STARTED' };
    const fresh = t.state === 'EN_ROUTE' && Number.isFinite(t.observedAt) && now() - t.observedAt < 45000;
    return { state: t.state, departedAt: t.departedAt ?? null, updatedAt: t.observedAt ?? null,
      etaFresh: fresh, arrivalAt: fresh ? t.arrivalAt : null, remainingSeconds: fresh ? Math.max(0, Math.ceil((Date.parse(t.arrivalAt) - now()) / 1000)) : null };
  };
  const view = (a, driver = false) => ({
    id: a.id, driverName: a.driverName, passengerName: a.passengerName,
    place: a.pickupLocation?.name ?? a.place, meetingPoint: a.meetingPoint, meetingAt: a.meetingAt,
    note: a.note, status: status(a), createdAt: a.createdAt,
    updatedAt: a.updatedAt, respondedAt: a.respondedAt ?? null,
    expiresAt: a.expiresAt, version: a.version,
    pickupLocation: a.pickupLocation ?? null,
    pickupMode: a.pickupMode ?? 'SCHEDULED', trip: tripView(a),
    ...(driver ? { inviteToken: a.inviteToken, passengerContact: a.passengerContact } : {})
  });
  const invitation = (secret) => {
    const a = secret && db.appointments.find(a => hash(a.inviteToken) === hash(secret));
    if (!a) throw new ApiError(404, '초대 링크를 찾을 수 없습니다. 운전자에게 링크를 다시 요청해 주세요.');
    return a;
  };
  return {
    register(input) {
      const displayName = clean(input.displayName ?? '운전자', '보내는 이름', 30);
      const accessToken = token();
      const driver = { id: randomUUID(), displayName, tokenHash: hash(accessToken) };
      db.drivers.push(driver); save();
      return { accessToken, displayName };
    },
    list(secret) {
      const d = getDriver(secret);
      return db.appointments.filter(a => a.driverId === d.id).sort((a, b) => b.createdAt - a.createdAt).map(a => view(a, true));
    },
    create(secret, input) {
      const d = getDriver(secret);
      const passengerName = clean(input.passengerName, '탑승자 이름', 40);
      const passengerContact = clean(input.passengerContact, '연락처', 30, true);
      if (passengerContact && !/^[+\d ()-]{5,30}$/.test(passengerContact)) throw new ApiError(400, '연락처 형식을 확인해 주세요.');
      const place = clean(input.place, '만날 장소', 100);
      const immediate = input.pickupMode === 'NOW' || (input.pickupMode == null && input.meetingAt == null);
      if (input.pickupMode != null && !['NOW', 'SCHEDULED'].includes(input.pickupMode)) throw new ApiError(400, '픽업 방식을 확인해 주세요.');
      const meetingPoint = clean(input.meetingPoint, '상세 승차 지점', 120, immediate);
      const note = clean(input.note, '전달 메모', 240, true);
      const location = pickupLocation(input.pickupLocation);
      const meetingMs = immediate ? null : typeof input.meetingAt === 'string' ? Date.parse(input.meetingAt) : NaN;
      if (!immediate && !Number.isFinite(meetingMs)) throw new ApiError(400, '만날 날짜와 시간을 확인해 주세요.');
      const clientRequestId = input.clientRequestId;
      if (clientRequestId !== undefined && (typeof clientRequestId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(clientRequestId)))
        throw new ApiError(400, '약속 생성 요청 정보를 확인해 주세요.');
      const requestFingerprint = hash(JSON.stringify({ passengerName, passengerContact, place, meetingPoint, meetingAt: immediate ? null : new Date(meetingMs).toISOString(), note,
        ...(location ? { pickupLocation: location } : {}) }));
      if (clientRequestId) {
        const previous = db.appointments.find(a => a.driverId === d.id && a.clientRequestId === clientRequestId);
        if (previous) {
          if (previous.requestFingerprint !== requestFingerprint) throw new ApiError(409, '같은 생성 요청으로 약속 내용을 변경할 수 없습니다.');
          return view(previous, true);
        }
      }
      if (!immediate && (meetingMs < now() || meetingMs > now() + 366 * 86400000))
        throw new ApiError(400, '앞으로 1년 이내의 만날 날짜와 시간을 선택해 주세요.');
      const a = {
        id: randomUUID(), driverId: d.id, driverName: d.displayName,
        passengerName, passengerContact, place, meetingPoint, note,
        meetingAt: immediate ? null : new Date(meetingMs).toISOString(), inviteToken: token(),
        pickupMode: immediate ? 'NOW' : 'SCHEDULED', trip: { state: 'NOT_STARTED' },
        status: 'PENDING', createdAt: now(), updatedAt: now(),
        expiresAt: immediate ? now() + 24 * 3600000 : meetingMs + 3600000, version: 1, pickupLocation: location
      };
      if (clientRequestId) Object.assign(a, { clientRequestId, requestFingerprint });
      db.appointments.push(a); save(); return view(a, true);
    },
    getInvitation(secret) { return view(invitation(secret)); },
    setPickupLocation(secret, id, input) {
      const d = getDriver(secret);
      const a = db.appointments.find(a => a.id === id && a.driverId === d.id);
      if (!a) throw new ApiError(404, '약속을 찾을 수 없습니다.');
      if (!['PENDING', 'CONFIRMED'].includes(status(a))) throw new ApiError(409, '이미 종료된 약속의 장소는 변경할 수 없습니다.');
      if (!Number.isSafeInteger(input.expectedVersion) || input.expectedVersion !== a.version)
        throw new ApiError(409, '약속이 변경됐어요. 최신 내용을 확인한 뒤 다시 저장해 주세요.');
      const location = pickupLocation(input.pickupLocation);
      if (!location) throw new ApiError(400, '저장할 장소를 선택해 주세요.');
      if (JSON.stringify(a.pickupLocation ?? null) === JSON.stringify(location)) return view(a, true);
      a.pickupLocation = location;
      a.trip = { state: 'NOT_STARTED' };
      // A changed precise meeting location must be visible and reviewed by the passenger.
      if (a.status === 'CONFIRMED') { a.status = 'PENDING'; a.respondedAt = null; }
      a.updatedAt = now(); a.version++; save();
      return view(a, true);
    },
    updateTrip(secret, id, input) {
      const d = getDriver(secret);
      const a = db.appointments.find(a => a.id === id && a.driverId === d.id);
      if (!a) throw new ApiError(404, '픽업을 찾을 수 없습니다.');
      if (!['PENDING', 'CONFIRMED'].includes(status(a))) throw new ApiError(409, '종료된 픽업은 갱신할 수 없습니다.');
      // Telemetry does not change the invitation version; passenger acceptance must not race every ETA update.
      if (!a.pickupLocation || JSON.stringify(pickupLocation(input.pickupLocation)) !== JSON.stringify(a.pickupLocation))
        throw new ApiError(409, '픽업 목적지가 변경됐어요. 최신 목적지를 다시 확인해 주세요.');
      if (!['EN_ROUTE', 'UNAVAILABLE', 'ARRIVED', 'STOPPED'].includes(input.state)) throw new ApiError(400, '이동 상태를 확인해 주세요.');
      if (!Number.isSafeInteger(input.observedAt) || Math.abs(now() - input.observedAt) > 30000)
        throw new ApiError(400, '최신 내비 정보만 공유할 수 있습니다.');
      if (a.trip?.observedAt != null && input.observedAt <= a.trip.observedAt) return view(a, true);
      const seconds = input.remainingSeconds;
      if (input.state === 'EN_ROUTE' && (!Number.isSafeInteger(seconds) || seconds < 0 || seconds > 86400))
        throw new ApiError(400, '도착 예상 시간을 확인해 주세요.');
      if (input.state === 'EN_ROUTE' && !a.trip?.departedAt) a.trip = { departedAt: now() };
      a.trip = { ...a.trip, state: input.state, observedAt: input.observedAt,
        arrivalAt: input.state === 'EN_ROUTE' ? new Date(input.observedAt + seconds * 1000).toISOString() : null };
      save(); return view(a, true);
    },
    respond(secret, decision, expectedVersion) {
      if (!['accept', 'decline'].includes(decision)) throw new ApiError(400, '수락 또는 거절을 선택해 주세요.');
      const a = invitation(secret);
      const target = decision === 'accept' ? 'CONFIRMED' : 'DECLINED';
      if (status(a) === target) return view(a);
      if (status(a) !== 'PENDING') throw new ApiError(409, '이미 응답했거나 종료된 약속입니다. 최신 상태를 확인해 주세요.');
      if (a.pickupMode === 'NOW' && !a.trip?.departedAt) throw new ApiError(409, '운전자가 출발을 준비 중이에요. 잠시 후 다시 확인해 주세요.');
      if ((a.pickupLocation || expectedVersion !== undefined) && expectedVersion !== a.version)
        throw new ApiError(409, '약속 장소가 변경됐어요. 새 내용을 확인한 뒤 다시 응답해 주세요.');
      a.status = target; a.updatedAt = now(); a.respondedAt = now(); a.version++; save(); return view(a);
    },
    cancel(secret, id) {
      const d = getDriver(secret);
      const a = db.appointments.find(a => a.id === id && a.driverId === d.id);
      if (!a) throw new ApiError(404, '약속을 찾을 수 없습니다.');
      if (status(a) === 'CANCELLED') return view(a, true);
      if (!['PENDING', 'CONFIRMED'].includes(status(a))) throw new ApiError(409, '이미 종료된 약속입니다.');
      a.status = 'CANCELLED'; a.updatedAt = now(); a.version++; save(); return view(a, true);
    }
  };
}

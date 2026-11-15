import { pickupEta } from './eta.js';
const $ = id => document.getElementById(id);
const inviteToken = location.hash.slice(1);
const labels = { PENDING: '응답 대기', CONFIRMED: '픽업 약속 확정', DECLINED: '거절한 약속', CANCELLED: '취소된 약속', EXPIRED: '만료된 약속' };
let appointment = null;
let submitting = false;
let busy = false;
let terminalError = false;

async function request(method = 'GET', decision) {
  const result = await fetch(`/api/invitation${decision ? '/respond' : ''}`, {
    method,
    headers: { Authorization: `Bearer ${inviteToken}`, ...(decision ? { 'Content-Type': 'application/json' } : {}) },
    ...(decision ? { body: JSON.stringify({ decision, expectedVersion: appointment?.version }) } : {}), cache: 'no-store'
  });
  const data = await result.json();
  if (!result.ok) throw Object.assign(new Error(data.error || '요청을 처리하지 못했어요.'), { status: result.status });
  return data;
}
function render(a) {
  appointment = a;
  $('appointment').dataset.state = a.status.toLowerCase();
  $('loading').classList.add('hidden'); $('error').classList.add('hidden'); $('appointment').classList.remove('hidden');
  $('driver-name').textContent = a.driverName; $('passenger-name').textContent = a.passengerName;
  $('place').textContent = a.place; $('meeting-point').textContent = a.meetingPoint;
  $('pickup-location-box').classList.toggle('hidden', !a.pickupLocation);
  $('pickup-location-name').textContent = a.pickupLocation?.name || '';
  $('pickup-location-address').textContent = a.pickupLocation?.address || '';
  updateEta();
  $('note-box').classList.toggle('hidden', !a.note); $('note').textContent = a.note;
  const pending = a.status === 'PENDING'; const confirmed = a.status === 'CONFIRMED';
  $('status-label').textContent = labels[a.status] || '약속 확인';
  $('status').className = `status ${pending ? 'pending' : confirmed ? 'confirmed' : 'closed'}`;
  $('headline').textContent = pending || confirmed ? '픽업 약속' : '종료된 약속';
  $('intro').textContent = pending ? `${a.passengerName}님에게 도착한 픽업 초대입니다.` : confirmed ? '수락했어요. 차량의 도착 예상 시간을 확인해 주세요.' : '새로운 약속은 운전자에게 요청해 주세요.';
  $('response').classList.toggle('hidden', !pending || (a.pickupMode === 'NOW' && !a.trip?.departedAt));
  $('confirmed-box').classList.toggle('hidden', !confirmed);
  $('closed-message').classList.toggle('hidden', pending || confirmed);
  $('closed-message').textContent = a.status === 'CANCELLED' ? '운전자가 약속을 취소했어요. 이전 약속 장소로 이동하지 않도록 확인해 주세요.' : a.status === 'DECLINED' ? '거절한 내용이 운전자에게 전달됐어요.' : '초대의 유효 시간이 지났어요. 운전자에게 새 초대를 요청해 주세요.';
  $('sync-label').textContent = '내비 도착 시간과 픽업 상태를 갱신합니다';
  $('sync-label').parentElement.classList.remove('offline');
}
function updateEta() {
  if (!appointment) return;
  const value = pickupEta(appointment);
  $('time-label').textContent = appointment.pickupMode === 'NOW' ? '차량 도착까지' : '만나는 시간';
  $('time').textContent = value.title;
  $('date').textContent = value.detail;
}
function showError(message) {
  $('loading').classList.add('hidden'); $('appointment').classList.add('hidden'); $('error').classList.remove('hidden');
  $('error-message').textContent = message;
}
async function refresh() {
  if (busy || submitting || document.hidden || terminalError) return;
  if (!/^[A-Za-z0-9_-]{43}$/.test(inviteToken)) {
    terminalError = true;
    showError('차량 앱에서 만든 초대 링크를 열어 주세요. 이 페이지에서는 새로운 약속을 만들 수 없어요.');
    $('retry').classList.add('hidden'); return;
  }
  busy = true;
  try { render(await request()); }
  catch (e) {
    if (e.status === 404 || e.status === 401) { terminalError = true; $('retry').classList.add('hidden'); showError(e.message); }
    else if (!appointment) showError(e.status ? e.message : '서버에 연결하지 못했어요. 네트워크 연결을 확인해 주세요.');
    else { $('sync-label').textContent = '연결이 끊겼어요. 마지막으로 확인한 약속입니다.'; $('sync-label').parentElement.classList.add('offline'); }
  } finally { busy = false; }
}
async function respond(decision) {
  if (submitting) return;
  submitting = true; $('action-error').textContent = '';
  ['accept', 'decline', 'decline-submit'].forEach(id => $(id).disabled = true);
  try { render(await request('POST', decision)); $('decline-confirm').classList.add('hidden'); }
  catch (e) { $('action-error').textContent = e.status ? e.message : '응답을 전송하지 못했어요. 연결 후 다시 눌러 주세요.'; }
  finally { submitting = false; ['accept', 'decline', 'decline-submit'].forEach(id => $(id).disabled = false); await refresh(); }
}
$('accept').addEventListener('click', () => respond('accept'));
$('decline').addEventListener('click', () => $('decline-confirm').classList.remove('hidden'));
$('decline-back').addEventListener('click', () => $('decline-confirm').classList.add('hidden'));
$('decline-submit').addEventListener('click', () => respond('decline'));
$('retry').addEventListener('click', refresh);
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
// A different invitation can open in the same browser tab without a full navigation.
window.addEventListener('hashchange', () => location.reload());
refresh(); setInterval(refresh, 3000); setInterval(updateEta, 1000);

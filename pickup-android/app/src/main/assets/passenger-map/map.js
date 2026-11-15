/* Leaflet is bundled locally. All coordinates come from the fixed native demo scene. */
(() => {
  'use strict';
  let map, tiles, person, radius, connection, current, frame;
  let loaded = false;
  const loading = document.getElementById('loading');
  const loadingText = document.getElementById('loading-text');
  const retry = document.getElementById('retry');
  const latLng = point => [point.latitude, point.longitude];
  const symbols = {
    place: '<path d="M12 22s7-7.1 7-13a7 7 0 0 0-14 0c0 5.9 7 13 7 13Z"/><circle cx="12" cy="9" r="2.5"/>',
    person: '<circle cx="12" cy="7" r="3.5"/><path d="M5 22v-3a7 7 0 0 1 14 0v3"/>',
    car: '<path d="m5 8 2-5h10l2 5M4 16V9a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v7H4Zm1 0v4m14-4v4M4 13h16"/><circle cx="7" cy="11" r=".5"/><circle cx="17" cy="11" r=".5"/>'
  };
  function marker(point, kind, text, direction, offset) {
    return L.marker(latLng(point), {
      icon: L.divIcon({ className: 'majung-pin', html: '<div class="pin pin-' + kind + '"><svg viewBox="0 0 24 24" aria-hidden="true">' + symbols[kind] + '</svg></div>', iconSize: [38, 38], iconAnchor: [19, 19] }),
      keyboard: false, title: text + ' · 시연 위치'
    }).addTo(map).bindTooltip(Object.assign(document.createElement('span'), { textContent: text }), { permanent: true, direction, offset, className: 'map-name', opacity: 1 });
  }
  function showFailure() {
    if (loaded) return;
    loading.hidden = false;
    loadingText.textContent = '지도를 불러오지 못했어요. 인터넷 연결을 확인해 주세요.';
    retry.hidden = false;
  }
  function create(scene) {
    // Give the embedded vehicle WebView an explicit pixel height before Leaflet measures it.
    const container = document.getElementById('map');
    container.style.width = window.innerWidth + 'px';
    container.style.height = window.innerHeight + 'px';
    map = L.map('map', { zoomControl: false, attributionControl: true, scrollWheelZoom: false, maxZoom: 19, minZoom: 14 });
    map.attributionControl.setPrefix(false);
    L.control.zoom({ position: 'topright' }).addTo(map);
    tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19, keepBuffer: 0, updateWhenIdle: true, updateWhenZooming: false,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
    });
    tiles.on('tileload', () => { loaded = true; loading.hidden = true; });
    tiles.on('tileerror', showFailure);
    tiles.addTo(map);
    map.fitBounds(L.latLngBounds([latLng(scene.pickup), latLng(scene.car), latLng(scene.moving)]), { paddingTopLeft: [48, 44], paddingBottomRight: [64, 52], maxZoom: 17, animate: false });
    marker(scene.pickup, 'place', '약속 위치', 'bottom', [0, 22]);
    marker(scene.car, 'car', '내 차', 'bottom', [0, 22]);
    window.addEventListener('resize', () => {
      container.style.width = window.innerWidth + 'px';
      container.style.height = window.innerHeight + 'px';
      map.invalidateSize({ pan: false });
    });
    console.info('Demo map size: ' + container.offsetWidth + 'x' + container.offsetHeight);
    setTimeout(showFailure, 12000);
  }
  window.setPickupState = scene => {
    if (!map) create(scene);
    cancelAnimationFrame(frame);
    if (!scene.accepted) {
      for (const layer of [person, radius, connection]) if (layer) map.removeLayer(layer);
      person = radius = connection = current = null;
      return;
    }
    const target = latLng(scene.nearby ? scene.near : scene.moving);
    if (!person) {
      person = marker(scene.moving, 'person', scene.passengerName + '님', 'top', [0, -22]);
      person.setZIndexOffset(200);
      radius = L.circle(target, { radius: 15, color: '#79b63e', weight: 1, fillColor: '#b4ed76', fillOpacity: .18, interactive: false }).addTo(map);
      connection = L.polyline([latLng(scene.pickup), target], { color: '#204b22', weight: 2, opacity: .5, dashArray: '5 7', interactive: false }).addTo(map);
    }
    const origin = current || target;
    const start = performance.now();
    function animate(now) {
      const fraction = Math.min(1, (now - start) / 1100);
      const eased = fraction * fraction * (3 - 2 * fraction);
      current = origin.map((value, index) => value + (target[index] - value) * eased);
      person.setLatLng(current);
      radius.setLatLng(current);
      connection.setLatLngs([latLng(scene.pickup), current]);
      if (fraction < 1) frame = requestAnimationFrame(animate);
    }
    frame = requestAnimationFrame(animate);
  };
  retry.addEventListener('click', () => {
    loadingText.textContent = '실제 지도를 불러오는 중…'; retry.hidden = true;
    loaded = false; tiles.redraw(); setTimeout(showFailure, 12000);
  });
})();

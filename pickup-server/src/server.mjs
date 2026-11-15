import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { ApiError, createStore } from './store.mjs';

const root = fileURLToPath(new URL('../../pickup-web/', import.meta.url));
const assets = new Map([['/invite', ['index.html', 'text/html']], ['/', ['index.html', 'text/html']], ['/app.js', ['app.js', 'text/javascript']], ['/eta.js', ['eta.js', 'text/javascript']], ['/style.css', ['style.css', 'text/css']], ['/icon.svg', ['icon.svg', 'image/svg+xml']]]);
const secret = req => /^Bearer ([A-Za-z0-9_-]{40,100})$/.exec(req.headers.authorization ?? '')?.[1];
async function body(req) {
  const chunks = []; let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 16384) throw new ApiError(413, '요청이 너무 큽니다.');
    chunks.push(chunk);
  }
  try {
    const parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error();
    return parsed;
  } catch { throw new ApiError(400, '요청 내용을 확인해 주세요.'); }
}

export function makeServer({ store = createStore(null) } = {}) {
  return createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    const json = (code, data) => { res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(data)); };
    try {
      const url = new URL(req.url, 'http://localhost');
      const route = url.pathname;
      if (req.method === 'GET' && route === '/api/health') return json(200, { ok: true, app: 'ddakta', phase: 'invitations' });
      if (req.method === 'POST' && route === '/api/drivers') return json(201, store.register(await body(req)));
      if (req.method === 'GET' && route === '/api/appointments') return json(200, { appointments: store.list(secret(req)) });
      if (req.method === 'POST' && route === '/api/appointments') return json(201, store.create(secret(req), await body(req)));
      const location = /^\/api\/appointments\/([a-f0-9-]+)\/location$/.exec(route);
      if (req.method === 'POST' && location) return json(200, store.setPickupLocation(secret(req), location[1], await body(req)));
      const trip = /^\/api\/appointments\/([a-f0-9-]+)\/trip$/.exec(route);
      if (req.method === 'POST' && trip) return json(200, store.updateTrip(secret(req), trip[1], await body(req)));
      const cancel = /^\/api\/appointments\/([a-f0-9-]+)\/cancel$/.exec(route);
      if (req.method === 'POST' && cancel) return json(200, store.cancel(secret(req), cancel[1]));
      if (req.method === 'GET' && route === '/api/invitation') return json(200, store.getInvitation(secret(req)));
      if (req.method === 'POST' && route === '/api/invitation/respond') {
        const input = await body(req);
        return json(200, store.respond(secret(req), input.decision, input.expectedVersion));
      }
      if (req.method === 'GET' && assets.has(route)) {
        const [file, mime] = assets.get(route);
        res.writeHead(200, { 'Content-Type': `${mime}; charset=utf-8` });
        return res.end(await readFile(path.join(root, file)));
      }
      return json(404, { error: '요청한 페이지를 찾을 수 없습니다.' });
    } catch (error) {
      if (res.headersSent) return res.destroy();
      return json(error instanceof ApiError ? error.status : 500, {
        error: error instanceof ApiError ? error.message : '잠시 후 다시 시도해 주세요.'
      });
    }
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT ?? 8787);
  const host = process.env.HOST ?? '127.0.0.1';
  const file = process.env.DDAKTA_DATA_FILE ?? fileURLToPath(new URL('../.data/appointments.json', import.meta.url));
  makeServer({ store: createStore(file) }).listen(port, host, () => {
    console.log(`마중 약속 서버: http://${host}:${port}`);
    console.log('내비 도착 시간 공유 개발 서버입니다. 문자 발송은 차량의 문자 앱에서 확인합니다.');
  });
}

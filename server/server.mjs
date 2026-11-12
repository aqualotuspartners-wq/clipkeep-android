import { createServer } from 'node:http';
import { createHmac, timingSafeEqual } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pipeline } from 'node:stream/promises';

const MAX_BYTES = 200 * 1024 * 1024;
const TTL_SECONDS = 900;
const origin = new URL(process.env.PUBLIC_ORIGIN ?? 'http://127.0.0.1:8080');
const key = process.env.MEDIA_SIGNING_KEY ?? '';
const directory = resolve(process.env.MEDIA_DIRECTORY ?? './media');
if (Buffer.byteLength(key) < 32) throw new Error('Set MEDIA_SIGNING_KEY to at least 32 bytes.');
if (!['https:', 'http:'].includes(origin.protocol) || origin.username || origin.password ||
    origin.href !== origin.origin + '/' ||
    (origin.protocol === 'http:' && origin.hostname !== '127.0.0.1')) {
    throw new Error('Set PUBLIC_ORIGIN to the HTTPS origin of the server.');
}

function instagramPath(value) {
    try {
        const url = new URL(value);
        if (url.protocol !== 'https:' || url.username || url.password || url.port ||
            !['instagram.com', 'www.instagram.com'].includes(url.hostname)) return null;
        const path = url.pathname.replace(/\/$/, '');
        return /^\/(?:reel|p|tv)\/[A-Za-z0-9_-]+$|^\/stories\/[A-Za-z0-9._]+\/[0-9]+$/.test(path)
            ? path : null;
    } catch {
        return null;
    }
}

const index = new Map();
const files = new Map();
const catalog = JSON.parse(await readFile(process.env.CATALOG ?? './catalog.json', 'utf8'));
for (const { instagramUrl, id } of catalog) {
    const path = instagramPath(instagramUrl);
    if (!path || typeof id !== 'string' || !/^[a-zA-Z0-9_-]{1,80}$/.test(id) || index.has(path)) {
        throw new Error('Invalid or duplicate catalog entry.');
    }
    index.set(path, id);
    files.set(id, resolve(directory, id + '.mp4'));
}

function signature(id, expiration) {
    return createHmac('sha256', key).update(id + ':' + expiration).digest('hex');
}

function json(res, status, data) {
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(data));
}

async function lookup(req, res) {
    let size = 0;
    const chunks = [];
    for await (const chunk of req) {
        size += chunk.length;
        if (size > 4096) {
            json(res, 413, { error: 'request_too_large' });
            return;
        }
        chunks.push(chunk);
    }
    let body;
    try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
    catch { return json(res, 400, { error: 'invalid_json' }); }
    const path = typeof body?.url === 'string' && instagramPath(body.url);
    if (!path) return json(res, 400, { error: 'invalid_instagram_url' });
    const id = index.get(path);
    if (!id) return json(res, 404, { error: 'video_unavailable' });
    const info = await stat(files.get(id)).catch(() => null);
    if (!info?.isFile() || info.size < 1 || info.size > MAX_BYTES) {
        return json(res, 404, { error: 'video_unavailable' });
    }
    const expiration = Math.floor(Date.now() / 1000) + TTL_SECONDS;
    const url = new URL('/media/' + id, origin);
    url.searchParams.set('expires', expiration);
    url.searchParams.set('signature', signature(id, expiration));
    json(res, 200, { videoUrl: url.href });
}

async function media(req, res, url, id) {
    const expiration = url.searchParams.get('expires') ?? '';
    const token = url.searchParams.get('signature') ?? '';
    if (!/^\d{1,12}$/.test(expiration) || Number(expiration) <= Date.now() / 1000 ||
        !/^[a-f0-9]{64}$/.test(token) ||
        !timingSafeEqual(Buffer.from(token), Buffer.from(signature(id, expiration)))) {
        return json(res, 403, { error: 'invalid_or_expired_link' });
    }
    const file = files.get(id);
    const info = file && await stat(file).catch(() => null);
    if (!info?.isFile() || info.size < 1 || info.size > MAX_BYTES) {
        return json(res, 404, { error: 'video_unavailable' });
    }
    const headers = { 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes', 'Cache-Control': 'private, no-store' };
    let start = 0;
    let end = info.size - 1;
    const range = req.method === 'GET' && req.headers.range;
    if (range) {
        const match = /^bytes=(\d*)-(\d*)$/.exec(range);
        if (match && (match[1] || match[2])) {
            if (match[1]) {
                start = Number(match[1]);
                if (match[2]) end = Math.min(end, Number(match[2]));
            } else start = Math.max(0, info.size - Number(match[2]));
        } else start = info.size;
        if (start > end) {
            res.writeHead(416, { 'Content-Range': 'bytes */' + info.size }).end();
            return;
        }
        headers['Content-Range'] = `bytes ${start}-${end}/${info.size}`;
    }
    headers['Content-Length'] = end - start + 1;
    res.writeHead(range ? 206 : 200, headers);
    if (req.method === 'HEAD') return res.end();
    await pipeline(createReadStream(file, { start, end }), res);
}

const server = createServer(async (req, res) => {
    try {
        const url = new URL(req.url, 'http://localhost');
        if (req.method === 'GET' && url.pathname === '/health') return json(res, 200, { ok: true });
        if (req.method === 'POST' && url.pathname === '/resolve') return await lookup(req, res);
        const match = /^\/media\/([a-zA-Z0-9_-]{1,80})$/.exec(url.pathname);
        if (match && ['GET', 'HEAD'].includes(req.method)) return await media(req, res, url, match[1]);
        json(res, 404, { error: 'not_found' });
    } catch {
        if (res.headersSent) res.destroy();
        else json(res, 500, { error: 'server_error' });
    }
});
server.requestTimeout = 15_000;
server.headersTimeout = 10_000;
server.listen(Number(process.env.PORT ?? 8080), process.env.HOST ?? '127.0.0.1');

import { randomUUID } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const token = process.env.INSTAGRAM_ACCESS_TOKEN;
const version = process.env.INSTAGRAM_API_VERSION ?? 'v25.0';
const account = process.env.INSTAGRAM_USER_ID;
const maxBytes = 200 * 1024 * 1024;

function parseLink(value) {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password || url.port ||
        !['instagram.com', 'www.instagram.com'].includes(url.hostname)) throw new Error('Invalid Instagram URL.');
    const path = url.pathname.replace(/\/$/, '');
    const story = /^\/stories\/([\w.]+)\/(\d+)$/.exec(path);
    if (story) return { path, edge: 'stories', username: story[1].toLowerCase(), id: story[2] };
    if (/^\/reel\/[\w-]+$/.test(path)) return { path, edge: 'media' };
    throw new Error('Use a direct Story or Reel URL.');
}

async function graph(path, params) {
    const url = new URL(`https://graph.instagram.com/${version}/${path}`);
    for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value);
    const response = await fetch(url, {
        headers: { Authorization: `Bearer ${token}` },
        redirect: 'error',
        signal: AbortSignal.timeout(20_000)
    });
    if (!response.ok) throw new Error(`Meta API returned HTTP ${response.status}. Check the token and account permissions.`);
    return response.json();
}

async function findVideo(link) {
    const owner = await graph(account, { fields: 'username' });
    if (link.username && link.username !== owner.username?.toLowerCase()) {
        throw new Error('The Story belongs to a different Instagram account.');
    }
    let after;
    for (let page = 0; page < 10; page++) {
        const fields = 'id,media_type,media_url,permalink';
        const payload = await graph(`${account}/${link.edge}`, after ? { fields, after } : { fields });
        const found = (payload.data ?? []).find(item => {
            if (item.media_type !== 'VIDEO' || !item.media_url) return false;
            if (link.id && String(item.id) === link.id) return true;
            try { return parseLink(item.permalink).path === link.path; } catch { return false; }
        });
        if (found) return found.media_url;
        if (!payload.paging?.next || !payload.paging?.cursors?.after) break;
        after = payload.paging.cursors.after;
    }
    throw new Error('No matching video returned by Meta. Verify that the Reel belongs to this account or that the Story is active and its link matches the API media ID or permalink.');
}

async function importVideo() {
    if (!token || !/^\d+$/.test(account ?? '') || !/^v\d+\.\d+$/.test(version)) {
        throw new Error('Set INSTAGRAM_ACCESS_TOKEN and INSTAGRAM_USER_ID for an authorized professional account.');
    }
    const link = parseLink(process.argv[2]);
    const directory = resolve(process.env.MEDIA_DIRECTORY ?? './media');
    const catalogPath = resolve(process.env.CATALOG ?? './catalog.json');
    let catalog;
    try { catalog = JSON.parse(await readFile(catalogPath, 'utf8')); }
    catch (error) { if (error.code !== 'ENOENT') throw error; catalog = []; }
    if (catalog.some(item => new URL(item.instagramUrl).pathname.replace(/\/$/, '') === link.path)) {
        throw new Error('This link is already in the catalog.');
    }
    const mediaUrl = new URL(await findVideo(link));
    if (mediaUrl.protocol !== 'https:' || mediaUrl.username || mediaUrl.password || mediaUrl.port ||
        !/(^|\.)(cdninstagram\.com|fbcdn\.net)$/.test(mediaUrl.hostname)) {
        throw new Error('Meta returned an unexpected media host.');
    }
    const response = await fetch(mediaUrl, { redirect: 'error', signal: AbortSignal.timeout(120_000) });
    const type = response.headers.get('content-type')?.split(';')[0];
    if (!response.ok || !response.body || type !== 'video/mp4' ||
        Number(response.headers.get('content-length')) > maxBytes) {
        throw new Error('Meta did not return a supported MP4.');
    }
    await mkdir(directory, { recursive: true });
    const id = randomUUID();
    const file = resolve(directory, id + '.mp4');
    const temporaryCatalog = catalogPath + '.' + id;
    let size = 0;
    const limit = new Transform({
        transform(chunk, encoding, callback) {
            size += chunk.length;
            if (size > maxBytes) callback(new Error('Video exceeds 200 MB.'));
            else callback(null, chunk);
        }
    });
    try {
        await pipeline(Readable.fromWeb(response.body), limit, createWriteStream(file, { flags: 'wx' }));
        if (!size) throw new Error('Received an empty video.');
        catalog.push({ instagramUrl: 'https://www.instagram.com' + link.path, id });
        await writeFile(temporaryCatalog, JSON.stringify(catalog, null, 2) + '\n', { flag: 'wx' });
        await rename(temporaryCatalog, catalogPath);
    } catch (error) {
        await Promise.all([rm(file, { force: true }), rm(temporaryCatalog, { force: true })]);
        throw error;
    }
    console.log('Video imported. Restart the ClipKeep server to load the updated catalog.');
}

importVideo().catch(error => {
    console.error(error.message);
    process.exitCode = 1;
});

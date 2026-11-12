import { createWriteStream } from 'node:fs';
import { rename, rm } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const host = 'instagram-video-downloader22.p.rapidapi.com';

export async function fetchVideo(path, key, destination, maxBytes, request = fetch) {
    const form = new FormData();
    form.set('url', 'https://www.instagram.com' + path + '/');
    const response = await request('https://' + host + '/index.php', {
        method: 'POST',
        headers: { 'x-rapidapi-host': host, 'x-rapidapi-key': key },
        body: form,
        redirect: 'error',
        signal: AbortSignal.timeout(15_000)
    });
    if (!response.ok) throw new Error('Provider lookup failed');
    const payload = await response.json();
    const video = payload.medias?.find(item => item.type === 'video' && typeof item.url === 'string');
    if (!video) return false;
    const url = new URL(video.url);
    if (url.protocol !== 'https:' || url.username || url.password || url.port ||
        !url.hostname.endsWith('.cdninstagram.com') || !url.pathname.endsWith('.mp4')) {
        throw new Error('Unexpected media URL');
    }
    const media = await request(url, { redirect: 'error', signal: AbortSignal.timeout(20_000) });
    if (!media.ok || !media.body || media.headers.get('content-type')?.split(';')[0] !== 'video/mp4' ||
        Number(media.headers.get('content-length')) > maxBytes) {
        throw new Error('Invalid MP4 response');
    }
    const temporary = destination + '.' + randomUUID();
    let size = 0;
    const limit = new Transform({
        transform(chunk, encoding, done) {
            size += chunk.length;
            if (size > maxBytes) done(new Error('Video exceeds size limit'));
            else done(null, chunk);
        }
    });
    try {
        await pipeline(Readable.fromWeb(media.body), limit, createWriteStream(temporary, { flags: 'wx' }));
        if (!size) throw new Error('Empty video');
        await rename(temporary, destination);
        return true;
    } finally {
        await rm(temporary, { force: true });
    }
}

# ClipKeep for Android

Native Android source targeting API 36. The Android app uses the approved Instagram-inspired design with one input for automatic link recognition. Remote video previews are available before viewing ads. Remote clips can be saved to Movies/ClipKeep independently of ad availability or consent. A banner appears below the preview and download controls when an ad loads. Debug builds also allow local MP4 preview and saving.

## Build

Install JDK 17, Android SDK 36 and Gradle 8.13. Open this directory in Android Studio, or run `gradle :app:assembleDebug`. The SDK and external dependencies were unavailable in the authoring environment, so compilation and device QA are pending.

## Instagram resolver

The client accepts Reel, post and Story URLs. Configure `-PresolverUrl=https://YOUR_HOST/resolve` to enable lookup. The client POSTs a JSON body containing `url` and expects `{"videoUrl":"https://YOUR_HOST/media/short-lived-id"}`. Only return media the requester is authorized to download. Serve the MP4 on the same HTTPS host with HEAD and GET support, no redirects, a video/mp4 content type and a maximum 200 MB response. The resolver and account authorization are separate production dependencies. No Instagram lookup is available in the default build. For Internal testing, configure the HTTPS resolver and verify an authorized clip before uploading. Use Google test ads during device QA. YouTube is outside this version.

## Release

- Confirm the app name and package ID before registering them.
- Configure consent messaging, create an AdMob Android app and banner unit, and supply `-PadmobAppId=...` and `-PbannerAdId=...`. Debug builds use Google test IDs. Release builds default to the configured ClipKeep AdMob App ID and use the configured ClipKeep banner unit ID. Verify consent, opt-out, banner layout, download and no-fill on real devices before switching to live IDs.
- Verify the deployed resolver with authorized Reels and Stories. Also test rejected URLs, MP4 export and download failure.
- Create the signed AAB from Android Studio using your upload key. Complete Play Console app content, ads, privacy policy, Data safety, content rating, store graphics, testing and production review.

## Included resolver

`server/` contains a Node 22 resolver for a curated catalog of videos approved for public download. Copy the example catalog, replace the Instagram links with real links and place each authorized original at `media/<id>.mp4`. The catalog is read at startup. The server does not fetch media from Instagram or access private content. Arbitrary links return 404. Expiring links control access to the file endpoint; the catalog itself is public to anyone who knows a registered Instagram link.

For local tests, run the server from its directory with `CATALOG=./catalog.json MEDIA_SIGNING_KEY=<random-secret> node server.mjs`. For production, mount catalog.json and media/ under /content in the container, set MEDIA_SIGNING_KEY to a random secret of at least 32 bytes and PUBLIC_ORIGIN to your HTTPS domain. Put it behind HTTPS ingress with request rate limits and keep the origin behind that ingress. Configure the Android resolver URL with the same origin and /resolve. Verify the real authorized clips on device before signing.

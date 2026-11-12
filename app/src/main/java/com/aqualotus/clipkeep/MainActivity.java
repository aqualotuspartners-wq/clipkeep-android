package com.aqualotus.clipkeep;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import com.google.android.gms.ads.*;
import com.google.android.ump.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class MainActivity extends Activity {
    private static final int INK = 0xff29143d, PANEL = 0xfffae9f5, ACCENT = 0xffa52882;
    private static final int WHITE = 0xffffffff, MUTED = 0xff694b70;
    private static final long MAX_BYTES = 200L * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText input;
    private TextView status;
    private Button check, save, privacy;
    private LinearLayout bannerSlot;
    private VideoView preview;
    private Uri clip;
    private AdView banner;
    private ConsentInformation consent;
    private boolean adsStarted, adsReady, busy, previewReady;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xfff2e3ff, 0xfffff8fc, 0xfffff1e1}));
        scroll.setFillViewport(true);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT < 30) {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            }
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(28), dp(24), dp(32));
        scroll.addView(body);
        setContentView(scroll);

        add(body, label("↓  ClipKeep", 22, ACCENT, true), 28);
        add(body, label("Download Instagram", 37, INK, true), 3);
        add(body, label("Stories & Reels", 42, ACCENT, true), 14);
        add(body, label("Paste an Instagram link to get started.", 16, MUTED, false), 25);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(17), dp(16), dp(17));
        card.setBackground(background(WHITE, 24));
        card.setElevation(dp(4));
        add(body, card, 16);
        add(card, label("Instagram link", 16, INK, true), 8);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setHint("https://www.instagram.com/…");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        input.setBackground(background(0xfffbf3fc, 12));
        add(card, input, 10);
        add(card, button("Paste link", false, v -> paste()), 8);
        check = button("Find video", true, v -> resolve());
        add(card, check, 0);
        if (BuildConfig.DEBUG) {
            add(body, button("Choose a video from this device", false, v -> pick()), 14);
        }
        status = label("", 15, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(body, status, 12);
        preview = new VideoView(this);
        preview.setVisibility(View.GONE);
        LinearLayout.LayoutParams player = new LinearLayout.LayoutParams(-1, dp(235));
        player.bottomMargin = dp(12);
        body.addView(preview, player);
        preview.setOnClickListener(v -> {
            if (preview.isPlaying()) preview.pause();
            else preview.start();
        });
        preview.setOnErrorListener((mp, what, extra) -> {
            status.setText("Preview unavailable. Choose another video.");
            select(null);
            return true;
        });
        save = button("Save to device", true, v -> save());
        add(body, save, 8);
        bannerSlot = new LinearLayout(this);
        bannerSlot.setGravity(android.view.Gravity.CENTER);
        bannerSlot.setVisibility(View.GONE);
        add(body, bannerSlot, 14);
        privacy = button("Ad privacy options", false, v -> {
            UserMessagingPlatform.showPrivacyOptionsForm(this, error -> {
                clearBanner();
                if (consent.canRequestAds()) startAds();
                render();
            });
        });
        add(body, privacy, 0);
        privacy.setVisibility(View.GONE);
        render();

        consent = UserMessagingPlatform.getConsentInformation(this);
        consent.requestConsentInfoUpdate(this, new ConsentRequestParameters.Builder().build(),
            () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(this, error -> {
                privacy.setVisibility(consent.getPrivacyOptionsRequirementStatus()
                    == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED ? View.VISIBLE : View.GONE);
                if (consent.canRequestAds()) startAds();
            }), error -> { if (consent.canRequestAds()) startAds(); });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private Button button(String title, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? WHITE : INK);
        if (primary) {
            GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xff7431aa, 0xffbd265d});
            gradient.setCornerRadius(dp(15));
            button.setBackground(gradient);
        } else button.setBackground(background(PANEL, 15));
        button.setMinHeight(dp(50));
        button.setOnClickListener(listener);
        return button;
    }

    private void add(LinearLayout parent, View view, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(margin);
        parent.addView(view, params);
    }

    private void paste() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData data = clipboard.getPrimaryClip();
        if (data != null && data.getItemCount() > 0)
            input.setText(data.getItemAt(0).coerceToText(this));
    }

    private void pick() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/mp4");
        startActivityForResult(intent, 1);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && result == RESULT_OK && data != null && !busy) select(data.getData());
    }

    private void select(Uri video) {
        clip = video;
        previewReady = false;
        preview.stopPlayback();
        preview.setVisibility(video == null ? View.GONE : View.VISIBLE);
        if (video != null) {
            preview.setOnPreparedListener(mp -> {
                mp.setLooping(false);
                preview.seekTo(1);
                previewReady = true;
                render();
            });
            preview.setVideoURI(video);
            status.setText("Video ready · Tap preview to play or pause");
        }
        render();
    }

    private void resolve() {
        Uri link = Uri.parse(input.getText().toString().trim());
        String host = link.getHost();
        String path = link.getPath();
        if (!"https".equals(link.getScheme()) || link.getUserInfo() != null || link.getPort() != -1
            || !("instagram.com".equals(host) || "www.instagram.com".equals(host))
            || path == null || !path.matches("/(reel|p|tv)/[A-Za-z0-9_-]+/?|/stories/[A-Za-z0-9._]+/[0-9]+/?")) {
            status.setText("Enter a valid Instagram Reel, post or Story link.");
            return;
        }
        if (BuildConfig.RESOLVER_URL.trim().isEmpty()) {
            status.setText("Instagram lookup is unavailable in this build. You can choose a local video.");
            return;
        }
        select(null);
        busy = true;
        render();
        status.setText("Checking video availability…");
        worker.execute(() -> {
            Uri found = null;
            try {
                Uri endpoint = Uri.parse(BuildConfig.RESOLVER_URL);
                if (!"https".equals(endpoint.getScheme())) throw new IOException();
                HttpURLConnection connection = open(endpoint);
                try {
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    JSONObject payload = new JSONObject().put("url", "https://www.instagram.com" + path);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    try (InputStream response = connection.getInputStream()) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        copy(response, bytes, 16384);
                        Uri candidate = Uri.parse(new JSONObject(bytes.toString("UTF-8")).getString("videoUrl"));
                        if ("https".equals(candidate.getScheme()) && endpoint.getHost().equals(candidate.getHost())
                            && endpoint.getPort() == candidate.getPort() && candidate.getUserInfo() == null) {
                            HttpURLConnection probe = open(candidate);
                            try {
                                probe.setRequestMethod("HEAD");
                                verifyVideo(probe);
                                found = candidate;
                            } finally { probe.disconnect(); }
                        }
                    }
                } finally { connection.disconnect(); }
            } catch (Exception ignored) { }
            Uri result = found;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                select(result);
                if (result == null) status.setText("This video is unavailable from the connected source.");
            });
        });
    }

    private static HttpURLConnection open(Uri uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    private static void verifyVideo(HttpURLConnection connection) throws IOException {
        String type = connection.getContentType();
        if (connection.getResponseCode() != 200 || type == null || !type.split(";")[0].equalsIgnoreCase("video/mp4")
            || connection.getContentLengthLong() > MAX_BYTES) throw new IOException();
    }

    private static void copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[32768];
        long count = 0;
        for (int size; (size = input.read(buffer)) != -1;) {
            count += size;
            if (count > limit) throw new IOException();
            output.write(buffer, 0, size);
        }
        if (count == 0) throw new IOException();
    }

    private void save() {
        Uri selected = clip;
        if (selected == null || busy || !previewReady) return;
        busy = true;
        status.setText("Saving video…");
        render();
        worker.execute(() -> {
            Uri output = null;
            boolean saved = false;
            HttpURLConnection connection = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, "ClipKeep-" + System.currentTimeMillis() + ".mp4");
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipKeep");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                if ("https".equals(selected.getScheme())) {
                    connection = open(selected);
                    verifyVideo(connection);
                }
                try (InputStream source = connection == null
                        ? getContentResolver().openInputStream(selected) : connection.getInputStream()) {
                    if (source == null) throw new IOException();
                    output = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                    if (output == null) throw new IOException();
                    try (OutputStream target = getContentResolver().openOutputStream(output)) {
                        if (target == null) throw new IOException();
                        copy(source, target, MAX_BYTES);
                    }
                }
                values.clear();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                saved = getContentResolver().update(output, values, null, null) == 1;
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
                if (!saved && output != null) {
                    try { getContentResolver().delete(output, null, null); } catch (Exception ignored) { }
                }
            }
            boolean result = saved;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                render();
                status.setText(result ? "Saved in Movies / ClipKeep." : "Could not save. Try a valid MP4 under 200 MB.");
            });
        });
    }

    private void startAds() {
        if (adsStarted || BuildConfig.BANNER_ID.isEmpty()) return;
        adsStarted = true;
        MobileAds.initialize(this, result -> runOnUiThread(() -> {
            if (isDestroyed()) return;
            adsReady = true;
            render();
        }));
    }

    private void clearBanner() {
        if (banner == null) return;
        banner.destroy();
        bannerSlot.removeAllViews();
        bannerSlot.setVisibility(View.GONE);
        banner = null;
    }

    private void updateBanner() {
        if (!previewReady || !adsReady || !consent.canRequestAds()) {
            clearBanner();
            return;
        }
        if (banner != null) return;
        AdView view = new AdView(this);
        banner = view;
        view.setAdSize(AdSize.BANNER);
        view.setAdUnitId(BuildConfig.BANNER_ID);
        view.setAdListener(new AdListener() {
            @Override public void onAdLoaded() {
                if (banner == view && previewReady) bannerSlot.setVisibility(View.VISIBLE);
            }
        });
        bannerSlot.addView(view);
        view.loadAd(new AdRequest.Builder().build());
    }

    private void render() {
        check.setEnabled(!busy);
        save.setVisibility(previewReady ? View.VISIBLE : View.GONE);
        save.setEnabled(!busy);
        updateBanner();
    }

    @Override protected void onPause() {
        if (banner != null) banner.pause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (banner != null) banner.resume();
    }

    @Override protected void onDestroy() {
        clearBanner();
        worker.shutdown();
        super.onDestroy();
    }
}

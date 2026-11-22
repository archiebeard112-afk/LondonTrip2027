package com.londontrip.guide;

import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String REPO = "archiebeard112-afk/LondonTrip2027";
    private static final String ACTION_INSTALL_STATUS = "com.londontrip.guide.INSTALL_STATUS";
    private static final int PICK_DOCS = 41;
    private static final long MAX_DOC_BYTES = 30L * 1024 * 1024;

    private WebView web;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile String updateUrl;
    private volatile int updateVersion;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if ("file".equals(url.getScheme())) {
                    return false;
                }
                // Google Flights and Skyscanner open inside the app.
                String title = FlightsActivity.titleFor(url);
                if (title != null) {
                    Intent flights = new Intent(MainActivity.this, FlightsActivity.class);
                    flights.putExtra(FlightsActivity.EXTRA_URL, url.toString());
                    flights.putExtra(FlightsActivity.EXTRA_TITLE, title);
                    startActivity(flights);
                    return true;
                }
                // Map directions and other web links open in Google Maps or the browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException ignored) {
                    // No app can open this link.
                }
                return true;
            }
        });

        if (savedInstanceState == null) {
            web.loadUrl("file:///android_asset/index.html");
        } else {
            web.restoreState(savedInstanceState);
        }

        // Flight price tracking was removed; clear its notification channel from older versions.
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.deleteNotificationChannel("flight_prices");
        }
    }

    /** Methods the page can call through window.AndroidApp. */
    private class Bridge {
        @JavascriptInterface
        public int getVersion() {
            return currentVersion();
        }

        @JavascriptInterface
        public void checkForUpdate() {
            io.execute(MainActivity.this::checkForUpdateNow);
        }

        @JavascriptInterface
        public void installUpdate() {
            io.execute(MainActivity.this::installUpdateNow);
        }

        /** Downloads a weather or Tube status URL and passes the text (or null) to window.onNet(id, text). */
        @JavascriptInterface
        public void getText(String url, String id) {
            io.execute(() -> getTextNow(url, id));
        }

        /** Opens the system picker for screenshots and PDFs; results arrive in window.onDocs(list). */
        @JavascriptInterface
        public void pickDocs() {
            runOnUiThread(() -> {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(pick, PICK_DOCS);
                } catch (ActivityNotFoundException e) {
                    docsMessage("This phone has no file picker.");
                }
            });
        }

        /** Saved confirmations as JSON: [{id, name, type, size, time}], newest first. */
        @JavascriptInterface
        public String listDocs() {
            return docsJson();
        }

        @JavascriptInterface
        public void openDoc(String id) {
            File f = DocsProvider.fileFor(DocsProvider.docsDir(MainActivity.this), id);
            if (f == null) {
                docsMessage("That file is no longer saved.");
                return;
            }
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(DocsProvider.uriFor(id), DocsProvider.typeOf(f.getName()));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            runOnUiThread(() -> {
                try {
                    startActivity(view);
                } catch (ActivityNotFoundException e) {
                    docsMessage("No app on this phone can open this file.");
                }
            });
        }

        @JavascriptInterface
        public boolean deleteDoc(String id) {
            File f = DocsProvider.fileFor(DocsProvider.docsDir(MainActivity.this), id);
            return f != null && f.delete();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_DOCS || resultCode != RESULT_OK || data == null) {
            return;
        }
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        io.execute(() -> {
            int failed = 0;
            for (Uri u : uris) {
                if (!saveDoc(u)) failed++;
            }
            String js = "window.onDocs && window.onDocs(" + JSONObject.quote(docsJson()) + ","
                    + (failed == 0 ? "null" : JSONObject.quote(failed == 1
                        ? "One file couldn't be saved. Only photos, screenshots and PDFs up to 30 MB work."
                        : failed + " files couldn't be saved. Only photos, screenshots and PDFs up to 30 MB work.")) + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        });
    }

    /** Copies a picked file into private storage so it stays available offline. */
    private boolean saveDoc(Uri uri) {
        String type = getContentResolver().getType(uri);
        if (type == null || !(type.startsWith("image/") || type.equals("application/pdf"))) {
            return false;
        }
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        } catch (Exception ignored) {
            // Fall back to a generic name.
        }
        if (name == null || name.trim().isEmpty()) {
            name = type.equals("application/pdf") ? "Confirmation.pdf" : "Screenshot.jpg";
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (type.equals("application/pdf") && !name.toLowerCase().endsWith(".pdf")) {
            name += ".pdf";
        }
        File out = new File(DocsProvider.docsDir(this), System.currentTimeMillis() + "_" + name);
        long total = 0;
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("no stream");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_DOC_BYTES) throw new IOException("too big");
                os.write(buf, 0, n);
            }
        } catch (Exception e) {
            out.delete();
            return false;
        }
        return true;
    }

    private String docsJson() {
        File[] files = DocsProvider.docsDir(this).listFiles();
        JSONArray arr = new JSONArray();
        if (files != null) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : files) {
                if (!f.isFile()) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", f.getName());
                    o.put("name", DocsProvider.displayName(f.getName()));
                    o.put("type", DocsProvider.typeOf(f.getName()));
                    o.put("size", f.length());
                    o.put("time", f.lastModified());
                    o.put("src", DocsProvider.uriFor(f.getName()).toString());
                    arr.put(o);
                } catch (Exception ignored) {
                    // Skip a file we can't describe.
                }
            }
        }
        return arr.toString();
    }

    private void docsMessage(String msg) {
        String js = "window.onDocs && window.onDocs(null," + JSONObject.quote(msg) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    /** Hosts the page may read through getText. */
    private static final String[] NET_HOSTS = {"api.open-meteo.com", "api.tfl.gov.uk"};

    private void getTextNow(String url, String id) {
        String text = null;
        try {
            String host = Uri.parse(url).getHost();
            boolean allowed = url.startsWith("https://");
            boolean known = false;
            for (String h : NET_HOSTS) {
                known |= h.equals(host);
            }
            if (allowed && known) {
                HttpURLConnection conn = open(url);
                conn.setUseCaches(false);
                if (conn.getResponseCode() == 200) {
                    text = readAll(conn.getInputStream());
                }
            }
        } catch (Exception e) {
            text = null;
        }
        String js = "window.onNet && window.onNet(" + JSONObject.quote(id) + ","
                + (text == null ? "null" : JSONObject.quote(text)) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private int currentVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private void checkForUpdateNow() {
        int current = currentVersion();
        try {
            HttpURLConnection conn = open("https://api.github.com/repos/" + REPO + "/releases/latest");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            int code = conn.getResponseCode();
            if (code == 404) {
                report("error", "No releases found. Check that the repo is public and has a release.");
                return;
            }
            if (code != 200) {
                report("error", "GitHub answered with error " + code + ". Try again later.");
                return;
            }
            JSONObject release = new JSONObject(readAll(conn.getInputStream()));
            int latest = parseVersion(release.optString("tag_name", ""));
            String apk = null;
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a.optString("browser_download_url");
                        break;
                    }
                }
            }
            if (latest <= current || apk == null) {
                report("latest", "You have the latest version (v" + current + ").");
                return;
            }
            updateUrl = apk;
            updateVersion = latest;
            report("available", "Version v" + latest + " is ready. You have v" + current + ".");
        } catch (IOException e) {
            report("error", "Couldn't reach GitHub. Check your connection and try again.");
        } catch (Exception e) {
            report("error", "Couldn't read the release info from GitHub.");
        }
    }

    private void installUpdateNow() {
        if (busy) {
            return;
        }
        String url = updateUrl;
        if (url == null) {
            report("error", "Tap Update to check for a new version first.");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException ignored) {
                    // Settings screen missing on this device.
                }
            });
            report("permission", "Turn on \"Allow from this source\" for London Trip, come back, then tap Install again.");
            return;
        }

        busy = true;
        report("downloading", "Downloading v" + updateVersion + "…");
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        int sessionId = -1;
        PackageInstaller.Session session = null;
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);

            HttpURLConnection conn = open(url);
            if (conn.getResponseCode() != 200) {
                throw new IOException("HTTP " + conn.getResponseCode());
            }
            try (InputStream in = conn.getInputStream();
                 OutputStream out = session.openWrite("update.apk", 0, -1)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }

            Intent statusIntent = new Intent(this, MainActivity.class).setAction(ACTION_INSTALL_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pending = PendingIntent.getActivity(this, 0, statusIntent, flags);
            session.commit(pending.getIntentSender());
            session.close();
            report("installing", "Follow the prompt to install the update.");
        } catch (Exception e) {
            if (session != null) {
                session.abandon();
            }
            report("error", "The download didn't finish. Check your connection and try again.");
        } finally {
            busy = false;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    startActivity(confirm);
                } catch (ActivityNotFoundException e) {
                    report("error", "This phone couldn't open the installer.");
                }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            report("latest", "Updated. Restart the app if it didn't reopen.");
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            report("error", "Install cancelled. Tap Update to try again.");
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            report("error", "Install failed" + (msg != null ? ": " + msg : ".")
                    + " If it mentions a conflict, uninstall London Trip and install the new APK once.");
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "LondonTrip-Android");
        return conn;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static int parseVersion(String tag) {
        String digits = tag.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void report(String state, String message) {
        String js = "window.onUpdateStatus && window.onUpdateStatus("
                + JSONObject.quote(state) + "," + JSONObject.quote(message) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}

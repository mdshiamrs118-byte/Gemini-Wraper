package app.castor.shell;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String DEFAULT_HOME = "https://gemini.google.com/app";
    private static final Pattern INTERNAL = Pattern.compile(
            "(^|.*\\.)(google\\.[a-z]{2,3}(\\.[a-z]{2})?|youtube\\.com|gstatic\\.com|googleusercontent\\.com|googleapis\\.com)$");
    private static final int REQ_FILE = 11, REQ_MIC = 12;
    private static final int ACCENT = 0xFF8AB4F8, TEXT = 0xFFE8EAED, MUTED = 0xFF9AA0A6;

    private static final String ERR_HTML =
            "<meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<style>body{font:16px sans-serif;display:flex;align-items:center;justify-content:center;"
            + "height:90vh;margin:0;text-align:center;color:#888}</style>"
            + "<body><div><h2>No connection</h2>Pull down to try again.</div></body>";

    // Tells native code whether the thing under the finger is scrolled to its top (for pull-to-refresh).
    private static final String SCROLL_JS =
            "(function(){if(window.__castor)return;window.__castor=1;"
            + "function sc(e){while(e&&e!==document.body&&e!==document.documentElement){"
            + "var o=getComputedStyle(e).overflowY;"
            + "if((o==='auto'||o==='scroll')&&e.scrollHeight>e.clientHeight+1)return e;e=e.parentElement;}return null;}"
            + "var cur=null;"
            + "function rep(){var t=cur?cur.scrollTop<=0:(window.pageYOffset||document.documentElement.scrollTop||0)<=0;"
            + "CastorBridge.atTop(t);}"
            + "addEventListener('touchstart',function(e){cur=sc(e.target);rep();},{passive:true,capture:true});"
            + "addEventListener('scroll',function(e){var t=e.target;"
            + "if(t===cur||(!cur&&(t===document||t===document.documentElement)))rep();},{passive:true,capture:true});"
            + "})();";

    private SharedPreferences prefs;
    private Accounts accounts;
    private UiCache cache;
    private boolean multiProfile, desktop, immersive, panelOpen;
    private volatile boolean atTop = true;
    private volatile String ua = "";
    private String failedUrl;
    private float dp;

    private FrameLayout root, webHost;
    private SwipeRefreshLayout swipe;
    private ProgressBar progress;
    private View scrim, handleArea;
    private LinearLayout panel, panelList;
    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendingPerm;

    /* ------------------------------------------------------------------ lifecycle */

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("castor", MODE_PRIVATE);
        accounts = new Accounts(prefs);
        cache = new UiCache(getCacheDir());
        multiProfile = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
        desktop = prefs.getBoolean("desktop", false);
        immersive = prefs.getBoolean("immersive", true);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        webHost = findViewById(R.id.webHost);
        swipe = findViewById(R.id.swipe);
        progress = findViewById(R.id.progress);
        scrim = findViewById(R.id.scrim);
        handleArea = findViewById(R.id.handleArea);
        panel = findViewById(R.id.panel);
        panelList = findViewById(R.id.panelList);

        // Keep content clear of bars / notch / keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        applyBars();

        // Pull to refresh.
        swipe.setColorSchemeColors(ACCENT);
        swipe.setProgressBackgroundColorSchemeColor(0xFF2B2D31);
        swipe.setDistanceToTriggerSync(px(96));
        swipe.setOnChildScrollUpCallback((parent, child) -> !atTop);
        swipe.setOnRefreshListener(() -> {
            if (failedUrl != null) { String f = failedUrl; failedUrl = null; web.loadUrl(f); }
            else web.reload();
        });

        // Edge handle: tap or drag left to open.
        handleArea.setOnTouchListener(new View.OnTouchListener() {
            float x0; boolean fired;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: x0 = e.getRawX(); fired = false; break;
                    case MotionEvent.ACTION_MOVE:
                        if (!fired && x0 - e.getRawX() > px(20)) { fired = true; setPanel(true); }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (!fired && Math.abs(x0 - e.getRawX()) < px(10)) { v.performClick(); setPanel(true); }
                        break;
                }
                return true;
            }
        });
        // Stop the system back-gesture from stealing touches on the handle.
        handleArea.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (Build.VERSION.SDK_INT >= 29) {
                v.setSystemGestureExclusionRects(
                        Collections.singletonList(new Rect(0, 0, v.getWidth(), v.getHeight())));
            }
        });
        scrim.setOnClickListener(v -> setPanel(false));
        panel.setTranslationX(px(340));

        String start = home();
        Uri d = getIntent().getData();
        if (d != null && "gemini.google.com".equals(d.getHost())) start = d.toString();
        openAccount(accounts.current(), start);
        cache.trimAsync();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri d = intent.getData();
        if (d != null && "gemini.google.com".equals(d.getHost()) && web != null) web.loadUrl(d.toString());
    }

    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        flushCookies();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) { webHost.removeView(web); web.destroy(); web = null; }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyBars();
    }

    @Override
    public void onBackPressed() {
        if (panelOpen) setPanel(false);
        else if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /* ------------------------------------------------------------------ WebView */

    private void openAccount(Accounts.Account a, String url) {
        if (web != null) { webHost.removeView(web); web.destroy(); web = null; }
        WebView w = new WebView(this);
        if (multiProfile) {
            try {
                ProfileStore.getInstance().getOrCreateProfile(a.id);
                WebViewCompat.setProfile(w, a.id);
            } catch (Exception e) {
                multiProfile = false;
            }
        }
        configure(w);
        webHost.addView(w, new FrameLayout.LayoutParams(-1, -1));
        web = w;
        accounts.setActive(a.id);
        atTop = true;
        failedUrl = null;
        w.loadUrl(url);
        rebuildPanel();
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void configure(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        applyViewMode(s);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, Collections.<String>emptySet());
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setBackgroundColor(Color.TRANSPARENT);
        w.addJavascriptInterface(new Bridge(), "CastorBridge");

        w.setDownloadListener((url, agent, cd, mime, len) -> handleDownload(url, agent, cd, mime));

        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                return !openInApp(r.getUrl());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return cache.handle(r, ua);
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap icon) {
                atTop = true;
                progress.setProgress(8);
                progress.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView v, String url) {
                progress.setVisibility(View.GONE);
                swipe.setRefreshing(false);
                v.evaluateJavascript(SCROLL_JS, null);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (!r.isForMainFrame()) return;
                failedUrl = r.getUrl().toString();
                swipe.setRefreshing(false);
                v.loadDataWithBaseURL(failedUrl, ERR_HTML, "text/html", "utf-8", failedUrl);
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { progress.setProgress(Math.max(p, 8)); }

            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }

            @Override public void onPermissionRequest(PermissionRequest r) {
                runOnUiThread(() -> {
                    if (!"gemini.google.com".equals(r.getOrigin().getHost())) { r.deny(); return; }
                    boolean wantsMic = false;
                    for (String res : r.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) wantsMic = true;
                    }
                    if (!wantsMic) { r.deny(); return; }
                    String[] mic = {PermissionRequest.RESOURCE_AUDIO_CAPTURE};
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        r.grant(mic);
                    } else {
                        pendingPerm = r;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    }
                });
            }
        });

        ua = w.getSettings().getUserAgentString();
    }

    private void applyViewMode(WebSettings s) {
        ua = makeUa();
        s.setUserAgentString(ua);
        s.setUseWideViewPort(desktop);
        s.setLoadWithOverviewMode(desktop);
        s.setSupportZoom(desktop);
        s.setBuiltInZoomControls(desktop);
        s.setDisplayZoomControls(false);
    }

    /** Looks like regular Chrome so Google sign-in doesn't reject the embedded WebView. */
    private String makeUa() {
        String def = WebSettings.getDefaultUserAgent(this);
        String ver = "126.0.0.0";
        Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(def);
        if (m.find()) ver = m.group(1);
        if (desktop) {
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + ver + " Safari/537.36";
        }
        return def.replace("; wv", "").replace("Version/4.0 ", "");
    }

    /** True = load inside the app. Anything else is handed to the system. */
    private boolean openInApp(Uri u) {
        String s = u.getScheme(), h = u.getHost();
        if (s == null) return true;
        if (s.equals("http") || s.equals("https")) {
            if (h != null && INTERNAL.matcher(h).matches()) return true;
            String hh = homeHost();
            if (h != null && hh != null && (h.equals(hh) || h.endsWith("." + hh))) return true;
        } else if (s.equals("about") || s.equals("blob") || s.equals("data") || s.equals("javascript")) {
            return true;
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
        return false;
    }

    private final class Bridge {
        @JavascriptInterface public void atTop(boolean v) { atTop = v; }
        @JavascriptInterface public void saveBlob(String dataUrl) { saveDataUrl(dataUrl); }
    }

    private void flushCookies() {
        try {
            if (multiProfile) {
                Profile p = ProfileStore.getInstance().getProfile(accounts.currentId());
                if (p != null) p.getCookieManager().flush();
            } else {
                CookieManager.getInstance().flush();
            }
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_MIC && pendingPerm != null) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) pendingPerm.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pendingPerm.deny();
            pendingPerm = null;
        }
    }

    /* ------------------------------------------------------------------ system bars */

    private void applyBars() {
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        c.setAppearanceLightStatusBars(!night);
        c.setAppearanceLightNavigationBars(!night);
        if (immersive) c.hide(WindowInsetsCompat.Type.systemBars());
        else c.show(WindowInsetsCompat.Type.systemBars());
    }

    /* ------------------------------------------------------------------ side panel */

    private void setPanel(boolean open) {
        if (open == panelOpen) return;
        panelOpen = open;
        if (open) {
            rebuildPanel();
            scrim.setVisibility(View.VISIBLE);
            panel.setVisibility(View.VISIBLE);
            panel.animate().translationX(0).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
            scrim.animate().alpha(1f).setDuration(220).start();
            handleArea.animate().alpha(0f).setDuration(150).start();
        } else {
            panel.animate().translationX(px(340)).setDuration(180).setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        if (!panelOpen) { panel.setVisibility(View.INVISIBLE); scrim.setVisibility(View.GONE); }
                    }).start();
            scrim.animate().alpha(0f).setDuration(180).start();
            handleArea.animate().alpha(1f).setDuration(200).start();
        }
    }

    private void rebuildPanel() {
        panelList.removeAllViews();

        section("ACCOUNTS");
        if (multiProfile) {
            for (Accounts.Account a : accounts.all()) addAccountRow(a);
            panelList.addView(row(icon(R.drawable.ic_add), "Add account", null, null, this::promptAddAccount), rowLp());
        } else {
            TextView n = text("Separate accounts need a newer Android System WebView. Update it in Play Store.", 12, MUTED);
            n.setPadding(px(20), px(4), px(20), px(8));
            panelList.addView(n);
        }

        section("NAVIGATE");
        panelList.addView(row(icon(R.drawable.ic_home), "Home", null, null, () -> go(home())), rowLp());
        if (!prefs.getString("home", "").isEmpty()) {
            panelList.addView(row(icon(R.drawable.ic_chat), "Open Gemini", null, null, () -> go(DEFAULT_HOME)), rowLp());
        }
        panelList.addView(row(icon(R.drawable.ic_refresh), "Reload", null, null, () -> {
            if (web != null) web.reload();
            setPanel(false);
        }), rowLp());

        section("SETTINGS");
        String h = prefs.getString("home", "");
        panelList.addView(row(icon(R.drawable.ic_link), "Home page",
                h.isEmpty() ? "Gemini (default)" : h.replaceFirst("^https?://", ""), null, this::promptHome), rowLp());
        panelList.addView(row(icon(R.drawable.ic_download), "Downloads",
                prefs.getInt("dl", 0) == 0 ? "System download manager" : "Ask which app", null, this::chooseDownloader), rowLp());
        switchRow(R.drawable.ic_desktop, "Desktop site", desktop, on -> {
            desktop = on;
            prefs.edit().putBoolean("desktop", on).apply();
            if (web != null) { applyViewMode(web.getSettings()); web.reload(); }
            setPanel(false);
        });
        switchRow(R.drawable.ic_fullscreen, "Full screen", immersive, on -> {
            immersive = on;
            prefs.edit().putBoolean("immersive", on).apply();
            applyBars();
        });

        section("TOOLS");
        panelList.addView(row(icon(R.drawable.ic_share), "Share link", null, null, () -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, currentUrl());
            try { startActivity(Intent.createChooser(i, null)); } catch (Exception ignored) { }
            setPanel(false);
        }), rowLp());
        panelList.addView(row(icon(R.drawable.ic_open), "Open in browser", null, null, () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl()))); } catch (Exception ignored) { }
            setPanel(false);
        }), rowLp());
        panelList.addView(row(icon(R.drawable.ic_download), "Open downloads", null, null, () -> {
            try { startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); } catch (Exception ignored) { }
            setPanel(false);
        }), rowLp());

        section("STORAGE");
        panelList.addView(row(icon(R.drawable.ic_delete), "Clear UI cache", fmt(cache.size()) + " saved", null, () -> {
            cache.clear();
            if (web != null) web.clearCache(true);
            Toast.makeText(this, "UI cache cleared", Toast.LENGTH_SHORT).show();
            rebuildPanel();
        }), rowLp());
    }

    /* ------------------------------------------------------------------ home page */

    private String home() {
        String h = prefs.getString("home", "");
        return h.isEmpty() ? DEFAULT_HOME : h;
    }

    private String homeHost() {
        String host = Uri.parse(home()).getHost();
        if (host != null && host.startsWith("www.")) host = host.substring(4);
        return host;
    }

    private String currentUrl() {
        return web != null && web.getUrl() != null ? web.getUrl() : home();
    }

    private void promptHome() {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(prefs.getString("home", ""));
        et.setHint(DEFAULT_HOME);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(px(24), px(8), px(24), 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("Home page")
                .setMessage("The page the app opens. Leave empty to use Gemini.")
                .setView(box)
                .setPositiveButton("Save", (dlg, which) -> saveHome(et.getText().toString()))
                .setNeutralButton("Current page", (dlg, which) -> saveHome(currentUrl()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveHome(String raw) {
        String u = raw.trim();
        if (!u.isEmpty()) {
            if (!u.contains("://")) u = "https://" + u;
            else if (u.startsWith("http://")) u = "https://" + u.substring(7);
            if (Uri.parse(u).getHost() == null) {
                Toast.makeText(this, "That doesn't look like a link", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (u.equals(DEFAULT_HOME)) u = "";
        prefs.edit().putString("home", u).apply();
        Toast.makeText(this, u.isEmpty() ? "Home page: Gemini" : "Home page saved", Toast.LENGTH_SHORT).show();
        rebuildPanel();
    }

    /* ------------------------------------------------------------------ downloads */

    private void chooseDownloader() {
        new AlertDialog.Builder(this)
                .setTitle("Downloads")
                .setSingleChoiceItems(new String[]{"System download manager", "Ask which app (1DM, ADM, browser...)"},
                        prefs.getInt("dl", 0), (dlg, which) -> {
                            prefs.edit().putInt("dl", which).apply();
                            dlg.dismiss();
                            rebuildPanel();
                        })
                .show();
    }

    private void handleDownload(String url, String agent, String cd, String mime) {
        if (url.startsWith("data:")) {
            new Thread(() -> saveDataUrl(url)).start();
            return;
        }
        if (url.startsWith("blob:")) {
            if (web != null) {
                web.evaluateJavascript("(function(u){fetch(u).then(function(r){return r.blob()}).then(function(b){"
                        + "var f=new FileReader();f.onloadend=function(){CastorBridge.saveBlob(String(f.result))};"
                        + "f.readAsDataURL(b)})})('" + url.replace("'", "%27") + "')", null);
            }
            return;
        }
        if (prefs.getInt("dl", 0) == 1 || !(url.startsWith("http://") || url.startsWith("https://"))) {
            externalDownload(url);
            return;
        }
        try {
            String name = URLUtil.guessFileName(url, cd, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (mime != null && !mime.isEmpty()) r.setMimeType(mime);
            String cookie = cookieFor(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            r.addRequestHeader("User-Agent", agent);
            if (web != null && web.getUrl() != null) r.addRequestHeader("Referer", web.getUrl());
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= 29) r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            else r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            externalDownload(url);
        }
    }

    private void externalDownload(String url) {
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Download with"));
        } catch (Exception e) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
    }

    private String cookieFor(String url) {
        try {
            if (multiProfile) {
                Profile p = ProfileStore.getInstance().getProfile(accounts.currentId());
                if (p != null) return p.getCookieManager().getCookie(url);
            }
            return CookieManager.getInstance().getCookie(url);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Saves a data: URL (also used for blob: URLs, which the page converts to data: first). */
    private void saveDataUrl(String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            String head = dataUrl.substring(5, comma);
            String mime = head.split(";")[0];
            if (mime.isEmpty()) mime = "application/octet-stream";
            String body = dataUrl.substring(comma + 1);
            byte[] bytes = head.contains(";base64")
                    ? Base64.decode(body, Base64.DEFAULT)
                    : Uri.decode(body).getBytes("UTF-8");
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            String name = "castor_" + System.currentTimeMillis() + (ext != null ? "." + ext : "");
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(bytes); }
            } else {
                File f = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
            }
            final String shown = name;
            runOnUiThread(() -> Toast.makeText(this, "Saved to Downloads: " + shown, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show());
        }
    }

    private void addAccountRow(Accounts.Account a) {
        boolean active = a.id.equals(accounts.currentId());
        TextView av = new TextView(this);
        av.setText(a.name.isEmpty() ? "?" : a.name.substring(0, 1).toUpperCase(Locale.getDefault()));
        av.setGravity(Gravity.CENTER);
        av.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        av.setTypeface(Typeface.DEFAULT_BOLD);
        av.setTextColor(active ? 0xFF0B1220 : TEXT);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(active ? ACCENT : 0xFF3C4043);
        av.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(36), px(36));
        lp.rightMargin = px(14);
        av.setLayoutParams(lp);

        View trail = null;
        if (active) {
            trail = small(R.drawable.ic_check, ACCENT);
        } else if (accounts.size() > 1) {
            ImageView d = small(R.drawable.ic_close, MUTED);
            d.setOnClickListener(v -> confirmDelete(a));
            trail = d;
        }
        LinearLayout r = row(av, a.name, active ? "Active" : null, trail, null);
        if (!active) r.setOnClickListener(v -> { setPanel(false); openAccount(a, home()); });
        panelList.addView(r, rowLp());
    }

    private void promptAddAccount() {
        EditText et = new EditText(this);
        et.setHint("e.g. Work");
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(px(24), px(8), px(24), 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("New account")
                .setView(box)
                .setPositiveButton("Add", (dlg, which) -> {
                    String n = et.getText().toString().trim();
                    Accounts.Account a = accounts.add(n.isEmpty() ? "Account " + (accounts.size() + 1) : n);
                    setPanel(false);
                    openAccount(a, home());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(Accounts.Account a) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + a.name + "?")
                .setMessage("This signs out and deletes all data stored for this account in the app.")
                .setPositiveButton("Remove", (dlg, which) -> {
                    accounts.remove(a);
                    if (multiProfile) {
                        try { ProfileStore.getInstance().deleteProfile(a.id); } catch (Exception ignored) { }
                    }
                    rebuildPanel();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void go(String url) {
        failedUrl = null;
        if (web != null) web.loadUrl(url);
        setPanel(false);
    }

    /* ------------------------------------------------------------------ small UI helpers */

    private int px(int v) { return Math.round(v * dp); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setMaxLines(sp <= 12 ? 3 : 1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private void section(String title) {
        TextView t = text(title, 11, MUTED);
        t.setLetterSpacing(0.1f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(px(20), px(16), px(20), px(6));
        panelList.addView(t);
    }

    private ImageView icon(int res) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(TEXT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(24), px(24));
        lp.rightMargin = px(16);
        lp.leftMargin = px(6);
        iv.setLayoutParams(lp);
        return iv;
    }

    private ImageView small(int res, int tint) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        iv.setPadding(px(6), px(6), px(6), px(6));
        iv.setLayoutParams(new LinearLayout.LayoutParams(px(32), px(32)));
        return iv;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(px(8), px(1), px(8), px(1));
        return lp;
    }

    private LinearLayout row(View lead, String title, String sub, View trail, Runnable click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(12), px(6), px(8), px(6));
        r.setMinimumHeight(px(52));
        r.setBackgroundResource(R.drawable.row_bg);
        r.setClickable(true);
        if (lead != null) r.addView(lead);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(title, 15, TEXT));
        if (sub != null) t.addView(text(sub, 12, MUTED));
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trail != null) r.addView(trail);
        if (click != null) r.setOnClickListener(v -> click.run());
        return r;
    }

    private void switchRow(int icon, String title, boolean on, Consumer<Boolean> cb) {
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setClickable(false);
        LinearLayout r = row(icon(icon), title, null, sw, null);
        r.setOnClickListener(v -> { sw.toggle(); cb.accept(sw.isChecked()); });
        panelList.addView(r, rowLp());
    }

    private static String fmt(long b) {
        if (b < 1024) return b + " B";
        if (b < (1 << 20)) return (b / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }
}

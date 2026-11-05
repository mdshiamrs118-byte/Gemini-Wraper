package app.castor.shell;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;

/**
 * Disk cache for the web UI (JS, CSS, fonts, images served from Google's static hosts).
 * First load: downloaded and saved. Next loads: served straight from disk, no network.
 * Account data / chat content is never touched; only versioned static assets are stored.
 */
final class UiCache {
    private static final long MAX_BYTES = 200L << 20;           // total cap
    private static final long MAX_ITEM = 6L << 20;              // per-file cap
    private static final long MAX_AGE = 14L * 24 * 3600 * 1000; // refresh after 14 days
    private static final Set<String> EXT = new HashSet<>(Arrays.asList(
            "js", "mjs", "css", "woff", "woff2", "ttf", "otf", "png", "jpg", "jpeg",
            "gif", "svg", "webp", "ico", "wasm"));

    private final File dir;

    UiCache(File cacheRoot) {
        dir = new File(cacheRoot, "ui");
        dir.mkdirs();
    }

    static boolean cacheable(Uri u) {
        if (!"https".equals(u.getScheme())) return false;
        String host = u.getHost(), path = u.getPath();
        if (host == null || path == null) return false;
        if (host.equals("gstatic.com") || host.endsWith(".gstatic.com")) return true;
        if (host.equals("gemini.google.com")) {
            return path.contains("/_/js/") || path.contains("/_/ss/") || EXT.contains(ext(path));
        }
        return false;
    }

    private static String ext(String p) {
        int s = p.lastIndexOf('/'), d = p.lastIndexOf('.');
        return d > s ? p.substring(d + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** Called from WebViewClient.shouldInterceptRequest (background thread). Null = let WebView handle it. */
    WebResourceResponse handle(WebResourceRequest req, String ua) {
        try {
            if (!"GET".equals(req.getMethod())) return null;
            Uri u = req.getUrl();
            if (!cacheable(u)) return null;
            Map<String, String> rh = req.getRequestHeaders();
            if (rh.containsKey("Range")) return null;

            String key = sha1(u.toString());
            File data = new File(dir, key), meta = new File(dir, key + ".m");
            String origin = rh.get("Origin");

            if (data.isFile() && meta.isFile()
                    && System.currentTimeMillis() - meta.lastModified() < MAX_AGE) {
                String[] m = readMeta(meta);
                if (m != null) {
                    data.setLastModified(System.currentTimeMillis()); // LRU touch
                    return respond(m[0], m[1], new FileInputStream(data), origin);
                }
            }
            return fetch(u.toString(), rh, ua, data, meta, origin);
        } catch (Exception e) {
            return null;
        }
    }

    private WebResourceResponse fetch(String url, Map<String, String> rh, String ua,
                                      File data, File meta, String origin) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        String agent = rh.get("User-Agent");
        c.setRequestProperty("User-Agent", agent != null ? agent : ua);
        for (String h : new String[]{"Accept", "Accept-Language", "Referer"}) {
            String v = rh.get(h);
            if (v != null) c.setRequestProperty(h, v);
        }
        int code = c.getResponseCode();
        String ct = c.getContentType();
        if (code != 200 || ct == null || ct.startsWith("text/html") || c.getContentLengthLong() > MAX_ITEM) {
            c.disconnect();
            return null;
        }
        File tmp = new File(dir, data.getName() + ".t" + System.nanoTime());
        long total = 0;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_ITEM) throw new IOException("too big");
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            tmp.delete();
            c.disconnect();
            return null;
        }
        c.disconnect();

        String mime = ct.split(";")[0].trim();
        String enc = charset(ct);
        if (enc == null && (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")))
            enc = "utf-8";
        if (!tmp.renameTo(data)) { tmp.delete(); return null; }
        try (FileWriter w = new FileWriter(meta)) { w.write(mime + "\n" + (enc == null ? "" : enc)); }
        return respond(mime, enc, new FileInputStream(data), origin);
    }

    private static WebResourceResponse respond(String mime, String enc, InputStream in, String origin) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", origin != null ? origin : "*");
        if (origin != null) h.put("Access-Control-Allow-Credentials", "true");
        h.put("Cross-Origin-Resource-Policy", "cross-origin");
        h.put("Cache-Control", "public, max-age=86400");
        return new WebResourceResponse(mime, enc == null || enc.isEmpty() ? null : enc, 200, "OK", h, in);
    }

    private static String charset(String ct) {
        for (String part : ct.split(";")) {
            part = part.trim();
            if (part.toLowerCase(Locale.ROOT).startsWith("charset=")) return part.substring(8).replace("\"", "");
        }
        return null;
    }

    private static String[] readMeta(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String mime = r.readLine(), enc = r.readLine();
            return mime == null ? null : new String[]{mime, enc == null ? "" : enc};
        } catch (IOException e) {
            return null;
        }
    }

    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    long size() {
        long s = 0;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) s += f.length();
        return s;
    }

    void clear() {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    /** Drops expired entries and, if over the size cap, the least recently used ones. */
    void trimAsync() {
        new Thread(() -> {
            File[] fs = dir.listFiles();
            if (fs == null) return;
            long now = System.currentTimeMillis(), total = 0;
            List<File> datas = new ArrayList<>();
            for (File f : fs) {
                String n = f.getName();
                if (n.contains(".t")) { if (now - f.lastModified() > 3600_000L) f.delete(); continue; }
                if (n.endsWith(".m")) continue;
                File meta = new File(dir, n + ".m");
                if (!meta.isFile() || now - meta.lastModified() > MAX_AGE) { f.delete(); meta.delete(); continue; }
                total += f.length();
                datas.add(f);
            }
            if (total > MAX_BYTES) {
                Collections.sort(datas, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (File f : datas) {
                    if (total <= MAX_BYTES * 8 / 10) break;
                    total -= f.length();
                    f.delete();
                    new File(dir, f.getName() + ".m").delete();
                }
            }
        }, "castor-trim").start();
    }
}

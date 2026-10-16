package com.babayaga.scrapetest;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test app v0.4: Flashscore walker.
 * Walk = from a Flashscore match link (the one with ?mid=): open the H2H page, find each
 * team's last 5 matches, open each one, read its Stats for Match / 1st half / 2nd half,
 * and save everything as one JSON file (app folder + Downloads).
 * Open and Page data still work on any site (FotMob etc.).
 */
public class MainActivity extends Activity {

    private static final String SNIFFER =
            "(function(){"
          + "if(window.__bb)return;window.__bb=1;"
          + "var bad=['google','doubleclick','facebook','sentry','analytics','clarity','hotjar','adnxs',"
          + "'criteo','pubmatic','rubiconproject','onetrust','cookielaw','amazon-adsystem','taboola','outbrain'];"
          + "function skip(u){u=String(u).toLowerCase();for(var i=0;i<bad.length;i++){if(u.indexOf(bad[i])>=0)return true;}return false;}"
          + "var of=window.fetch;"
          + "if(of){window.fetch=function(){var a=arguments;"
          + "var u=(typeof a[0]==='string')?a[0]:((a[0]&&a[0].url)||'');"
          + "return of.apply(this,a).then(function(r){try{if(!skip(u)){"
          + "var ct=(r.headers.get('content-type')||'').toLowerCase();"
          + "if(ct.indexOf('image')<0&&ct.indexOf('font')<0&&ct.indexOf('css')<0&&ct.indexOf('video')<0&&ct.indexOf('audio')<0){"
          + "r.clone().text().then(function(t){Android.captured(String(r.url||u),r.status,t);});}}}catch(e){}return r;});};}"
          + "var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;"
          + "XMLHttpRequest.prototype.open=function(m,u){this.__u=String(u);return oo.apply(this,arguments);};"
          + "XMLHttpRequest.prototype.send=function(){var x=this;x.addEventListener('load',function(){try{"
          + "if(!skip(x.__u)){var t=(x.responseType===''||x.responseType==='text')?x.responseText:'';"
          + "Android.captured(x.__u,x.status,t);}}catch(e){}});return os.apply(this,arguments);};"
          + "})();";

    private static final String PAGE_JS =
            "(function(){try{"
          + "var h=location.hostname+'_';"
          + "var n=document.getElementById('__NEXT_DATA__');"
          + "if(n){Android.pageData(h+'next_data.json',n.textContent);}"
          + "Android.pageData(h+'page_text.txt',document.body?document.body.innerText:'');"
          + "Android.pageData(h+'page_html.html',document.documentElement.outerHTML);"
          + "}catch(e){Android.pageData('script-error.txt',String(e));}})();";

    /** Everything visible on the page, as text. */
    private static final String SNAP_JS =
            "(function(){return document.body?document.body.innerText:'';})()";

    /** Lists the H2H rows (and the section title above each) on the H2H page. */
    private static final String DISCOVER_JS =
            "(function(){try{"
          + "var sel='[class*=h2h__row]';"
          + "var tsel='[class*=section__title]';"
          + "function arr(x){return Array.prototype.slice.call(x);}"
          + "function top(list,s){return list.filter(function(e){return !(e.parentElement&&e.parentElement.closest(s));});}"
          + "var rows=top(arr(document.querySelectorAll(sel)),sel);"
          + "var titles=top(arr(document.querySelectorAll(tsel)),tsel);"
          + "var out=[];"
          + "rows.forEach(function(r,i){"
          + "var s='';"
          + "titles.forEach(function(t){if(t.compareDocumentPosition(r)&4){s=(t.innerText||'').trim().replace(/\\s+/g,' ');}});"
          + "var a=(r.tagName==='A')?r:r.querySelector('a[href*=\"mid=\"],a[href*=\"/match/\"]');"
          + "if(!a){a=r.closest('a');}"
          + "var h=(a&&a.href&&(a.href.indexOf('mid=')>=0||a.href.indexOf('/match/')>=0))?a.href:'';"
          + "out.push({i:i,s:s,t:(r.innerText||'').trim().replace(/\\s+/g,' ').slice(0,120),h:h});});"
          + "var cls={};arr(document.querySelectorAll('[class*=h2h]')).forEach(function(e){cls[String(e.className)]=1;});"
          + "return JSON.stringify({rows:out,classes:Object.keys(cls).slice(0,25)});"
          + "}catch(e){return JSON.stringify({rows:[],classes:['error '+e]});}})()";

    /** Clicks the n-th H2H row (same row list as DISCOVER_JS). */
    private static final String CLICK_ROW_JS =
            "(function(n){var sel='[class*=h2h__row]';"
          + "var rows=Array.prototype.slice.call(document.querySelectorAll(sel)).filter(function(e){return !(e.parentElement&&e.parentElement.closest(sel));});"
          + "var r=rows[n];if(!r)return 'norow';"
          + "r.scrollIntoView();"
          + "var a=(r.tagName==='A')?r:r.querySelector('a[href*=\"mid=\"],a[href*=\"/match/\"]');"
          + "(a||r).click();return 'ok';})(__N__);";

    /** Clicks the smallest visible element whose text is exactly the given text. */
    private static final String CLICK_TEXT_JS =
            "(function(txt){"
          + "var c=document.querySelectorAll('a,button,li,span,div,[role=tab]');"
          + "var best=null,any=null;"
          + "for(var i=0;i<c.length;i++){var e=c[i];"
          + "var t=(e.innerText||'').trim().replace(/\\s+/g,' ').toLowerCase();"
          + "if(t!==txt)continue;"
          + "var n=e.getElementsByTagName('*').length;"
          + "if(!any||n<any.n)any={e:e,n:n};"
          + "if(e.offsetParent!==null&&(!best||n<best.n))best={e:e,n:n};}"
          + "var h=best||any;if(!h)return 'notfound';"
          + "h.e.scrollIntoView();h.e.click();return 'ok';})('__TXT__');";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();
    private final AtomicInteger counter = new AtomicInteger();

    private EditText input;
    private WebView webView;
    private TextView logView;
    private ScrollView scroll;

    // walker state (all touched on the UI thread)
    private volatile boolean walking = false;
    private Runnable onLoaded;
    private int loadToken = 0;
    private String mid = "";
    private String h2hUrl = "";
    private JSONObject result;
    private JSONArray matchesOut;
    private final List<Item> items = new ArrayList<>();
    private int cur = 0;
    private Uri lastSavedUri;

    private static class Item {
        String group = "";
        String text = "";
        String href = "";
        String url = "";
        int row = -1;
    }

    private interface Cb {
        void run(String s);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        input = new EditText(this);
        input.setHint("Paste a match link (Flashscore with ?mid=, FotMob...)");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(3);
        root.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(makeRow(
                makeButton("Open", v -> openUrl()),
                makeButton("Walk", v -> startWalk()),
                makeButton("Stop", v -> stopWalk())));
        root.addView(makeRow(
                makeButton("Page data", v -> pageData()),
                makeButton("Share log", v -> shareLog()),
                makeButton("Share data", v -> shareData())));

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                view.evaluateJavascript(SNIFFER, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(SNIFFER, null);
                if (!walking) log("Loaded: " + url);
                if (walking && onLoaded != null) {
                    final Runnable r = onLoaded;
                    onLoaded = null;
                    ui.postDelayed(() -> {
                        if (walking) r.run();
                    }, 3500);
                }
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f));

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // A link sent with another app's Share button: Flashscore links start the walk,
        // anything else just opens.
        Intent in = getIntent();
        if (in != null && Intent.ACTION_SEND.equals(in.getAction())) {
            String shared = in.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null) {
                input.setText(shared);
                log("Received shared text:\n" + shared);
                if (shared.contains("flashscore.") && shared.contains("mid=")) {
                    startWalk();
                } else {
                    openUrl();
                }
            }
        }
    }

    private Button makeButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12f);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout makeRow(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b : buttons) {
            row.addView(b, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack() && !walking) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------- simple buttons

    private void openUrl() {
        String text = input.getText().toString();
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        if (!m.find()) {
            log("No link found in the box. Paste a match link first.");
            return;
        }
        String url = m.group();
        log("Opening " + url);
        webView.loadUrl(url);
    }

    private void pageData() {
        String page = webView.getUrl();
        if (page == null) {
            log("Open a page first (tap Open).");
            return;
        }
        log("Reading page data from " + page);
        webView.evaluateJavascript(PAGE_JS, null);
    }

    private void shareLog() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        synchronized (logBuf) {
            send.putExtra(Intent.EXTRA_TEXT, logBuf.toString());
        }
        startActivity(Intent.createChooser(send, "Share log"));
    }

    private void shareData() {
        if (lastSavedUri == null) {
            log("No saved walk file yet. Run Walk first (the file is in Downloads).");
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/json");
        send.putExtra(Intent.EXTRA_STREAM, lastSavedUri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share data"));
    }

    // ---------------------------------------------------------------- the walker

    private void startWalk() {
        if (walking) {
            log("Already walking. Tap Stop first.");
            return;
        }
        String text = input.getText().toString();
        Matcher m = Pattern.compile(
                "https?://((?:www\\.)?flashscore\\.[a-z.]+)/match/([^/?#\\s]+)/([^/?#\\s]+)/([^/?#\\s]+)")
                .matcher(text);
        Matcher mm = Pattern.compile("[?&]mid=([A-Za-z0-9]+)").matcher(text);
        if (!m.find() || !mm.find()) {
            log("Walk needs a Flashscore match link that has ?mid= in it.");
            return;
        }
        mid = mm.group(1);
        h2hUrl = "https://" + m.group(1) + "/match/" + m.group(2) + "/" + m.group(3) + "/"
                + m.group(4) + "/h2h/overall/?mid=" + mid;

        result = new JSONObject();
        matchesOut = new JSONArray();
        items.clear();
        cur = 0;
        jput(result, "mid", mid);
        jput(result, "h2h_url", h2hUrl);
        walking = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        log("WALK started for " + mid + " (keep the app open until it says finished)");
        loadThen(h2hUrl, this::readH2H);
    }

    private void stopWalk() {
        if (!walking) {
            log("Not walking.");
            return;
        }
        log("Stopped by you. Saving what was collected so far.");
        finish();
    }

    /** Loads a page, then runs next a few seconds after it finishes loading. */
    private void loadThen(String url, Runnable next) {
        if (!walking) return;
        final int token = ++loadToken;
        onLoaded = next;
        webView.loadUrl(url);
        ui.postDelayed(() -> {
            if (walking && token == loadToken && onLoaded != null) {
                Runnable r = onLoaded;
                onLoaded = null;
                log("   (page slow, continuing anyway)");
                r.run();
            }
        }, 30000);
    }

    private void jsWalk(String script, Cb cb) {
        if (!walking) return;
        webView.evaluateJavascript(script, v -> {
            if (walking) cb.run(unquote(v));
        });
    }

    private void readH2H() {
        jsWalk(SNAP_JS, text -> {
            jput(result, "h2h_text", text);
            log("H2H page text: " + text.length() + " chars");
            jsWalk(DISCOVER_JS, json -> {
                try {
                    JSONObject o = new JSONObject(json);
                    JSONArray rows = o.getJSONArray("rows");

                    boolean hasLast = false;
                    for (int i = 0; i < rows.length(); i++) {
                        if (rows.getJSONObject(i).optString("s").toLowerCase().contains("last")) {
                            hasLast = true;
                            break;
                        }
                    }
                    Map<String, Integer> perSection = new HashMap<>();
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject r = rows.getJSONObject(i);
                        String sec = r.optString("s");
                        boolean take;
                        String group = sec;
                        if (hasLast) {
                            Integer c = perSection.get(sec);
                            int cnt = c == null ? 0 : c;
                            take = sec.toLowerCase().contains("last") && cnt < 5;
                            if (take) perSection.put(sec, cnt + 1);
                        } else {
                            take = items.size() < 10;
                            group = items.size() < 5 ? "home last 5 (assumed)" : "away last 5 (assumed)";
                        }
                        if (take) {
                            Item it = new Item();
                            it.group = group;
                            it.text = r.optString("t");
                            it.href = r.optString("h");
                            it.row = r.optInt("i");
                            items.add(it);
                        }
                    }
                    log("H2H rows found: " + rows.length() + ", will walk " + items.size()
                            + (hasLast ? "" : " (no 'Last matches' titles found)"));
                    if (rows.length() == 0) {
                        log("No rows matched. Page classes seen: " + o.optJSONArray("classes"));
                    }
                } catch (Exception e) {
                    log("Could not read the H2H rows: " + e);
                }
                if (items.isEmpty()) {
                    finish();
                } else {
                    processNext();
                }
            });
        });
    }

    private void processNext() {
        if (!walking) return;
        if (cur >= items.size()) {
            finish();
            return;
        }
        final Item it = items.get(cur);
        log("Match " + (cur + 1) + "/" + items.size() + ": " + shorten(it.text)
                + "  [" + shorten(it.group) + "]");
        if (!it.href.isEmpty()) {
            it.url = it.href;
            loadThen(it.href, () -> statsFlow(it));
        } else {
            loadThen(h2hUrl, () -> clickRow(it));
        }
    }

    private void clickRow(final Item it) {
        jsWalk(CLICK_ROW_JS.replace("__N__", String.valueOf(it.row)), res -> {
            if (!"ok".equals(res)) {
                log("   row click failed: " + res);
                recordFailure(it, "row click " + res);
                return;
            }
            pollUrl(0, it);
        });
    }

    /** After a row click: wait until the browser shows a different match. */
    private void pollUrl(final int tries, final Item it) {
        if (!walking) return;
        String u = webView.getUrl();
        boolean changed = u != null && !u.equals(h2hUrl) && !u.contains("mid=" + mid);
        if (changed) {
            it.url = u;
            ui.postDelayed(() -> {
                if (walking) statsFlow(it);
            }, 3500);
            return;
        }
        if (tries >= 14) {
            log("   the row did not open a match");
            recordFailure(it, "row did not open a match");
            return;
        }
        ui.postDelayed(() -> pollUrl(tries + 1, it), 500);
    }

    private void recordFailure(Item it, String why) {
        JSONObject entry = new JSONObject();
        jput(entry, "group", it.group);
        jput(entry, "row_text", it.text);
        jput(entry, "error", why);
        matchesOut.put(entry);
        cur++;
        ui.postDelayed(this::processNext, 1000);
    }

    /** On a match page: read the summary, then Stats for Match, 1st half, 2nd half. */
    private void statsFlow(final Item it) {
        if (!walking) return;
        final JSONObject entry = new JSONObject();
        final JSONObject tabs = new JSONObject();
        jput(entry, "group", it.group);
        jput(entry, "row_text", it.text);
        jput(entry, "url", webView.getUrl());
        jput(entry, "tabs", tabs);
        jsWalk(SNAP_JS, t0 -> {
            jput(tabs, "summary", t0);
            jsWalk(clickText("stats"), r1 -> {
                jput(entry, "click_stats", r1);
                ui.postDelayed(() -> jsWalk(SNAP_JS, t1 -> {
                    jput(tabs, "match", t1);
                    jsWalk(clickText("1st half"), r2 -> {
                        jput(entry, "click_1st_half", r2);
                        ui.postDelayed(() -> jsWalk(SNAP_JS, t2 -> {
                            jput(tabs, "1st_half", t2);
                            jsWalk(clickText("2nd half"), r3 -> {
                                jput(entry, "click_2nd_half", r3);
                                ui.postDelayed(() -> jsWalk(SNAP_JS, t3 -> {
                                    jput(tabs, "2nd_half", t3);
                                    matchesOut.put(entry);
                                    log("   done: match " + t1.length() + ", 1st " + t2.length()
                                            + ", 2nd " + t3.length() + " chars  (clicks "
                                            + r1 + "/" + r2 + "/" + r3 + ")");
                                    cur++;
                                    ui.postDelayed(this::processNext, 1500);
                                }), 1500);
                            });
                        }), 1500);
                    });
                }), 2500);
            });
        });
    }

    private static String clickText(String txt) {
        return CLICK_TEXT_JS.replace("__TXT__", txt);
    }

    private void finish() {
        walking = false;
        onLoaded = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (result == null) return;
        jput(result, "matches", matchesOut);
        jput(result, "collected_at", System.currentTimeMillis());
        String json;
        try {
            json = result.toString(1);
        } catch (JSONException e) {
            json = result.toString();
        }
        String name = "flashscore_" + mid + "_" + (System.currentTimeMillis() / 1000) + ".json";
        save(name, json);
        saveToDownloads(name, json);
        log("=== WALK finished: " + (matchesOut == null ? 0 : matchesOut.length())
                + " matches saved. Tap Share data to send the file. ===");
    }

    private void saveToDownloads(String name, String body) {
        if (Build.VERSION.SDK_INT < 29) {
            log("Saved in the app's files folder only (this Android is older than 10).");
            return;
        }
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                log("Could not create the file in Downloads.");
                return;
            }
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) {
                log("Could not open the file in Downloads.");
                return;
            }
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();
            lastSavedUri = uri;
            log("Saved to Downloads: " + name);
        } catch (Exception e) {
            log("Saving to Downloads failed: " + e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String unquote(String v) {
        if (v == null || v.equals("null")) return "";
        try {
            Object o = new JSONTokener(v).nextValue();
            return o == null ? "" : String.valueOf(o);
        } catch (JSONException e) {
            return v;
        }
    }

    private static void jput(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
            // nothing to do
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    /** Called from the page's JavaScript (a background thread). */
    private class Bridge {
        @JavascriptInterface
        public void captured(String url, int status, String body) {
            handleCall(url, status, body);
        }

        @JavascriptInterface
        public void pageData(String name, String content) {
            handlePageData(name, content);
        }
    }

    private void handleCall(String url, int status, String body) {
        if (body == null) body = "";
        String path = shortPath(url);
        if (!walking) {
            log("page " + status + "  " + body.length() + " bytes  " + path);
            if (status >= 400 && body.length() > 0) {
                String snip = body.length() > 120 ? body.substring(0, 120) : body;
                log("   reply: " + snip.replace('\n', ' '));
            }
        }
        if (status == 200 && body.length() > 0 && body.length() < 8000000 && counter.get() < 800) {
            save("page_" + counter.incrementAndGet() + "_" + safeName(path) + ".txt", body);
        }
    }

    private void handlePageData(String name, String content) {
        if (content == null) content = "";
        log("data  " + name + "  " + content.length() + " chars");
        if (name.endsWith(".txt") && content.length() > 0) {
            String snip = content.length() > 300 ? content.substring(0, 300) : content;
            log("   text: " + snip.replace('\n', ' '));
        }
        if (content.length() > 0 && content.length() < 8000000) {
            String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
            save("data_" + counter.incrementAndGet() + "_" + clean, content);
        }
    }

    private static String shortPath(String url) {
        if (url == null) return "";
        String p = url.replaceFirst("^https?://", "");
        return p.length() > 110 ? p.substring(0, 110) : p;
    }

    private static String safeName(String path) {
        String n = path.replaceAll("[^A-Za-z0-9]+", "_");
        return n.length() > 70 ? n.substring(0, 70) : n;
    }

    private void save(String name, String body) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            FileOutputStream fos = new FileOutputStream(new File(dir, name));
            fos.write(body.getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception e) {
            log("Could not save " + name + ": " + e);
        }
    }

    private void log(String line) {
        synchronized (logBuf) {
            logBuf.append(line).append('\n');
        }
        ui.post(() -> {
            synchronized (logBuf) {
                logView.setText(logBuf.toString());
            }
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
}

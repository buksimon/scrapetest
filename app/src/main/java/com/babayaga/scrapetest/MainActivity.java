package com.babayaga.scrapetest;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test app v0.7: Flashscore H2H walker.
 * Scrap = from a Flashscore match link (with ?mid=): wait for the H2H tab, take the last 2
 * matches under HEAD-TO-HEAD MATCHES, and for each one open it, read its summary (the full
 * preview first, if there is one), then read its LINEUPS tab. A match only counts as done
 * when both its summary and its lineups were read. The result is saved as one JSON file in
 * Downloads.
 * Needs two files in app/src/main/assets: logo.png and fonts/Rajdhani-Bold.ttf.
 */
public class MainActivity extends Activity {

    // ---------------------------------------------------------------- theme

    private static final int BLACK = 0xFF000000;
    private static final int GREEN = 0xFF39FF6B;
    private static final int GREEN_HINT = 0x9939FF6B;
    private static final int GREEN_DIM = 0x3339FF6B;
    private static final int RED = 0xFFFF3B3B;
    private static final int WHITE = 0xFFFFFFFF;

    private static final String FONT_FILE = "fonts/Rajdhani-Bold.ttf";
    private static final String LOGO_FILE = "logo.png";

    // row states
    private static final int WAIT = 0;
    private static final int WORK = 1;
    private static final int DONE = 2;
    private static final int FAIL = 3;

    /** How many H2H matches to walk. */
    private static final int H2H_COUNT = 2;

    // ---------------------------------------------------------------- page scripts

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

    /** Everything visible on the page, as text. */
    private static final String SNAP_JS =
            "(function(){return document.body?document.body.innerText:'';})()";

    /**
     * Lists the match links that sit under the HEAD-TO-HEAD MATCHES heading, in page order.
     * The heading is found by its visible text; a LAST MATCHES heading switches the scan off.
     */
    private static final String LIST_H2H_JS =
            "(function(){try{"
          + "var out=[];var seen={};var cur='';"
          + "var all=document.querySelectorAll('*');"
          + "for(var i=0;i<all.length;i++){"
          + "var e=all[i];"
          + "var t=(e.textContent||'').replace(/\\s+/g,' ').trim().toUpperCase();"
          + "if(t.length<80){"
          + "if(t.indexOf('HEAD-TO-HEAD')===0){cur='h2h';}"
          + "else if(t.indexOf('LAST MATCHES')===0){cur='last';}"
          + "}"
          + "if(e.tagName==='A'&&cur==='h2h'){"
          + "var h=e.href||'';"
          + "if(h.indexOf('mid=')>=0&&!seen[h]){"
          + "seen[h]=1;"
          + "out.push({h:h,t:(e.innerText||'').replace(/\\s+/g,' ').trim().slice(0,160)});"
          + "}}}"
          + "return JSON.stringify({links:out});"
          + "}catch(e){return JSON.stringify({links:[],err:String(e)});}})()";

    /** Clicks the smallest element whose visible text is exactly the given text. */
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

    // ---------------------------------------------------------------- state

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();
    private final AtomicInteger counter = new AtomicInteger();
    private final List<H2HMatch> matches = new ArrayList<>();

    private Typeface font;
    private EditText input;
    private Button scrapBtn;
    private TextView badge;
    private TextView statusView;
    private Row h2hListRow;
    private final Row[] matchRows = new Row[H2H_COUNT];
    private WebView webView;
    private TextView logView;
    private ScrollView scroll;

    // walker state (all touched on the UI thread)
    private volatile boolean walking = false;
    private Runnable onLoaded;
    private int loadToken = 0;
    private String mid = "";
    private String matchUrl = "";
    private JSONObject result;
    private int cur = 0;

    // the last saved result, for the Download button
    private String lastJson = "";
    private String lastName = "";
    private Uri lastSavedUri;

    /** One H2H match and everything read from its page. */
    private static class H2HMatch {
        String url = "";
        String rowText = "";
        String fullPreviewClick = "";
        String summary = "";
        String lineupsClick = "";
        String lineups = "";
        boolean complete = false;
    }

    /** One line in the status list: a dot, a pulsing dot, a check or a red mark, and a label. */
    private static class Row {
        LinearLayout view;
        View dot;
        TextView name;
        TextView mark;
        int state = -1;
        ObjectAnimator pulse;
    }

    private interface Cb {
        void run(String s);
    }

    // ---------------------------------------------------------------- screen

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        if (Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(BLACK);
        font = loadFont();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BLACK);
        root.setPadding(dp(16), dp(24), dp(16), dp(12));

        // header: the logo, or the name as text if logo.png is missing
        Bitmap logo = loadAssetBitmap(LOGO_FILE);
        if (logo != null) {
            ImageView logoView = new ImageView(this);
            logoView.setImageBitmap(logo);
            logoView.setAdjustViewBounds(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(72));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(16);
            root.addView(logoView, lp);
        } else {
            TextView title = new TextView(this);
            title.setText("BABAYAGA");
            title.setTextColor(GREEN);
            title.setTextSize(30f);
            title.setGravity(Gravity.CENTER);
            applyFont(title);
            root.addView(title, fullWidth(dp(16)));
        }

        // link box: a long pill with a neon green ring
        input = new EditText(this);
        input.setHint("Paste a match link");
        input.setHintTextColor(GREEN_HINT);
        input.setTextColor(GREEN);
        input.setTextSize(16f);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPadding(dp(22), dp(12), dp(22), dp(12));
        input.setBackground(pill(BLACK, GREEN, dp(30)));
        applyFont(input);
        root.addView(input, fullWidth(dp(14)));

        // buttons: Open + Scrap on one row, Download + Clear on the next
        scrapBtn = makePill("Scrap", v -> {
            if (walking) stopWalk();
            else startWalk();
        });
        root.addView(buttonRow(makePill("Open", v -> openUrl()), scrapBtn), fullWidth(0));

        FrameLayout downloadWrap = new FrameLayout(this);
        downloadWrap.addView(makePill("Download", v -> {
            badge.setVisibility(View.GONE);
            downloadFile();
        }), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        badge = new TextView(this);
        badge.setText("1");
        badge.setTextColor(WHITE);
        badge.setTextSize(13f);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(0, 0, 0, 0);
        badge.setBackground(circle(RED));
        applyFont(badge);
        badge.setVisibility(View.GONE);
        downloadWrap.addView(badge, new FrameLayout.LayoutParams(
                dp(22), dp(22), Gravity.END | Gravity.TOP));
        root.addView(buttonRow(downloadWrap, makePill("Clear", v -> clearAll())), fullWidth(dp(14)));

        // the current step, in plain words, so you can see what is happening
        statusView = new TextView(this);
        statusView.setText("Ready. Paste a match link and tap Scrap.");
        statusView.setTextColor(GREEN);
        statusView.setTextSize(17f);
        applyFont(statusView);
        root.addView(statusView, fullWidth(dp(12)));

        // status rows: the H2H list, then each H2H match
        h2hListRow = buildRow("H2H list");
        matchRows[0] = buildRow("H2H match 1");
        matchRows[1] = buildRow("H2H match 2");
        root.addView(h2hListRow.view, fullWidth(0));
        root.addView(matchRows[0].view, fullWidth(0));
        root.addView(matchRows[1].view, fullWidth(0));

        // the browser: hidden until Open or Scrap, then small and dimmed
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
                if (!walking && !"about:blank".equals(url)) log("Loaded: " + url);
                if (walking && onLoaded != null) {
                    final Runnable r = onLoaded;
                    onLoaded = null;
                    ui.postDelayed(() -> {
                        if (walking) r.run();
                    }, 3500);
                }
            }
        });
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160));
        wlp.topMargin = dp(12);
        root.addView(webView, wlp);
        webView.setVisibility(View.GONE);

        // log: fixed height, so it is never squeezed out of the screen
        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12f);
        logView.setTextColor(GREEN);
        logView.setTextIsSelectable(true);
        applyFont(logView);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));

        // the whole screen scrolls, so nothing is pushed off the bottom
        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(BLACK);
        page.addView(root);
        setContentView(page);

        if (font == null) log("Rajdhani font not found (assets/fonts/Rajdhani-Bold.ttf). Using the system font.");
        if (logo == null) log("Logo not found (assets/logo.png). Showing the name as text.");

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

    @Override
    public void onBackPressed() {
        if (webView != null && webView.getVisibility() == View.VISIBLE
                && webView.canGoBack() && !walking) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------- theme helpers

    private Typeface loadFont() {
        try {
            return Typeface.createFromAsset(getAssets(), FONT_FILE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void applyFont(TextView tv) {
        if (font != null) tv.setTypeface(font);
    }

    private Bitmap loadAssetBitmap(String path) {
        try (InputStream is = getAssets().open(path)) {
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable pill(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(radius);
        g.setStroke(dp(2), stroke);
        return g;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** A green-outline pill with green text and no fill. */
    private Button makePill(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(GREEN);
        b.setTextSize(16f);
        applyFont(b);
        b.setBackground(pill(0x00000000, GREEN, dp(22)));
        b.setMinHeight(dp(46));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout buttonRow(View left, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addWeighted(row, left);
        addWeighted(row, right);
        return row;
    }

    private void addWeighted(LinearLayout row, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(5), 0, dp(5), dp(10));
        row.addView(v, lp);
    }

    private LinearLayout.LayoutParams fullWidth(int bottomMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = bottomMargin;
        return lp;
    }

    private Row buildRow(String label) {
        Row r = new Row();
        r.view = new LinearLayout(this);
        r.view.setOrientation(LinearLayout.HORIZONTAL);
        r.view.setGravity(Gravity.CENTER_VERTICAL);
        r.view.setPadding(0, dp(10), 0, dp(10));

        r.dot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotLp.rightMargin = dp(14);
        r.view.addView(r.dot, dotLp);

        r.name = new TextView(this);
        r.name.setText(label);
        r.name.setTextColor(GREEN);
        r.name.setTextSize(20f);
        applyFont(r.name);
        r.view.addView(r.name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        r.mark = new TextView(this);
        r.mark.setTextSize(22f);
        applyFont(r.mark);
        r.mark.setVisibility(View.GONE);
        r.view.addView(r.mark, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setRowState(r, WAIT);
        return r;
    }

    /** WAIT = dim dot, WORK = pulsing green dot, DONE = green check, FAIL = red mark. */
    private void setRowState(Row r, int state) {
        if (r.pulse != null) {
            r.pulse.cancel();
            r.pulse = null;
        }
        r.state = state;
        r.dot.setAlpha(1f);
        if (state == WAIT) {
            r.dot.setBackground(circle(GREEN_DIM));
            r.dot.setVisibility(View.VISIBLE);
            r.mark.setVisibility(View.GONE);
        } else if (state == WORK) {
            r.dot.setBackground(circle(GREEN));
            r.dot.setVisibility(View.VISIBLE);
            r.mark.setVisibility(View.GONE);
            r.pulse = ObjectAnimator.ofFloat(r.dot, "alpha", 1f, 0.15f);
            r.pulse.setDuration(700);
            r.pulse.setRepeatCount(ValueAnimator.INFINITE);
            r.pulse.setRepeatMode(ValueAnimator.REVERSE);
            r.pulse.start();
        } else {
            r.dot.setVisibility(View.GONE);
            r.mark.setVisibility(View.VISIBLE);
            r.mark.setText(state == DONE ? "\u2713" : "!");
            r.mark.setTextColor(state == DONE ? GREEN : RED);
        }
    }

    /** The browser sits small and dimmed while a page loads or a scrape runs. */
    private void showBrowser() {
        webView.setVisibility(View.VISIBLE);
        webView.setAlpha(0.35f);
    }

    /** Shows a message in the status line and in the log. */
    private void status(final String msg) {
        ui.post(() -> statusView.setText(msg));
        log(msg);
    }

    // ---------------------------------------------------------------- buttons

    private void openUrl() {
        String text = input.getText().toString();
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        if (!m.find()) {
            status("No link found in the box. Paste a match link first.");
            return;
        }
        String url = m.group();
        status("Opening the page...");
        showBrowser();
        webView.loadUrl(url);
    }

    /**
     * Download: the file is saved to Downloads as soon as the walk finishes, so this only
     * confirms it (or saves it again if that first save failed). No share sheet.
     */
    private void downloadFile() {
        if (lastJson.isEmpty()) {
            status("Nothing to download yet. Tap Scrap first.");
            return;
        }
        if (lastSavedUri != null) {
            status("Already in Downloads: " + lastName);
            return;
        }
        saveToDownloads(lastName, lastJson);
        if (lastSavedUri != null) {
            status("Saved to Downloads: " + lastName);
        } else {
            status("Could not save to Downloads. Check the log.");
        }
    }

    /** Clears the link box, the log, the status rows and the browser. Stops a running scrape first. */
    private void clearAll() {
        if (walking) stopWalk();
        input.setText("");
        synchronized (logBuf) {
            logBuf.setLength(0);
        }
        logView.setText("");
        matches.clear();
        cur = 0;
        result = null;
        lastJson = "";
        lastName = "";
        lastSavedUri = null;
        badge.setVisibility(View.GONE);
        setRowState(h2hListRow, WAIT);
        setRowState(matchRows[0], WAIT);
        setRowState(matchRows[1], WAIT);
        webView.loadUrl("about:blank");
        webView.setVisibility(View.GONE);
        status("Cleared. Paste a match link and tap Scrap.");
    }

    // ---------------------------------------------------------------- the walker

    private void startWalk() {
        if (walking) {
            status("Already scrapping. Tap Stop first.");
            return;
        }
        String text = input.getText().toString();
        Matcher u = Pattern.compile("https?://\\S+").matcher(text);
        if (!u.find()) {
            status("Scrap needs a Flashscore match link that has ?mid= in it.");
            return;
        }
        matchUrl = u.group();
        Matcher mm = Pattern.compile("[?&]mid=([A-Za-z0-9]+)").matcher(matchUrl);
        if (!matchUrl.contains("flashscore.") || !mm.find()) {
            status("Scrap needs a Flashscore match link that has ?mid= in it.");
            return;
        }
        mid = mm.group(1);

        result = new JSONObject();
        matches.clear();
        cur = 0;
        jput(result, "mid", mid);
        jput(result, "match_url", matchUrl);

        badge.setVisibility(View.GONE);
        setRowState(h2hListRow, WORK);
        setRowState(matchRows[0], WAIT);
        setRowState(matchRows[1], WAIT);

        walking = true;
        scrapBtn.setText("Stop");
        showBrowser();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status("Scrap started for " + mid + ". Keep the app open until it says done.");
        loadThen(matchUrl, this::openH2HTab);
    }

    private void stopWalk() {
        if (!walking) {
            status("Not scrapping.");
            return;
        }
        status("Stopped by you. Saving what was collected so far.");
        finishWalk();
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

    /**
     * Clicks the element with this visible text. If it is not on the page yet, tries again
     * once a second, up to maxTries times. Calls done with "ok" or "notfound".
     */
    private void waitClick(final String txt, final int maxTries, final Cb done) {
        tryClick(txt, 0, maxTries, done);
    }

    private void tryClick(final String txt, final int tries, final int maxTries, final Cb done) {
        if (!walking) return;
        jsWalk(clickText(txt), res -> {
            if ("ok".equals(res) || tries + 1 >= maxTries) {
                done.run(res);
            } else {
                ui.postDelayed(() -> tryClick(txt, tries + 1, maxTries, done), 1000);
            }
        });
    }

    /** On the match page: wait for the H2H tab, then open it. */
    private void openH2HTab() {
        status("Looking for the H2H tab...");
        waitClick("h2h", 20, res -> {
            if (!"ok".equals(res)) {
                status("The H2H tab did not appear after 20 seconds. Nothing was scraped.");
                setRowState(h2hListRow, FAIL);
                finishWalk();
                return;
            }
            status("H2H tab opened. Waiting for the match rows...");
            ui.postDelayed(() -> pollH2H(0), 2500);
        });
    }

    /** Waits for the H2H rows to render, then keeps the first H2H_COUNT match links. */
    private void pollH2H(final int tries) {
        if (!walking) return;
        jsWalk(LIST_H2H_JS, json -> {
            try {
                JSONArray links = new JSONObject(json).getJSONArray("links");
                if (links.length() == 0) {
                    if (tries < 15) {
                        ui.postDelayed(() -> pollH2H(tries + 1), 1000);
                    } else {
                        status("No match links found under HEAD-TO-HEAD MATCHES.");
                        setRowState(h2hListRow, FAIL);
                        finishWalk();
                    }
                    return;
                }
                for (int i = 0; i < links.length() && matches.size() < H2H_COUNT; i++) {
                    JSONObject l = links.getJSONObject(i);
                    H2HMatch m = new H2HMatch();
                    m.url = l.optString("h");
                    m.rowText = l.optString("t");
                    matches.add(m);
                    log("Found H2H " + matches.size() + ": " + shorten(m.rowText));
                }
                setRowState(h2hListRow, DONE);
                for (int i = matches.size(); i < H2H_COUNT; i++) {
                    setRowState(matchRows[i], FAIL);
                }
                status("Found " + matches.size() + " H2H match(es). Reading them now.");
                cur = 0;
                nextMatch();
            } catch (Exception e) {
                status("Could not read the H2H list: " + e);
                setRowState(h2hListRow, FAIL);
                finishWalk();
            }
        });
    }

    /** Opens the next H2H match page directly by its link. */
    private void nextMatch() {
        if (!walking) return;
        if (cur >= matches.size()) {
            finishWalk();
            return;
        }
        final int idx = cur;
        H2HMatch m = matches.get(idx);
        setRowState(matchRows[idx], WORK);
        status("Reading H2H " + (idx + 1) + " of " + matches.size() + "...");
        loadThen(m.url, () -> readMatch(idx));
    }

    /** On an H2H match page: full preview if there is one, then the summary, then LINEUPS. */
    private void readMatch(final int idx) {
        final H2HMatch m = matches.get(idx);
        waitClick("show full preview", 4, fp -> {
            m.fullPreviewClick = fp;
            ui.postDelayed(() -> jsWalk(SNAP_JS, summary -> {
                m.summary = summary;
                waitClick("lineups", 15, lc -> {
                    m.lineupsClick = lc;
                    if (!"ok".equals(lc)) {
                        finishMatch(idx);
                        return;
                    }
                    ui.postDelayed(() -> jsWalk(SNAP_JS, lineups -> {
                        m.lineups = lineups;
                        finishMatch(idx);
                    }), 2500);
                });
            }), 1500);
        });
    }

    /** A match counts as done only when both its summary and its lineups were read. */
    private void finishMatch(int idx) {
        H2HMatch m = matches.get(idx);
        boolean hasSummary = !m.summary.trim().isEmpty();
        boolean hasLineups = !m.lineups.trim().isEmpty();
        m.complete = hasSummary && hasLineups;
        setRowState(matchRows[idx], m.complete ? DONE : FAIL);
        log("H2H " + (idx + 1) + ": summary "
                + (hasSummary ? m.summary.length() + " chars" : "MISSING")
                + ", lineups "
                + (hasLineups ? m.lineups.length() + " chars" : "MISSING"));
        cur = idx + 1;
        ui.postDelayed(this::nextMatch, 1000);
    }

    /** Stops the walk, saves whatever was collected, and shows the Download badge. */
    private void finishWalk() {
        if (!walking) return;
        walking = false;
        onLoaded = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scrapBtn.setText("Scrap");
        if (h2hListRow.state == WORK) setRowState(h2hListRow, WAIT);
        for (Row r : matchRows) {
            if (r.state == WORK) setRowState(r, WAIT);
        }
        if (result == null) return;

        int complete = 0;
        JSONArray arr = new JSONArray();
        for (H2HMatch m : matches) {
            if (m.complete) complete++;
            JSONObject o = new JSONObject();
            jput(o, "url", m.url);
            jput(o, "row_text", m.rowText);
            jput(o, "complete", m.complete);
            jput(o, "full_preview_click", m.fullPreviewClick);
            jput(o, "summary", m.summary);
            jput(o, "lineups_click", m.lineupsClick);
            jput(o, "lineups", m.lineups);
            arr.put(o);
        }
        jput(result, "h2h", arr);
        jput(result, "complete_count", complete);
        jput(result, "collected_at", System.currentTimeMillis());

        String json;
        try {
            json = result.toString(1);
        } catch (JSONException e) {
            json = result.toString();
        }
        lastJson = json;
        lastName = "flashscore_h2h_" + mid + "_" + (System.currentTimeMillis() / 1000) + ".json";
        saveToDownloads(lastName, json);
        save(lastName, json);
        badge.setVisibility(View.VISIBLE);
        status("Done: " + complete + " of " + H2H_COUNT + " H2H matches complete."
                + (lastSavedUri != null ? " Saved to Downloads: " + lastName : ""));
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

    private static String clickText(String txt) {
        return CLICK_TEXT_JS.replace("__TXT__", txt);
    }

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

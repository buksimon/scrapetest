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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test app v0.8: Flashscore walker.
 *
 * Scrap, from a Flashscore match link with ?mid=:
 *  1. Main match: answer the age check YES, wait for "Page ready", then save
 *     SUMMARY (full preview + events by half), STATS (match, 1st half, 2nd half),
 *     LINEUPS (AVG. RATING), PLAYER STATS (every sub-tab) and REPORT.
 *  2. H2H tab (Overall): the first 2 HEAD-TO-HEAD matches, the first 5 LAST MATCHES
 *     of the home team and the first 5 of the away team.
 *  3. Each of those matches gets the same capture as step 1.
 *  4. Every tab is marked captured, empty or missing. A match is complete when no tab
 *     on its page is empty and its summary was captured.
 * The result is saved as one JSON file in Downloads.
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

    /** How many HEAD-TO-HEAD matches to walk, and how many last matches per team. */
    private static final int H2H_COUNT = 2;
    private static final int LAST_COUNT = 5;

    /** Summary headings we keep. Everything else on the summary (video, odds, ads) is dropped. */
    private static final String[] KEEP_HEADINGS = {"FLASHSCORE PREVIEW", "1ST HALF", "2ND HALF"};
    private static final String[] DROP_HEADINGS = {
            "ODDS", "BONUS OFFERS", "REPORT", "VIDEO PREVIEW", "MATCH HIGHLIGHTS", "HIGHLIGHTS", "YOUTUBE"};

    /** Tab labels that must never be clicked as player-stats sub-tabs. */
    private static final String[] MAIN_TABS = {
            "ALL", "OVERALL", "SUMMARY", "STATS", "LINEUPS", "PLAYER STATS", "REPORT", "ODDS",
            "H2H", "STANDINGS", "NEWS", "MATCH", "1ST HALF", "2ND HALF"};

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
     * Page state for the "Page ready" check: a hash of the visible text (so we can see when it
     * stops changing), whether the match tabs are on screen, and whether the age check is up.
     */
    private static final String READY_JS =
            "(function(){try{"
          + "var s=document.body?document.body.innerText:'';"
          + "var h=0;for(var i=0;i<s.length;i++){h=((h<<5)-h+s.charCodeAt(i))|0;}"
          + "var u=s.toUpperCase();"
          + "return JSON.stringify({h:h,n:s.length,tabs:(u.indexOf('SUMMARY')>=0||u.indexOf('H2H')>=0),"
          + "age:(u.indexOf('LEGAL AGE')>=0)});"
          + "}catch(err){return JSON.stringify({h:0,n:0,tabs:false,age:false});}})()";

    /**
     * Finds the H2H tab's sections by their visible headings: HEAD-TO-HEAD MATCHES, and
     * LAST MATCHES: <team> (in page order). Rows are links that start with a date.
     * Any other all-caps heading ends the current section.
     */
    private static final String H2H_LIST_JS =
            "(function(){try{"
          + "var sections=[];var cur=null;var lastHeading='';var seen={};"
          + "var dateRe=/\\d{2}\\.\\d{2}\\.\\d{2}/;"
          + "var startRe=/^\\d{2}\\.\\d{2}\\.\\d{2}\\s+\\S/;"
          + "var all=document.querySelectorAll('*');"
          + "for(var i=0;i<all.length;i++){"
          + "var e=all[i];"
          + "var t=(e.textContent||'').replace(/\\s+/g,' ').trim();"
          + "if(t.length>0&&t.length<80&&t===t.toUpperCase()&&/[A-Z]{4,}/.test(t)){"
          + "if(t===lastHeading)continue;"
          + "lastHeading=t;"
          + "if(t.indexOf('HEAD-TO-HEAD')===0){cur={type:'h2h',name:'HEAD-TO-HEAD',rows:[],dates:0};sections.push(cur);}"
          + "else if(t.indexOf('LAST MATCHES')===0){cur={type:'last',name:t.replace(/^LAST MATCHES:?\\s*/,''),rows:[],dates:0};sections.push(cur);}"
          + "else{cur=null;}"
          + "continue;}"
          + "if(!cur)continue;"
          + "var it=(e.textContent||'').replace(/\\s+/g,' ').trim();"
          + "if(e.tagName==='A'&&dateRe.test(it)){"
          + "cur.rows.push({h:e.href||'',t:it.slice(0,160)});}"
          + "else if(!e.closest('a')&&startRe.test(it)&&!seen[it]){seen[it]=1;cur.dates++;}"
          + "}"
          + "return JSON.stringify({sections:sections});"
          + "}catch(err){return JSON.stringify({sections:[],err:String(err)});}})()";

    /**
     * On a match page's STATS, PLAYER STATS or LINEUPS view: the sub-tab labels sitting in the
     * same row as TOP STATS (the first sub-tab of PLAYER STATS).
     */
    private static final String PLAYER_SUBTABS_JS =
            "(function(){try{"
          + "var all=document.querySelectorAll('a,button,li,span,div,[role=tab]');"
          + "var anchor=null;"
          + "for(var i=0;i<all.length;i++){"
          + "var e=all[i];"
          + "var t=(e.innerText||'').trim().replace(/\\s+/g,' ').toUpperCase();"
          + "if(t==='TOP STATS'&&e.offsetParent!==null){anchor=e;break;}}"
          + "var out=[];"
          + "if(anchor&&anchor.parentElement){"
          + "var kids=anchor.parentElement.children;"
          + "for(var j=0;j<kids.length;j++){"
          + "var kt=(kids[j].innerText||'').trim().replace(/\\s+/g,' ');"
          + "if(kt&&kt.length<=24&&kt===kt.toUpperCase()&&/[A-Z]/.test(kt)&&out.indexOf(kt)<0){out.push(kt);}"
          + "}}"
          + "return JSON.stringify({labels:out});"
          + "}catch(err){return JSON.stringify({labels:[],err:String(err)});}})()";

    /** Clicks the smallest visible element whose visible text is exactly the given text. */
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
    private final List<Rec> recs = new ArrayList<>();

    private Typeface font;
    private EditText input;
    private Button scrapBtn;
    private TextView badge;
    private TextView statusView;
    private Row homeRow;
    private Row awayRow;
    private Row h2hRow;
    private WebView webView;
    private TextView logView;
    private ScrollView scroll;

    // walker state (all touched on the UI thread)
    private volatile boolean walking = false;
    private Runnable onLoaded;
    private int loadToken = 0;
    private String mid = "";
    private String matchUrl = "";
    private Rec mainRec;
    private int cur = 0;
    private boolean scanned = false;
    private boolean h2hFound = false;
    private boolean homeFound = false;
    private boolean awayFound = false;
    private String homeName = "Home";
    private String awayName = "Away";

    // the last saved result, for the Download button
    private String lastJson = "";
    private String lastName = "";
    private Uri lastSavedUri;

    /** One match page (the main match or one of the matches from the H2H tab). */
    private static class Rec {
        String group = "";                 // main, h2h, home_last, away_last
        String url = "";
        String rowText = "";
        String fullPreviewClick = "";
        JSONObject tabs = new JSONObject();
        boolean summaryOk = false;
        boolean emptyFound = false;
        boolean done = false;
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

        // the current step, in plain words
        statusView = new TextView(this);
        statusView.setText("Ready. Paste a match link and tap Scrap.");
        statusView.setTextColor(GREEN);
        statusView.setTextSize(17f);
        applyFont(statusView);
        root.addView(statusView, fullWidth(dp(12)));

        // status rows: home team, away team, then the H2H row under them
        homeRow = buildRow("Home");
        awayRow = buildRow("Away");
        h2hRow = buildRow("H2H");
        root.addView(homeRow.view, fullWidth(0));
        root.addView(awayRow.view, fullWidth(0));
        root.addView(h2hRow.view, fullWidth(0));

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
        recs.clear();
        mainRec = null;
        cur = 0;
        scanned = false;
        h2hFound = false;
        homeFound = false;
        awayFound = false;
        homeName = "Home";
        awayName = "Away";
        homeRow.name.setText("Home");
        awayRow.name.setText("Away");
        lastJson = "";
        lastName = "";
        lastSavedUri = null;
        badge.setVisibility(View.GONE);
        setRowState(homeRow, WAIT);
        setRowState(awayRow, WAIT);
        setRowState(h2hRow, WAIT);
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

        mainRec = new Rec();
        mainRec.group = "main";
        mainRec.url = matchUrl;
        recs.clear();
        cur = 0;
        scanned = false;
        h2hFound = false;
        homeFound = false;
        awayFound = false;
        homeName = "Home";
        awayName = "Away";
        homeRow.name.setText("Home");
        awayRow.name.setText("Away");
        badge.setVisibility(View.GONE);
        setRowState(homeRow, WAIT);
        setRowState(awayRow, WAIT);
        setRowState(h2hRow, WAIT);

        walking = true;
        scrapBtn.setText("Stop");
        showBrowser();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status("Scrap started for " + mid + ". Keep the app open until it says done.");
        loadThen(matchUrl, this::walkMain);
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
     * Waits until the page's visible text stops changing for about 2 seconds, with the
     * match tabs on screen. Answers the age check YES if it shows up. If label is not null,
     * shows "Page ready: label" when the page has settled.
     */
    private void waitSettled(String label, Runnable done) {
        waitReady(0, -1, 0, label, label != null ? 60 : 24, done);
    }

    private void waitReady(final int tries, final int lastHash, final int stable,
                           final String label, final int maxTries, final Runnable done) {
        if (!walking) return;
        jsWalk(READY_JS, json -> {
            final JSONObject o = parseOrEmpty(json);
            final int h = o.optInt("h");
            final int n = o.optInt("n");
            final boolean tabsShown = o.optBoolean("tabs");
            final boolean ageAsked = o.optBoolean("age");
            if (ageAsked) {
                log("Age check on screen: answering YES");
                tryClick("yes", 0, 3, res -> {
                    ui.postDelayed(() -> waitReady(tries + 1, -1, 0, label, maxTries, done), 800);
                });
                return;
            }
            final int nextStable = (tabsShown && n > 0 && h == lastHash) ? stable + 1 : 0;
            if (nextStable >= 4) {
                if (label != null) status("Page ready: " + label);
                done.run();
                return;
            }
            if (tries >= maxTries) {
                log("Page did not settle in time. Continuing with what is on screen.");
                done.run();
                return;
            }
            ui.postDelayed(() -> waitReady(tries + 1, h, nextStable, label, maxTries, done), 500);
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

    // ---- step 1 and 3: capture one match page

    /** Captures SUMMARY, STATS, LINEUPS, PLAYER STATS and REPORT on the page that is open. */
    private void captureRecord(final Rec r, final String label, final Runnable done) {
        waitSettled(label, () -> waitClick("show full preview", 4, fp -> {
            r.fullPreviewClick = fp;
            waitSettled(null, () -> jsWalk(SNAP_JS, raw -> {
                String summary = filterSummary(raw);
                r.summaryOk = !summary.isEmpty();
                putLeaf(r, r.tabs, "summary", summary);
                stepStats(r, () -> stepLineups(r, () -> stepPlayerStats(r, () -> stepReport(r, done))));
            }));
        }));
    }

    /** STATS: the MATCH view, then 1ST HALF and 2ND HALF. */
    private void stepStats(final Rec r, final Runnable next) {
        final JSONObject stats = new JSONObject();
        waitClick("stats", 4, res -> {
            if (!"ok".equals(res)) {
                putMissing(r.tabs, "stats");
                next.run();
                return;
            }
            jput(r.tabs, "stats", stats);
            waitSettled(null, () -> jsWalk(SNAP_JS, raw -> {
                putLeaf(r, stats, "match", raw);
                statSub(r, stats, "1st half", "first_half",
                        () -> statSub(r, stats, "2nd half", "second_half", next));
            }));
        });
    }

    private void statSub(final Rec r, final JSONObject parent, final String clickLabel,
                         final String key, final Runnable next) {
        waitClick(clickLabel, 4, res -> {
            if (!"ok".equals(res)) {
                putMissing(parent, key);
                next.run();
                return;
            }
            waitSettled(null, () -> jsWalk(SNAP_JS, raw -> {
                putLeaf(r, parent, key, raw);
                next.run();
            }));
        });
    }

    /** LINEUPS: the AVG. RATING view when the page has the sub-tabs, otherwise the pitch as shown. */
    private void stepLineups(final Rec r, final Runnable next) {
        waitClick("lineups", 4, res -> {
            if (!"ok".equals(res)) {
                putMissing(r.tabs, "lineups");
                next.run();
                return;
            }
            waitSettled(null, () -> {
                waitClick("avg. rating", 2, ar -> {
                    waitSettled(null, () -> jsWalk(SNAP_JS, raw -> {
                        putLeaf(r, r.tabs, "lineups", raw);
                        next.run();
                    }));
                });
            });
        });
    }

    /** PLAYER STATS: every sub-tab the page shows, one after the other. */
    private void stepPlayerStats(final Rec r, final Runnable next) {
        waitClick("player stats", 4, res -> {
            if (!"ok".equals(res)) {
                putMissing(r.tabs, "player_stats");
                next.run();
                return;
            }
            final JSONObject ps = new JSONObject();
            jput(r.tabs, "player_stats", ps);
            waitSettled(null, () -> jsWalk(PLAYER_SUBTABS_JS, json -> {
                final List<String> labels = subTabLabels(json);
                if (labels.isEmpty()) {
                    jsWalk(SNAP_JS, raw -> {
                        putLeaf(r, ps, "default", raw);
                        next.run();
                    });
                    return;
                }
                log("   player stats sub-tabs: " + labels);
                subTabs(r, ps, labels, 0, next);
            }));
        });
    }

    private void subTabs(final Rec r, final JSONObject ps, final List<String> labels,
                         final int i, final Runnable next) {
        if (i >= labels.size()) {
            next.run();
            return;
        }
        final String label = labels.get(i);
        final String key = keyOf(label);
        waitClick(label.toLowerCase(Locale.ROOT), 3, res -> {
            if (!"ok".equals(res)) {
                putMissing(ps, key);
                subTabs(r, ps, labels, i + 1, next);
                return;
            }
            waitSettled(null, () -> jsWalk(SNAP_JS, raw -> {
                putLeaf(r, ps, key, raw);
                subTabs(r, ps, labels, i + 1, next);
            }));
        });
    }

    /** REPORT: the article page. */
    private void stepReport(final Rec r, final Runnable next) {
        waitClick("report", 4, res -> {
            if (!"ok".equals(res)) {
                putMissing(r.tabs, "report");
                next.run();
                return;
            }
            waitSettled(null, () -> jsWalk(SNAP_JS, raw -> {
                putLeaf(r, r.tabs, "report", raw);
                next.run();
            }));
        });
    }

    private void finishRecord(Rec r) {
        r.done = true;
        r.complete = r.summaryOk && !r.emptyFound;
        log((r == mainRec ? "Main match" : "Match") + ": " + (r.complete ? "complete" : "incomplete"));
        updateRows();
    }

    // ---- step 1: the main match, then the H2H tab

    private void walkMain() {
        captureRecord(mainRec, "main match", () -> {
            finishRecord(mainRec);
            stepH2H();
        });
    }

    private void stepH2H() {
        status("Opening the H2H tab...");
        setRowState(h2hRow, WORK);
        waitClick("h2h", 20, res -> {
            if (!"ok".equals(res)) {
                status("H2H tab not found. Only the main match was saved.");
                setRowState(h2hRow, FAIL);
                finishWalk();
                return;
            }
            waitSettled(null, () -> waitClick("overall", 3, o -> {
                waitSettled(null, () -> pollH2H(0));
            }));
        });
    }

    /** Waits for the H2H sections to render, then reads them. */
    private void pollH2H(final int tries) {
        if (!walking) return;
        jsWalk(H2H_LIST_JS, json -> {
            JSONObject o = parseOrEmpty(json);
            JSONArray secs = o.optJSONArray("sections");
            int lastCount = 0;
            boolean h2h = false;
            if (secs != null) {
                for (int i = 0; i < secs.length(); i++) {
                    JSONObject s = secs.optJSONObject(i);
                    if (s == null) continue;
                    String type = s.optString("type");
                    if ("h2h".equals(type)) h2h = true;
                    if ("last".equals(type)) lastCount++;
                }
            }
            if ((h2h && lastCount >= 2) || tries >= 15) {
                processSections(o);
            } else {
                ui.postDelayed(() -> pollH2H(tries + 1), 1000);
            }
        });
    }

    /** Takes the first H2H_COUNT head-to-head rows and the first LAST_COUNT rows per team. */
    private void processSections(JSONObject o) {
        JSONArray secs = o.optJSONArray("sections");
        JSONObject h2hSec = null;
        List<JSONObject> lastSecs = new ArrayList<>();
        if (secs != null) {
            for (int i = 0; i < secs.length(); i++) {
                JSONObject s = secs.optJSONObject(i);
                if (s == null) continue;
                String type = s.optString("type");
                if ("h2h".equals(type) && h2hSec == null) {
                    h2hSec = s;
                } else if ("last".equals(type)) {
                    lastSecs.add(s);
                }
            }
        }

        h2hFound = h2hSec != null;
        homeFound = lastSecs.size() >= 1;
        awayFound = lastSecs.size() >= 2;
        if (homeFound) {
            String n = lastSecs.get(0).optString("name").trim();
            homeName = n.isEmpty() ? "Home" : n;
        }
        if (awayFound) {
            String n = lastSecs.get(1).optString("name").trim();
            awayName = n.isEmpty() ? "Away" : n;
        }
        homeRow.name.setText(homeName);
        awayRow.name.setText(awayName);

        recs.clear();
        if (h2hSec != null) {
            addRows(h2hSec.optJSONArray("rows"), "h2h", H2H_COUNT, h2hSec.optInt("dates"));
        } else {
            log("HEAD-TO-HEAD MATCHES section not found.");
        }
        if (homeFound) {
            addRows(lastSecs.get(0).optJSONArray("rows"), "home_last", LAST_COUNT, lastSecs.get(0).optInt("dates"));
        } else {
            log("LAST MATCHES section for the home team not found.");
        }
        if (awayFound) {
            addRows(lastSecs.get(1).optJSONArray("rows"), "away_last", LAST_COUNT, lastSecs.get(1).optInt("dates"));
        } else {
            log("LAST MATCHES section for the away team not found.");
        }

        scanned = true;
        setRowState(h2hRow, h2hFound ? DONE : FAIL);
        updateRows();
        status("Found " + recs.size() + " matches to open: "
                + count(recs, "h2h") + " H2H, " + count(recs, "home_last") + " home, "
                + count(recs, "away_last") + " away.");
        if (recs.isEmpty()) {
            finishWalk();
            return;
        }
        nextRec(0);
    }

    private void addRows(JSONArray rows, String group, int max, int dates) {
        if (rows == null) return;
        int added = 0;
        for (int i = 0; i < rows.length() && added < max; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String href = row.optString("h");
            String text = row.optString("t");
            if (href.isEmpty()) {
                log("Row has no link, skipped: " + shorten(text));
                continue;
            }
            Rec r = new Rec();
            r.group = group;
            r.url = href;
            r.rowText = text;
            recs.add(r);
            added++;
        }
        if (added == 0 && dates > 0) {
            log("Rows exist for " + group + " but none have links.");
        }
    }

    private static int count(List<Rec> list, String group) {
        int n = 0;
        for (Rec r : list) {
            if (group.equals(r.group)) n++;
        }
        return n;
    }

    /** Opens the next match from the H2H tab and captures it. */
    private void nextRec(final int i) {
        if (!walking) return;
        if (i >= recs.size()) {
            finishWalk();
            return;
        }
        cur = i;
        final Rec r = recs.get(i);
        updateRows();
        status("Opening match " + (i + 1) + " of " + recs.size() + ": " + shorten(r.rowText));
        loadThen(r.url, () -> captureRecord(r, shorten(r.rowText), () -> {
            finishRecord(r);
            nextRec(i + 1);
        }));
    }

    // ---- rows and finishing

    private void updateRows() {
        if (!scanned) return;
        setGroupRow(homeRow, "home_last", homeFound);
        setGroupRow(awayRow, "away_last", awayFound);
        setGroupRow(h2hRow, "h2h", h2hFound);
    }

    private void setGroupRow(Row row, String group, boolean found) {
        int total = 0;
        int finished = 0;
        int complete = 0;
        boolean active = false;
        for (int i = 0; i < recs.size(); i++) {
            Rec r = recs.get(i);
            if (!group.equals(r.group)) continue;
            total++;
            if (r.done) {
                finished++;
                if (r.complete) complete++;
            } else if (i == cur && walking) {
                active = true;
            }
        }
        int state;
        if (!found) {
            state = FAIL;
        } else if (total == 0) {
            state = DONE;
        } else if (finished == total) {
            state = (complete == total) ? DONE : FAIL;
        } else if (active) {
            state = WORK;
        } else {
            state = WAIT;
        }
        setRowState(row, state);
    }

    /** Stops the walk, saves whatever was collected, and shows the Download badge. */
    private void finishWalk() {
        if (!walking) return;
        walking = false;
        onLoaded = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scrapBtn.setText("Scrap");
        for (Row row : new Row[] {homeRow, awayRow, h2hRow}) {
            if (row.state == WORK) setRowState(row, WAIT);
        }
        updateRows();
        if (mainRec == null) return;

        int complete = 0;
        for (Rec r : recs) {
            if (r.complete) complete++;
        }

        JSONObject out = new JSONObject();
        jput(out, "mid", mid);
        jput(out, "match_url", matchUrl);

        JSONObject mainObj = new JSONObject();
        jput(mainObj, "complete", mainRec.complete);
        jput(mainObj, "tabs", mainRec.tabs);
        jput(out, "main_match", mainObj);

        JSONArray h2hArr = new JSONArray();
        JSONArray homeArr = new JSONArray();
        JSONArray awayArr = new JSONArray();
        for (Rec r : recs) {
            JSONObject o = recJson(r);
            if ("h2h".equals(r.group)) {
                h2hArr.put(o);
            } else if ("home_last".equals(r.group)) {
                homeArr.put(o);
            } else {
                awayArr.put(o);
            }
        }
        jput(out, "h2h", h2hArr);

        JSONObject home = new JSONObject();
        jput(home, "name", homeName);
        jput(home, "matches", homeArr);
        JSONObject away = new JSONObject();
        jput(away, "name", awayName);
        jput(away, "matches", awayArr);
        JSONObject last = new JSONObject();
        jput(last, "home_team", home);
        jput(last, "away_team", away);
        jput(out, "last_matches", last);

        jput(out, "complete_count", complete);
        jput(out, "total_count", recs.size());
        jput(out, "collected_at", System.currentTimeMillis());

        String json;
        try {
            json = out.toString(1);
        } catch (JSONException e) {
            json = out.toString();
        }
        lastJson = json;
        lastName = "flashscore_" + mid + "_" + (System.currentTimeMillis() / 1000) + ".json";
        saveToDownloads(lastName, json);
        save(lastName, json);
        badge.setVisibility(View.VISIBLE);
        status("Done: " + complete + " of " + recs.size() + " matches complete. Main match "
                + (mainRec.complete ? "complete" : "incomplete") + "."
                + (lastSavedUri != null ? " Saved to Downloads: " + lastName : ""));
    }

    private JSONObject recJson(Rec r) {
        JSONObject o = new JSONObject();
        jput(o, "url", r.url);
        jput(o, "row_text", r.rowText);
        jput(o, "full_preview_click", r.fullPreviewClick);
        jput(o, "done", r.done);
        jput(o, "complete", r.complete);
        jput(o, "tabs", r.tabs);
        return o;
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

    // ---------------------------------------------------------------- tab records

    private JSONObject leaf(String status, String text) {
        JSONObject o = new JSONObject();
        jput(o, "status", status);
        jput(o, "text", text);
        return o;
    }

    /** Records a tab that was clicked (or read): captured if it has text, empty if not. */
    private void putLeaf(Rec r, JSONObject parent, String key, String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) r.emptyFound = true;
        jput(parent, key, leaf(t.isEmpty() ? "empty" : "captured", t));
    }

    /** Records a tab that is not on the page. */
    private void putMissing(JSONObject parent, String key) {
        jput(parent, key, leaf("missing", ""));
    }

    /**
     * Keeps only the preview and the events by half from the summary. A line that is one of
     * the headings above starts a section; the section ends at the next heading in DROP.
     */
    private static String filterSummary(String raw) {
        StringBuilder sb = new StringBuilder();
        boolean keep = false;
        String[] lines = (raw == null ? "" : raw).split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String up = t.toUpperCase(Locale.ROOT);
            boolean heading = false;
            for (String k : KEEP_HEADINGS) {
                if (up.startsWith(k)) {
                    keep = true;
                    heading = true;
                    break;
                }
            }
            if (!heading) {
                for (String d : DROP_HEADINGS) {
                    if (up.equals(d)) {
                        keep = false;
                        break;
                    }
                }
            }
            if (keep && !up.equals("SHOW FULL PREVIEW") && !up.equals("SHOW LESS")
                    && !up.equals("ADVERTISEMENT")) {
                sb.append(t).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** Sub-tab labels from the page, without the main tabs and the team filter. */
    private static List<String> subTabLabels(String json) {
        List<String> out = new ArrayList<>();
        JSONArray arr = parseOrEmpty(json).optJSONArray("labels");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i).trim();
            if (s.isEmpty()) continue;
            String up = s.toUpperCase(Locale.ROOT);
            boolean main = false;
            for (String m : MAIN_TABS) {
                if (m.equals(up)) {
                    main = true;
                    break;
                }
            }
            if (!main) out.add(s);
        }
        return out;
    }

    private static String keyOf(String label) {
        return label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    // ---------------------------------------------------------------- helpers

    private static String clickText(String txt) {
        return CLICK_TEXT_JS.replace("__TXT__", txt);
    }

    private static JSONObject parseOrEmpty(String json) {
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            return new JSONObject();
        }
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

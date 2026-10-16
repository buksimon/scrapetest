package com.babayaga.scrapetest;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test app v0.3: any match link (FotMob, Flashscore, ...) opens inside the app's own
 * browser view. A shared link opens by itself. Two ways of getting the data:
 * 1) a sniffer records every data call the page makes (adverts and trackers skipped);
 * 2) "Page data" reads what is already on the page: embedded data, visible text, HTML.
 * Everything is saved in the app's files folder; the log can be shared back.
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

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();
    private final AtomicInteger counter = new AtomicInteger();

    private EditText input;
    private WebView webView;
    private TextView logView;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        input = new EditText(this);
        input.setHint("Paste a match link (FotMob, Flashscore...)");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(3);
        root.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(makeButton("Open", v -> openUrl()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(makeButton("Page data", v -> pageData()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(makeButton("Share log", v -> shareLog()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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
                log("Loaded: " + url);
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

        // A link sent with another app's Share button opens straight away
        Intent in = getIntent();
        if (in != null && Intent.ACTION_SEND.equals(in.getAction())) {
            String shared = in.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null) {
                input.setText(shared);
                log("Received shared text:\n" + shared);
                openUrl();
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

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

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
        log("page " + status + "  " + body.length() + " bytes  " + path);
        if (status >= 400 && body.length() > 0) {
            String snip = body.length() > 120 ? body.substring(0, 120) : body;
            log("   reply: " + snip.replace('\n', ' '));
        }
        if (status == 200 && body.length() > 0 && body.length() < 8000000 && counter.get() < 200) {
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

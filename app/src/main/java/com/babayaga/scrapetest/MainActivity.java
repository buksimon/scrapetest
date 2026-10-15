package com.babayaga.scrapetest;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test app: paste (or Share) a Sofascore match link, then fetch one match and one player
 * to see whether Sofascore answers requests from this phone. Raw JSON is saved to the
 * app's files folder; the log can be shared back.
 */
public class MainActivity extends Activity {

    private static final String BASE = "https://api.sofascore.com/api/v1";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final long DELAY_MS = 1000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();

    private EditText input;
    private TextView logView;
    private ScrollView scroll;
    private Button runBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        input = new EditText(this);
        input.setHint("Paste Sofascore match link or match ID");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        runBtn = new Button(this);
        runBtn.setText("Fetch match + one player");
        runBtn.setOnClickListener(v -> start());
        root.addView(runBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button shareBtn = new Button(this);
        shareBtn.setText("Share log");
        shareBtn.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, logBuf.toString());
            startActivity(Intent.createChooser(send, "Share log"));
        });
        root.addView(shareBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12f);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // Link sent via the Share button of another app
        Intent in = getIntent();
        if (in != null && Intent.ACTION_SEND.equals(in.getAction())) {
            String shared = in.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null) {
                input.setText(shared);
                log("Received shared text:\n" + shared);
            }
        }
    }

    private void start() {
        final String id = extractId(input.getText().toString());
        if (id == null) {
            log("No match ID found. Paste a Sofascore match link (it ends with #id:12345678) or the number.");
            return;
        }
        runBtn.setEnabled(false);
        new Thread(() -> {
            try {
                work(id);
            } catch (Throwable t) {
                log("ERROR: " + t);
            } finally {
                ui.post(() -> runBtn.setEnabled(true));
            }
        }).start();
    }

    private static String extractId(String text) {
        if (text == null) return null;
        String t = text.trim();
        Matcher m = Pattern.compile("id:(\\d+)").matcher(t);
        if (m.find()) return m.group(1);
        m = Pattern.compile("/event/(\\d+)").matcher(t);
        if (m.find()) return m.group(1);
        m = Pattern.compile("^\\d{5,}$").matcher(t);
        if (m.find()) return m.group();
        return null;
    }

    private void work(String eventId) throws Exception {
        log("=== Match " + eventId + " ===");
        Res ev = get(BASE + "/event/" + eventId, "event_" + eventId + ".json");
        if (ev.code != 200) {
            log("Stopping: the match request did not succeed.");
            return;
        }

        JSONObject event = new JSONObject(ev.body).getJSONObject("event");
        JSONObject home = event.getJSONObject("homeTeam");
        JSONObject away = event.getJSONObject("awayTeam");
        log("Match: " + home.optString("name") + " v " + away.optString("name"));

        int homeId = home.getInt("id");
        Res squad = get(BASE + "/team/" + homeId + "/players", "team_" + homeId + "_players.json");
        int playerId = -1;
        if (squad.code == 200) {
            try {
                JSONArray arr = new JSONObject(squad.body).optJSONArray("players");
                if (arr != null && arr.length() > 0) {
                    JSONObject p = arr.getJSONObject(0).optJSONObject("player");
                    if (p != null) {
                        playerId = p.getInt("id");
                        log("First player: " + p.optString("name") + " (" + playerId + ")");
                    }
                }
            } catch (Exception e) {
                log("Could not read the squad list: " + e);
            }
        }
        if (playerId < 0) {
            log("Stopping: no player ID found.");
            return;
        }

        get(BASE + "/player/" + playerId, "player_" + playerId + ".json");
        get(BASE + "/player/" + playerId + "/attribute-overviews", "player_" + playerId + "_attributes.json");
        get(BASE + "/player/" + playerId + "/statistics/seasons", "player_" + playerId + "_seasons.json");
        log("=== Done. Raw JSON saved in the app's files folder. ===");
    }

    private static class Res {
        int code = -1;
        String body = "";
    }

    /** One GET request, spaced out by DELAY_MS. Logs status and size, saves the body. */
    private Res get(String url, String fileName) {
        Res r = new Res();
        try {
            Thread.sleep(DELAY_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Referer", "https://www.sofascore.com/");
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? "" : readAll(in);

            String shortUrl = url.replace(BASE, "");
            log(r.code + "  " + r.body.length() + " bytes  " + shortUrl);
            if (r.code != 200) {
                String snippet = r.body.length() > 150 ? r.body.substring(0, 150) : r.body;
                log("   reply: " + snippet.replace('\n', ' '));
            }
            save(fileName, r.body);
        } catch (Exception e) {
            log("FAILED " + url.replace(BASE, "") + " -> " + e);
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
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

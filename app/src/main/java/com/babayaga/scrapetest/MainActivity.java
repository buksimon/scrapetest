package com.babayaga.scrapetest;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
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
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BABAYAGA Universal Football Simulator, app v2.0: four pages in one app.
 *
 *  Page 1  Choose the SportyBet file (the list of matches and their odds).
 *  Page 2  Flashscore data: the walker below. Login = type your own Flashscore login in the page, the
 *          app keeps it. Scrap with the link box empty walks the matches of your favorites. Load file
 *          = use a saved Flashscore file instead.
 *  Page 3  The simulation (Core.java): six components -> goal rates -> 200,000 simulated matches ->
 *          every market of the SportyBet match. Flashscore favorite number N goes with SportyBet
 *          line N; the team names are the second check.
 *  Page 4  Final results, match by match, with a Download button.
 *  The BABAYAGA wordmark at the top is the home key. Football only.
 *
 * Files: MainActivity.java (this one) and Core.java (the numbers) in the same folder, and in
 * app/src/main/assets: babayaga_wordmark.png and babayaga_bg.gif (the app also runs without them).
 *
 * FAVORITES MODE (v1.5). With the link box EMPTY, Open loads https://www.flashscore.com/favorites/
 * (big and bright, so you can log in to Flashscore inside the app: the login stays) and says how many
 * football matches it found there. Scrap then walks every one of those matches, one after the other,
 * with exactly the walk below, and saves ONE file: flashscore_favorites_<time>.json =
 * { exportedAt, source, totalMatches, matches: [ one object per match, same layout as before ] }.
 * Stop saves the matches finished so far (and the one in progress). A pasted match link still works
 * as before (one match, one file).
 *
 * The walk of one match, from its Flashscore match link with ?mid=:
 *  1. Main match: answer the consent box and the age check, wait until the page is really loaded
 *     (the tab row is on screen), then save SUMMARY, STATS (match, 1st half, 2nd half),
 *     LINEUPS, PLAYER STATS (every sub-tab), COMMENTARY and REPORT.
 *  2. H2H tab: "Show more matches" is clicked every time (and again while a team has fewer than
 *     10 league matches). Then the 10 latest LEAGUE matches of each team (the league is the
 *     competition code most of its latest matches carry, so friendlies, cups and European games
 *     are left out) and the 2 latest head-to-head matches are taken. The main match is never
 *     taken from those lists. A match in more than one list is opened once.
 *  3. Each of those matches gets the same capture as step 1.
 *  4. A page that does not load is loaded again. After a tab is clicked its data must really be
 *     on screen before it is read; a click that did not open the tab is repeated.
 *  5. After one round, the app goes back to each played match that has an empty tab and reads
 *     only what is still missing (up to 2 extra rounds; stops early when nothing new comes).
 *  6. A tab not in the tab row is "missing". A tab that came back blank is "empty". A tab where
 *     the page says there is no data is listed as empty but is not a failure. A match is
 *     complete when no tab came back blank and its summary was captured (a match not played
 *     yet: when its summary was captured).
 *

 * For the notification on Android 13+, AndroidManifest.xml needs
 *   <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 */
public class MainActivity extends Activity {

  // ---------------------------------------------------------------- theme

  private static final int BLACK = 0xFF000000;
  private static final int GREEN = 0xFF39FF6B;
  private static final int GREEN_HINT = 0x9939FF6B;
  private static final int GREEN_DIM = 0x3339FF6B;
  private static final int RED = 0xFFFF3B3B;
  private static final int WHITE = 0xFFFFFFFF;

  /** Matches to walk: the latest league matches of each team and the head-to-head matches. */
  private static final int H2H_COUNT = 2;
  private static final int LAST_COUNT = 10;
  private static final int TARGET_TOTAL = H2H_COUNT + 2 * LAST_COUNT;

  /** How many of a team's latest matches are looked at to find its league. */
  private static final int LEAGUE_WINDOW = 12;
  /** Most "Show more matches" rounds, and most extra passes over pages with empty tabs. */
  private static final int MAX_MORE_ROUNDS = 8;
  private static final int MAX_RETRY_PASSES = 2;

  /** A list row starts with the date, then the competition code: "27.09.26 L2 Alianza ...". */
  private static final Pattern ROW_CODE = Pattern.compile("^\\d{2}\\.\\d{2}\\.\\d{2}\\s+(\\S+)\\s");

  /** Competition codes that are never a team's league. Only used to break a tie. */
  private static final Set<String> NOT_LEAGUE = new HashSet<>(java.util.Arrays.asList(
      "FRI", "FRIENDLY", "CUP", "UCL", "UEL", "UECL", "ECL", "CL", "EL"));

  /** The page whose matches are walked when the link box is empty. */
  private static final String FAV_URL = "https://www.flashscore.com/favorites/";

  private static final String CHANNEL_ID = "scrap_done";
  private static final int NOTIF_ID = 7;
  private static final int REQ_NOTIF = 11;

  /** The tab row at the top of a match page. Everything before it is the match header. */
  private static final String[] NAV_LABELS = {
      "MATCH", "REPORT", "ODDS", "H2H", "STANDINGS", "NEWS", "VIDEO",
      "SUMMARY", "STATS", "LINEUPS", "PLAYER STATS", "COMMENTARY"};

  /** Labels that belong to the odds block. The block is dropped. */
  private static final String[] ODDS_LABELS = {
      "ODDS", "1X2", "OVER/UNDER", "BOTH TEAMS TO SCORE", "DOUBLE CHANCE",
      "PLACE A BET", "BONUS OFFERS"};

  /** Single lines that are never data: ads, video, "show more" buttons. */
  private static final String[] NOISE_LINES = {
      "SKIP TO MAIN CONTENT", "ADVERTISEMENT", "AD", "SHOW MORE", "SHOW LESS",
      "SHOW FULL PREVIEW", "SHOW MORE MATCHES", "SHOW ALL STANDINGS", "VIDEO PREVIEW",
      "MATCH HIGHLIGHTS", "YOUTUBE", "1XBET"};

  /** Sub-tab and column labels. A tab that has only these (or "no data" lines) is empty. */
  private static final String[] SUB_LABELS = {
      "MATCH", "1ST HALF", "2ND HALF", "TOP STATS", "SHOTS", "ATTACK", "PASSES", "DEFENSE",
      "GOALKEEPING", "GENERAL", "ALL", "AVG. RATING", "MARKET VALUE", "AGE", "HEIGHT", "OVERALL"};

  /** Tab labels that must never be clicked as player-stats sub-tabs. */
  private static final String[] MAIN_TABS = {
      "ALL", "OVERALL", "SUMMARY", "STATS", "LINEUPS", "PLAYER STATS", "COMMENTARY", "REPORT",
      "ODDS", "H2H", "STANDINGS", "NEWS", "VIDEO", "MATCH", "1ST HALF", "2ND HALF"};

  private static final Pattern NO_DATA = Pattern.compile(
      "(?i)\\bno\\b.{0,40}\\b(data|statistics|stats|information|commentary|lineups?|events?|report|available)\\b"
     + "|not available|unavailable");

  // ---------------------------------------------------------------- page scripts

  /** Everything visible on the page, as text. */
  private static final String SNAP_JS =
      "(function(){return document.body?document.body.innerText:'';})()";

  /**
   * Page state for the "Page ready" check: a hash of the visible text (so we can see when it
   * stops changing), whether the match tab row is really on screen (two or more tabs: the page
   * has loaded, not just its text skeleton), and whether the age check is up.
   */
  private static final String READY_JS =
      "(function(){try{"
     + "var s=document.body?document.body.innerText:'';"
     + "var h=0;for(var i=0;i<s.length;i++){h=((h<<5)-h+s.charCodeAt(i))|0;}"
     + "var u=s.toUpperCase();"
     + "var tc=0;var tt=document.querySelectorAll('[data-testid=\"wcl-tab\"]');"
     + "for(var k=0;k<tt.length;k++){if(tt[k].offsetParent!==null)tc++;}"
     + "return JSON.stringify({h:h,n:s.length,tabs:(tc>=2),tc:tc,age:(u.indexOf('LEGAL AGE')>=0)});"
     + "}catch(err){return JSON.stringify({h:0,n:0,tabs:false,tc:0,age:false});}})()";

  /**
   * Answers a cookie / consent box if one is on screen. "Do not consent" style buttons are
   * tried first; only if the box has none, the "Consent / Accept" button. Returns "rejected",
   * "accepted" or "none".
   */
  private static final String CONSENT_JS =
      "(function(){try{"
     + "function vis(e){return e&&e.offsetParent!==null;}"
     + "function norm(e){return (e.innerText||e.value||'').replace(/\\s+/g,' ').trim().toLowerCase();}"
     + "var rej=['#onetrust-reject-all-handler','.fc-cta-do-not-consent','#didomi-notice-disagree-button'];"
     + "var acc=['#onetrust-accept-btn-handler','.fc-cta-consent','#didomi-notice-agree-button'];"
     + "var rejRe=/^(do not consent|reject all|reject|refuse all|refuse|disagree|decline|deny all|deny)$/;"
     + "var accRe=/^(consent|i accept|accept all|accept|agree|agree and close|allow all|got it)$/;"
     + "var btn=document.querySelectorAll('button,a,[role=button],input[type=button]');"
     + "var i,e;"
     + "for(i=0;i<rej.length;i++){e=document.querySelector(rej[i]);if(vis(e)){e.click();return 'rejected';}}"
     + "for(i=0;i<btn.length;i++){if(vis(btn[i])&&rejRe.test(norm(btn[i]))){btn[i].click();return 'rejected';}}"
     + "for(i=0;i<acc.length;i++){e=document.querySelector(acc[i]);if(vis(e)){e.click();return 'accepted';}}"
     + "var box=document.body?document.body.innerText.toLowerCase():'';"
     + "if(box.indexOf('we and our partners')>=0||box.indexOf('your privacy choices')>=0||box.indexOf('we value your privacy')>=0){"
     + "for(i=0;i<btn.length;i++){if(vis(btn[i])&&accRe.test(norm(btn[i]))){btn[i].click();return 'accepted';}}}"
     + "return 'none';"
     + "}catch(err){return 'none';}})()";

  /**
   * Finds the H2H tab's sections: HEAD-TO-HEAD MATCHES and LAST MATCHES: <team> (page order,
   * home team first). The page writes the headings in normal case and shows them in capitals, so
   * they are compared in capitals, case-blind. Rows are the links inside a section that carry a
   * dd.mm.yy date; the row text is read as shown ("25.10.26 L2 Home Away 1 0 W"). If the page has
   * no section blocks, the headings are found by walking the page text instead.
   */
  private static final String H2H_LIST_JS =
      "(function(){try{"
     + "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}"
     + "function kind(u){if(u.indexOf('HEAD-TO-HEAD')===0)return 'h2h';if(u.indexOf('LAST MATCHES')===0)return 'last';return '';}"
     + "function nm(raw){return norm(raw).replace(/^last matches:?\\s*/i,'').toUpperCase();}"
     + "function rowsOf(root){var o=[];var seen={};var a=root.querySelectorAll('a[href]');"
     + "for(var k=0;k<a.length;k++){var it=norm(a[k].innerText||a[k].textContent);"
     + "if(!dateRe.test(it))continue;var h=a[k].href||'';if(h&&seen[h+it])continue;seen[h+it]=1;"
     + "o.push({h:h,t:it.slice(0,160)});}return o;}"
     + "var dateRe=/\\d{2}\\.\\d{2}\\.\\d{2}/;"
     + "var sections=[];"
     + "var secs=document.querySelectorAll('.h2h__section');"
     + "for(var i=0;i<secs.length;i++){var s=secs[i];"
     + "var hd=s.querySelector('[data-testid=\"wcl-overline-02\"]')||s.querySelector('[data-testid^=\"wcl-headerSection\"]');"
     + "var raw=hd?(hd.innerText||hd.textContent||''):'';"
     + "var ty=kind(norm(raw).toUpperCase());if(!ty)continue;"
     + "sections.push({type:ty,name:(ty==='h2h'?'HEAD-TO-HEAD':nm(raw)),rows:rowsOf(s),dates:0});}"
     + "if(sections.length===0){"
     + "var cur=null;var lastHeading='';var all=document.querySelectorAll('*');"
     + "for(var j=0;j<all.length;j++){var e=all[j];"
     + "var tc=e.textContent||'';"
     + "if(tc.length>0&&tc.length<80){var t=norm(e.innerText||tc).toUpperCase();"
     + "if(t.length>0&&t.length<80&&kind(t)){"
     + "if(t===lastHeading)continue;lastHeading=t;"
     + "cur={type:kind(t),name:(kind(t)==='h2h'?'HEAD-TO-HEAD':nm(e.innerText||tc)),rows:[],dates:0};sections.push(cur);continue;}}"
     + "if(!cur)continue;"
     + "if(e.tagName==='A'){var it=norm(e.innerText||e.textContent);"
     + "if(dateRe.test(it)){var hh=e.href||'';var dup=false;"
     + "for(var q=0;q<cur.rows.length;q++){if(cur.rows[q].h===hh&&cur.rows[q].t===it.slice(0,160)){dup=true;break;}}"
     + "if(!dup)cur.rows.push({h:hh,t:it.slice(0,160)});}}}}"
     + "return JSON.stringify({sections:sections});"
     + "}catch(err){return JSON.stringify({sections:[],err:String(err)});}})()";

  /**
   * The date of the main match as the lists show it: "23.10.2026 13:30" in the match header
   * becomes "23.10.26" (the page title's 23/10/2026 is the fallback). Empty if not found.
   */
  private static final String PAGE_DATE_JS =
      "(function(){try{"
     + "var e=document.querySelector('.duelParticipant__startTime');"
     + "var t=e?(e.innerText||e.textContent||''):'';"
     + "var m=/(\\d{2})\\.(\\d{2})\\.(\\d{4})/.exec(t);"
     + "if(!m){m=/(\\d{2})\\/(\\d{2})\\/(\\d{4})/.exec(document.title||'');}"
     + "return m?(m[1]+'.'+m[2]+'.'+m[3].slice(2)):'';"
     + "}catch(err){return '';}})()";

  /** The address the H2H tab links to (empty if the tab is not there). */
  private static final String H2H_HREF_JS =
      "(function(){try{"
     + "var tabs=document.querySelectorAll('[data-testid=\"wcl-tab\"]');"
     + "for(var i=0;i<tabs.length;i++){var t=(tabs[i].innerText||'').trim().toLowerCase();"
     + "if(t==='h2h'){var a=tabs[i].closest('a');if(a&&a.href)return a.href;}}"
     + "return '';}catch(err){return '';}})()";

  /**
   * Clicks every visible "Show more matches" button (the innermost element that has that text,
   * so one button is clicked once). Returns how many it clicked.
   */
  private static final String SHOW_MORE_JS =
      "(function(){"
     + "function norm(x){return (x||'').replace(/\\s+/g,' ').trim().toLowerCase().replace(/[^a-z ]/g,'').trim();}"
     + "var c=document.querySelectorAll('a,button,span,div');var n=0;"
     + "for(var i=0;i<c.length;i++){var e=c[i];"
     + "if(e.offsetParent===null)continue;"
     + "if(norm(e.innerText)!=='show more matches')continue;"
     + "if(e.getElementsByTagName('*').length>=6)continue;"
     + "var inner=false;"
     + "for(var k=0;k<e.children.length;k++){if(norm(e.children[k].innerText)==='show more matches'){inner=true;break;}}"
     + "if(inner)continue;"
     + "e.scrollIntoView();e.click();n++;}"
     + "return String(n);})()";

  /** The visible tabs of the page (the tab row and the sub-tab rows), lower case. */
  private static final String TABS_JS =
      "(function(){var o=[];try{"
     + "var tabs=document.querySelectorAll('[data-testid=\"wcl-tab\"]');"
     + "for(var i=0;i<tabs.length;i++){var e=tabs[i];if(e.offsetParent===null)continue;"
     + "o.push((e.innerText||'').trim().replace(/\\s+/g,' ').toLowerCase());}"
     + "}catch(err){}return JSON.stringify(o);})()";

  /**
   * The page text (t) and the tabs that show as selected (s, lower case). If the page does not
   * mark the selected tab, s is empty and the walker goes by the text changing instead.
   */
  private static final String SNAP_SEL_JS =
      "(function(){try{var s=[];"
     + "var tabs=document.querySelectorAll('[data-testid=\"wcl-tab\"]');"
     + "for(var i=0;i<tabs.length;i++){var e=tabs[i];"
     + "var a=e.getAttribute('aria-selected');"
     + "var sel=(a==='true')||(e.getAttribute('aria-current')==='true')||(e.getAttribute('data-selected')==='true')"
     + "||(a===null&&/selected/i.test(String(e.className||'')));"
     + "if(sel)s.push((e.innerText||'').trim().replace(/\\s+/g,' ').toLowerCase());}"
     + "return JSON.stringify({t:(document.body?document.body.innerText:''),s:s});"
     + "}catch(err){return JSON.stringify({t:'',s:[]});}})()";

  /**
   * On a match page's PLAYER STATS view: the sub-tab labels sitting in the same row as
   * TOP STATS (the first sub-tab of PLAYER STATS).
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

  /**
   * Every visible table on the page, one line per row, cells joined with " | ".
   * A player cell keeps the position and the player id: "Name / Position #id".
   * v2.1: a header cell keeps the page's stat key ("Rating [FS_RATING]"). In a player table every
   * row ends with five more cells, and the header row ends with their names:
   *   Team (read from the team logo in the row, matched to the logos in the match header) |
   *   Best player (yes/no: the star on the rating; empty on a tab that has no rating column) |
   *   Leads (the stats where the page shows the blue pill, i.e. the leading value of that column) |
   *   Rating color (the hex colour of the rating badge) | Photo (the player photo link).
   * Core.java does not read these tables.
   */
  private static final String TABLE_JS =
      "(function(){try{"
     + "var out=[];"
     + "function fk(src){return (src||'').split('/').pop().split('?')[0];}"
     + "function lk(sel){var e=document.querySelector(sel+' img');return e?fk(e.getAttribute('src')):'';}"
     + "function nm(sel){var e=document.querySelector(sel+' a.participant__participantName')||document.querySelector(sel+' .participant__participantName');return e?(e.textContent||'').replace(/\\s+/g,' ').trim():'';}"
     + "var hk=lk('.duelParticipant__home'),ak=lk('.duelParticipant__away');"
     + "var hn=nm('.duelParticipant__home')||'HOME',an=nm('.duelParticipant__away')||'AWAY';"
     + "var tb=document.querySelectorAll('table');"
     + "for(var i=0;i<tb.length;i++){"
     + "if(tb[i].offsetParent===null)continue;"
     + "var isP=tb[i].querySelector('[data-analytics-player-id]')!==null;"
     + "var keys=[];"
     + "var rows=tb[i].querySelectorAll('tr');"
     + "for(var j=0;j<rows.length;j++){"
     + "var cells=rows[j].querySelectorAll('th,td');var v=[];var lead=[];"
     + "for(var k=0;k<cells.length;k++){var c=cells[k];"
     + "var b=c.querySelector('button');"
     + "var t=b?(b.textContent||''):(c.innerText||'');"
     + "t=t.split('\\n').map(function(x){return x.replace(/\\s+/g,' ').trim();}).filter(function(x){return x;}).join(' / ');"
     + "var f=c.querySelector('[data-analytics-player-id]');"
     + "if(f)t+=' #'+f.getAttribute('data-analytics-player-id');"
     + "var ky=c.getAttribute('data-analytics-alias');"
     + "if(c.tagName==='TH'){keys[k]=ky||'';if(ky)t+=' ['+ky+']';}"
     + "else if(c.querySelector('[class*=\"hasBackground\"]')){lead.push(keys[k]||String(k));}"
     + "v.push(t);}"
     + "if(isP&&v.length){"
     + "if(rows[j].querySelector('th')){v.push('Team');v.push('Best player');v.push('Leads');v.push('Rating color');v.push('Photo');}"
     + "else{"
     + "var lg=rows[j].querySelector('[class*=\"wcl-teamLogo\"] img');var lc=lg?fk(lg.getAttribute('src')):'';"
     + "var team='';if(lc){if(hk&&lc===hk)team=hn;else if(ak&&lc===ak)team=an;}"
     + "v.push(team);"
     + "var bd=rows[j].querySelector('[data-testid=\"wcl-badgeRating\"]');"
     + "v.push(bd?(rows[j].querySelector('[data-testid=\"wcl-icon-other-rating-star\"]')?'yes':'no'):'');"
     + "v.push(lead.join(','));"
     + "v.push(bd?(bd.getAttribute('data-color')||''):'');"
     + "var ph=rows[j].querySelector('figure img');"
     + "v.push(ph?(ph.getAttribute('src')||''):'');}}"
     + "if(v.length)out.push(v.join(' | '));}"
     + "out.push('');}"
     + "return out.join('\\n');"
     + "}catch(err){return '';}})()";

  /**
   * The match header as data: sport > country > competition (with round) and their links, kick-off,
   * status, score, and for each team its name, Flashscore team id, link and logo. Saved as "info".
   */
  private static final String INFO_JS =
      "(function(){try{"
     + "function tx(e){return e?(e.textContent||'').replace(/\\s+/g,' ').trim():'';}"
     + "function at(e,a){return e?(e.getAttribute(a)||''):'';}"
     + "var o={};"
     + "var bc=[];var li=document.querySelectorAll('[data-testid=\"wcl-breadcrumbsItem\"]');"
     + "for(var i=0;i<li.length;i++){bc.push({name:tx(li[i]),url:at(li[i].querySelector('a'),'href')});}"
     + "o.breadcrumb=bc;"
     + "o.kickoff=tx(document.querySelector('.duelParticipant__startTime'));"
     + "o.status=tx(document.querySelector('.fixedHeaderDuel__detailStatus'));"
     + "var sc=document.querySelectorAll('.duelParticipant__score .detailScore__wrapper span');var sv=[];"
     + "for(var k=0;k<sc.length;k++){if(!/divider/.test(sc[k].className||''))sv.push(tx(sc[k]));}"
     + "o.score=sv.join('-');"
     + "var sd=['home','away'];"
     + "for(var s=0;s<2;s++){"
     + "var p=document.querySelector('.duelParticipant__'+sd[s]);"
     + "var a=p?p.querySelector('a.participant__participantName'):null;"
     + "var im=p?p.querySelector('img.participant__image'):null;"
     + "var h=at(a,'href');var m=h.match(/\\/([A-Za-z0-9]+)\\/?$/);"
     + "o[sd[s]]={name:tx(a),id:m?m[1]:'',url:h,logo:at(im,'src')};}"
     + "o.page_title=document.title||'';"
     + "o.page_url=location.href;"
     + "return JSON.stringify(o);"
     + "}catch(err){return '{}';}})()";

  /**
   * Clicks a real tab of the page (the tab row, the stats, player stats and H2H sub-tabs) whose
   * visible text is exactly the given text. A heading with the same words is never clicked, so
   * "notfound" means the tab is not on the page.
   */
  private static final String CLICK_TAB_JS =
      "(function(txt){"
     + "var tabs=document.querySelectorAll('[data-testid=\"wcl-tab\"]');"
     + "for(var q=0;q<tabs.length;q++){var te=tabs[q];"
     + "var tt=(te.innerText||'').trim().replace(/\\s+/g,' ').toLowerCase();"
     + "if(tt===txt&&te.offsetParent!==null){te.scrollIntoView();te.click();return 'ok';}}"
     + "return 'notfound';})('__TXT__');";

  /** Clicks the smallest visible element whose visible text is exactly the given text. */
  private static final String CLICK_TEXT_JS =
      "(function(txt){"
     + "var c=document.querySelectorAll('a,button,li,span,div,[role=tab]');"
     + "var best=null;"
     + "for(var i=0;i<c.length;i++){var e=c[i];"
     + "var t=(e.innerText||'').trim().replace(/\\s+/g,' ').toLowerCase();"
     + "if(t!==txt||e.offsetParent===null)continue;"
     + "var n=e.getElementsByTagName('*').length;"
     + "if(!best||n<best.n)best={e:e,n:n};}"
     + "if(!best)return 'notfound';"
     + "best.e.scrollIntoView();best.e.click();return 'ok';})('__TXT__');";

  /**
   * The football matches on the favorites page. Only the match rows of the list itself count (each
   * row has a link of class eventRowLink and a team name on each side). The page footer also holds
   * a "Match results" list of popular matches: those links are NOT favorites and are left out (they
   * are counted in "footer" for the log). A match that is on the page twice is taken once. Matches
   * of other sports are counted in "skipped". If the page ever has no such rows, the finder falls
   * back to every match link outside the footer.
   */
  private static final String FAV_LIST_JS =
      "(function(){try{"
     + "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}"
     + "function cls(e){var c=e.className;return (c&&c.toString)?c.toString():'';}"
     + "function inFooter(e){var p=e;while(p&&p!==document.body){if(p.tagName==='FOOTER'||/seoFooter/i.test(cls(p)))return true;p=p.parentElement;}return false;}"
     + "var out=[];var seen={};var skipped=0;var foot=0;"
     + "var as=document.querySelectorAll('a.eventRowLink[href*=\"/match/\"]');"
     + "if(as.length===0)as=document.querySelectorAll('[data-event-row] a[href*=\"/match/\"],.event__match a[href*=\"/match/\"]');"
     + "if(as.length===0)as=document.querySelectorAll('a[href*=\"/match/\"]');"
     + "for(var i=0;i<as.length;i++){var a=as[i];var h=a.href||'';"
     + "if(!/\\/match\\/[^\\/?#]+\\/[^\\/?#]+\\/[^\\/?#]+/.test(h))continue;"
     + "if(inFooter(a)){foot++;continue;}"
     + "var m=/[?&]mid=([A-Za-z0-9]+)/.exec(h);var id=m?m[1]:h.split('?')[0];"
     + "if(seen[id])continue;seen[id]=1;"
     + "if(h.indexOf('/match/football/')<0){skipped++;continue;}"
     + "var row=a;for(var d=0;d<8&&row.parentElement;d++){var p=row.parentElement;"
     + "if(p.querySelectorAll('a[href*=\"/match/\"]').length>1)break;row=p;}"
     + "var ev=a.closest?a.closest('.event__match'):null;"
     + "var he=ev?ev.querySelector('.event__homeParticipant'):null;"
     + "var ae=ev?ev.querySelector('.event__awayParticipant'):null;"
     + "out.push({h:h,t:norm(row.innerText||row.textContent).slice(0,160),n:norm(a.getAttribute('aria-label')||''),hn:he?norm(he.innerText||he.textContent):'',an:ae?norm(ae.innerText||ae.textContent):''});}"
     + "var lg=false;var re=/^\\s*(log in|login|sign in)\\s*$/i;"
     + "var tl=(document.body?document.body.innerText:'').split('\\n');"
     + "for(var q=0;q<tl.length&&!lg;q++){if(re.test(tl[q]))lg=true;}"
     + "var le=document.querySelectorAll('a,button,span,div');"
     + "for(var w=0;w<le.length&&!lg;w++){if(le[w].children.length===0&&re.test(le[w].textContent||''))lg=true;}"
     + "return JSON.stringify({rows:out,skipped:skipped,footer:foot,login:lg});"
     + "}catch(err){return JSON.stringify({rows:[],skipped:0,footer:0,login:false,err:String(err)});}})()";

  /** Scrolls to the bottom, so a list that loads while it is scrolled shows all its rows. */
  private static final String FAV_SCROLL_JS =
      "(function(){try{window.scrollTo(0,document.body.scrollHeight);return 'ok';}"
     + "catch(err){return 'err';}})()";

  /** The first words of the page, for the log when no match is found (so the page can be checked). */
  private static final String FAV_DIAG_JS =
      "(function(){try{var t=(document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim();"
     + "return t.slice(0,700);}catch(err){return '';}})()";

  // ---------------------------------------------------------------- state

  private final Handler ui = new Handler(Looper.getMainLooper());
  private final StringBuilder logBuf = new StringBuilder();

  /** Every distinct match to open (a match in two lists is here once). */
  private final List<Rec> recs = new ArrayList<>();
  /** The matches in each list. The same Rec can be in more than one list. */
  private final List<Rec> homeSlots = new ArrayList<>();
  private final List<Rec> awaySlots = new ArrayList<>();
  private final List<Rec> h2hSlots = new ArrayList<>();
  private final List<String> pageNotes = new ArrayList<>();

  private EditText input;
  private Button scrapBtn;
  private TextView badge;
  private TextView statusView;
  private LinearLayout progressPanel;
  private TextView matchLabel;
  private TextView pageName;
  private TextView pagePct;
  private Bar pageBar;
  private LinearLayout counterBox;
  private TextView counterText;
  private TextView counterPct;
  private Bar counterBar;
  private LinearLayout resultPanel;
  private TextView marksView;
  private TextView unavailView;
  private WebView webView;
  private TextView logView;
  private ScrollView scroll;

  // walker state (all touched on the UI thread)
  private volatile boolean walking = false;
  private Runnable onLoaded;
  private int loadToken = 0;
  private int openToken = 0;
  private boolean awaitingReady = false;
  private String mid = "";
  /** True once the mid is known, or once it has been looked for in the lists (link had no ?mid=). */
  private boolean midChecked = false;
  private String matchUrl = "";
  /** True once this attempt has opened the H2H page by its address. */
  private boolean h2hDirectTried = false;
  private Rec mainRec;
  private int totalSlots = 0;
  private int doneSlots = 0;
  private String homeName = "Home";
  private String awayName = "Away";
  private boolean loadFailed = false;
  private int retryPass = 0;
  private int capturedBefore = -1;
  private String homeLeague = "";
  private String awayLeague = "";

  // favorites mode (all touched on the UI thread)
  private boolean favMode = false;
  private boolean favStop = false;
  /** True while the favorites page itself is loading (it has no match tab row). */
  private boolean favListLoad = false;
  /** True after Open on the favorites page (the page has no match tab row to wait for). */
  private boolean openingFav = false;
  private final List<Fav> favQueue = new ArrayList<>();
  private int favIndex = 0;
  private JSONArray favResults = new JSONArray();
  private final List<String> favLines = new ArrayList<>();
  private final List<String> favNotes = new ArrayList<>();
  private final StringBuilder favUnavail = new StringBuilder();
  private int favCompleteCount = 0;

  // the last saved result, for the Download button
  private String lastJson = "";
  private String lastName = "";
  private Uri lastSavedUri;

  /** One match of the favorites page. */
  private static class Fav {
    String url = "";
    String mid = "";
    String row = "";
    /** "Home - Away" as the favorites page itself writes it. */
    String name = "";
  }

  /** One match page (the main match or one of the matches from the H2H tab). */
  private static class Rec {
    String mid = "";
    String url = "";
    String rowText = "";
    final List<String> groups = new ArrayList<>();   // home, away, h2h
    JSONObject tabs = new JSONObject();
    JSONObject info = new JSONObject();   // the match header as data (INFO_JS)
    boolean summaryOk = false;
    boolean emptyFound = false;
    boolean done = false;
    boolean complete = false;
    final List<String> missing = new ArrayList<>();  // tab not in the tab row
    final List<String> emptyTabs = new ArrayList<>(); // tab there, no data
    /** Each tab by name: captured, empty (came back blank), nodata (the page says none) or missing. */
    final Map<String, String> state = new LinkedHashMap<>();
    final List<String> tabRow = new ArrayList<>();   // the tabs the page shows, lower case
    boolean played = true;     // false only for a main match that has not been played yet
    boolean retry = false;     // true once the app has gone back to this page
    boolean counted = false;
    int passes = 1;
  }

  /** A thin progress bar: a filled part and the rest, sized by weight. */
  private static class Bar {
    LinearLayout track;
    View fill;
    View rest;
  }

  private interface Cb {
    void run(String s);
  }

  private interface Flag {
    void run(boolean ok);
  }

  /** Result of reading a tab: found is false when the tab is not on the page. */
  private interface TabCb {
    void run(boolean found, String raw);
  }

  private interface Labels {
    void run(List<String> labels, boolean noData);
  }

  private interface Grown {
    void run(JSONObject o, boolean grew);
  }

  /** The state of one "click a tab and wait for its data". */
  private static class TabPoll {
    String txt = "";
    int before;
    int maxTries = 40;
    boolean requireChange;
    boolean changed;
    int tries;
    int stable;
    int lastHash = -1;
    int clicks = 1;
  }

  // ---------------------------------------------------------------- screen

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (getActionBar() != null) getActionBar().hide();
    if (Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(BLACK);

    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0x00000000);
    root.setPadding(dp(16), dp(8), dp(16), dp(12));

    root.addView(caption("Page 2 \u00b7 Flashscore"), fullWidth(dp(10)));

    // link box: a long pill with a neon green ring
    input = new EditText(this);
    input.setHint("Empty = your Flashscore favorites");
    input.setHintTextColor(GREEN_HINT);
    input.setTextColor(GREEN);
    input.setTextSize(16f);
    input.setInputType(InputType.TYPE_CLASS_TEXT);
    input.setSingleLine(true);
    input.setGravity(Gravity.CENTER_VERTICAL);
    input.setPadding(dp(22), dp(12), dp(22), dp(12));
    input.setBackground(pill(BLACK, GREEN, dp(30)));
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
    badge.setBackground(rounded(RED, dp(11)));
    badge.setVisibility(View.GONE);
    downloadWrap.addView(badge, new FrameLayout.LayoutParams(
        dp(22), dp(22), Gravity.END | Gravity.TOP));
    root.addView(buttonRow(downloadWrap, makePill("Clear", v -> clearAll())), fullWidth(0));

    // Login: a big browser where you type your own Flashscore login. Load file: a saved Flashscore file.
    loginBtn = makePill("Login", v -> loginTap());
    root.addView(buttonRow(loginBtn, makePill("Load file", v -> pickFile(REQ_FLASH, true))), fullWidth(dp(14)));

    // the current step, in plain words
    statusView = new TextView(this);
    statusView.setText("Ready. Tap Login to log in to Flashscore (once), then Scrap. (Or paste one match link, or Load file.)");
    statusView.setTextColor(GREEN);
    statusView.setTextSize(17f);
    root.addView(statusView, fullWidth(dp(12)));

    // progress: only while scraping. The page being scraped and its percentage, then the
    // match counter once the H2H page has been read.
    progressPanel = new LinearLayout(this);
    progressPanel.setOrientation(LinearLayout.VERTICAL);
    progressPanel.setVisibility(View.GONE);

    matchLabel = new TextView(this);
    matchLabel.setTextColor(GREEN_HINT);
    matchLabel.setTextSize(14f);
    progressPanel.addView(matchLabel, fullWidth(dp(8)));

    LinearLayout pageRow = new LinearLayout(this);
    pageRow.setOrientation(LinearLayout.HORIZONTAL);
    pageName = new TextView(this);
    pageName.setTextColor(GREEN);
    pageName.setTextSize(20f);
    pageName.setTypeface(Typeface.DEFAULT_BOLD);
    pageRow.addView(pageName, new LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    pagePct = new TextView(this);
    pagePct.setTextColor(GREEN);
    pagePct.setTextSize(20f);
    pagePct.setTypeface(Typeface.DEFAULT_BOLD);
    pageRow.addView(pagePct, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    progressPanel.addView(pageRow, fullWidth(dp(4)));
    pageBar = makeBar();
    progressPanel.addView(pageBar.track, barParams(dp(16)));

    counterBox = new LinearLayout(this);
    counterBox.setOrientation(LinearLayout.VERTICAL);
    counterBox.setVisibility(View.GONE);
    LinearLayout counterRow = new LinearLayout(this);
    counterRow.setOrientation(LinearLayout.HORIZONTAL);
    counterText = new TextView(this);
    counterText.setTextColor(GREEN);
    counterText.setTextSize(20f);
    counterText.setTypeface(Typeface.DEFAULT_BOLD);
    counterRow.addView(counterText, new LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    counterPct = new TextView(this);
    counterPct.setTextColor(GREEN);
    counterPct.setTextSize(20f);
    counterPct.setTypeface(Typeface.DEFAULT_BOLD);
    counterRow.addView(counterPct, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    counterBox.addView(counterRow, fullWidth(dp(4)));
    counterBar = makeBar();
    counterBox.addView(counterBar.track, barParams(dp(8)));
    progressPanel.addView(counterBox, fullWidth(0));
    root.addView(progressPanel, fullWidth(dp(8)));

    // result: only when a scrap has ended
    resultPanel = new LinearLayout(this);
    resultPanel.setOrientation(LinearLayout.VERTICAL);
    resultPanel.setVisibility(View.GONE);
    marksView = new TextView(this);
    marksView.setTextColor(GREEN);
    marksView.setTextSize(18f);
    marksView.setTypeface(Typeface.MONOSPACE);
    resultPanel.addView(marksView, fullWidth(dp(14)));
    unavailView = new TextView(this);
    unavailView.setTextColor(GREEN_HINT);
    unavailView.setTextSize(14f);
    unavailView.setTextIsSelectable(true);
    resultPanel.addView(unavailView, fullWidth(0));
    root.addView(resultPanel, fullWidth(dp(8)));

    p2Summary = new TextView(this);
    p2Summary.setTextColor(GREEN);
    p2Summary.setTextSize(16f);
    root.addView(p2Summary, fullWidth(dp(8)));
    p2Next = makePill("Next  \u203A", v -> goTo(3));
    p2Next.setVisibility(View.GONE);
    root.addView(p2Next, fullWidth(dp(12)));

    // the browser: hidden until Open or Scrap, then small and dimmed
    webView = new WebView(this);
    WebSettings s = webView.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
    webView.setWebViewClient(new WebViewClient() {
      @Override
      public void onReceivedError(WebView view, WebResourceRequest request,
                    WebResourceError error) {
        super.onReceivedError(view, request, error);
        if (walking && onLoaded != null && request != null && request.isForMainFrame()) {
          loadFailed = true;
          final int token = loadToken;
          final String failed = request.getUrl().toString();
          log("   the page gave an error, loading it again in 4 s");
          ui.postDelayed(() -> {
            if (walking && token == loadToken && onLoaded != null) {
              loadFailed = false;
              webView.loadUrl(failed);
            }
          }, 4000);
        }
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        if (!walking && !"about:blank".equals(url)) log("Loaded: " + url);
        if (!walking && awaitingReady && !"about:blank".equals(url)) {
          awaitingReady = false;
          final int token = openToken;
          status("Page loaded. Waiting for it to be ready...");
          ui.postDelayed(() -> pollOpen(token, 0, -1, 0), 1000);
        }
        if (walking && onLoaded != null && !loadFailed) {
          final Runnable r = onLoaded;
          onLoaded = null;
          ui.postDelayed(() -> {
            if (walking) r.run();
          }, 2500);
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
    scroll.addView(logView);
    root.addView(scroll, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(200)));

    // the four pages, the wordmark and the background; page 2 is the walker built above
    buildShell(root);
    registerBack();

    // Android 13+ asks before a notification can show.
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
    }

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
    handleBack();
  }

  /** Back: inside the Flashscore browser first, then one page back, and on page 1 it leaves the app. */
  private void handleBack() {
    if (curPage == 2 && webView != null && webView.getVisibility() == View.VISIBLE
        && webView.canGoBack() && !walking) {
      webView.goBack();
    } else if (curPage > 1) {
      if (walking || simRunning) {
        Toast.makeText(this, "Something is running. Wait or tap Stop first.", Toast.LENGTH_SHORT).show();
      } else {
        goTo(curPage - 1);
      }
    } else {
      finish();
    }
  }

  /**
   * Android 13+ with the new back gesture: the same rules as above. Written with reflection so the
   * app builds with any compile SDK; if anything here fails, the old back key path still works.
   */
  private void registerBack() {
    try {
      if (Build.VERSION.SDK_INT < 33) return;
      final Class<?> dispC = Class.forName("android.window.OnBackInvokedDispatcher");
      final Class<?> cbC = Class.forName("android.window.OnBackInvokedCallback");
      Object disp = Activity.class.getMethod("getOnBackInvokedDispatcher").invoke(this);
      Object cb = Proxy.newProxyInstance(cbC.getClassLoader(), new Class<?>[] {cbC},
          (proxy, method, args) -> {
            String n = method.getName();
            if ("onBackInvoked".equals(n)) {
              ui.post(() -> handleBack());
              return null;
            }
            if ("hashCode".equals(n)) return System.identityHashCode(proxy);
            if ("equals".equals(n)) return proxy == args[0];
            if ("toString".equals(n)) return "BackCallback";
            return null;
          });
      dispC.getMethod("registerOnBackInvokedCallback", int.class, cbC).invoke(disp, 0, cb);
    } catch (Throwable t) {
      // the old back key path still works
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    try {
      CookieManager.getInstance().flush();     // the Flashscore login stays
    } catch (Exception e) {
      // nothing to do
    }
  }

  // ================================================================ the four pages
  //
  //  Page 1  SportyBet file (the list of matches and their odds)
  //  Page 2  Flashscore data (the walker below: favorites, login, or a saved file)
  //  Page 3  the simulation (6 components -> goal rates -> 200,000 simulated matches -> every market)
  //  Page 4  final results, match by match
  //
  // The BABAYAGA wordmark at the top is the home key. The background is the flaming football.

  private static final int REQ_SPORTY = 21;
  private static final int REQ_FLASH = 22;
  private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
  private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

  private static final String[] FAMILY_ORDER = {
      "Full-time result", "Double chance", "Draw no bet", "Handicap", "Asian handicap", "Total goals",
      "Team goals", "Both teams to score", "Odd / even", "Correct score", "Half time / full time",
      "Goal counts", "Goal sequence", "Clean sheet / win to nil", "Combinations", "Two-half specials",
      "First half", "Second half"};

  private final ScrollView[] pages = new ScrollView[5];
  private int curPage = 1;

  private List<Core.SMatch> sporty = new ArrayList<>();
  private final List<Core.FMatch> flash = new ArrayList<>();
  private List<Core.Pair> pairs = new ArrayList<>();
  private List<Core.Fixture> fixtures = new ArrayList<>();
  private Core.Params simParams = new Core.Params();
  private boolean simRunning = false;
  private boolean simDone = false;

  private ImageView bgView;
  private TextView p1Status;
  private TextView p1List;
  private Button p1Next;
  private TextView p2Summary;
  private Button p2Next;
  private Button loginBtn;
  private boolean loginMode = false;
  private TextView p3Status;
  private TextView p3Pct;
  private Bar p3Bar;
  private TextView p3List;
  private Button p3Run;
  private Button p3Next;
  private TextView p4Head;
  private LinearLayout p4Cards;
  private Button p4Picks;
  private Button p4Edge;
  private boolean p4PicksOnly = false;
  private boolean p4EdgeOnly = false;
  private final List<TextView> p4Details = new ArrayList<>();

  // ---------------------------------------------------------------- shell: background, wordmark, pages

  private void buildShell(LinearLayout page2Root) {
    FrameLayout shell = new FrameLayout(this);
    shell.setBackgroundColor(BLACK);

    bgView = new ImageView(this);
    bgView.setScaleType(ImageView.ScaleType.FIT_CENTER);   // the whole ball, centred, black around it
    shell.addView(bgView, new FrameLayout.LayoutParams(MATCH, MATCH));
    loadBackground();
    View dim = new View(this);
    dim.setBackgroundColor(0x8C000000);
    shell.addView(dim, new FrameLayout.LayoutParams(MATCH, MATCH));

    LinearLayout col = new LinearLayout(this);
    col.setOrientation(LinearLayout.VERTICAL);

    // the wordmark: top centre of every page, the home key on every page but the first
    FrameLayout head = new FrameLayout(this);
    head.setPadding(dp(16), dp(10), dp(16), dp(4));
    Bitmap wm = loadAssetBitmap("babayaga_wordmark.png", 1100);
    if (wm != null) {
      ImageView iv = new ImageView(this);
      iv.setImageBitmap(wm);
      iv.setAdjustViewBounds(true);
      iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
      head.addView(iv, new FrameLayout.LayoutParams(MATCH, dp(60), Gravity.CENTER));
    } else {
      TextView t = new TextView(this);
      t.setText("BABAYAGA");
      t.setTextColor(GREEN);
      t.setTextSize(30f);
      t.setGravity(Gravity.CENTER);
      t.setTypeface(Typeface.DEFAULT_BOLD);
      head.addView(t, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));
    }
    head.setOnClickListener(v -> goHome());
    col.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

    FrameLayout content = new FrameLayout(this);
    pages[1] = scrolled(buildPage1());
    pages[2] = scrolled(page2Root);
    pages[3] = scrolled(buildPage3());
    pages[4] = scrolled(buildPage4());
    for (int i = 1; i <= 4; i++) {
      pages[i].setVisibility(i == 1 ? View.VISIBLE : View.GONE);
      content.addView(pages[i], new FrameLayout.LayoutParams(MATCH, MATCH));
    }
    col.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));
    shell.addView(col, new FrameLayout.LayoutParams(MATCH, MATCH));
    setContentView(shell);
  }

  private ScrollView scrolled(View v) {
    ScrollView s = new ScrollView(this);
    s.setBackgroundColor(0x00000000);
    s.addView(v);
    return s;
  }

  /** The flaming football (assets/babayaga_bg.gif), tinted neon green. No file: plain black. */
  private void loadBackground() {
    try {
      if (Build.VERSION.SDK_INT >= 28) {
        loadAnimatedBackground();
      } else {
        Bitmap b = loadAssetBitmap("babayaga_bg.gif", 1000);
        if (b != null) {
          bgView.setImageBitmap(b);
          bgView.setColorFilter(GREEN, PorterDuff.Mode.MULTIPLY);
        }
      }
    } catch (Throwable t) {
      log("No background picture: " + t);
    }
  }

  /** Android 9+: the GIF plays (the ball rolls, the flames move). */
  private void loadAnimatedBackground() throws Exception {
    ImageDecoder.Source src = ImageDecoder.createSource(getAssets(), "babayaga_bg.gif");
    Drawable d = ImageDecoder.decodeDrawable(src);
    d.setColorFilter(GREEN, PorterDuff.Mode.MULTIPLY);
    bgView.setImageDrawable(d);
    if (d instanceof AnimatedImageDrawable) {
      AnimatedImageDrawable ad = (AnimatedImageDrawable) d;
      ad.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
      ad.start();
    }
  }

  private Bitmap loadAssetBitmap(String name, int maxWidth) {
    try {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      InputStream in = getAssets().open(name);
      BitmapFactory.decodeStream(in, null, o);
      in.close();
      int sample = 1;
      while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
      BitmapFactory.Options o2 = new BitmapFactory.Options();
      o2.inSampleSize = sample;
      InputStream in2 = getAssets().open(name);
      Bitmap b = BitmapFactory.decodeStream(in2, null, o2);
      in2.close();
      return b;
    } catch (Throwable t) {
      return null;
    }
  }

  private void goTo(int n) {
    curPage = n;
    for (int i = 1; i <= 4; i++) pages[i].setVisibility(i == n ? View.VISIBLE : View.GONE);
    pages[n].scrollTo(0, 0);
    if (n == 3) enterPage3();
    if (n == 4) enterPage4();
  }

  private void goHome() {
    if (curPage == 1) return;
    if (walking || simRunning) {
      Toast.makeText(this, "Something is running. Wait or tap Stop first.", Toast.LENGTH_SHORT).show();
      return;
    }
    goTo(1);
  }

  // ---------------------------------------------------------------- small view helpers

  private TextView caption(String s) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextColor(GREEN);
    t.setTextSize(22f);
    t.setTypeface(Typeface.DEFAULT_BOLD);
    t.setGravity(Gravity.CENTER);
    return t;
  }

  private TextView body(String s, float size, boolean mono) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextColor(GREEN);
    t.setTextSize(size);
    if (mono) t.setTypeface(Typeface.MONOSPACE);
    t.setTextIsSelectable(true);
    return t;
  }

  private LinearLayout pageBox() {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(LinearLayout.VERTICAL);
    c.setPadding(dp(16), dp(8), dp(16), dp(16));
    return c;
  }

  // ---------------------------------------------------------------- files

  private void pickFile(int req, boolean multi) {
    try {
      Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("*/*");
      if (multi) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
      startActivityForResult(i, req);
    } catch (Exception e) {
      Toast.makeText(this, "Could not open the file picker: " + e, Toast.LENGTH_LONG).show();
    }
  }

  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (res != RESULT_OK || data == null) return;
    final List<Uri> uris = new ArrayList<>();
    if (data.getClipData() != null) {
      for (int k = 0; k < data.getClipData().getItemCount(); k++) {
        uris.add(data.getClipData().getItemAt(k).getUri());
      }
    } else if (data.getData() != null) {
      uris.add(data.getData());
    }
    if (uris.isEmpty()) return;
    if (req == REQ_SPORTY) loadSporty(uris.get(0));
    else if (req == REQ_FLASH) loadFlash(uris);
  }

  private String readText(Uri uri) throws Exception {
    InputStream in = getContentResolver().openInputStream(uri);
    if (in == null) throw new IllegalStateException("cannot open the file");
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    byte[] buf = new byte[65536];
    int n;
    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
    in.close();
    return new String(bo.toByteArray(), StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------- page 1: SportyBet

  private View buildPage1() {
    LinearLayout c = pageBox();
    c.addView(caption("Page 1 · SportyBet"), fullWidth(dp(10)));
    c.addView(body("Choose the SportyBet file: the list of matches with their odds.", 16f, false), fullWidth(dp(14)));
    c.addView(makePill("Choose SportyBet file", v -> pickFile(REQ_SPORTY, false)), fullWidth(dp(14)));
    p1Status = body("No file yet.", 17f, false);
    c.addView(p1Status, fullWidth(dp(10)));
    p1List = body("", 12f, true);
    c.addView(p1List, fullWidth(dp(14)));
    p1Next = makePill("Next  ›", v -> goTo(2));
    p1Next.setVisibility(View.GONE);
    c.addView(p1Next, fullWidth(dp(20)));
    return c;
  }

  private void loadSporty(final Uri uri) {
    p1Status.setText("Reading the file...");
    p1Next.setVisibility(View.GONE);
    new Thread(() -> {
      try {
        final List<Core.SMatch> list = Core.parseSporty(readText(uri));
        ui.post(() -> onSportyLoaded(list));
      } catch (final Throwable t) {
        ui.post(() -> {
          p1Status.setText("Could not read this file: " + t.getMessage());
          p1List.setText("");
        });
      }
    }).start();
  }

  private void onSportyLoaded(List<Core.SMatch> list) {
    sporty = list;
    autoReset();
    simDone = false;
    fixtures = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    int mk = 0;
    for (Core.SMatch m : list) {
      mk += m.markets.size();
      sb.append(String.format(Locale.US, "%2d. %s v %s\n    %s · %s %s · %d markets\n",
          m.no, m.home, m.away, m.league, m.date, m.time, m.markets.size()));
    }
    p1List.setText(sb.toString());
    if (list.isEmpty()) {
      p1Status.setText("No match found in this file.");
      p1Next.setVisibility(View.GONE);
    } else {
      p1Status.setText(list.size() + " match" + (list.size() == 1 ? "" : "es") + " loaded ✓  (" + mk + " market lines)");
      p1Next.setVisibility(View.VISIBLE);
    }
  }

  // ---------------------------------------------------------------- page 2 additions: login, saved file, next

  /** Login: the Flashscore page in a big browser, where you type your login yourself. */
  private void loginTap() {
    if (walking) {
      status("A scrap is running. Tap Stop first.");
      return;
    }
    if (!loginMode) {
      loginMode = true;
      loginBtn.setText("Login done");
      input.setText("");
      openingFav = true;
      openToken++;
      awaitingReady = false;
      status("Log in inside the page below (tap the page, type your email and password). "
          + "When your favorites show, tap Login done.");
      showBrowserFull();
      // the page scrolls inside the browser while you log in, not the screen behind it
      webView.setOnTouchListener((v, e) -> {
        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
        return false;
      });
      webView.loadUrl(FAV_URL);
      webView.requestFocus();
    } else {
      resetLogin();
      CookieManager.getInstance().flush();
      openUrl();
    }
  }

  private void resetLogin() {
    loginMode = false;
    if (loginBtn != null) loginBtn.setText("Login");
    if (webView != null) webView.setOnTouchListener(null);
  }

  private void showBrowserFull() {
    webView.setVisibility(View.VISIBLE);
    webView.setAlpha(1f);
    int h = getResources().getDisplayMetrics().heightPixels - dp(200);
    setBrowserHeight(Math.max(dp(360), h));
    pages[2].post(() -> pages[2].scrollTo(0, Math.max(0, webView.getTop() - dp(8))));
  }

  /** Load file: a saved Flashscore file (favorites file or walker files) instead of a walk. */
  private void loadFlash(final List<Uri> uris) {
    if (walking) {
      status("A scrap is running. Tap Stop first.");
      return;
    }
    status("Reading " + uris.size() + " file" + (uris.size() == 1 ? "" : "s") + "...");
    new Thread(() -> {
      final List<Core.FMatch> got = new ArrayList<>();
      final StringBuilder err = new StringBuilder();
      for (Uri u : uris) {
        try {
          got.addAll(Core.parseFlashFile(readText(u)));
        } catch (Throwable t) {
          err.append("A file could not be read: ").append(t.getMessage()).append(". ");
        }
      }
      ui.post(() -> {
        flash.clear();
        flash.addAll(got);
        // keep the favorite numbers a file carries; number by order only when they clash
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        boolean clash = false;
        for (Core.FMatch f : flash) {
          if (f.favLine <= 0 || !seen.add(f.favLine)) clash = true;
        }
        if (clash) {
          for (int i = 0; i < flash.size(); i++) flash.get(i).favLine = i + 1;
        }
        autoReset();
        simDone = false;
        fixtures = new ArrayList<>();
        status(err + "" + flash.size() + " Flashscore match" + (flash.size() == 1 ? "" : "es") + " loaded from file.");
        updateP2Next();
      });
    }).start();
  }

  private void updateP2Next() {
    if (flash.isEmpty()) {
      p2Summary.setText("");
      p2Next.setVisibility(View.GONE);
      return;
    }
    if (autoSim) {
      p2Summary.setText(flash.size() + " scraped · " + fixtures.size() + " simulated"
          + (autoPending > 0 ? " · " + autoPending + " running" : "") + ".");
    } else {
      p2Summary.setText(flash.size() + " Flashscore match" + (flash.size() == 1 ? "" : "es") + " ready for the simulation.");
    }
    p2Next.setVisibility(walking || autoPending > 0 ? View.GONE : View.VISIBLE);
  }

  /** A finished walk of one match: keep its data for the simulation (a light copy, not the raw pages). */
  private Core.FMatch keepFlash(JSONObject one, int line) {
    try {
      Core.FMatch fm = Core.parseFlash(Core.M(Core.Json.parse(one.toString())));
      fm.favLine = line;
      flash.add(fm);
      if (!autoSim) {
        simDone = false;
        fixtures = new ArrayList<>();
      }
      return fm;
    } catch (Throwable t) {
      log("Could not read this match for the simulation: " + t);
      return null;
    }
  }

  // ---------------------------------------------------------------- the pipeline: scrap -> simulate

  /** True while finished matches go to the simulator one after the other (a SportyBet file is loaded). */
  private boolean autoSim = false;
  /** Bumped whenever the data is reset, so an answer that comes late is dropped. */
  private int autoGen = 0;
  /** Simulations queued or running. */
  private int autoPending = 0;
  /** The scrape is over: the run closes when the last simulation is done. */
  private boolean autoEnded = false;
  /** Open page 3 by itself when the run closes (the scrape finished on its own, not by Stop). */
  private boolean autoOpen = false;
  private java.util.concurrent.ExecutorService simExec;

  private void autoReset() {
    autoGen++;
    autoSim = false;
    autoPending = 0;
    autoEnded = false;
    autoOpen = false;
    simRunning = false;
  }

  /** A new scrape begins: every match it finishes is simulated at once, behind the earlier ones. */
  private void autoStart() {
    autoReset();
    autoSim = !sporty.isEmpty();
    if (autoSim) {
      log("SportyBet file is loaded: each finished match goes straight to the simulator.");
    } else {
      log("No SportyBet file is loaded (page 1): the matches can be simulated from page 3.");
    }
  }

  /** The label of a match for the log. */
  private static String fmLabel(Core.FMatch fm) {
    String n = (fm.home.isEmpty() || fm.away.isEmpty()) ? fm.favName : fm.home + " v " + fm.away;
    return n.isEmpty() ? "match" : n;
  }

  /** A finished match goes to the simulator now, in line behind the ones before it. */
  private void autoHandOver(final Core.FMatch fm) {
    if (!autoSim || fm == null) return;
    pairs = Core.join(flash, sporty);
    Core.Pair mine = null;
    for (Core.Pair p : pairs) if (p.fm == fm) mine = p;
    if (mine == null) return;
    if (mine.sm == null) {
      log("Simulator: " + shorten(fmLabel(fm), 40) + " has no SportyBet match, left out.");
      return;
    }
    final Core.Pair pr = mine;
    final List<Core.FMatch> seen = new ArrayList<>(flash);
    final Core.Params prm = simParams;
    final int gen = autoGen;
    autoPending++;
    simRunning = true;
    log("Sent to the simulator: " + shorten(fmLabel(fm), 40) + " with SportyBet line " + pr.sm.no + " (" + pr.how + ").");
    updateP2Next();
    simExecutor().execute(() -> {
      Core.Fixture fx;
      try {
        fx = Core.runOne(pr, seen, prm);
      } catch (Throwable t) {
        fx = new Core.Fixture();
        fx.no = pr.sm.no;
        fx.sm = pr.sm;
        fx.fm = pr.fm;
        fx.matchedBy = pr.how;
        fx.error = String.valueOf(t);
      }
      final Core.Fixture done = fx;
      ui.post(() -> autoDone(gen, done));
    });
  }

  private java.util.concurrent.ExecutorService simExecutor() {
    if (simExec == null) {
      simExec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "simulator");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
      });
    }
    return simExec;
  }

  /** One simulation is done (UI thread). */
  private void autoDone(int gen, Core.Fixture fx) {
    if (gen != autoGen) return;
    fixtures.add(fx);
    autoPending--;
    String who = fx.sm.home + " v " + fx.sm.away;
    if (fx.ok) {
      log(String.format(Locale.US, "Simulated: %s · goals %.2f - %.2f ✓", shorten(who, 36), fx.lamH, fx.lamA));
    } else {
      log("Simulated: " + shorten(who, 36) + " ! " + fx.error);
    }
    if (autoPending <= 0) {
      autoPending = 0;
      simRunning = false;
    }
    updateP2Next();
    if (autoPending == 0 && autoEnded) autoFinish();
  }

  /**
   * The scrape is over. When the last simulation is done the run closes: matches simulated before
   * the end used the home advantage of the matches scraped so far, so they are run once more with
   * the home advantage of the whole run (only those that differ), and the result is then the same as
   * a run of all matches at once.
   */
  private void autoEnd(boolean openPage3) {
    if (!autoSim) return;
    autoEnded = true;
    autoOpen = openPage3;
    if (autoPending == 0) autoFinish();
  }

  private void autoFinish() {
    if (flash.isEmpty() && fixtures.isEmpty()) {
      autoReset();
      return;
    }
    final int gen = autoGen;
    final List<Core.FMatch> all = new ArrayList<>(flash);
    final List<Core.Fixture> have = new ArrayList<>(fixtures);
    final Core.Params prm = simParams;
    autoPending++;
    simRunning = true;
    simExecutor().execute(() -> {
      final List<Core.Fixture> out = new ArrayList<>();
      try {
        double hfa = Core.runHfa(all, prm);
        for (Core.Fixture f : have) {
          if (f.ok && f.sm != null && f.fm != null && Math.abs(f.hfa - hfa) > 1e-9) {
            out.add(Core.rerun(f, prm, hfa));
          } else {
            out.add(f);
          }
        }
      } catch (Throwable t) {
        out.clear();
        out.addAll(have);
      }
      ui.post(() -> {
        if (gen != autoGen) return;
        autoPending = 0;
        simRunning = false;
        simDone = true;
        fixtures = out;
        pairs = Core.join(flash, sporty);
        int okN = 0;
        for (Core.Fixture f : out) if (f.ok) okN++;
        log("Simulation finished: " + okN + " of " + out.size() + " match" + (out.size() == 1 ? "" : "es") + " simulated.");
        updateP2Next();
        try {
          notifyDone("Simulation finished", okN + " of " + out.size() + " matches simulated.");
        } catch (Throwable t) {
          // the notification is only a courtesy
        }
        if (autoOpen && okN > 0 && curPage == 2) goTo(3);
      });
    });
  }

  // ---------------------------------------------------------------- page 3: simulation

  private View buildPage3() {
    LinearLayout c = pageBox();
    c.addView(caption("Page 3 · Simulation"), fullWidth(dp(10)));
    p3Status = body("", 17f, false);
    c.addView(p3Status, fullWidth(dp(10)));
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    p3Pct = body("", 18f, false);
    p3Pct.setTypeface(Typeface.DEFAULT_BOLD);
    row.addView(p3Pct, new LinearLayout.LayoutParams(WRAP, WRAP));
    c.addView(row, fullWidth(dp(4)));
    p3Bar = makeBar();
    c.addView(p3Bar.track, barParams(dp(14)));
    p3List = body("", 12f, true);
    c.addView(p3List, fullWidth(dp(14)));
    p3Run = makePill("Run again", v -> startSim());
    p3Run.setVisibility(View.GONE);
    c.addView(p3Run, fullWidth(dp(10)));
    p3Next = makePill("Next  ›", v -> goTo(4));
    p3Next.setVisibility(View.GONE);
    c.addView(p3Next, fullWidth(dp(20)));
    return c;
  }

  private void enterPage3() {
    if (sporty.isEmpty()) {
      p3Status.setText("Page 1 first: load the SportyBet file.");
      return;
    }
    if (flash.isEmpty()) {
      p3Status.setText("Page 2 first: scrap your favorites or load a Flashscore file.");
      return;
    }
    if (simRunning) {
      p3Status.setText("The simulator is still running. A moment...");
      return;
    }
    pairs = Core.join(flash, sporty);
    if (simDone) {
      renderSimResults();
      return;
    }
    startSim();
  }

  private void showPairLines() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < pairs.size(); i++) {
      Core.Pair p = pairs.get(i);
      sb.append(String.format(Locale.US, "%2d. %s v %s\n", i + 1, p.fm.home, p.fm.away));
      sb.append("    ").append(p.sm == null ? "no SportyBet match, left out" : p.how).append('\n');
    }
    p3List.setText(sb.toString());
  }

  private void startSim() {
    if (simRunning || pairs.isEmpty()) return;
    simRunning = true;
    simDone = false;
    p3Next.setVisibility(View.GONE);
    p3Run.setVisibility(View.GONE);
    p3Status.setText("Simulating " + simParams.nSim + " matches for each fixture...");
    p3Pct.setText("0%");
    setBar(p3Bar, 0);
    showPairLines();
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    final List<Core.Pair> ps = pairs;
    final Core.Params prm = simParams;
    new Thread(() -> {
      try {
        final List<Core.Fixture> out = Core.runAll(ps, prm, (done, total, text) ->
            ui.post(() -> {
              int pct = total <= 0 ? 0 : done * 100 / total;
              p3Pct.setText(done + " / " + total + "   " + pct + "%");
              setBar(p3Bar, pct);
              p3Status.setText(done < total ? "Simulating: " + text : "Simulated.");
            }));
        ui.post(() -> simFinished(out));
      } catch (final Throwable t) {
        ui.post(() -> {
          simRunning = false;
          getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          p3Status.setText("The simulation stopped: " + t);
          p3Run.setVisibility(View.VISIBLE);
        });
      }
    }).start();
  }

  private void simFinished(List<Core.Fixture> out) {
    simRunning = false;
    simDone = true;
    fixtures = out;
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    int okN = renderSimResults();
    try {
      notifyDone("Simulation finished", okN + " matches simulated.");
    } catch (Throwable t) {
      // the notification is only a courtesy
    }
  }

  /** Writes the result of every pair on page 3 (after a run, and when the page is opened again). */
  private int renderSimResults() {
    final List<Core.Fixture> out = fixtures;
    StringBuilder sb = new StringBuilder();
    int okN = 0;
    int idx = 0;
    for (Core.Pair p : pairs) {
      idx++;
      sb.append(String.format(Locale.US, "%2d. %s v %s\n", idx, p.fm.home, p.fm.away));
      if (p.sm == null) {
        sb.append("    no SportyBet match, left out\n");
        continue;
      }
      Core.Fixture fx = null;
      for (Core.Fixture f : out) {
        if (f.sm == p.sm) fx = f;
      }
      if (fx == null) continue;
      if (fx.ok) {
        okN++;
        sb.append(String.format(Locale.US, "    ✓ goals %.2f - %.2f · %s\n", fx.lamH, fx.lamA, fx.group));
      } else {
        sb.append("    ! ").append(fx.error).append('\n');
      }
    }
    p3List.setText(sb.toString());
    p3Status.setText("Done: " + okN + " of " + out.size() + " match" + (out.size() == 1 ? "" : "es") + " simulated.");
    p3Pct.setText("100%");
    setBar(p3Bar, 100);
    p3Run.setVisibility(View.VISIBLE);
    p3Next.setVisibility(okN > 0 ? View.VISIBLE : View.GONE);
    return okN;
  }

  // ---------------------------------------------------------------- page 4: final results

  private View buildPage4() {
    LinearLayout c = pageBox();
    c.addView(caption("Page 4 · Final results"), fullWidth(dp(10)));
    p4Head = body("", 15f, false);
    c.addView(p4Head, fullWidth(dp(10)));
    p4Picks = makePill("Picks only: off", v -> {
      p4PicksOnly = !p4PicksOnly;
      p4Picks.setText("Picks only: " + (p4PicksOnly ? "on" : "off"));
      refreshDetails();
    });
    p4Edge = makePill("Edge > 0: off", v -> {
      p4EdgeOnly = !p4EdgeOnly;
      p4Edge.setText("Edge > 0: " + (p4EdgeOnly ? "on" : "off"));
      refreshDetails();
    });
    c.addView(buttonRow(p4Picks, p4Edge), fullWidth(dp(4)));
    c.addView(makePill("Download results", v -> downloadResults()), fullWidth(dp(14)));
    p4Cards = new LinearLayout(this);
    p4Cards.setOrientation(LinearLayout.VERTICAL);
    c.addView(p4Cards, fullWidth(dp(20)));
    return c;
  }

  private void enterPage4() {
    p4Cards.removeAllViews();
    p4Details.clear();
    if (fixtures.isEmpty()) {
      p4Head.setText("No results yet. Run the simulation on page 3.");
      return;
    }
    int ok = 0;
    for (Core.Fixture f : fixtures) if (f.ok) ok++;
    p4Head.setText(ok + " match" + (ok == 1 ? "" : "es") + ". Tap a match to open it. "
        + "Columns: odds · model chance · bookmaker chance (margin removed) · edge in points. ◀ = the model's pick of that line.");
    for (final Core.Fixture fx : fixtures) {
      final LinearLayout card = new LinearLayout(this);
      card.setOrientation(LinearLayout.VERTICAL);
      card.setBackground(pill(0xCC000000, GREEN, dp(18)));
      card.setPadding(dp(14), dp(10), dp(14), dp(10));
      TextView headT = body(cardHead(fx), 15f, false);
      headT.setTextIsSelectable(false);
      headT.setTypeface(Typeface.DEFAULT_BOLD);
      card.addView(headT, fullWidth(0));
      final TextView det = body("", 11f, true);
      det.setVisibility(View.GONE);
      det.setTag(fx);
      p4Details.add(det);
      card.addView(det, fullWidth(0));
      card.setOnClickListener(v -> {
        if (det.getVisibility() == View.VISIBLE) {
          det.setVisibility(View.GONE);
        } else {
          det.setText(fixtureText(fx));
          det.setVisibility(View.VISIBLE);
        }
      });
      LinearLayout.LayoutParams lp = fullWidth(dp(10));
      p4Cards.addView(card, lp);
    }
  }

  private void refreshDetails() {
    for (TextView d : p4Details) {
      if (d.getVisibility() == View.VISIBLE && d.getTag() instanceof Core.Fixture) {
        d.setText(fixtureText((Core.Fixture) d.getTag()));
      }
    }
  }

  private String cardHead(Core.Fixture fx) {
    String t = fx.no + ". " + fx.sm.home + " v " + fx.sm.away;
    if (!fx.ok) return t + "\n! " + fx.error;
    return t + String.format(Locale.US, "\ngoals %.2f - %.2f  ·  1 %.0f%%  X %.0f%%  2 %.0f%%",
        fx.lamH, fx.lamA, 100 * fx.pHome, 100 * fx.pDraw, 100 * fx.pAway);
  }

  private static String pct(double v) {
    return Double.isNaN(v) ? "   --" : String.format(Locale.US, "%5.1f", 100 * v);
  }

  private static String cut(String s, int n) {
    return s.length() <= n ? s : s.substring(0, n - 1) + "…";
  }

  private String fixtureText(Core.Fixture fx) {
    StringBuilder sb = new StringBuilder();
    sb.append(fx.sm.league).append("\n").append(fx.sm.date).append(" ").append(fx.sm.time).append("\n");
    sb.append("Matched: ").append(fx.matchedBy).append("\n");
    if (!fx.ok) {
      sb.append(fx.error).append("\n");
      return sb.toString();
    }
    sb.append(String.format(Locale.US, "Data: %s · past games %d / %d · home advantage %.3f\n",
        fx.group, fx.nH, fx.nA, fx.hfa));
    sb.append("Formations: ").append(fx.formH.isEmpty() ? "-" : fx.formH).append(" v ")
        .append(fx.formA.isEmpty() ? "-" : fx.formA).append(" (").append(fx.formSource).append(")\n\n");
    sb.append(String.format(Locale.US, "Expected goals  home %.2f · away %.2f\n", fx.lamH, fx.lamA));
    sb.append(String.format(Locale.US, "Total %.2f · first half %.2f\n", fx.eTotal, fx.e1h));
    sb.append(String.format(Locale.US, "Home %.1f%% · Draw %.1f%% · Away %.1f%%\n", 100 * fx.pHome, 100 * fx.pDraw, 100 * fx.pAway));
    sb.append(String.format(Locale.US, "Over 2.5 %.1f%% · Both score %.1f%%\n", 100 * fx.pOver25, 100 * fx.pBtts));
    sb.append("Likely scores: ");
    for (int i = 0; i < fx.scores.size(); i++) sb.append(i == 0 ? "" : " · ").append(fx.scores.get(i));
    sb.append("\n\nSix components (home | away)\n");
    String[] names = {"Attack Potency", "Defensive Solidity", "Pressure & Wing", "Class & Dominance", "Formation Effect"};
    for (String n : names) {
      Double h = fx.comp.get(n + " home");
      Double a = fx.comp.get(n + " away");
      if (h == null || a == null) continue;
      sb.append(String.format(Locale.US, " %-19s %5.2f | %5.2f\n", n, h, a));
    }
    sb.append(" Opponent Strength     neutral (no league tables)\n");
    for (String n : fx.notes) sb.append(" · ").append(n).append("\n");
    sb.append(String.format(Locale.US, "Markets with a prediction: %d · without: %d\n", fx.nSupported, fx.nUnsupported));

    for (String fam : FAMILY_ORDER) {
      StringBuilder fs = new StringBuilder();
      for (Core.Market mk : fx.sm.markets) {
        if (!fam.equals(mk.family)) continue;
        StringBuilder ms = new StringBuilder();
        for (Core.Outcome o : mk.outs) {
          boolean edgeOk = !Double.isNaN(o.q) && !Double.isNaN(o.book) && o.q > o.book;
          if (p4PicksOnly && !o.pick) continue;
          if (p4EdgeOnly && !edgeOk) continue;
          String edge = (Double.isNaN(o.q) || Double.isNaN(o.book)) ? "   --"
              : String.format(Locale.US, "%+5.1f", 100 * (o.q - o.book));
          ms.append(String.format(Locale.US, " %-14s%6.2f %s %s %s%s\n", cut(o.label, 14), o.odds,
              pct(o.q), pct(o.book), edge, o.pick ? " ◀" : ""));
          if (Double.isNaN(o.q) && !o.why.isEmpty()) ms.append("   (").append(o.why).append(")\n");
        }
        if (ms.length() == 0) continue;
        fs.append(mk.name).append(mk.line == null ? "" : "  [" + mk.line + "]").append('\n').append(ms);
      }
      if (fs.length() > 0) sb.append("\n── ").append(fam).append(" ──\n").append(fs);
    }
    return sb.toString();
  }

  private void downloadResults() {
    if (fixtures.isEmpty()) {
      Toast.makeText(this, "No results yet.", Toast.LENGTH_SHORT).show();
      return;
    }
    String name = "babayaga_results_" + (System.currentTimeMillis() / 1000) + ".json";
    Uri keep = lastSavedUri;
    saveToDownloads(name, Core.resultsJson(fixtures, simParams));
    boolean saved = lastSavedUri != null && lastSavedUri != keep;
    lastSavedUri = keep;
    Toast.makeText(this, saved ? "Saved to Downloads: " + name : "Could not save. See the log on page 2.",
        Toast.LENGTH_LONG).show();
  }

  // ---------------------------------------------------------------- theme helpers

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

  private GradientDrawable rounded(int color, int radius) {
    GradientDrawable g = new GradientDrawable();
    g.setShape(GradientDrawable.RECTANGLE);
    g.setColor(color);
    g.setCornerRadius(radius);
    return g;
  }

  /** A green-outline pill with green text and no fill. */
  private Button makePill(String label, View.OnClickListener l) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextColor(GREEN);
    b.setTextSize(16f);
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

  private LinearLayout.LayoutParams barParams(int bottomMargin) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
    lp.bottomMargin = bottomMargin;
    return lp;
  }

  private Bar makeBar() {
    Bar b = new Bar();
    b.track = new LinearLayout(this);
    b.track.setOrientation(LinearLayout.HORIZONTAL);
    b.track.setWeightSum(100f);
    b.track.setBackground(rounded(GREEN_DIM, dp(4)));
    b.fill = new View(this);
    b.fill.setBackground(rounded(GREEN, dp(4)));
    b.rest = new View(this);
    b.track.addView(b.fill, new LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.MATCH_PARENT, 0f));
    b.track.addView(b.rest, new LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.MATCH_PARENT, 100f));
    return b;
  }

  private void setBar(Bar b, int pct) {
    int p = Math.max(0, Math.min(100, pct));
    ((LinearLayout.LayoutParams) b.fill.getLayoutParams()).weight = p;
    ((LinearLayout.LayoutParams) b.rest.getLayoutParams()).weight = 100 - p;
    b.track.requestLayout();
  }

  /** The browser sits small and dimmed while a page loads or a scrape runs. */
  private void showBrowser() {
    webView.setVisibility(View.VISIBLE);
    webView.setAlpha(0.35f);
    setBrowserHeight(dp(160));
  }

  /** The browser big and bright: for looking at the favorites page and logging in to Flashscore. */
  private void showBrowserBig() {
    webView.setVisibility(View.VISIBLE);
    webView.setAlpha(1f);
    setBrowserHeight(dp(430));
  }

  private void setBrowserHeight(int px) {
    android.view.ViewGroup.LayoutParams lp = webView.getLayoutParams();
    if (lp != null && lp.height != px) {
      lp.height = px;
      webView.setLayoutParams(lp);
    }
  }

  /** The match label; in favorites mode it also says which favorite this is. */
  private void setLabel(String s) {
    if (favMode && !favQueue.isEmpty()) {
      s = "Favorite " + (favIndex + 1) + " / " + favQueue.size() + "  ·  " + s;
    }
    matchLabel.setText(s);
  }

  /** Shows a message in the status line and in the log. */
  private void status(final String msg) {
    ui.post(() -> statusView.setText(msg));
    log(msg);
  }

  /** The page being scraped and how far along it is. Shows only while scraping. */
  private void showPage(String name, int done, int total) {
    int pct = total <= 0 ? 0 : Math.min(100, done * 100 / total);
    progressPanel.setVisibility(View.VISIBLE);
    pageName.setText(name);
    pagePct.setText(pct + "%");
    setBar(pageBar, pct);
  }

  private void updateCounter() {
    counterBox.setVisibility(View.VISIBLE);
    int pct = totalSlots <= 0 ? 0 : Math.min(100, doneSlots * 100 / totalSlots);
    counterText.setText("Matches " + doneSlots + " / " + totalSlots);
    counterPct.setText(pct + "%");
    setBar(counterBar, pct);
  }

  private void resetPanels() {
    progressPanel.setVisibility(View.GONE);
    counterBox.setVisibility(View.GONE);
    resultPanel.setVisibility(View.GONE);
    matchLabel.setText("");
    setBar(pageBar, 0);
    setBar(counterBar, 0);
  }

  // ---------------------------------------------------------------- buttons

  /** Loads the link and says when the page is ready for scrap. */
  private void openUrl() {
    if (walking) {
      status("A scrap is running. Tap Stop first.");
      return;
    }
    String text = input.getText().toString();
    Matcher m = Pattern.compile("https?://\\S+").matcher(text);
    String url;
    if (m.find()) {
      url = m.group();
      openingFav = false;
      status("Opening the page...");
      showBrowser();
    } else {
      url = FAV_URL;
      openingFav = true;
      status("Opening your Flashscore favorites...");
      showBrowserBig();
    }
    openToken++;
    awaitingReady = true;
    webView.loadUrl(url);
  }

  /** After Open: waits until the page stops changing, then says it is ready for scrap. */
  private void pollOpen(final int token, final int tries, final int lastHash, final int stable) {
    if (token != openToken || walking) return;
    webView.evaluateJavascript(READY_JS, v -> {
      if (token != openToken || walking) return;
      JSONObject o = parseOrEmpty(unquote(v));
      final int h = o.optInt("h");
      int n = o.optInt("n");
      boolean tabsShown = o.optBoolean("tabs") || (openingFav && n > 150);
      if (!tabsShown) webView.evaluateJavascript(CONSENT_JS, null);
      if (o.optBoolean("age")) {
        log("Age check on screen: answering YES");
        webView.evaluateJavascript(clickText("yes"), null);
        ui.postDelayed(() -> pollOpen(token, tries + 1, -1, 0), 1000);
        return;
      }
      final int nextStable = (tabsShown && n > 0 && h == lastHash) ? stable + 1 : 0;
      if (nextStable >= 4) {
        if (openingFav) {
          reportFavorites();
        } else {
          status("Page ready ✓  Tap Scrap to start.");
        }
        return;
      }
      if (tries >= 60) {
        status("The page opened but did not settle. You can still tap Scrap.");
        return;
      }
      ui.postDelayed(() -> pollOpen(token, tries + 1, h, nextStable), 500);
    });
  }

  /** After Open on the favorites page: says how many football matches are on it. */
  private void reportFavorites() {
    webView.evaluateJavascript(FAV_LIST_JS, v -> {
      JSONObject o = parseOrEmpty(unquote(v));
      JSONArray rows = o.optJSONArray("rows");
      int n = rows == null ? 0 : rows.length();
      if (n > 0) {
        status("Favorites ready ✓  " + n + " football match" + (n == 1 ? "" : "es")
            + " on the page. Tap Scrap to walk them.");
      } else {
        status("No football match found on this page. If you are not logged in to Flashscore, "
            + "tap Login, log in, then tap Login done.");
        webView.evaluateJavascript(FAV_DIAG_JS, d -> log("Page text starts: " + unquote(d)));
      }
    });
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

  /** Clears the link box, the log, the progress and the browser. Stops a running scrape first. */
  private void clearAll() {
    if (walking) stopWalk();
    openToken++;
    awaitingReady = false;
    input.setText("");
    synchronized (logBuf) {
      logBuf.setLength(0);
    }
    logView.setText("");
    resetData();
    lastJson = "";
    lastName = "";
    lastSavedUri = null;
    favMode = false;
    favStop = false;
    favListLoad = false;
    openingFav = false;
    resetFav();
    resetLogin();
    flash.clear();
    autoReset();
    simDone = false;
    fixtures = new ArrayList<>();
    updateP2Next();
    badge.setVisibility(View.GONE);
    resetPanels();
    webView.loadUrl("about:blank");
    webView.setVisibility(View.GONE);
    status("Cleared. Tap Login (once), then Scrap. (Or paste one match link, or Load file.)");
  }

  private void resetData() {
    recs.clear();
    homeSlots.clear();
    awaySlots.clear();
    h2hSlots.clear();
    pageNotes.clear();
    mainRec = null;
    totalSlots = 0;
    doneSlots = 0;
    homeName = "Home";
    awayName = "Away";
    retryPass = 0;
    capturedBefore = -1;
    homeLeague = "";
    awayLeague = "";
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
      startFavorites();
      return;
    }
    favMode = false;
    openingFav = false;
    matchUrl = baseMatchUrl(u.group());
    if (!matchUrl.equals(u.group())) log("The link was a tab of the match. Starting from the match page: " + matchUrl);
    Matcher mm = Pattern.compile("[?&]mid=([A-Za-z0-9]+)").matcher(matchUrl);
    if (!matchUrl.contains("flashscore.")) {
      status("Scrap needs a Flashscore match link that has ?mid= in it.");
      return;
    }
    // A link without ?mid= is accepted: the mid is taken from the H2H lists (see takeMidFrom).
    mid = mm.find() ? mm.group(1) : "";
    midChecked = !mid.isEmpty();

    openToken++;
    awaitingReady = false;
    resetData();
    mainRec = new Rec();
    mainRec.mid = mid;
    mainRec.url = matchUrl;
    resetLogin();
    flash.clear();
    simDone = false;
    fixtures = new ArrayList<>();
    autoStart();
    updateP2Next();
    badge.setVisibility(View.GONE);
    resetPanels();

    walking = true;
    scrapBtn.setText("Stop");
    showBrowser();
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    matchLabel.setText("Main match");
    showPage("Opening the page", 0, 1);
    status("Scrap started" + (mid.isEmpty() ? "" : " for " + mid)
        + ". Keep the app open until it says done.");
    loadThen(matchUrl, this::walkMain);
  }

  private void stopWalk() {
    if (!walking) {
      status("Not scrapping.");
      return;
    }
    status("Stopped by you. Saving what was collected so far.");
    if (favMode) favStop = true;
    finishWalk(false);
  }

  // ---- loading a page

  /**
   * Loads a page and runs next once it has loaded. A page that does not load (no signal, an
   * error, a hang) is loaded again and again until it does, or until Stop is tapped.
   */
  private void loadThen(final String url, final Runnable next) {
    if (!walking) return;
    final int token = ++loadToken;
    onLoaded = next;
    loadFailed = false;
    loadAttempt(url, token, 0);
  }

  private void loadAttempt(final String url, final int token, final int attempt) {
    if (!walking || token != loadToken || onLoaded == null) return;
    if (attempt > 0) log("   the page did not load, loading it again (try " + (attempt + 1) + ")");
    loadFailed = false;
    webView.loadUrl(url);
    ui.postDelayed(() -> {
      if (!walking || token != loadToken || onLoaded == null) return;
      // 45 s and the page never said it had finished. If it is on screen, go on; if not, load again.
      jsWalk(READY_JS, json -> {
        if (token != loadToken || onLoaded == null) return;
        JSONObject o = parseOrEmpty(json);
        if ((o.optBoolean("tabs") || favListLoad) && o.optInt("n") > 150) {
          Runnable r = onLoaded;
          onLoaded = null;
          log("   (the page is on screen, going on)");
          r.run();
        } else {
          loadAttempt(url, token, attempt + 1);
        }
      });
    }, 45000);
  }

  private void jsWalk(String script, Cb cb) {
    if (!walking) return;
    webView.evaluateJavascript(script, v -> {
      if (walking) cb.run(unquote(v));
    });
  }

  // ---- waiting for a page

  /**
   * Waits until the page is really there: the match tabs are on screen and the text has not
   * changed for 2 seconds. Answers the age check YES. ok is false if that did not happen in 20 s.
   */
  private void waitPageReady(final String label, final Flag done) {
    pageReady(0, -1, 0, label, 40, done);
  }

  private void pageReady(final int tries, final int lastHash, final int stable,
              final String label, final int maxTries, final Flag done) {
    if (!walking) return;
    jsWalk(READY_JS, json -> {
      final JSONObject o = parseOrEmpty(json);
      final int h = o.optInt("h");
      final int n = o.optInt("n");
      final boolean tabsShown = o.optBoolean("tabs");
      if (!tabsShown || tries % 8 == 0) {
        // a consent box can cover the page and keep it from loading: answer it
        jsWalk(CONSENT_JS, c -> {
          if ("rejected".equals(c) || "accepted".equals(c)) log("Consent box on screen: " + c);
        });
      }
      if (o.optBoolean("age")) {
        log("Age check on screen: answering YES");
        tryClick("yes", 0, 3, false, res -> {
          ui.postDelayed(() -> pageReady(tries + 1, -1, 0, label, maxTries, done), 800);
        });
        return;
      }
      final int nextStable = (tabsShown && n > 150 && h == lastHash) ? stable + 1 : 0;
      if (nextStable >= 4) {
        if (label != null) status("Page ready ✓ " + label);
        done.run(true);
        return;
      }
      if (tries >= maxTries) {
        done.run(false);
        return;
      }
      ui.postDelayed(() -> pageReady(tries + 1, h, nextStable, label, maxTries, done), 500);
    });
  }

  /**
   * Clicks the tab with this visible text. If it is not on the page yet, tries again
   * once a second, up to maxTries times. Calls done with "ok" or "notfound".
   */
  private void waitClick(final String txt, final int maxTries, final Cb done) {
    tryClick(txt, 0, maxTries, true, done);
  }

  private void tryClick(final String txt, final int tries, final int maxTries,
             final boolean tabOnly, final Cb done) {
    if (!walking) return;
    jsWalk(tabOnly ? clickTab(txt) : clickText(txt), res -> {
      if ("ok".equals(res) || tries + 1 >= maxTries) {
        done.run(res);
      } else {
        ui.postDelayed(() -> tryClick(txt, tries + 1, maxTries, tabOnly, done), 1000);
      }
    });
  }

  /** The tabs in the page's tab row, read once the page is ready. */
  private void readTabRow(final Rec r, final Runnable next) {
    jsWalk(TABS_JS, json -> {
      r.tabRow.clear();
      try {
        JSONArray a = new JSONArray(json);
        for (int i = 0; i < a.length(); i++) r.tabRow.add(a.optString(i));
      } catch (JSONException ignored) {
        // no tab row read: every tab is looked for the long way
      }
      next.run();
    });
  }

  /** How long to look for a tab: long if it is in the tab row, a moment if it is not. */
  private static int findTries(Rec r, String tab) {
    return (r.tabRow.isEmpty() || r.tabRow.contains(tab)) ? 4 : 2;
  }

  // ---- reading a tab after it is clicked

  /**
   * Clicks a tab and waits until its data is really on screen: the page has changed since the
   * click (or the tab shows as selected) and the text has stopped changing for 1.5 s. A tab
   * that shows "no data" is accepted after 4 s. The click is repeated if the tab did not open.
   * Calls done(false, "") when the tab is not on the page.
   */
  private void readTab(final String txt, final int findTries, final int maxTries,
             final boolean requireChange, final TabCb done) {
    jsWalk(SNAP_SEL_JS, json -> {
      final TabPoll p = new TabPoll();
      p.txt = txt;
      p.maxTries = maxTries;
      p.requireChange = requireChange;
      p.before = cleanPage(parseOrEmpty(json).optString("t"), false).hashCode();
      waitClick(txt, findTries, res -> {
        if (!"ok".equals(res)) {
          done.run(false, "");
          return;
        }
        pollTab(p, done);
      });
    });
  }

  private void pollTab(final TabPoll p, final TabCb done) {
    ui.postDelayed(() -> jsWalk(SNAP_SEL_JS, json -> {
      JSONObject o = parseOrEmpty(json);
      String raw = o.optString("t");
      JSONArray sel = o.optJSONArray("s");
      p.tries++;
      String body = cleanPage(raw, false);
      int h = body.hashCode();
      if (h != p.before) p.changed = true;
      boolean selected = false;
      if (sel != null) {
        for (int i = 0; i < sel.length(); i++) {
          if (p.txt.equals(sel.optString(i))) selected = true;
        }
      }
      boolean open = !p.requireChange || p.changed || (selected && p.tries >= 8);
      if (open) {
        if (h == p.lastHash) p.stable++;
        else p.stable = 0;
        p.lastHash = h;
        boolean empty = isEmptyBody(body);
        if (!empty && p.stable >= 3) {
          done.run(true, raw);
          return;
        }
        if (empty && hasNoDataLine(body) && p.stable >= 8) {
          done.run(true, raw);
          return;
        }
      } else if (p.tries % 8 == 0 && p.clicks < 3) {
        p.clicks++;
        log("   the tab did not open, clicking it again");
        jsWalk(clickTab(p.txt), res -> { });
      }
      if (p.tries >= p.maxTries) {
        done.run(true, open ? raw : "");
        return;
      }
      pollTab(p, done);
    }), 500);
  }

  /** Same for a tab whose data is a table (player stats). txt null: just wait for the table. */
  private void readTable(final String txt, final int findTries, final boolean requireChange,
              final TabCb done) {
    jsWalk(TABLE_JS, before -> {
      final int h0 = before.hashCode();
      if (txt == null) {
        pollTable(h0, requireChange, 0, -1, 0, done);
        return;
      }
      waitClick(txt, findTries, res -> {
        if (!"ok".equals(res)) {
          done.run(false, "");
          return;
        }
        pollTable(h0, requireChange, 0, -1, 0, done);
      });
    });
  }

  private void pollTable(final int before, final boolean requireChange, final int tries,
              final int lastHash, final int stable, final TabCb done) {
    ui.postDelayed(() -> jsWalk(TABLE_JS, table -> {
      int h = table.hashCode();
      boolean changed = !requireChange || h != before;
      int st = (h == lastHash) ? stable + 1 : 0;
      String t = table.trim();
      int rows = t.isEmpty() ? 0 : t.split("\n").length;
      if (changed && rows >= 2 && st >= 3) {
        done.run(true, table);
        return;
      }
      if (tries + 1 >= 30) {
        done.run(true, changed ? table : "");
        return;
      }
      pollTable(before, requireChange, tries + 1, h, st, done);
    }), 500);
  }

  // ---- step 1 and 3: capture one match page

  /**
   * Captures SUMMARY, STATS, LINEUPS, PLAYER STATS, COMMENTARY and REPORT on the page that is
   * loading. Waits for the page before it starts; a page that never shows up is loaded again.
   * On a second pass (r.retry) only what is not captured yet is read again.
   */
  private void captureRecord(final Rec r, final String label, final Runnable done) {
    showPage("Waiting for the page", 0, 1);
    waitPageReady(label, ok -> {
      if (!ok) {
        log("   the page is not ready, loading it again");
        loadThen(r.url, () -> captureRecord(r, label, done));
        return;
      }
      readTabRow(r, () -> readSummary(r, 0, () -> stepStats(r, () -> stepLineups(r,
          () -> stepPlayerStats(r, () -> stepCommentary(r, () -> stepReport(r, done)))))));
    });
  }

  /** SUMMARY: the page as it opens. If it comes back empty the page is still filling: look again. */
  private void readSummary(final Rec r, final int tries, final Runnable next) {
    if (r.retry && "captured".equals(r.state.get("Summary"))) {
      next.run();
      return;
    }
    showPage("Summary", 0, 1);
    jsWalk(SNAP_JS, raw -> {
      boolean empty = isEmptyBody(cleanPage(raw, false));
      if (empty && tries < 20) {
        ui.postDelayed(() -> readSummary(r, tries + 1, next), 500);
        return;
      }
      String header = cleanPage(raw, true);
      r.played = r != mainRec || looksPlayed(header);
      putLeaf(r, r.tabs, "summary", "Summary", header, empty);
      jsWalk(INFO_JS, ij -> {
        JSONObject inf = parseOrEmpty(ij);
        if (inf.length() > 0) r.info = inf;
        showPage("Summary", 1, 1);
        next.run();
      });
    });
  }

  /** STATS: the MATCH view, then 1ST HALF and 2ND HALF. */
  private void stepStats(final Rec r, final Runnable next) {
    if (stepDone(r, "Stats")) {
      next.run();
      return;
    }
    showPage("Stats", 0, 3);
    readTab("stats", findTries(r, "stats"), 40, true, (found, raw) -> {
      if (!found) {
        putMissing(r, r.tabs, "stats", "Stats");
        next.run();
        return;
      }
      final JSONObject stats = objectFor(r, r.tabs, "stats", "Stats");
      putBody(r, stats, "match", "Stats / match", raw);
      showPage("Stats", 1, 3);
      // no data on the MATCH view: the half tabs are not there either, so do not wait for them
      final boolean quick = !"captured".equals(r.state.get("Stats / match"));
      statSub(r, stats, "1st half", "first_half", "Stats / 1st half", 2, quick,
          () -> statSub(r, stats, "2nd half", "second_half", "Stats / 2nd half", 3, quick, next));
    });
  }

  private void statSub(final Rec r, final JSONObject parent, final String clickLabel,
             final String key, final String name, final int step,
             final boolean quick, final Runnable next) {
    readTab(clickLabel, quick ? 1 : 4, 40, true, (found, raw) -> {
      if (!found) {
        putMissing(r, parent, key, name);
      } else {
        putBody(r, parent, key, name, raw);
      }
      showPage("Stats", step, 3);
      next.run();
    });
  }

  /**
   * LINEUPS: on a finished match the ratings are already in the page. AVG. RATING is clicked
   * once if it is there.
   */
  private void stepLineups(final Rec r, final Runnable next) {
    if (stepDone(r, "Lineups")) {
      next.run();
      return;
    }
    showPage("Lineups", 0, 1);
    readTab("lineups", findTries(r, "lineups"), 40, true, (found, raw) -> {
      if (!found) {
        putMissing(r, r.tabs, "lineups", "Lineups");
        next.run();
        return;
      }
      if (isEmptyBody(cleanPage(raw, false))) {
        putBody(r, r.tabs, "lineups", "Lineups", raw);
        showPage("Lineups", 1, 1);
        next.run();
        return;
      }
      readTab("avg. rating", 1, 10, true, (ok, raw2) -> {
        String use = (ok && !isEmptyBody(cleanPage(raw2, false))) ? raw2 : raw;
        putBody(r, r.tabs, "lineups", "Lineups", use);
        showPage("Lineups", 1, 1);
        next.run();
      });
    });
  }

  /** PLAYER STATS: every sub-tab the page shows, one line per player. */
  private void stepPlayerStats(final Rec r, final Runnable next) {
    if (stepDone(r, "Player stats")) {
      next.run();
      return;
    }
    showPage("Player stats", 0, 1);
    waitClick("player stats", findTries(r, "player stats"), res -> {
      if (!"ok".equals(res)) {
        putMissing(r, r.tabs, "player_stats", "Player stats");
        next.run();
        return;
      }
      final JSONObject ps = objectFor(r, r.tabs, "player_stats", "Player stats");
      waitSubTabs(0, (labels, noData) -> {
        if (labels.isEmpty()) {
          if (noData) {
            putNoData(r, ps, "default", "Player stats");
            showPage("Player stats", 1, 1);
            next.run();
            return;
          }
          readTable(null, 0, false, (f, table) -> {
            putTable(r, ps, "default", "Player stats", table);
            showPage("Player stats", 1, 1);
            next.run();
          });
          return;
        }
        log("   player stats sub-tabs: " + labels);
        // an earlier pass that found no sub-tabs kept one plain table: the sub-tabs replace it
        ps.remove("default");
        r.state.remove("Player stats");
        subTabs(r, ps, labels, 0, next);
      });
    });
  }

  /** Waits for the sub-tab row of PLAYER STATS to appear (up to 14 s, or until the page says no data). */
  private void waitSubTabs(final int tries, final Labels done) {
    jsWalk(PLAYER_SUBTABS_JS, json -> {
      final List<String> labels = subTabLabels(json);
      if (!labels.isEmpty() || tries >= 20) {
        done.run(labels, false);
        return;
      }
      if (tries == 10) jsWalk(clickTab("player stats"), res -> { });
      if (tries >= 6) {
        jsWalk(SNAP_JS, raw -> {
          if (hasNoDataLine(cleanPage(raw, false))) {
            done.run(labels, true);
          } else {
            ui.postDelayed(() -> waitSubTabs(tries + 1, done), 700);
          }
        });
        return;
      }
      ui.postDelayed(() -> waitSubTabs(tries + 1, done), 700);
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
    final String shown = "Player stats · " + titleCase(label);
    showPage(shown, i, labels.size());
    // the first sub-tab (TOP STATS) is already open, so its table does not have to change
    final boolean first = i == 0 || label.equalsIgnoreCase("TOP STATS");
    readTable(label.toLowerCase(Locale.ROOT), 4, !first, (found, table) -> {
      if (!found) {
        putMissing(r, ps, key, "Player stats / " + titleCase(label));
      } else {
        putTable(r, ps, key, "Player stats / " + titleCase(label), table);
      }
      showPage(shown, i + 1, labels.size());
      subTabs(r, ps, labels, i + 1, next);
    });
  }

  /** COMMENTARY: the text of the tab as shown. */
  private void stepCommentary(final Rec r, final Runnable next) {
    stepText(r, "commentary", "commentary", "Commentary", next);
  }

  /** REPORT: the article page. */
  private void stepReport(final Rec r, final Runnable next) {
    stepText(r, "report", "report", "Report", next);
  }

  private void stepText(final Rec r, final String tab, final String key, final String name,
             final Runnable next) {
    if (stepDone(r, name)) {
      next.run();
      return;
    }
    showPage(name, 0, 1);
    readTab(tab, findTries(r, tab), 40, true, (found, raw) -> {
      if (!found) {
        putMissing(r, r.tabs, key, name);
      } else {
        putBody(r, r.tabs, key, name, raw);
      }
      showPage(name, 1, 1);
      next.run();
    });
  }

  /** On a second pass: true when this tab (and its sub-tabs) was captured on an earlier pass. */
  private static boolean stepDone(Rec r, String prefix) {
    if (!r.retry) return false;
    boolean any = false;
    for (Map.Entry<String, String> e : r.state.entrySet()) {
      String k = e.getKey();
      if (k.equals(prefix) || k.startsWith(prefix + " / ")) {
        any = true;
        if (!"captured".equals(e.getValue()) && !"nodata".equals(e.getValue())) return false;
      }
    }
    return any;
  }

  /** The object a tab with sub-tabs is kept in. An object from an earlier pass is reused. */
  private JSONObject objectFor(Rec r, JSONObject parent, String key, String name) {
    JSONObject cur = parent.optJSONObject(key);
    if (cur != null && !cur.has("status")) return cur;
    JSONObject fresh = new JSONObject();
    jput(parent, key, fresh);
    r.state.remove(name);
    return fresh;
  }

  private void finishRecord(Rec r) {
    r.done = true;
    rebuildLists(r);
    // A match that has not been played has no stats, so empty tabs are expected there.
    r.complete = r.summaryOk && (!r.emptyFound || !r.played);
    log((r == mainRec ? "Main match" : "Match") + ": " + (r.complete ? "complete" : "incomplete")
        + (r.played ? "" : "  (not played yet, so empty tabs are normal)")
        + (r.missing.isEmpty() ? "" : "  (not on page: " + join(r.missing) + ")")
        + (r.emptyTabs.isEmpty() ? "" : "  (empty: " + join(r.emptyTabs) + ")"));
    if (r != mainRec && !r.counted) {
      r.counted = true;
      doneSlots += r.groups.size();
      updateCounter();
    }
  }

  /** Rebuilds the missing / empty lists from what each tab ended up as. */
  private static void rebuildLists(Rec r) {
    r.missing.clear();
    r.emptyTabs.clear();
    boolean failed = false;
    for (Map.Entry<String, String> e : r.state.entrySet()) {
      String v = e.getValue();
      if ("missing".equals(v)) {
        r.missing.add(e.getKey());
      } else if ("empty".equals(v)) {
        r.emptyTabs.add(e.getKey());
        failed = true;
      } else if ("nodata".equals(v)) {
        r.emptyTabs.add(e.getKey());   // the page itself says there is no data: not a failure
      }
    }
    r.emptyFound = failed;
    r.summaryOk = "captured".equals(r.state.get("Summary"));
  }

  private static boolean looksPlayed(String header) {
    String u = header.toUpperCase(Locale.ROOT);
    return u.contains("FINISHED") || u.contains("AFTER EXTRA TIME") || u.contains("AFTER PENALTIES")
        || u.contains("AWARDED") || u.contains("WALKOVER");
  }

  // ---- step 1: the main match, then the H2H tab

  private void walkMain() {
    setLabel("Main match");
    captureRecord(mainRec, "main match", () -> {
      finishRecord(mainRec);
      stepH2H(0);
    });
  }

  private void stepH2H(final int attempt) {
    status("Opening the H2H tab...");
    setLabel("Main match");
    showPage("H2H", 0, 1);
    h2hDirectTried = false;
    waitClick("h2h", 20, res -> {
      if (!"ok".equals(res)) {
        if (attempt < 2) {
          status("H2H tab not found. Loading the main page again...");
          loadThen(matchUrl, () -> waitPageReady("main match", ok -> stepH2H(attempt + 1)));
          return;
        }
        status("H2H tab not found. Only the main match was saved.");
        pageNotes.add("H2H tab not found on the main match page.");
        finishWalk(true);
        return;
      }
      waitPageReady(null, ok -> waitClick("overall", 3, o ->
          waitPageReady(null, ok2 -> pollH2H(0, attempt))));
    });
  }

  /**
   * Waits for the H2H sections to render (up to 35 s). If they are not there after 12 s the tab
   * is clicked again; after 20 s the H2H page is opened by its address instead of the tab; and if
   * they still do not show, the main page is loaded again (twice at most).
   */
  private void pollH2H(final int tries, final int attempt) {
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
      if (h2h && lastCount >= 2) {
        showMore(o, 0);
        return;
      }
      if (tries == 12) {
        log("   the H2H lists are not there yet, opening the tab again");
        jsWalk(clickTab("h2h"), res -> { });
      }
      if (tries == 20 && !h2hDirectTried) {
        h2hDirectTried = true;
        log("   the H2H lists are still not there, opening the H2H page by its address");
        status("Opening the H2H page directly...");
        jsWalk(H2H_HREF_JS, href -> {
          String url = href.startsWith("http") ? href : h2hUrlFor(matchUrl);
          loadThen(url, () -> waitPageReady(null, ok -> pollH2H(0, attempt)));
        });
        return;
      }
      if (tries < 35) {
        ui.postDelayed(() -> pollH2H(tries + 1, attempt), 1000);
        return;
      }
      if (attempt < 2) {
        status("H2H did not load. Loading the main page again...");
        loadThen(matchUrl, () -> waitPageReady("main match", ok -> stepH2H(attempt + 1)));
        return;
      }
      pageNotes.add("The H2H lists did not load.");
      showMore(o, 0);
    });
  }

  private static final Pattern MATCH_BASE =
      Pattern.compile("^(https?://[^/?#]+/match/[^/?#]+/[^/?#]+/[^/?#]+)[^?#]*(\\?[^#]*)?");

  /**
   * A pasted link can be a tab of the match (H2H, odds, standings). The walk starts from the
   * match page itself: .../team-a/team-b/h2h/overall/?mid=X becomes .../team-a/team-b/?mid=X.
   * Any other kind of link is left as it is.
   */
  private static String baseMatchUrl(String url) {
    Matcher m = MATCH_BASE.matcher(url);
    if (!m.find()) return url;
    return m.group(1) + "/" + (m.group(2) == null ? "" : m.group(2));
  }

  /** The H2H page of a match: .../match/.../?mid=X becomes .../match/.../h2h/overall/?mid=X. */
  private static String h2hUrlFor(String url) {
    int q = url.indexOf('?');
    String path = q < 0 ? url : url.substring(0, q);
    String query = q < 0 ? "" : url.substring(q);
    if (path.contains("/h2h/")) return url;
    if (!path.endsWith("/")) path += "/";
    return path + "h2h/overall/" + query;
  }

  /**
   * Clicks "Show more matches" every time, at least once, and again while a team still has
   * fewer than LAST_COUNT league matches (or H2H fewer than H2H_COUNT) to take.
   */
  private void showMore(final JSONObject o, final int round) {
    if (!walking) return;
    if ((round > 0 && !isShort(o)) || round >= MAX_MORE_ROUNDS) {
      processSections(o);
      return;
    }
    final int before = rowTotal(o);
    showPage("Show more matches", round, MAX_MORE_ROUNDS);
    jsWalk(SHOW_MORE_JS, n -> {
      int clicked = 0;
      try {
        clicked = Integer.parseInt(n.trim());
      } catch (NumberFormatException ignored) {
        // treated as no button
      }
      if (clicked == 0) {
        log(round == 0 ? "No 'Show more matches' button on the page."
            : "No more 'Show more matches' buttons.");
        processSections(o);
        return;
      }
      log("Show more matches: clicked " + clicked + " button(s) (round " + (round + 1) + ").");
      waitRowsGrow(before, 0, -1, 0, (grown, grew) -> {
        if (grew) {
          showMore(grown, round + 1);
        } else {
          processSections(grown);
        }
      });
    });
  }

  /** After a click: waits until the lists have more rows and have stopped growing. */
  private void waitRowsGrow(final int before, final int tries, final int lastTotal,
               final int stable, final Grown done) {
    ui.postDelayed(() -> jsWalk(H2H_LIST_JS, json -> {
      JSONObject o = parseOrEmpty(json);
      int total = rowTotal(o);
      int st = (total == lastTotal) ? stable + 1 : 0;
      if (total > before && st >= 2) {
        done.run(o, true);
        return;
      }
      if (tries >= 14) {
        done.run(o, total > before);
        return;
      }
      waitRowsGrow(before, tries + 1, total, st, done);
    }), 700);
  }

  private static int rowTotal(JSONObject o) {
    int n = 0;
    JSONArray secs = o.optJSONArray("sections");
    if (secs == null) return 0;
    for (int i = 0; i < secs.length(); i++) {
      JSONObject s = secs.optJSONObject(i);
      JSONArray rows = s == null ? null : s.optJSONArray("rows");
      if (rows != null) n += rows.length();
    }
    return n;
  }

  /** What would be taken from the lists as they are now. */
  private static class Plan {
    JSONObject h2hSec;
    JSONObject homeSec;
    JSONObject awaySec;
    List<JSONObject> h2h = new ArrayList<>();
    List<JSONObject> home = new ArrayList<>();
    List<JSONObject> away = new ArrayList<>();
    String homeLeague = "";
    String awayLeague = "";
    int homeSkipped;
    int awaySkipped;
  }

  private Plan plan(JSONObject o) {
    Plan p = new Plan();
    JSONArray secs = o.optJSONArray("sections");
    List<JSONObject> lastSecs = new ArrayList<>();
    if (secs != null) {
      for (int i = 0; i < secs.length(); i++) {
        JSONObject s = secs.optJSONObject(i);
        if (s == null) continue;
        String type = s.optString("type");
        if ("h2h".equals(type) && p.h2hSec == null) {
          p.h2hSec = s;
        } else if ("last".equals(type)) {
          lastSecs.add(s);
        }
      }
    }
    p.homeSec = lastSecs.size() >= 1 ? lastSecs.get(0) : null;
    p.awaySec = lastSecs.size() >= 2 ? lastSecs.get(1) : null;
    int[] skipped = new int[1];
    p.h2h = pick(p.h2hSec, H2H_COUNT, "", skipped);
    p.homeLeague = leagueCode(p.homeSec);
    p.home = pick(p.homeSec, LAST_COUNT, p.homeLeague, skipped);
    p.homeSkipped = skipped[0];
    p.awayLeague = leagueCode(p.awaySec);
    p.away = pick(p.awaySec, LAST_COUNT, p.awayLeague, skipped);
    p.awaySkipped = skipped[0];
    return p;
  }

  private boolean isShort(JSONObject o) {
    Plan p = plan(o);
    return (p.h2hSec != null && p.h2h.size() < H2H_COUNT)
        || (p.homeSec != null && p.home.size() < LAST_COUNT)
        || (p.awaySec != null && p.away.size() < LAST_COUNT);
  }

  /**
   * The competition code of a row: "27.09.26 L2 Alianza Huanuco Tacna Heroica 2 2" gives "L2".
   * Empty when the row does not look like that.
   */
  private static String codeOf(String rowText) {
    Matcher m = ROW_CODE.matcher(rowText == null ? "" : rowText.trim());
    return m.find() ? m.group(1) : "";
  }

  /** True for a row that can be taken: it has a match link and is not the main match. */
  private boolean usable(JSONObject row) {
    if (row == null) return false;
    String id = midOf(row.optString("h"));
    return !row.optString("h").isEmpty() && !id.isEmpty() && !id.equals(mid);
  }

  /**
   * The league of a team's list: the competition code that most of its latest matches carry.
   * Friendlies, cups and European games are a few rows each, so the league wins. On a tie a
   * code that is not a known friendly/cup/European code wins, then the more recent match. Empty
   * when no code can be read (then nothing is filtered).
   */
  private String leagueCode(JSONObject sec) {
    if (sec == null) return "";
    JSONArray rows = sec.optJSONArray("rows");
    if (rows == null) return "";
    Map<String, Integer> count = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    int looked = 0;
    for (int i = 0; i < rows.length() && looked < LEAGUE_WINDOW; i++) {
      JSONObject row = rows.optJSONObject(i);
      if (!usable(row)) continue;
      if (!seen.add(midOf(row.optString("h")))) continue;
      looked++;
      String code = codeOf(row.optString("t"));
      if (code.isEmpty()) continue;
      Integer c = count.get(code);
      count.put(code, c == null ? 1 : c + 1);
    }
    String best = "";
    int bestN = 0;
    for (Map.Entry<String, Integer> e : count.entrySet()) {
      boolean better = e.getValue() > bestN
          || (e.getValue() == bestN && NOT_LEAGUE.contains(best) && !NOT_LEAGUE.contains(e.getKey()));
      if (better) {
        best = e.getKey();
        bestN = e.getValue();
      }
    }
    return best;
  }

  /**
   * The first max matches of a section, newest first, without the main match, without repeats
   * and, when a league code is given, only matches of that competition. skipped[0] is the
   * number of matches left out because they were another competition.
   */
  private List<JSONObject> pick(JSONObject sec, int max, String league, int[] skipped) {
    List<JSONObject> out = new ArrayList<>();
    skipped[0] = 0;
    if (sec == null) return out;
    JSONArray rows = sec.optJSONArray("rows");
    if (rows == null) return out;
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < rows.length() && out.size() < max; i++) {
      JSONObject row = rows.optJSONObject(i);
      if (!usable(row)) continue;
      if (!seen.add(midOf(row.optString("h")))) continue;
      if (!league.isEmpty() && !league.equals(codeOf(row.optString("t")))) {
        skipped[0]++;
        continue;
      }
      out.add(row);
    }
    return out;
  }

  private static final Pattern MATCH_PATH = Pattern.compile("/match/[^/?#]+/[^/?#]+/[^/?#]+");

  /** ".../match/football/team-a-ID/team-b-ID" of a match address, without host, tab or query. */
  private static String matchPathOf(String url) {
    Matcher m = MATCH_PATH.matcher(url == null ? "" : url);
    return m.find() ? m.group() : "";
  }

  /**
   * The pasted link had no ?mid=. A match that has been played is in the lists with its mid:
   * the row whose match address is the main match's and whose date is the main match's date
   * ("23.10.26"). Sets mid (and the main record) when it finds it. If it does not, the mid stays
   * empty: a match not played yet is not in the lists, so there is nothing to leave out.
   */
  private void takeMidFrom(JSONObject o, String date) {
    String path = matchPathOf(matchUrl);
    JSONArray secs = o.optJSONArray("sections");
    String found = "";
    if (!path.isEmpty() && !date.isEmpty() && secs != null) {
      for (int i = 0; i < secs.length() && found.isEmpty(); i++) {
        JSONObject s = secs.optJSONObject(i);
        JSONArray rows = s == null ? null : s.optJSONArray("rows");
        if (rows == null) continue;
        for (int j = 0; j < rows.length(); j++) {
          JSONObject row = rows.optJSONObject(j);
          if (row == null) continue;
          String href = row.optString("h");
          String id = midOf(href);
          if (!id.isEmpty() && path.equalsIgnoreCase(matchPathOf(href))
              && row.optString("t").contains(date)) {
            found = id;
            break;
          }
        }
      }
    }
    if (found.isEmpty()) {
      log("The link had no ?mid= and the main match is not in the lists. Its mid stays empty.");
      pageNotes.add("The link had no ?mid= and the main match was not found in the lists, "
          + "so its mid is empty.");
      return;
    }
    mid = found;
    matchUrl = matchUrl + (matchUrl.contains("?") ? "&" : "?") + "mid=" + mid;
    if (mainRec != null) {
      mainRec.mid = mid;
      mainRec.url = matchUrl;
    }
    log("The link had no ?mid=. The main match is in the lists: " + mid);
  }

  /** Builds the list of matches to open from what is on the page now. */
  private void processSections(JSONObject o) {
    if (!walking) return;
    if (!midChecked) {
      midChecked = true;
      jsWalk(PAGE_DATE_JS, date -> {
        takeMidFrom(o, date.trim());
        processSections(o);
      });
      return;
    }
    Plan p = plan(o);
    Map<String, Rec> byMid = new LinkedHashMap<>();
    List<Rec> h2hSel = select(p.h2h, "h2h", byMid);
    List<Rec> homeSel = select(p.home, "home", byMid);
    List<Rec> awaySel = select(p.away, "away", byMid);

    if (p.homeSec != null) {
      String n = p.homeSec.optString("name").trim();
      homeName = n.isEmpty() ? "Home" : n;
    }
    if (p.awaySec != null) {
      String n = p.awaySec.optString("name").trim();
      awayName = n.isEmpty() ? "Away" : n;
    }
    homeLeague = p.homeLeague;
    awayLeague = p.awayLeague;
    if (p.homeSec != null) {
      log(homeName + ": league " + (homeLeague.isEmpty() ? "not known (no filter)" : homeLeague)
          + ", " + p.homeSkipped + " other-competition match(es) left out.");
    }
    if (p.awaySec != null) {
      log(awayName + ": league " + (awayLeague.isEmpty() ? "not known (no filter)" : awayLeague)
          + ", " + p.awaySkipped + " other-competition match(es) left out.");
    }
    if (p.h2hSec == null) pageNotes.add("HEAD-TO-HEAD MATCHES section not found.");
    if (p.homeSec == null) pageNotes.add("LAST MATCHES section for the home team not found.");
    if (p.awaySec == null) pageNotes.add("LAST MATCHES section for the away team not found.");
    if (p.h2hSec != null && h2hSel.size() < H2H_COUNT) {
      pageNotes.add("Only " + h2hSel.size() + " of " + H2H_COUNT + " head-to-head matches on the page.");
    }
    if (p.homeSec != null && homeSel.size() < LAST_COUNT) {
      pageNotes.add("Only " + homeSel.size() + " of " + LAST_COUNT + " league matches for " + homeName + ".");
    }
    if (p.awaySec != null && awaySel.size() < LAST_COUNT) {
      pageNotes.add("Only " + awaySel.size() + " of " + LAST_COUNT + " league matches for " + awayName + ".");
    }

    recs.clear();
    recs.addAll(byMid.values());
    h2hSlots.clear();
    h2hSlots.addAll(h2hSel);
    homeSlots.clear();
    homeSlots.addAll(homeSel);
    awaySlots.clear();
    awaySlots.addAll(awaySel);
    totalSlots = h2hSel.size() + homeSel.size() + awaySel.size();
    doneSlots = 0;
    updateCounter();
    status("Found " + totalSlots + " matches to scrap (" + recs.size() + " pages to open): "
        + homeSel.size() + " " + homeName + ", " + awaySel.size() + " " + awayName
        + ", " + h2hSel.size() + " H2H.");
    if (recs.isEmpty()) {
      finishWalk(true);
      return;
    }
    nextRec(0);
  }

  /** One Rec per distinct match; a match in more than one list is the same Rec in each. */
  private List<Rec> select(List<JSONObject> rows, String group, Map<String, Rec> byMid) {
    List<Rec> out = new ArrayList<>();
    for (JSONObject row : rows) {
      String href = row.optString("h");
      String id = midOf(href);
      Rec r = byMid.get(id);
      if (r == null) {
        r = new Rec();
        r.mid = id;
        r.url = href;
        r.rowText = row.optString("t");
        byMid.put(id, r);
      }
      r.groups.add(group);
      out.add(r);
    }
    return out;
  }

  /** Opens the next match from the H2H tab and captures it. */
  private void nextRec(final int i) {
    if (!walking) return;
    if (i >= recs.size()) {
      startRetry();
      return;
    }
    final Rec r = recs.get(i);
    setLabel(groupLabel(r) + "  ·  " + shorten(r.rowText));
    showPage("Opening the page", 0, 1);
    status("Opening match " + (i + 1) + " of " + recs.size() + ": " + shorten(r.rowText));
    loadThen(r.url, () -> captureRecord(r, shorten(r.rowText), () -> {
      finishRecord(r);
      nextRec(i + 1);
    }));
  }

  // ---- going back for what is missing

  /** True for a played match whose summary is not captured or that has a tab that came back empty. */
  private static boolean needsRetry(Rec r) {
    return r != null && r.played && (!r.done || !r.summaryOk || r.emptyFound);
  }

  private static int capturedIn(Rec r) {
    int n = 0;
    if (r == null) return 0;
    for (String v : r.state.values()) {
      if ("captured".equals(v)) n++;
    }
    return n;
  }

  private int capturedCount() {
    int n = capturedIn(mainRec);
    for (Rec r : recs) n += capturedIn(r);
    return n;
  }

  /**
   * After every match has been opened once: goes back to the ones with a tab that came back
   * empty (or a summary that did not load) and reads only what is still missing. Stops when
   * nothing is left, when a pass brought nothing new, or after MAX_RETRY_PASSES passes.
   */
  private void startRetry() {
    if (!walking) return;
    final List<Rec> todo = new ArrayList<>();
    if (needsRetry(mainRec)) todo.add(mainRec);
    for (Rec r : recs) {
      if (needsRetry(r)) todo.add(r);
    }
    int now = capturedCount();
    if (todo.isEmpty()) {
      finishWalk(true);
      return;
    }
    if (retryPass >= MAX_RETRY_PASSES || (capturedBefore >= 0 && now <= capturedBefore)) {
      log(todo.size() + " page(s) still have empty tabs after " + retryPass
          + " extra pass(es). Saving what was collected.");
      finishWalk(true);
      return;
    }
    capturedBefore = now;
    retryPass++;
    status("Going back to " + todo.size() + " page(s) with empty tabs (extra pass " + retryPass + ").");
    retryRec(todo, 0);
  }

  private void retryRec(final List<Rec> todo, final int i) {
    if (!walking) return;
    if (i >= todo.size()) {
      startRetry();
      return;
    }
    final Rec r = todo.get(i);
    r.retry = true;
    r.passes++;
    final String name = r == mainRec ? "main match" : shorten(r.rowText);
    setLabel("Going back  ·  " + name);
    showPage("Opening the page", 0, 1);
    status("Going back to " + name + " (" + (i + 1) + " of " + todo.size() + ", extra pass " + retryPass + ")");
    loadThen(r.url, () -> captureRecord(r, name, () -> {
      finishRecord(r);
      retryRec(todo, i + 1);
    }));
  }


  private String groupLabel(Rec r) {
    List<String> names = new ArrayList<>();
    for (String g : r.groups) {
      if ("home".equals(g)) names.add(homeName);
      else if ("away".equals(g)) names.add(awayName);
      else names.add("H2H");
    }
    return join(names, " + ");
  }

  // ---- finishing

  /** The result of the match that was just walked, as one JSON object (same layout as always). */
  private JSONObject buildResultJson() {
    int home = countComplete(homeSlots);
    int away = countComplete(awaySlots);
    int h2h = countComplete(h2hSlots);
    int overall = home + away + h2h;

    JSONObject out = new JSONObject();
    jput(out, "mid", mid);
    jput(out, "match_url", matchUrl);

    JSONObject mainObj = new JSONObject();
    jput(mainObj, "complete", mainRec.complete);
    jput(mainObj, "played", mainRec.played);
    jput(mainObj, "tabs", mainRec.tabs);
    jput(mainObj, "not_on_page", toArray(mainRec.missing));
    jput(mainObj, "empty", toArray(mainRec.emptyTabs));
    jput(out, "main_match", mainObj);

    JSONArray h2hArr = new JSONArray();
    for (Rec r : h2hSlots) h2hArr.put(recJson(r));
    JSONArray homeArr = new JSONArray();
    for (Rec r : homeSlots) homeArr.put(recJson(r));
    JSONArray awayArr = new JSONArray();
    for (Rec r : awaySlots) awayArr.put(recJson(r));
    jput(out, "h2h", h2hArr);

    JSONObject homeObj = new JSONObject();
    jput(homeObj, "name", homeName);
    jput(homeObj, "league_code", homeLeague);
    jput(homeObj, "matches", homeArr);
    JSONObject awayObj = new JSONObject();
    jput(awayObj, "name", awayName);
    jput(awayObj, "league_code", awayLeague);
    jput(awayObj, "matches", awayArr);
    JSONObject last = new JSONObject();
    jput(last, "home_team", homeObj);
    jput(last, "away_team", awayObj);
    jput(out, "last_matches", last);

    JSONObject marks = new JSONObject();
    jput(marks, "overall", overall);
    jput(marks, "overall_of", TARGET_TOTAL);
    jput(marks, "home", home);
    jput(marks, "home_of", LAST_COUNT);
    jput(marks, "away", away);
    jput(marks, "away_of", LAST_COUNT);
    jput(marks, "h2h", h2h);
    jput(marks, "h2h_of", H2H_COUNT);
    jput(marks, "main_match_complete", mainRec.complete);
    jput(out, "marks", marks);

    jput(out, "unavailable", unavailableJson());
    jput(out, "notes", toArray(pageNotes));
    jput(out, "complete_count", overall);
    jput(out, "total_count", totalSlots);
    jput(out, "extra_passes", retryPass);
    jput(out, "collected_at", System.currentTimeMillis());
    return out;
  }

  /** Stops the walk, saves whatever was collected, shows the marks and, if asked, notifies. */
  private void finishWalk(boolean notify) {
    if (!walking) return;
    if (favMode) {
      favMatchDone(notify);
      return;
    }
    walking = false;
    onLoaded = null;
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    scrapBtn.setText("Scrap");
    progressPanel.setVisibility(View.GONE);
    if (mainRec == null) return;

    int home = countComplete(homeSlots);
    int away = countComplete(awaySlots);
    int h2h = countComplete(h2hSlots);
    int overall = home + away + h2h;

    JSONObject out = buildResultJson();
    String unavailable = unavailableText();

    String json;
    try {
      json = out.toString(1);
    } catch (JSONException e) {
      json = out.toString();
    }
    lastJson = json;
    lastName = "flashscore_" + mid + "_" + (System.currentTimeMillis() / 1000) + ".json";
    lastSavedUri = null;
    saveToDownloads(lastName, json);
    badge.setVisibility(View.VISIBLE);
    flash.clear();
    autoHandOver(keepFlash(out, 1));
    autoEnd(notify);
    updateP2Next();

    StringBuilder m = new StringBuilder();
    m.append(markLine("Overall", overall, TARGET_TOTAL)).append('\n');
    m.append(markLine(shorten(homeName, 9), home, LAST_COUNT)).append('\n');
    m.append(markLine(shorten(awayName, 9), away, LAST_COUNT)).append('\n');
    m.append(markLine("H2H", h2h, H2H_COUNT)).append('\n');
    m.append("Main match  ").append(mainRec.complete ? "complete ✓" : "incomplete !");
    marksView.setText(m.toString());
    unavailView.setText(unavailable.isEmpty()
        ? "Unavailable tabs: none"
        : "Unavailable tabs\n" + unavailable);
    resultPanel.setVisibility(View.VISIBLE);

    String summary = overall + " of " + TARGET_TOTAL + " matches complete";
    status("Done: " + summary + ". Main match "
        + (mainRec.complete ? "complete" : "incomplete") + "."
        + (lastSavedUri != null ? " Saved to Downloads: " + lastName : ""));
    if (notify) {
      notifyDone("Scrap finished", summary + ". Tap to open Downloads.");
    }
  }


  // ---------------------------------------------------------------- favorites mode

  private void resetFav() {
    favQueue.clear();
    favIndex = 0;
    favResults = new JSONArray();
    favLines.clear();
    favNotes.clear();
    favUnavail.setLength(0);
    favCompleteCount = 0;
  }

  /** Scrap with an empty link box: open the favorites page, read its matches, walk them one by one. */
  private void startFavorites() {
    openToken++;
    awaitingReady = false;
    openingFav = false;
    resetData();
    resetFav();
    favMode = true;
    favStop = false;
    favListLoad = true;
    resetLogin();
    flash.clear();
    simDone = false;
    fixtures = new ArrayList<>();
    autoStart();
    updateP2Next();
    badge.setVisibility(View.GONE);
    resetPanels();

    walking = true;
    scrapBtn.setText("Stop");
    showBrowser();
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    matchLabel.setText("Favorites");
    showPage("Opening your favorites", 0, 1);
    status("Scrap started on your Flashscore favorites. Keep the app open until it says done.");
    loadThen(FAV_URL, () -> favReady(0, -1, 0));
  }

  /** Waits for the favorites page to settle (answers the consent box and the age check). */
  private void favReady(final int tries, final int lastHash, final int stable) {
    if (!walking) return;
    jsWalk(READY_JS, json -> {
      final JSONObject o = parseOrEmpty(json);
      final int h = o.optInt("h");
      final int n = o.optInt("n");
      if (tries % 6 == 0) {
        jsWalk(CONSENT_JS, c -> {
          if ("rejected".equals(c) || "accepted".equals(c)) log("Consent box on screen: " + c);
        });
      }
      if (o.optBoolean("age")) {
        log("Age check on screen: answering YES");
        tryClick("yes", 0, 3, false, res ->
            ui.postDelayed(() -> favReady(tries + 1, -1, 0), 800));
        return;
      }
      final int nextStable = (n > 150 && h == lastHash) ? stable + 1 : 0;
      if (nextStable >= 4 || tries >= 60) {
        showPage("Reading the favorites list", 0, 1);
        favScroll(0, -1, 0);
        return;
      }
      ui.postDelayed(() -> favReady(tries + 1, h, nextStable), 500);
    });
  }

  /** Scrolls down and reads the rows until their number stops growing. */
  private void favScroll(final int tries, final int lastCount, final int stable) {
    if (!walking) return;
    jsWalk(FAV_SCROLL_JS, x -> jsWalk(FAV_LIST_JS, json -> {
      JSONObject o = parseOrEmpty(json);
      JSONArray rows = o.optJSONArray("rows");
      int count = rows == null ? 0 : rows.length();
      int st = (count == lastCount) ? stable + 1 : 0;
      if ((st >= 3 && count > 0) || tries >= 12) {
        favTake(o);
        return;
      }
      ui.postDelayed(() -> favScroll(tries + 1, count, st), 800);
    }));
  }

  /** Builds the queue from the rows and starts with the first match. */
  private void favTake(JSONObject o) {
    if (!walking) return;
    favListLoad = false;
    JSONArray rows = o.optJSONArray("rows");
    Set<String> seen = new HashSet<>();
    if (rows != null) {
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.optJSONObject(i);
        if (row == null) continue;
        String href = row.optString("h");
        String id = midOf(href);
        String key = id.isEmpty() ? matchPathOf(href) : id;
        if (href.isEmpty() || !seen.add(key)) continue;
        Fav f = new Fav();
        f.url = href;
        f.mid = id;
        f.row = row.optString("t");
        String hn = row.optString("hn");
        String an = row.optString("an");
        f.name = (!hn.isEmpty() && !an.isEmpty()) ? hn + " - " + an : row.optString("n");
        favQueue.add(f);
      }
    }
    int footer = o.optInt("footer");
    if (footer > 0) {
      log(footer + " link(s) in the page footer (popular matches, not your favorites) left out.");
    }
    int skipped = o.optInt("skipped");
    if (skipped > 0) {
      favNotes.add(skipped + " match(es) of other sports on the favorites page were left out.");
      log(skipped + " match(es) of other sports left out.");
    }
    if (favQueue.isEmpty()) {
      favNotes.add("No football match was found on the favorites page.");
      status(o.optBoolean("login")
          ? "No football match found. You may not be logged in to Flashscore: tap Open, log in, then Scrap."
          : "No football match found on the favorites page.");
      jsWalk(FAV_DIAG_JS, d -> log("Page text starts: " + d));
      favMatchDone(false);
      return;
    }
    status("Found " + favQueue.size() + " football match" + (favQueue.size() == 1 ? "" : "es")
        + " on your favorites. Walking them one by one.");
    for (int i = 0; i < favQueue.size(); i++) {
      log("  " + (i + 1) + ". " + shorten(favLabel(favQueue.get(i)), 70));
    }
    favIndex = 0;
    beginFavMatch();
  }

  /** Starts the walk of favorite number favIndex: the same walk as one pasted match link. */
  private void beginFavMatch() {
    if (!walking || !favMode || favIndex >= favQueue.size()) return;
    Fav f = favQueue.get(favIndex);
    resetData();
    matchUrl = baseMatchUrl(f.url);
    mid = f.mid;
    midChecked = !mid.isEmpty();
    h2hDirectTried = false;
    loadFailed = false;
    mainRec = new Rec();
    mainRec.mid = mid;
    mainRec.url = matchUrl;
    resetPanels();
    setLabel("Main match");
    showPage("Opening the page", 0, 1);
    status("Favorite " + (favIndex + 1) + " of " + favQueue.size() + ": " + shorten(favLabel(f)));
    loadThen(matchUrl, this::walkMain);
  }

  /**
   * One favorite is walked (or Stop was tapped, or the list was empty): keep its result, then go
   * on with the next favorite, or save the one file when there is none left.
   */
  private void favMatchDone(boolean notify) {
    if (mainRec != null && !favQueue.isEmpty() && favIndex < favQueue.size()) {
      Fav f = favQueue.get(favIndex);
      JSONObject one = buildResultJson();
      JSONObject favInfo = new JSONObject();
      jput(favInfo, "line", favIndex + 1);
      jput(favInfo, "name", f.name);
      jput(favInfo, "row", f.row);
      jput(one, "favorite", favInfo);
      favResults.put(one);
      int home = countComplete(homeSlots);
      int away = countComplete(awaySlots);
      int h2h = countComplete(h2hSlots);
      int overall = home + away + h2h;
      boolean ok = mainRec.complete && overall >= TARGET_TOTAL;
      if (favStop && !ok) {
        // Stop was tapped while this one was half walked: it stays in the saved file, but it is not
        // sent to the simulator (it has not got its past games yet)
        log("Favorite " + (favIndex + 1) + " was stopped half way: left out of the simulation.");
      } else {
        autoHandOver(keepFlash(one, favIndex + 1));
      }
      if (ok) favCompleteCount++;
      favLines.add(String.format(Locale.US, "%-24s %2d/%-2d %s", shorten(favLabel(f), 24), overall,
          TARGET_TOTAL, ok ? "✓" : "!"));
      String un = unavailableText();
      if (!un.isEmpty()) {
        favUnavail.append(shorten(favLabel(f), 50)).append('\n').append(un).append("\n\n");
      }
      log("Favorite " + (favIndex + 1) + " done: " + overall + " of " + TARGET_TOTAL
          + " matches complete, main match " + (mainRec.complete ? "complete" : "incomplete") + ".");
    }
    if (favStop || favIndex + 1 >= favQueue.size()) {
      endFav(notify);
      return;
    }
    favIndex++;
    ui.postDelayed(() -> beginFavMatch(), 1500);
  }

  /** Saves the one file with every walked favorite and shows the marks. */
  private void endFav(boolean notify) {
    walking = false;
    onLoaded = null;
    favListLoad = false;
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    scrapBtn.setText("Scrap");
    progressPanel.setVisibility(View.GONE);

    int total = favQueue.size();
    int got = favResults.length();
    StringBuilder m = new StringBuilder();
    autoEnd(notify && !favStop);
    updateP2Next();
    if (got == 0) {
      favMode = false;
      resultPanel.setVisibility(View.GONE);
      if (total > 0) {
        status(favStop ? "Stopped. Nothing was collected yet." : "Nothing was collected: no match finished.");
      }
      return;
    }
    JSONObject all = new JSONObject();
    java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
    iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    jput(all, "exportedAt", iso.format(new java.util.Date()));
    jput(all, "source", FAV_URL);
    jput(all, "totalMatches", got);
    jput(all, "matches_on_page", total);
    jput(all, "stopped_early", favStop);
    jput(all, "complete_matches", favCompleteCount);
    jput(all, "notes", toArray(favNotes));
    jput(all, "matches", favResults);
    String json;
    try {
      json = all.toString(1);
    } catch (JSONException e) {
      json = all.toString();
    }
    lastJson = json;
    lastName = "flashscore_favorites_" + (System.currentTimeMillis() / 1000) + ".json";
    lastSavedUri = null;
    saveToDownloads(lastName, json);
    badge.setVisibility(View.VISIBLE);

    m.append(String.format(Locale.US, "%-9s %2d / %-2d %s", "Favorites", favCompleteCount, total,
        favCompleteCount >= total ? "✓" : "!")).append('\n');
    for (String line : favLines) m.append(line).append('\n');
    marksView.setText(m.toString().trim());
    String un = favUnavail.toString().trim();
    unavailView.setText(un.isEmpty() ? "Unavailable tabs: none" : "Unavailable tabs\n" + un);
    resultPanel.setVisibility(View.VISIBLE);

    String summary = favCompleteCount + " of " + total + " favorites complete";
    status((favStop ? "Stopped: " : "Done: ") + summary + "."
        + (lastSavedUri != null ? " Saved to Downloads: " + lastName : ""));
    if (notify) notifyDone("Favorites scrap finished", summary + ". Tap to open Downloads.");
    favMode = false;
  }

  /** The name of a favorite: the page's own "Home - Away" label when it has one. */
  private static String favLabel(Fav f) {
    return f.name.isEmpty() ? f.row : f.name;
  }

  private static int countComplete(List<Rec> list) {
    int n = 0;
    for (Rec r : list) {
      if (r.complete) n++;
    }
    return n;
  }

  private static String markLine(String name, int ok, int total) {
    return String.format(Locale.US, "%-9s %2d / %-2d %s", name, ok, total,
        ok >= total ? "✓" : "!");
  }

  /** One block per match that has a tab that is not on its page, empty, or not scraped. */
  private String unavailableText() {
    StringBuilder sb = new StringBuilder();
    for (String n : pageNotes) sb.append("- ").append(n).append('\n');
    appendUnavailable(sb, "Main match", mainRec);
    for (Rec r : h2hSlots) appendUnavailable(sb, "H2H: " + shorten(r.rowText), r);
    for (Rec r : homeSlots) appendUnavailable(sb, homeName + ": " + shorten(r.rowText), r);
    for (Rec r : awaySlots) appendUnavailable(sb, awayName + ": " + shorten(r.rowText), r);
    return sb.toString().trim();
  }

  private static void appendUnavailable(StringBuilder sb, String title, Rec r) {
    if (r == null) return;
    if (r.done && r.missing.isEmpty() && r.emptyTabs.isEmpty()) return;
    sb.append(title).append('\n');
    if (!r.done) sb.append("   not scraped\n");
    if (!r.missing.isEmpty()) sb.append("   not on the page: ").append(join(r.missing)).append('\n');
    if (!r.emptyTabs.isEmpty()) sb.append("   empty: ").append(join(r.emptyTabs)).append('\n');
  }

  private JSONArray unavailableJson() {
    JSONArray arr = new JSONArray();
    addUnavailable(arr, "main", "Main match", mainRec);
    for (Rec r : h2hSlots) addUnavailable(arr, "h2h", r.rowText, r);
    for (Rec r : homeSlots) addUnavailable(arr, "home", r.rowText, r);
    for (Rec r : awaySlots) addUnavailable(arr, "away", r.rowText, r);
    return arr;
  }

  private static void addUnavailable(JSONArray arr, String group, String title, Rec r) {
    if (r == null) return;
    if (r.done && r.missing.isEmpty() && r.emptyTabs.isEmpty()) return;
    JSONObject o = new JSONObject();
    jput(o, "group", group);
    jput(o, "match", title);
    jput(o, "mid", r.mid);
    jput(o, "scraped", r.done);
    jput(o, "not_on_page", toArray(r.missing));
    jput(o, "empty", toArray(r.emptyTabs));
    arr.put(o);
  }

  private JSONObject recJson(Rec r) {
    JSONObject o = new JSONObject();
    jput(o, "mid", r.mid);
    jput(o, "url", r.url);
    jput(o, "row_text", r.rowText);
    jput(o, "also_in", toArray(r.groups));
    jput(o, "done", r.done);
    jput(o, "complete", r.complete);
    jput(o, "retried", r.retry);
    jput(o, "not_on_page", toArray(r.missing));
    jput(o, "empty", toArray(r.emptyTabs));
    if (r.info != null && r.info.length() > 0) jput(o, "info", r.info);
    jput(o, "tabs", r.tabs);
    return o;
  }

  private void saveToDownloads(String name, String body) {
    if (Build.VERSION.SDK_INT < 29) {
      save(name, body);
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

  /** A notification that opens the Downloads app when it is tapped. */
  private void notifyDone(String title, String text) {
    try {
      NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      if (nm == null) return;
      Notification.Builder b;
      if (Build.VERSION.SDK_INT >= 26) {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Scrap finished", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(ch);
        b = new Notification.Builder(this, CHANNEL_ID);
      } else {
        b = new Notification.Builder(this);
      }
      Intent open = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
      open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      int flags = PendingIntent.FLAG_UPDATE_CURRENT;
      if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
      PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
      b.setSmallIcon(android.R.drawable.stat_sys_download_done)
          .setContentTitle(title)
          .setContentText(text)
          .setContentIntent(pi)
          .setAutoCancel(true);
      nm.notify(NOTIF_ID, b.build());
    } catch (Exception e) {
      log("Could not show the notification: " + e);
    }
  }

  // ---------------------------------------------------------------- tab records

  private JSONObject leaf(String status, String text) {
    JSONObject o = new JSONObject();
    jput(o, "status", status);
    jput(o, "text", text);
    return o;
  }

  /**
   * Records a tab: captured if it has data, empty if not. A tab that came back empty never
   * replaces data an earlier pass already captured. If the page itself says there is no data,
   * the tab is "nodata": listed as empty, but not a failure.
   */
  private void putLeaf(Rec r, JSONObject parent, String key, String name, String text, boolean empty) {
    if (empty && "captured".equals(r.state.get(name))) return;
    String st = !empty ? "captured" : (hasNoDataLine(text) ? "nodata" : "empty");
    r.state.put(name, st);
    jput(parent, key, leaf(empty ? "empty" : "captured", text));
  }

  /** Records a tab from the page text, cleaned (no header, odds, ads, news or footer). */
  private void putBody(Rec r, JSONObject parent, String key, String name, String raw) {
    String body = cleanPage(raw, false);
    putLeaf(r, parent, key, name, body, isEmptyBody(body));
  }

  /** Records a player stats table. It needs a header row and at least one player. */
  private void putTable(Rec r, JSONObject parent, String key, String name, String table) {
    String t = table == null ? "" : table.trim();
    int rows = t.isEmpty() ? 0 : t.split("\n").length;
    putLeaf(r, parent, key, name, t, rows < 2);
  }

  /** Records a tab where the page says there is no data. Not a failure. */
  private void putNoData(Rec r, JSONObject parent, String key, String name) {
    if ("captured".equals(r.state.get(name))) return;
    r.state.put(name, "nodata");
    jput(parent, key, leaf("empty", ""));
  }

  /**
   * Records a tab that is not in the page's tab row. This never makes a match incomplete, and
   * it never replaces data an earlier pass captured.
   */
  private void putMissing(Rec r, JSONObject parent, String key, String name) {
    JSONObject cur = parent.optJSONObject(key);
    if (cur != null && !"missing".equals(cur.optString("status"))) return;
    r.state.put(name, "missing");
    jput(parent, key, leaf("missing", ""));
  }

  /** True when a line says there is no data ("No statistics available", and so on). */
  private static boolean hasNoDataLine(String body) {
    if (body == null) return false;
    for (String line : body.split("\n")) {
      if (NO_DATA.matcher(line.trim()).find()) return true;
    }
    return false;
  }

  /**
   * Cleans the visible text of a match page. Drops the odds and bonus block, ads, "show more"
   * buttons, video lines, the match momentum axis, the news list and everything after it
   * (footer). The tab row is always dropped. With withHeader the match header (teams,
   * score, status, round) is kept; without it the text starts where the tab content starts.
   */
  private static String cleanPage(String raw, boolean withHeader) {
    List<String> lines = new ArrayList<>();
    for (String s : (raw == null ? "" : raw).split("\n")) {
      String t = s.trim();
      if (!t.isEmpty()) lines.add(t);
    }
    int end = lines.size();
    for (int i = 0; i < lines.size(); i++) {
      String t = lines.get(i);
      if (t.equals("FLASHSCORE NEWS")
          || (t.startsWith("Follow ") && t.contains("football coverage"))) {
        end = i;
        break;
      }
    }
    // The tab row starts at the first nav label (MATCH, or SUMMARY on small matches).
    int navStart = -1;
    for (int i = 0; i < Math.min(end, 80); i++) {
      if (indexOf(NAV_LABELS, lines.get(i)) >= 0) {
        navStart = i;
        break;
      }
    }
    int bodyStart = 0;
    if (navStart >= 0) {
      boolean[] used = new boolean[NAV_LABELS.length];
      int i = navStart;
      while (i < end) {
        int k = indexOf(NAV_LABELS, lines.get(i));
        if (k < 0 || used[k]) break;
        used[k] = true;
        i++;
      }
      bodyStart = i;
    }

    StringBuilder sb = new StringBuilder();
    boolean skipOdds = false;
    for (int i = withHeader ? 0 : bodyStart; i < end; i++) {
      if (withHeader && navStart >= 0 && i >= navStart && i < bodyStart) continue;
      String t = lines.get(i);
      String up = t.toUpperCase(Locale.ROOT);
      if (skipOdds) {
        if (isHeading(t) && indexOf(ODDS_LABELS, up) < 0) {
          skipOdds = false;
        } else {
          continue;
        }
      }
      if (up.equals("ODDS") && i + 1 < end
          && indexOf(ODDS_LABELS, lines.get(i + 1).toUpperCase(Locale.ROOT)) >= 0) {
        skipOdds = true;
        continue;
      }
      if (indexOf(NOISE_LINES, up) >= 0) continue;
      if (up.equals("MATCH MOMENTUM")) {
        while (i + 1 < end && lines.get(i + 1).matches("\\d+'")) i++;
        continue;
      }
      sb.append(t).append('\n');
    }
    return sb.toString().trim();
  }

  /** True when the text has no data: nothing, or only sub-tab labels and "no data" lines. */
  private static boolean isEmptyBody(String body) {
    if (body == null) return true;
    for (String line : body.split("\n")) {
      String t = line.trim();
      if (t.isEmpty()) continue;
      if (indexOf(SUB_LABELS, t.toUpperCase(Locale.ROOT)) >= 0) continue;
      if (NO_DATA.matcher(t).find()) continue;
      return false;
    }
    return true;
  }

  private static boolean isHeading(String t) {
    return t.length() <= 40 && t.equals(t.toUpperCase(Locale.ROOT))
        && t.replaceAll("[^A-Za-z]", "").length() >= 4;
  }

  private static int indexOf(String[] arr, String s) {
    for (int i = 0; i < arr.length; i++) {
      if (arr[i].equals(s)) return i;
    }
    return -1;
  }

  /** Sub-tab labels from the page, without the main tabs and the team filter. */
  private static List<String> subTabLabels(String json) {
    List<String> out = new ArrayList<>();
    JSONArray arr = parseOrEmpty(json).optJSONArray("labels");
    if (arr == null) return out;
    for (int i = 0; i < arr.length(); i++) {
      String s = arr.optString(i).trim();
      if (s.isEmpty()) continue;
      if (indexOf(MAIN_TABS, s.toUpperCase(Locale.ROOT)) < 0) out.add(s);
    }
    return out;
  }

  private static String keyOf(String label) {
    return label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
  }

  private static String titleCase(String s) {
    String t = s.toLowerCase(Locale.ROOT);
    return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
  }

  // ---------------------------------------------------------------- helpers

  private static String clickTab(String txt) {
    return CLICK_TAB_JS.replace("__TXT__", txt);
  }

  private static String clickText(String txt) {
    return CLICK_TEXT_JS.replace("__TXT__", txt);
  }

  private static String midOf(String href) {
    if (href == null) return "";
    Matcher m = Pattern.compile("[?&]mid=([A-Za-z0-9]+)").matcher(href);
    return m.find() ? m.group(1) : "";
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

  private static JSONArray toArray(List<String> list) {
    JSONArray a = new JSONArray();
    for (String s : list) a.put(s);
    return a;
  }

  private static String join(List<String> list) {
    return join(list, ", ");
  }

  private static String join(List<String> list, String sep) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) sb.append(sep);
      sb.append(list.get(i));
    }
    return sb.toString();
  }

  private static String shorten(String s) {
    return shorten(s, 60);
  }

  private static String shorten(String s, int max) {
    if (s == null) return "";
    return s.length() > max ? s.substring(0, max) + "..." : s;
  }

  /** Only used on Android 9 and older, where Downloads is not reachable. */
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
// END OF FILE MainActivity.java (v2.0)

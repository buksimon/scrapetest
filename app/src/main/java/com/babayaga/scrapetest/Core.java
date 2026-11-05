package com.babayaga.scrapetest;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BABAYAGA Universal Football Simulator: the part with no screen in it.
 *
 *  Json      reads and writes JSON (no Android classes, so it is the same on every machine)
 *  SBook     reads the SportyBet export: matches, markets, odds
 *  FlashData reads the Flashscore walker files: last 10 games per team, stats, formations
 *  Names     matches a Flashscore team name to a SportyBet team name
 *  Engine    one settlement rule per market (the same rule for every simulated match)
 *  Model     the six components -> goal rates -> 200,000 simulated matches -> every market
 *
 * The model is the one tested on the 14-match workbook: statistics only, no bookmaker odds go in.
 * The odds are only read afterwards, to list the markets and to show the bookmaker's own chance.
 */
public final class Core {
  private Core() {}

  public static final String VERSION = "1.0";

  // ================================================================ JSON

  public static final class Json {
    private final String s;
    private int i;

    private Json(String s) {
      this.s = s;
    }

    public static Object parse(String text) {
      if (text == null) throw new IllegalArgumentException("JSON: empty");
      if (text.startsWith("﻿")) text = text.substring(1);
      Json j = new Json(text);
      j.ws();
      Object v = j.val();
      j.ws();
      if (j.i != j.s.length()) throw j.err("extra text after the end");
      return v;
    }

    private IllegalArgumentException err(String m) {
      return new IllegalArgumentException("JSON: " + m + " at character " + i);
    }

    private void ws() {
      while (i < s.length()) {
        char c = s.charAt(i);
        if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
        else break;
      }
    }

    private Object val() {
      if (i >= s.length()) throw err("unexpected end");
      char c = s.charAt(i);
      if (c == '{') return obj();
      if (c == '[') return arr();
      if (c == '"') return str();
      if (s.startsWith("true", i)) {
        i += 4;
        return Boolean.TRUE;
      }
      if (s.startsWith("false", i)) {
        i += 5;
        return Boolean.FALSE;
      }
      if (s.startsWith("null", i)) {
        i += 4;
        return null;
      }
      return num();
    }

    private Map<String, Object> obj() {
      i++;
      LinkedHashMap<String, Object> m = new LinkedHashMap<>();
      ws();
      if (i < s.length() && s.charAt(i) == '}') {
        i++;
        return m;
      }
      while (true) {
        ws();
        String k = str();
        ws();
        if (i >= s.length() || s.charAt(i) != ':') throw err("expected :");
        i++;
        ws();
        m.put(k, val());
        ws();
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i++);
        if (c == ',') continue;
        if (c == '}') return m;
        throw err("expected , or }");
      }
    }

    private List<Object> arr() {
      i++;
      ArrayList<Object> a = new ArrayList<>();
      ws();
      if (i < s.length() && s.charAt(i) == ']') {
        i++;
        return a;
      }
      while (true) {
        ws();
        a.add(val());
        ws();
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i++);
        if (c == ',') continue;
        if (c == ']') return a;
        throw err("expected , or ]");
      }
    }

    private String str() {
      if (i >= s.length() || s.charAt(i) != '"') throw err("expected a string");
      i++;
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (i >= s.length()) throw err("unterminated string");
        char c = s.charAt(i++);
        if (c == '"') return sb.toString();
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        if (i >= s.length()) throw err("bad escape");
        char e = s.charAt(i++);
        switch (e) {
          case 'n': sb.append('\n'); break;
          case 't': sb.append('\t'); break;
          case 'r': sb.append('\r'); break;
          case 'b': sb.append('\b'); break;
          case 'f': sb.append('\f'); break;
          case 'u':
            if (i + 4 > s.length()) throw err("bad \\u escape");
            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            i += 4;
            break;
          default: sb.append(e);
        }
      }
    }

    private Double num() {
      int st = i;
      while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
      if (st == i) throw err("unexpected character '" + s.charAt(i) + "'");
      return Double.valueOf(s.substring(st, i));
    }

    // ---- writer
    public static String write(Object o) {
      StringBuilder sb = new StringBuilder();
      w(sb, o);
      return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void w(StringBuilder sb, Object o) {
      if (o == null) {
        sb.append("null");
      } else if (o instanceof Map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
          if (!first) sb.append(',');
          first = false;
          q(sb, e.getKey());
          sb.append(':');
          w(sb, e.getValue());
        }
        sb.append('}');
      } else if (o instanceof List) {
        sb.append('[');
        boolean first = true;
        for (Object x : (List<Object>) o) {
          if (!first) sb.append(',');
          first = false;
          w(sb, x);
        }
        sb.append(']');
      } else if (o instanceof Number) {
        double d = ((Number) o).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
        else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
        else sb.append(d);
      } else if (o instanceof Boolean) {
        sb.append(((Boolean) o) ? "true" : "false");
      } else {
        q(sb, o.toString());
      }
    }

    private static void q(StringBuilder sb, String s) {
      sb.append('"');
      for (int k = 0; k < s.length(); k++) {
        char c = s.charAt(k);
        switch (c) {
          case '"': sb.append("\\\""); break;
          case '\\': sb.append("\\\\"); break;
          case '\n': sb.append("\\n"); break;
          case '\r': sb.append("\\r"); break;
          case '\t': sb.append("\\t"); break;
          default:
            if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
            else sb.append(c);
        }
      }
      sb.append('"');
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> M(Object o) {
    return o instanceof Map ? (Map<String, Object>) o : null;
  }

  @SuppressWarnings("unchecked")
  static List<Object> L(Object o) {
    return o instanceof List ? (List<Object>) o : null;
  }

  static String S(Object o) {
    if (o == null) return null;
    if (o instanceof String) return (String) o;
    if (o instanceof Double) {
      double d = (Double) o;
      return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
    return String.valueOf(o);
  }

  static double D(Object o) {
    if (o instanceof Number) return ((Number) o).doubleValue();
    if (o instanceof String) {
      try {
        return Double.parseDouble(((String) o).trim());
      } catch (NumberFormatException e) {
        return Double.NaN;
      }
    }
    return Double.NaN;
  }

  /** Walks keys through nested maps; null when any step is missing. */
  static Object path(Object o, String... keys) {
    Object cur = o;
    for (String k : keys) {
      Map<String, Object> m = M(cur);
      if (m == null) return null;
      cur = m.get(k);
    }
    return cur;
  }

  // ================================================================ names

  public static final class Names {
    private Names() {}

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
        "fc", "cf", "sc", "ac", "as", "afc", "cd", "ca", "fk", "sk", "nk", "bk", "sv", "vfb", "vfl",
        "tsv", "fsv", "ssc", "us", "ud", "cp", "ec", "se", "rc", "club", "de", "the", "calcio",
        "csd", "csdc", "cs", "ssd", "sd", "ad", "cfc", "ifk", "if", "ik", "bsc", "fcsb", "fcb"));

    private static final Map<String, String> SYN = new HashMap<>();

    static {
      SYN.put("utd", "united");
      SYN.put("man", "manchester");
      SYN.put("cologne", "koln");
      SYN.put("koeln", "koln");
      SYN.put("st", "saint");
      SYN.put("internazionale", "inter");
      SYN.put("spurs", "tottenham");
      SYN.put("wolves", "wolverhampton");
      SYN.put("athletic", "athletic");
      SYN.put("atl", "atletico");
      SYN.put("atletico", "atletico");
      SYN.put("jrs", "juniors");
      SYN.put("dep", "deportivo");
      SYN.put("sporting", "sporting");
      SYN.put("borussia", "borussia");
    }

    /** Lower case, no accents, no punctuation, one space between words. */
    public static String norm(String s) {
      if (s == null) return "";
      String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
      t = t.toLowerCase(Locale.ROOT).replace("ø", "o").replace("æ", "ae").replace("ß", "ss");
      t = t.replaceAll("['´`’]", "");
      t = t.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
      return t;
    }

    static List<String> tokens(String s) {
      String n = norm(s);
      List<String> out = new ArrayList<>();
      for (String tk : n.split(" ")) {
        if (tk.isEmpty()) continue;
        String m = SYN.containsKey(tk) ? SYN.get(tk) : tk;
        if (m.contains("gladbach")) m = "gladbach";
        out.add(m);
      }
      List<String> keep = new ArrayList<>();
      for (String tk : out) {
        if (!STOP.contains(tk)) keep.add(tk);
      }
      return keep.isEmpty() ? out : keep;
    }

    private static String join(List<String> t) {
      StringBuilder sb = new StringBuilder();
      for (String x : t) sb.append(x);
      return sb.toString();
    }

    /** 0..1: how likely two names are the same team. */
    public static double sim(String a, String b) {
      List<String> ta = tokens(a);
      List<String> tb = tokens(b);
      if (ta.isEmpty() || tb.isEmpty()) return 0;
      String ja = join(ta);
      String jb = join(tb);
      if (ja.equals(jb)) return 1.0;
      Set<String> sa = new HashSet<>(ta);
      Set<String> sb = new HashSet<>(tb);
      if (sa.containsAll(sb) || sb.containsAll(sa)) return 0.9;
      if (Math.min(ja.length(), jb.length()) >= 4 && (ja.startsWith(jb) || jb.startsWith(ja))) return 0.85;
      // every word of the shorter name starts a word of the longer one (Dep. = Deportivo, Utd = United)
      List<String> small = ta.size() <= tb.size() ? ta : tb;
      List<String> big = ta.size() <= tb.size() ? tb : ta;
      boolean allPrefix = true;
      for (String w : small) {
        boolean hit = false;
        for (String v : big) {
          if (v.equals(w) || (w.length() >= 3 && v.startsWith(w)) || (v.length() >= 3 && w.startsWith(v))) {
            hit = true;
            break;
          }
        }
        if (!hit) {
          allPrefix = false;
          break;
        }
      }
      if (allPrefix) return 0.75;
      Set<String> inter = new HashSet<>(sa);
      inter.retainAll(sb);
      Set<String> uni = new HashSet<>(sa);
      uni.addAll(sb);
      return uni.isEmpty() ? 0 : (double) inter.size() / uni.size();
    }

    public static final double OK = 0.7;

    /** How well (home, away) of one source fits (home, away) of the other: min of the two. */
    public static double pair(String h1, String a1, String h2, String a2) {
      return Math.min(sim(h1, h2), sim(a1, a2));
    }
  }

  // ================================================================ SportyBet

  public static final class Outcome {
    public String label;
    public double odds;
    /** Chance of this outcome in the simulated matches (given the bet is not void). NaN = no prediction. */
    public double q = Double.NaN;
    /** Chance of a void (push) in the simulated matches. */
    public double pvoid = 0;
    /** The bookmaker's own chance (odds with the margin taken out). NaN when it cannot be told. */
    public double book = Double.NaN;
    public boolean pick;
    public char side = 0;           // Asian handicap: 'H' or 'A'
    public String why = "";         // why there is no prediction
  }

  public static final class Market {
    public String name;
    public String line;
    public String family = "";
    public final List<Outcome> outs = new ArrayList<>();
  }

  public static final class SMatch {
    public int no;
    public String key = "";
    public String home = "";
    public String away = "";
    public String league = "";
    public String date = "";
    public String time = "";
    public final List<Market> markets = new ArrayList<>();
  }

  /** Reads a SportyBet export file (the merged football JSON). */
  public static List<SMatch> parseSporty(String text) {
    Object root = Json.parse(text);
    List<Object> ms = L(path(root, "matches"));
    if (ms == null) ms = L(root);
    if (ms == null) {
      Map<String, Object> single = M(root);
      if (single != null && single.get("markets") != null) {
        ms = new ArrayList<>();
        ms.add(single);
      }
    }
    if (ms == null) throw new IllegalArgumentException("This file has no list of matches.");
    List<SMatch> out = new ArrayList<>();
    int no = 0;
    for (Object mo : ms) {
      Map<String, Object> m = M(mo);
      if (m == null) continue;
      SMatch sm = new SMatch();
      sm.no = ++no;
      sm.key = S(m.get("matchKey")) == null ? "" : S(m.get("matchKey"));
      Map<String, Object> h = M(m.get("header"));
      if (h != null) {
        sm.home = nz(S(h.get("homeTeam")));
        sm.away = nz(S(h.get("awayTeam")));
        sm.league = nz(S(h.get("league")));
        sm.date = nz(S(h.get("matchDate")));
        sm.time = nz(S(h.get("matchTime")));
      }
      List<Object> mks = L(m.get("markets"));
      if (mks != null) {
        for (Object mko : mks) {
          Map<String, Object> mk = M(mko);
          if (mk == null) continue;
          String name = S(mk.get("marketName"));
          Map<String, Object> vals = M(mk.get("values"));
          if (name == null || vals == null || vals.isEmpty()) continue;
          boolean nested = false;
          for (Object v : vals.values()) {
            if (v instanceof Map) {
              nested = true;
              break;
            }
          }
          if (!nested) {
            Market x = new Market();
            x.name = name;
            x.line = null;
            for (Map.Entry<String, Object> e : vals.entrySet()) addOutcome(x, e.getKey(), e.getValue());
            if (x.outs.size() > 0) out2(sm, x);
          } else {
            for (Map.Entry<String, Object> e : vals.entrySet()) {
              Map<String, Object> inner = M(e.getValue());
              if (inner == null) continue;
              Market x = new Market();
              x.name = name;
              x.line = e.getKey();
              for (Map.Entry<String, Object> e2 : inner.entrySet()) addOutcome(x, e2.getKey(), e2.getValue());
              if (x.outs.size() > 0) out2(sm, x);
            }
          }
        }
      }
      Engine.prepare(sm);
      out.add(sm);
    }
    return out;
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  private static void out2(SMatch sm, Market x) {
    x.family = Engine.family(x.name);
    sm.markets.add(x);
  }

  private static void addOutcome(Market x, String label, Object odds) {
    double d = D(odds);
    if (Double.isNaN(d) || d < 1.0) return;
    Outcome o = new Outcome();
    o.label = label;
    o.odds = d;
    x.outs.add(o);
  }

  // ================================================================ Flashscore

  public static final class Game {
    public String date;                 // yyyy-mm-dd, from the row of the list
    public String home = "";
    public String away = "";
    public int hg = -1;
    public int ag = -1;
    public char res = 0;                // W, D or L for the team whose list it is
    public final double[] xg = {Double.NaN, Double.NaN};
    public final double[] shots = {Double.NaN, Double.NaN};
    public final double[] sot = {Double.NaN, Double.NaN};
    public final double[] corners = {Double.NaN, Double.NaN};
    public final double[] poss = {Double.NaN, Double.NaN};
    public final double[] fouls = {Double.NaN, Double.NaN};
    public String formHome;
    public String formAway;
    public String row = "";

    String key() {
      return date + "|" + home + "|" + away + "|" + hg + "|" + ag;
    }
  }

  public static final class FMatch {
    public String mid = "";
    public String home = "";
    public String away = "";
    public String date = "";            // yyyy-mm-dd of the match
    public String headline = "";
    public String favName = "";         // "Home - Away" as the favorites page shows it
    public int favLine = 0;             // position on the favorites page (1 = first)
    public String mainFormHome;
    public String mainFormAway;
    public String teamHome = "";
    public String teamAway = "";
    public final List<Game> lastHome = new ArrayList<>();
    public final List<Game> lastAway = new ArrayList<>();
    public final List<Game> h2h = new ArrayList<>();
    public int complete;
    public int total;
  }

  private static final Pattern P_ROW_DATE = Pattern.compile("^(\\d\\d)\\.(\\d\\d)\\.(\\d\\d)\\b");
  private static final Pattern P_ROW_SCORE = Pattern.compile("(\\d+)\\s+(\\d+)(?:\\s+([WDL]))?$");
  private static final Pattern P_SUMMARY = Pattern.compile("^(.*?) v (.*?)\\s+\\((\\d\\d)/(\\d\\d)/(\\d{4})\\)");
  private static final Pattern P_FORM = Pattern.compile("^\\d(?: - \\d)+$");
  private static final Pattern P_NUM = Pattern.compile("^-?\\d+(?:\\.\\d+)?$");

  private static final String[][] STAT_LABELS = {
      {"Expected goals (xG)"},
      {"Total shots"},
      {"Shots on target", "On target"},
      {"Corner kicks"},
      {"Ball possession"},
      {"Fouls"}};

  private static double numOf(String s) {
    String t = s.trim().replace("%", "");
    return P_NUM.matcher(t).matches() ? Double.parseDouble(t) : Double.NaN;
  }

  /** Reads "value / label / value" triplets of a Flashscore stats page. Missing stays NaN. */
  static double[][] statPairs(String text) {
    double[][] out = new double[STAT_LABELS.length][2];
    for (double[] p : out) {
      p[0] = Double.NaN;
      p[1] = Double.NaN;
    }
    if (text == null) return out;
    String[] lines = text.split("\n");
    for (int k = 0; k < lines.length; k++) lines[k] = lines[k].trim();
    for (int k = 0; k < STAT_LABELS.length; k++) {
      boolean found = false;
      for (String lab : STAT_LABELS[k]) {
        for (int i = 1; i < lines.length - 1 && !found; i++) {
          if (lines[i].equalsIgnoreCase(lab)) {
            double a = numOf(lines[i - 1]);
            double b = numOf(lines[i + 1]);
            if (!Double.isNaN(a) && !Double.isNaN(b)) {
              out[k][0] = a;
              out[k][1] = b;
              found = true;
            }
          }
        }
        if (found) break;
      }
    }
    return out;
  }

  /** {home formation, away formation} from a lineups page, or null. */
  static String[] formations(String text) {
    if (text == null) return null;
    String[] l = text.split("\n");
    if (l.length < 6) return null;
    for (int k = 0; k < l.length; k++) l[k] = l[k].trim();
    if (!"FORMATION".equals(l[1]) || !P_FORM.matcher(l[0]).matches() || !P_FORM.matcher(l[2]).matches()) return null;
    return new String[] {l[0], l[2]};
  }

  /** Words in a match header that are not team names (status, the stream button, ...). */
  private static final Set<String> HEAD_WORDS = new HashSet<>(Arrays.asList(
      "STREAM", "FINISHED", "LIVE", "HALF TIME", "HALFTIME", "AFTER EXTRA TIME", "AFTER PENALTIES",
      "EXTRA TIME", "PENALTIES", "POSTPONED", "CANCELLED", "ABANDONED", "AWARDED", "WALKOVER",
      "INTERRUPTED", "SUSPENDED", "DELAYED", "SCHEDULED", "PREVIEW", "1ST HALF", "2ND HALF",
      "BREAK TIME", "TO FINISH", "VS", "V"));
  /** "03.11.2026 20:45" (the time is optional): the date line under the competition line. */
  private static final Pattern P_HEAD_DATE =
      Pattern.compile("^(\\d{2})\\.(\\d{2})\\.(\\d{4})(?:\\s+\\d{1,2}:\\d{2})?$");
  /** A line made only of digits and score or time marks: never a team name. */
  private static final Pattern P_NOT_NAME = Pattern.compile("^[\\d\\s'\u2019+:.\\-\u2013()]*$");

  private static boolean headName(String s) {
    if (s.isEmpty() || P_NOT_NAME.matcher(s).matches()) return false;
    return !HEAD_WORDS.contains(s.toUpperCase(Locale.ROOT));
  }

  /**
   * {home, away, yyyy-mm-dd} from the top of a summary page, or null. The page text starts with the
   * competition line, then the date line, then the home team, its score and status, then the away
   * team:  FOOTBALLENGLANDLEAGUE ONE - ROUND 14 / 31.10.2026 16:00 / Wigan / 3 / - / 2 / FINISHED /
   * Bromley ...  (a match not played yet has "-" and STREAM between the two names). The older layout,
   * "Home v Away (dd/mm/yyyy)" on the first line, is still read.
   */
  static String[] summaryHead(String text) {
    if (text == null) return null;
    String[] lines = text.split("\n");
    int lim = Math.min(lines.length, 12);
    for (int i = 0; i < lim; i++) {
      Matcher md = P_HEAD_DATE.matcher(lines[i].replace('\u00a0', ' ').trim());
      if (!md.matches()) continue;
      String home = null;
      String away = null;
      for (int k = i + 1; k < Math.min(lines.length, i + 14); k++) {
        String t = lines[k].replace('\u00a0', ' ').trim();
        if (!headName(t)) continue;
        if (home == null) {
          home = t;
        } else {
          away = t;
          break;
        }
      }
      if (home != null && away != null) {
        return new String[] {home, away, md.group(3) + "-" + md.group(2) + "-" + md.group(1)};
      }
      break;
    }
    String first = lines[0].replace('\u00a0', ' ');
    Matcher m = P_SUMMARY.matcher(first);
    if (!m.find()) return null;
    return new String[] {m.group(1).trim(), m.group(2).trim(), m.group(5) + "-" + m.group(4) + "-" + m.group(3)};
  }

  /** "notts-county" -> "notts county" (a name taken from a match link). */
  private static String unslug(String s) {
    return s.replaceAll("-(?=[A-Za-z0-9]*[A-Z0-9])[A-Za-z0-9]{8}$", "").replace('-', ' ').trim();
  }

  /**
   * {home, away} worked out from a list row ("31.10.26 LO Wigan Bromley 3 2 W", home first) and the
   * match link, whose two team names are only used to find where the row text splits (the link lists
   * the teams alphabetically, not home and away). Null when it cannot be done safely.
   */
  static String[] namesFromRow(String row, String url) {
    if (row == null || url == null) return null;
    Matcher mu = Pattern.compile("/match/football/([^/?#]+)/([^/?#]+)/").matcher(url);
    if (!mu.find()) return null;
    String s0 = unslug(mu.group(1));
    String s1 = unslug(mu.group(2));
    String t = row.replace('\u00a0', ' ').trim()
        .replaceFirst("^\\d{2}\\.\\d{2}\\.\\d{2}\\s+\\S+\\s+", "")
        .replaceFirst("\\s+\\d+\\s+\\d+(?:\\s+[WDL])?$", "").trim();
    String[] w = t.split("\\s+");
    if (w.length < 2) return null;
    double best = 0;
    String[] out = null;
    for (int k = 1; k < w.length; k++) {
      String left = String.join(" ", Arrays.copyOfRange(w, 0, k));
      String right = String.join(" ", Arrays.copyOfRange(w, k, w.length));
      double sc = Math.max(Names.sim(left, s0) + Names.sim(right, s1), Names.sim(left, s1) + Names.sim(right, s0));
      if (sc > best) {
        best = sc;
        out = new String[] {left, right};
      }
    }
    return best >= 1.4 ? out : null;
  }

  private static String tabText(Map<String, Object> g, String tab) {
    Object t = path(g, "tabs", tab, "text");
    return t instanceof String ? (String) t : null;
  }

  static Game parseGame(Map<String, Object> g) {
    Game x = new Game();
    String row = nz(S(g.get("row_text")));
    x.row = row;
    Matcher md = P_ROW_DATE.matcher(row);
    if (md.find()) x.date = "20" + md.group(3) + "-" + md.group(2) + "-" + md.group(1);
    Matcher ms = P_ROW_SCORE.matcher(row);
    if (ms.find()) {
      x.hg = Integer.parseInt(ms.group(1));
      x.ag = Integer.parseInt(ms.group(2));
      if (ms.group(3) != null) x.res = ms.group(3).charAt(0);
    }
    String[] head = summaryHead(tabText(g, "summary"));
    if (head != null) {
      x.home = head[0];
      x.away = head[1];
    } else {
      String[] nm = namesFromRow(row, nz(S(g.get("url"))));
      if (nm != null) {
        x.home = nm[0];
        x.away = nm[1];
      }
    }
    Object statsTab = path(g, "tabs", "stats", "match", "text");
    double[][] sp = statPairs(statsTab instanceof String ? (String) statsTab : null);
    System.arraycopy(sp[0], 0, x.xg, 0, 2);
    System.arraycopy(sp[1], 0, x.shots, 0, 2);
    System.arraycopy(sp[2], 0, x.sot, 0, 2);
    System.arraycopy(sp[3], 0, x.corners, 0, 2);
    System.arraycopy(sp[4], 0, x.poss, 0, 2);
    System.arraycopy(sp[5], 0, x.fouls, 0, 2);
    String[] f = formations(tabText(g, "lineups"));
    if (f != null) {
      x.formHome = f[0];
      x.formAway = f[1];
    }
    return x;
  }

  /** Reads one walker file (one match). */
  public static FMatch parseFlash(Map<String, Object> d) {
    FMatch fm = new FMatch();
    fm.mid = nz(S(d.get("mid")));
    Map<String, Object> main = M(d.get("main_match"));
    String sum = main == null ? null : tabText(main, "summary");
    String[] head = summaryHead(sum);
    if (head != null) {
      fm.home = head[0];
      fm.away = head[1];
      fm.date = head[2];
    }
    if (sum != null) {
      String[] ls = sum.split("\n");
      if (ls.length > 1) fm.headline = ls[1].trim();
    }
    if (main != null) {
      String[] f = formations(tabText(main, "lineups"));
      if (f != null) {
        fm.mainFormHome = f[0];
        fm.mainFormAway = f[1];
      }
    }
    Map<String, Object> fav = M(d.get("favorite"));
    if (fav != null) {
      fm.favName = nz(S(fav.get("name")));
      double ln = D(fav.get("line"));
      fm.favLine = Double.isNaN(ln) ? 0 : (int) ln;
      if ((fm.home.isEmpty() || fm.away.isEmpty()) && fm.favName.contains(" - ")) {
        String[] nm = fm.favName.split(" - ", 2);
        fm.home = nm[0].trim();
        fm.away = nm[1].trim();
      }
    }
    Map<String, Object> lm = M(d.get("last_matches"));
    if (lm != null) {
      for (int side = 0; side < 2; side++) {
        Map<String, Object> t = M(lm.get(side == 0 ? "home_team" : "away_team"));
        if (t == null) continue;
        if (side == 0) fm.teamHome = nz(S(t.get("name")));
        else fm.teamAway = nz(S(t.get("name")));
        List<Object> games = L(t.get("matches"));
        if (games == null) continue;
        for (Object go : games) {
          Map<String, Object> g = M(go);
          if (g == null) continue;
          (side == 0 ? fm.lastHome : fm.lastAway).add(parseGame(g));
        }
      }
    }
    List<Object> h2h = L(d.get("h2h"));
    if (h2h != null) {
      for (Object go : h2h) {
        Map<String, Object> g = M(go);
        if (g != null) fm.h2h.add(parseGame(g));
      }
    }
    double cc = D(d.get("complete_count"));
    double tc = D(d.get("total_count"));
    fm.complete = Double.isNaN(cc) ? 0 : (int) cc;
    fm.total = Double.isNaN(tc) ? 0 : (int) tc;
    return fm;
  }

  /** Reads a Flashscore file: one walker file, or the favorites file with many. */
  public static List<FMatch> parseFlashFile(String text) {
    Object root = Json.parse(text);
    List<FMatch> out = new ArrayList<>();
    Map<String, Object> m = M(root);
    if (m != null && m.get("main_match") != null) {
      out.add(parseFlash(m));
    } else if (m != null && L(m.get("matches")) != null) {
      for (Object o : L(m.get("matches"))) {
        Map<String, Object> mm = M(o);
        if (mm != null && mm.get("main_match") != null) out.add(parseFlash(mm));
      }
    } else if (L(root) != null) {
      for (Object o : L(root)) {
        Map<String, Object> mm = M(o);
        if (mm != null && mm.get("main_match") != null) out.add(parseFlash(mm));
      }
    }
    if (out.isEmpty()) throw new IllegalArgumentException("This file has no Flashscore matches in it.");
    for (int i = 0; i < out.size(); i++) {
      if (out.get(i).favLine == 0) out.get(i).favLine = i + 1;
    }
    return out;
  }

  // ================================================================ engine

  interface Box {
    /** 0 = lost, 1 = won, 2 = void (stake back). */
    int eval(Feat f);
  }

  interface Cond {
    boolean test(Feat f);
  }

  interface IntPred {
    boolean test(int x);
  }

  /** What one simulated match looked like, as far as any market needs to know. */
  public static final class Feat {
    int h1, a1, h2, a2, hg, ag;
    int first, first1, first2, last, last1, last2;   // 0 nobody, 1 home, 2 away
    int leadH, leadA, runH, runA;
    long count;
  }

  public static final class Engine {
    private Engine() {}

    private static final Pattern P_HALF = Pattern.compile("^(1st|2nd) [Hh]alf(?: -)? (.+)$");

    /** {scope, core}: scope is FT, 1H or 2H. */
    static String[] split(String name) {
      Matcher mm = P_HALF.matcher(name);
      if (mm.matches()) return new String[] {mm.group(1).equals("1st") ? "1H" : "2H", mm.group(2)};
      if (name.startsWith("Half-time ")) return new String[] {"1H", name.substring("Half-time ".length())};
      return new String[] {"FT", name};
    }

    static String family(String name) {
      String[] sp = split(name);
      String half = sp[0];
      String core = sp[1];
      if (half.equals("1H")) return "First half";
      if (half.equals("2H")) return "Second half";
      if (core.equals("1X2")) return "Full-time result";
      if (core.equals("Double Chance")) return "Double chance";
      if (core.equals("Draw No Bet") || core.equals("Home No Bet") || core.equals("Away No Bet")) return "Draw no bet";
      if (core.equals("Handicap")) return "Handicap";
      if (core.equals("Asian Handicap")) return "Asian handicap";
      if (core.equals("Over/Under") || core.equals("Asian Over/Under")) return "Total goals";
      if (core.endsWith(" Over/Under")) return "Team goals";
      if (core.equals("GG/NG") || core.equals("GG/NG 2+")) return "Both teams to score";
      if (core.equals("Odd/Even") || core.equals("Home Team Odd/Even") || core.equals("Away Team Odd/Even")) return "Odd / even";
      if (core.equals("Correct Score")) return "Correct score";
      if (core.equals("Half Time/Full Time")) return "Half time / full time";
      if (core.equals("Home Team Goals") || core.equals("Away Team Goals") || core.equals("Goal Range")
          || core.equals("Exact Goals") || core.equals("Teams to Score")
          || core.startsWith("Excluded Number of Goals")) return "Goal counts";
      if (core.equals("1st Goal") || core.equals("Last Goal") || core.contains("in a Row")
          || core.contains("to lead by") || core.contains("From Behind")) return "Goal sequence";
      if (core.startsWith("Home Team or") || core.startsWith("Away Team or") || core.startsWith("Away or")
          || core.startsWith("Draw or")) return "Combinations";
      if (core.equals("Home Team Clean Sheet") || core.equals("Away Team Clean Sheet")
          || core.equals("Home Team to Win to Nil") || core.equals("Away Team to Win to Nil")) return "Clean sheet / win to nil";
      return "Two-half specials";
    }

    // ---- scope helpers (0 = full time, 1 = first half, 2 = second half)
    static int sh(Feat f, int half) {
      return half == 0 ? f.hg : (half == 1 ? f.h1 : f.h2);
    }

    static int sa(Feat f, int half) {
      return half == 0 ? f.ag : (half == 1 ? f.a1 : f.a2);
    }

    static int tot(Feat f, int half) {
      return sh(f, half) + sa(f, half);
    }

    private static int b(boolean w) {
      return w ? 1 : 0;
    }

    private static String lower(String s) {
      return s.toLowerCase(Locale.ROOT).replace("´", "'").trim();
    }

    /** 'H' or 'A' when a team name from a market title is clearly one of the two teams. */
    static char sideOf(String name, String home, String away) {
      String n = lower(name);
      String h = lower(home);
      String a = lower(away);
      boolean isH = n.equals(h) || h.startsWith(n) || n.startsWith(h);
      boolean isA = n.equals(a) || a.startsWith(n) || n.startsWith(a);
      if (isH && !isA) return 'H';
      if (isA && !isH) return 'A';
      return 0;
    }

    static IntPred countLabel(String lbl) {
      String s = lbl.trim();
      Matcher m = Pattern.compile("^(\\d+)\\+$").matcher(s);
      if (m.matches()) {
        final int k = Integer.parseInt(m.group(1));
        return x -> x >= k;
      }
      m = Pattern.compile("^(\\d+)-(\\d+)$").matcher(s);
      if (m.matches()) {
        final int lo = Integer.parseInt(m.group(1));
        final int hi = Integer.parseInt(m.group(2));
        return x -> x >= lo && x <= hi;
      }
      m = Pattern.compile("^(\\d+)$").matcher(s);
      if (m.matches()) {
        final int k = Integer.parseInt(m.group(1));
        return x -> x == k;
      }
      return null;
    }

    private static Box ou(final int half, final boolean team, final char side, String lineText, final boolean over) {
      double L;
      try {
        L = Double.parseDouble(lineText.trim());
      } catch (RuntimeException e) {
        return null;
      }
      if (L * 2 != Math.floor(L * 2)) return null;           // quarter lines are not modelled
      final double line = L;
      final boolean whole = L == Math.floor(L);
      return f -> {
        int x = team ? (side == 'H' ? sh(f, half) : sa(f, half)) : tot(f, half);
        if (whole && x == line) return 2;
        return b(over ? x > line : x < line);
      };
    }

    private static Box yn(final String label, final Cond c) {
      if (label.equals("Yes")) return f -> b(c.test(f));
      if (label.equals("No")) return f -> b(!c.test(f));
      return null;
    }

    private static Box result(final String who, final int half) {
      switch (who) {
        case "Home": return f -> b(sh(f, half) > sa(f, half));
        case "Draw": return f -> b(sh(f, half) == sa(f, half));
        case "Away": return f -> b(sh(f, half) < sa(f, half));
        default: return null;
      }
    }

    /** The settlement rule of one outcome of one market, or null when no model exists for it. */
    static Box box(final Market mk, final Outcome o, final String H, final String A) {
      String[] sp = split(mk.name);
      final int half = sp[0].equals("FT") ? 0 : (sp[0].equals("1H") ? 1 : 2);
      final String core = sp[1];
      final String label = o.label;
      final String line = mk.line;

      // ----- result family
      if (core.equals("1X2")) return result(label, half);
      if (core.equals("Double Chance")) {
        switch (label) {
          case "Home or Draw": return f -> b(sh(f, half) >= sa(f, half));
          case "Home or Away": return f -> b(sh(f, half) != sa(f, half));
          case "Draw or Away": return f -> b(sh(f, half) <= sa(f, half));
          default: return null;
        }
      }
      if (core.equals("Handicap") && line != null) {
        String[] xy = line.split(":");
        if (xy.length != 2) return null;
        final int x;
        final int y;
        try {
          x = Integer.parseInt(xy[0].trim());
          y = Integer.parseInt(xy[1].trim());
        } catch (NumberFormatException e) {
          return null;
        }
        switch (label) {
          case "Home": return f -> b(sh(f, half) + x > sa(f, half) + y);
          case "Draw": return f -> b(sh(f, half) + x == sa(f, half) + y);
          case "Away": return f -> b(sh(f, half) + x < sa(f, half) + y);
          default: return null;
        }
      }
      if (core.equals("Asian Handicap")) {
        if (o.side != 'H' && o.side != 'A') return null;
        double L;
        try {
          L = Double.parseDouble(label.replace("+", "").trim());
        } catch (NumberFormatException e) {
          return null;
        }
        if (L * 2 != Math.floor(L * 2)) return null;         // quarter lines are not modelled
        final double lineV = L;
        final boolean whole = L == Math.floor(L);
        final boolean home = o.side == 'H';
        return f -> {
          double d = (home ? sh(f, half) - sa(f, half) : sa(f, half) - sh(f, half)) + lineV;
          if (whole && d == 0) return 2;
          return b(d > 0);
        };
      }
      if (core.equals("Draw No Bet")) {
        if (label.equals("Home")) return f -> sh(f, half) == sa(f, half) ? 2 : b(sh(f, half) > sa(f, half));
        if (label.equals("Away")) return f -> sh(f, half) == sa(f, half) ? 2 : b(sh(f, half) < sa(f, half));
        return null;
      }
      if (core.equals("Home No Bet")) {            // void when Home wins
        if (label.equals("Draw")) return f -> sh(f, half) > sa(f, half) ? 2 : b(sh(f, half) == sa(f, half));
        if (label.equals("Away")) return f -> sh(f, half) > sa(f, half) ? 2 : b(sh(f, half) < sa(f, half));
        return null;
      }
      if (core.equals("Away No Bet")) {            // void when Away wins
        if (label.equals("Home")) return f -> sh(f, half) < sa(f, half) ? 2 : b(sh(f, half) > sa(f, half));
        if (label.equals("Draw")) return f -> sh(f, half) < sa(f, half) ? 2 : b(sh(f, half) == sa(f, half));
        return null;
      }

      // ----- goals family
      if ((core.equals("Over/Under") || core.equals("Asian Over/Under")) && line != null
          && (label.equals("Over") || label.equals("Under"))) {
        return ou(half, false, (char) 0, line, label.equals("Over"));
      }
      if (core.endsWith(" Over/Under") && line != null && (label.equals("Over") || label.equals("Under"))) {
        char side = sideOf(core.substring(0, core.length() - " Over/Under".length()), H, A);
        if (side == 0) return null;
        return ou(half, true, side, line, label.equals("Over"));
      }
      if (core.equals("GG/NG")) return yn(label, f -> sh(f, half) > 0 && sa(f, half) > 0);
      if (core.equals("GG/NG 2+")) return yn(label, f -> sh(f, half) >= 2 && sa(f, half) >= 2);
      if (core.startsWith("Excluded Number of Goals")) {
        String suf = core.contains(" - ") ? core.substring(core.indexOf(" - ") + 3) : "";
        String cnt = label.startsWith("Not ") ? label.substring(4) : label;
        final IntPred cl = countLabel(cnt);
        if (cl == null) return null;
        switch (suf) {
          case "": return f -> b(!cl.test(tot(f, half)));
          case "Home": return f -> b(!cl.test(f.hg));
          case "Away": return f -> b(!cl.test(f.ag));
          case "First Half": return f -> b(!cl.test(f.h1 + f.a1));
          default: return null;
        }
      }
      if ((core.equals("Odd/Even") || core.equals("Home Team Odd/Even") || core.equals("Away Team Odd/Even")
          || core.equals("Home Team Total Goals Odd or Even") || core.equals("Away Team Total Goals Odd or Even"))
          && (label.equals("Odd") || label.equals("Even"))) {
        final boolean homeOnly = core.startsWith("Home");
        final boolean awayOnly = core.startsWith("Away");
        final boolean odd = label.equals("Odd");
        return f -> {
          int x = homeOnly ? sh(f, half) : (awayOnly ? sa(f, half) : tot(f, half));
          return b(odd ? x % 2 == 1 : x % 2 == 0);
        };
      }
      if (core.equals("Home Team Goals") || core.equals("Away Team Goals")) {
        final IntPred cl = countLabel(label);
        if (cl == null) return null;
        final boolean home = core.startsWith("Home");
        return f -> b(cl.test(home ? sh(f, half) : sa(f, half)));
      }
      if (core.equals("Exact Goals") || core.equals("Goal Range")) {
        final IntPred cl = countLabel(label);
        if (cl == null) return null;
        return f -> b(cl.test(tot(f, half)));
      }
      if (core.equals("Teams to Score")) {
        switch (label) {
          case "None": return f -> b(sh(f, half) == 0 && sa(f, half) == 0);
          case "Only Home": return f -> b(sh(f, half) > 0 && sa(f, half) == 0);
          case "Only Away": return f -> b(sh(f, half) == 0 && sa(f, half) > 0);
          case "Both teams": return f -> b(sh(f, half) > 0 && sa(f, half) > 0);
          default: return null;
        }
      }

      // ----- score based
      if (core.equals("Correct Score")) {
        final Set<String> listed = new HashSet<>();
        for (Outcome x : mk.outs) {
          if (x.label.matches("\\d+:\\d+")) listed.add(x.label);
        }
        if (label.equals("Other")) return f -> b(!listed.contains(sh(f, half) + ":" + sa(f, half)));
        Matcher m = Pattern.compile("^(\\d+):(\\d+)$").matcher(label.trim());
        if (!m.matches()) return null;
        final int hh = Integer.parseInt(m.group(1));
        final int aa = Integer.parseInt(m.group(2));
        return f -> b(sh(f, half) == hh && sa(f, half) == aa);
      }
      if (core.equals("Half Time/Full Time")) {
        String[] parts = label.split("/");
        if (parts.length != 2) return null;
        final Box ht = result(parts[0].trim(), 1);
        final Box ft = result(parts[1].trim(), 0);
        if (ht == null || ft == null) return null;
        return f -> b(ht.eval(f) == 1 && ft.eval(f) == 1);
      }

      // ----- sequence based
      if ((core.equals("1st Goal") || core.equals("Last Goal"))
          && (label.equals("Home") || label.equals("None") || label.equals("Away"))) {
        final int code = label.equals("None") ? 0 : (label.equals("Home") ? 1 : 2);
        final boolean first = core.equals("1st Goal");
        return f -> {
          int v;
          if (first) v = half == 0 ? f.first : (half == 1 ? f.first1 : f.first2);
          else v = half == 0 ? f.last : (half == 1 ? f.last1 : f.last2);
          return b(v == code);
        };
      }
      Matcher mr = Pattern.compile("^(Any|Home|Away) Team To Score (\\d) or More Goals in a Row$").matcher(core);
      if (mr.matches()) {
        final int k = Integer.parseInt(mr.group(2));
        final String who = mr.group(1);
        return yn(label, f -> (who.equals("Any") ? Math.max(f.runH, f.runA) : (who.equals("Home") ? f.runH : f.runA)) >= k);
      }
      Matcher ml = Pattern.compile("^(Any|Home|Away) Team to lead by X Goal\\(s\\) at any time$").matcher(core);
      if (ml.matches() && line != null) {
        final int k;
        try {
          k = Integer.parseInt(line.replace("by", "").trim());
        } catch (NumberFormatException e) {
          return null;
        }
        final String who = ml.group(1);
        return yn(label, f -> (who.equals("Any") ? Math.max(f.leadH, f.leadA) : (who.equals("Home") ? f.leadH : f.leadA)) >= k);
      }

      // ----- yes / no specials
      if (label.equals("Yes") || label.equals("No")) {
        if (core.equals("Goal in Both Halves")) return yn(label, f -> f.h1 + f.a1 >= 1 && f.h2 + f.a2 >= 1);
        Matcher mb = Pattern.compile("^Both Halves (Over|Under) ([\\d.]+)$").matcher(core);
        if (mb.matches()) {
          final double L = Double.parseDouble(mb.group(2));
          final boolean over = mb.group(1).equals("Over");
          return yn(label, f -> over ? (f.h1 + f.a1 > L && f.h2 + f.a2 > L) : (f.h1 + f.a1 < L && f.h2 + f.a2 < L));
        }
        if (core.equals("Home Team to Score In Both Halves")) return yn(label, f -> f.h1 > 0 && f.h2 > 0);
        if (core.equals("Away Team to Score In Both Halves")) return yn(label, f -> f.a1 > 0 && f.a2 > 0);
        if (core.equals("Home Team To Win From Behind")) return yn(label, f -> f.hg > f.ag && f.leadA >= 1);
        if (core.equals("Away Team To Win From Behind")) return yn(label, f -> f.ag > f.hg && f.leadH >= 1);
        if (core.equals("Home Team Clean Sheet")) return yn(label, f -> sa(f, half) == 0);
        if (core.equals("Away Team Clean Sheet")) return yn(label, f -> sh(f, half) == 0);
        if (core.equals("Home Team to Win Both Halves")) return yn(label, f -> f.h1 > f.a1 && f.h2 > f.a2);
        if (core.equals("Away Team to Win Both Halves")) return yn(label, f -> f.a1 > f.h1 && f.a2 > f.h2);
        if (core.equals("Home Team to Win Either Half")) return yn(label, f -> f.h1 > f.a1 || f.h2 > f.a2);
        if (core.equals("Away Team to Win Either Half")) return yn(label, f -> f.a1 > f.h1 || f.a2 > f.h2);
        if (core.equals("Home Team to Win to Nil")) return yn(label, f -> sh(f, half) > sa(f, half) && sa(f, half) == 0);
        if (core.equals("Away Team to Win to Nil")) return yn(label, f -> sa(f, half) > sh(f, half) && sh(f, half) == 0);
        if (core.equals("Both Teams to Score in Both Halves Yes/No")) {
          return yn(label, f -> f.h1 > 0 && f.a1 > 0 && f.h2 > 0 && f.a2 > 0);
        }
        Matcher mc = Pattern.compile("^(Home Team|Draw|Away Team|Away) or (Over|Under) 2\\.5$").matcher(core);
        if (mc.matches()) {
          final String who = mc.group(1);
          final boolean over = mc.group(2).equals("Over");
          return yn(label, f -> whoWins(who, f) || (over ? f.hg + f.ag > 2.5 : f.hg + f.ag < 2.5));
        }
        mc = Pattern.compile("^(Home Team|Draw|Away Team) or GG$").matcher(core);
        if (mc.matches()) {
          final String who = mc.group(1);
          return yn(label, f -> whoWins(who, f) || (f.hg > 0 && f.ag > 0));
        }
        mc = Pattern.compile("^(Home Team|Draw|Away Team) or Any Clean Sheet$").matcher(core);
        if (mc.matches()) {
          final String who = mc.group(1);
          return yn(label, f -> whoWins(who, f) || f.hg == 0 || f.ag == 0);
        }
      }
      if (core.equals("1st/2nd Half GG/NG")) {
        Matcher mt = Pattern.compile("^(Yes|No)/(Yes|No)$").matcher(label);
        if (!mt.matches()) return null;
        final boolean y1 = mt.group(1).equals("Yes");
        final boolean y2 = mt.group(2).equals("Yes");
        return f -> b(((f.h1 > 0 && f.a1 > 0) == y1) && ((f.h2 > 0 && f.a2 > 0) == y2));
      }
      if (core.equals("Highest Scoring Half")
          && (label.equals("1st half") || label.equals("2nd half") || label.equals("Equal"))) {
        return f -> {
          int x1 = f.h1 + f.a1;
          int x2 = f.h2 + f.a2;
          return b(label.equals("1st half") ? x1 > x2 : (label.equals("2nd half") ? x2 > x1 : x1 == x2));
        };
      }
      if ((core.equals("Home Team Highest Scoring Half") || core.equals("Away Team Highest Scoring Half"))
          && (label.equals("1st half") || label.equals("2nd half") || label.equals("Equal"))) {
        final boolean home = core.startsWith("Home");
        return f -> {
          int x1 = home ? f.h1 : f.a1;
          int x2 = home ? f.h2 : f.a2;
          return b(label.equals("1st half") ? x1 > x2 : (label.equals("2nd half") ? x2 > x1 : x1 == x2));
        };
      }
      return null;
    }

    private static boolean whoWins(String who, Feat f) {
      if (who.equals("Home Team")) return f.hg > f.ag;
      if (who.equals("Draw")) return f.hg == f.ag;
      return f.hg < f.ag;
    }

    /**
     * Asian handicap: the export keeps only the signed lines ("+1.5", "-1.5"). The first is the home
     * side and the second the away side; a pair is accepted only when it adds to zero and agrees with
     * the match odds (the favourite gives goals). Otherwise the pair gets no prediction.
     */
    static void prepare(SMatch sm) {
      for (Market mk : sm.markets) {
        String[] sp = split(mk.name);
        if (!sp[1].equals("Asian Handicap")) continue;
        String why = null;
        if (mk.outs.size() != 2) {
          why = "not a pair of lines";
        } else {
          double l1 = D(mk.outs.get(0).label);
          double l2 = D(mk.outs.get(1).label);
          if (Double.isNaN(l1) || Double.isNaN(l2) || Math.abs(l1 + l2) > 1e-9) {
            why = "the two lines do not add to zero";
          } else {
            String want = sp[0].equals("1H") ? "1st Half - 1X2" : (sp[0].equals("2H") ? "2nd Half - 1X2" : "1X2");
            double oh = Double.NaN;
            double oa = Double.NaN;
            for (Market m1 : sm.markets) {
              if (m1.name.equals(want) || (sp[0].equals("FT") && m1.name.equals("1X2"))) {
                for (Outcome x : m1.outs) {
                  if (x.label.equals("Home")) oh = x.odds;
                  if (x.label.equals("Away")) oa = x.odds;
                }
                if (!Double.isNaN(oh) && !Double.isNaN(oa)) break;
              }
            }
            if (!Double.isNaN(oh) && !Double.isNaN(oa) && Math.abs(Math.log(oh / oa)) > 0.1) {
              boolean homeFav = oh < oa;
              if ((homeFav && l1 > 0) || (!homeFav && l1 < 0)) why = "sides cannot be told from the match odds";
            }
          }
        }
        for (int i = 0; i < mk.outs.size(); i++) {
          Outcome x = mk.outs.get(i);
          if (why == null) x.side = i == 0 ? 'H' : 'A';
          else x.why = "Asian handicap: " + why;
        }
      }
    }
  }

  // ================================================================ model

  public static final class Params {
    public double kShrink = 5;
    public double beta = 0.10;
    public double gamma = 0.10;
    public double zeta = 0.10;
    public double halfShare = 0.45;
    public int nSim = 200000;
    /** Home advantage (home goals / away goals). NaN = worked out from the games of the run. */
    public double hfa = Double.NaN;
    public double hfaDefault = 1.3313432835820898;
    public int hfaMinGames = 60;
  }

  /** One past game seen from one team's side. */
  static final class Row {
    char venue;                       // H or A
    int gf, ga;
    String key, date, formOwn, formOpp;
    final double[] sf = new double[5];    // for:     goals, xG, shots on target, shots, corners
    final double[] sa = new double[5];    // against
    char res;                         // W, D, L
  }

  static final int G = 0, XG = 1, SOT = 2, SH = 3, CO = 4;
  static final double[] W_G1 = {0.30, 0.40, 0.20, 0.10, 0};
  static final double[] W_G2 = {0.50, 0, 0.30, 0.20, 0};

  static final class SideStat {
    int n;
    final double[] rawFor = new double[5];
    final double[] rawAgainst = new double[5];
    final int[] nStat = new int[5];
    double ppg;
    double shotShare = Double.NaN;
  }

  public static final class Fixture {
    public int no;
    public SMatch sm;
    public FMatch fm;
    public String matchedBy = "";
    public double lamH, lamA, eTotal, e1h, hfa, mu;
    public String group = "";
    public String formSource = "";
    public String formH = "", formA = "";
    public int nH, nA, droppedH, droppedA;
    public final LinkedHashMap<String, Double> comp = new LinkedHashMap<>();
    public final List<String> notes = new ArrayList<>();
    public final List<String> scores = new ArrayList<>();      // most likely full-time scores
    public int nSupported, nUnsupported;
    public double pHome, pDraw, pAway, pOver25, pBtts;
    public boolean ok;
    public String error = "";
  }

  private static final class Rng {
    long s;

    Rng(long seed) {
      s = seed * 0x9E3779B97F4A7C15L + 0x2545F4914F6CDD1DL;
      if (s == 0) s = 88172645463325252L;
      for (int i = 0; i < 8; i++) next();
    }

    long next() {
      s ^= s >>> 12;
      s ^= s << 25;
      s ^= s >>> 27;
      return s * 0x2545F4914F6CDD1DL;
    }

    double nextDouble() {
      return (next() >>> 11) * (1.0 / (1L << 53));
    }
  }

  private static int poisson(Rng r, double lam) {
    double l = Math.exp(-lam);
    double p = 1;
    int k = 0;
    do {
      k++;
      p *= r.nextDouble();
    } while (p > l);
    return k - 1;
  }

  // ---- the window of one team: its past games, newest first, from before the match
  private static List<Row> window(FMatch fm, boolean home, int[] dropped, String[] notes) {
    String team = home ? fm.teamHome : fm.teamAway;
    List<Game> games = home ? fm.lastHome : fm.lastAway;
    List<Row> out = new ArrayList<>();
    int unresolved = 0;
    int unread = 0;
    for (Game g : games) {
      if (g.date == null || g.hg < 0 || g.ag < 0 || g.home.isEmpty()) {
        unread++;
        continue;
      }
      if (!fm.date.isEmpty() && g.date.compareTo(fm.date) >= 0) {
        dropped[0]++;
        continue;
      }
      double sh = Names.sim(team, g.home);
      double sa = Names.sim(team, g.away);
      boolean teamHome;
      if (sh > sa) teamHome = true;
      else if (sa > sh) teamHome = false;
      else if (g.res == 'W' && g.hg != g.ag) teamHome = g.hg > g.ag;
      else if (g.res == 'L' && g.hg != g.ag) teamHome = g.hg < g.ag;
      else {
        unresolved++;
        continue;
      }
      Row r = new Row();
      r.venue = teamHome ? 'H' : 'A';
      r.gf = teamHome ? g.hg : g.ag;
      r.ga = teamHome ? g.ag : g.hg;
      r.key = g.key();
      r.date = g.date;
      r.formOwn = teamHome ? g.formHome : g.formAway;
      r.formOpp = teamHome ? g.formAway : g.formHome;
      int ix = teamHome ? 0 : 1;
      r.sf[G] = r.gf;
      r.sa[G] = r.ga;
      double[][] pairs = {g.xg, g.sot, g.shots, g.corners};
      int[] idx = {XG, SOT, SH, CO};
      for (int k = 0; k < pairs.length; k++) {
        double a = pairs[k][ix];
        double b = pairs[k][1 - ix];
        if (Double.isNaN(a) || Double.isNaN(b)) {
          r.sf[idx[k]] = Double.NaN;
          r.sa[idx[k]] = Double.NaN;
        } else {
          r.sf[idx[k]] = a;
          r.sa[idx[k]] = b;
        }
      }
      r.res = r.gf > r.ga ? 'W' : (r.gf == r.ga ? 'D' : 'L');
      out.add(r);
    }
    if (unresolved > 0) notes[0] += (home ? "Home" : "Away") + ": " + unresolved + " game(s) skipped (team not found in the game). ";
    if (unread > 0) notes[0] += (home ? "Home" : "Away") + ": " + unread + " game(s) skipped (date or score not read). ";
    return out;
  }

  private static double meanSkipNaN(List<Row> w, int stat, boolean forSide, int[] nOut) {
    double s = 0;
    int n = 0;
    for (Row r : w) {
      double v = forSide ? r.sf[stat] : r.sa[stat];
      if (!Double.isNaN(v)) {
        s += v;
        n++;
      }
    }
    nOut[0] = n;
    return n == 0 ? Double.NaN : s / n;
  }

  private static double shrink(double ratio, double n, double k) {
    return (n * ratio + k) / (n + k);
  }

  private static SideStat sideStat(List<Row> w) {
    SideStat d = new SideStat();
    d.n = w.size();
    int[] n1 = new int[1];
    int[] n2 = new int[1];
    for (int k = 0; k < 5; k++) {
      d.rawFor[k] = meanSkipNaN(w, k, true, n1);
      d.rawAgainst[k] = meanSkipNaN(w, k, false, n2);
      d.nStat[k] = Math.min(n1[0], n2[0]);
    }
    double pts = 0;
    for (Row r : w) pts += r.res == 'W' ? 3 : (r.res == 'D' ? 1 : 0);
    d.ppg = d.n == 0 ? 0 : pts / d.n;
    double sf = d.rawFor[SH];
    double sa = d.rawAgainst[SH];
    if (!Double.isNaN(sf) && !Double.isNaN(sa) && sf + sa > 0) d.shotShare = sf / (sf + sa);
    return d;
  }

  /** Mean over the distinct games of both windows, for and against together. */
  private static double baseline(List<Row> all, int stat) {
    Set<String> seen = new HashSet<>();
    double s = 0;
    int n = 0;
    for (Row r : all) {
      if (!seen.add(r.key)) continue;
      if (!Double.isNaN(r.sf[stat])) {
        s += r.sf[stat];
        n++;
      }
      if (!Double.isNaN(r.sa[stat])) {
        s += r.sa[stat];
        n++;
      }
    }
    return n == 0 ? Double.NaN : s / n;
  }

  /** {AP, DX}: attack and goals-allowed measures as shrunk ratios, weighted over the stats that exist. */
  private static double[] apdx(SideStat d, double[] base, double[] weights, double k) {
    double sw = 0;
    double ap = 0;
    double dx = 0;
    for (int s = 0; s < 5; s++) {
      double wt = weights[s];
      if (wt <= 0) continue;
      if (Double.isNaN(base[s]) || Double.isNaN(d.rawFor[s]) || d.nStat[s] < 4) continue;
      sw += wt;
      ap += wt * shrink(d.rawFor[s] / base[s], d.nStat[s], k);
      dx += wt * shrink(d.rawAgainst[s] / base[s], d.nStat[s], k);
    }
    if (sw == 0) return new double[] {1.0, 1.0};
    return new double[] {ap / sw, dx / sw};
  }

  private static double[] pressure(SideStat d, double[] base, double k) {
    if (Double.isNaN(base[CO]) || Double.isNaN(d.rawFor[CO]) || d.nStat[CO] < 4) return new double[] {1.0, 1.0};
    return new double[] {shrink(d.rawFor[CO] / base[CO], d.nStat[CO], k),
        shrink(d.rawAgainst[CO] / base[CO], d.nStat[CO], k)};
  }

  private static double classDom(SideStat d, double ppgAll, double k) {
    double c = ppgAll > 0 ? shrink(d.ppg / ppgAll, d.n, k) : 1.0;
    if (!Double.isNaN(d.shotShare)) {
      double dom = shrink(2 * d.shotShare, d.n, k);
      return 0.5 * c + 0.5 * dom;
    }
    return c;
  }

  private static String modal(List<String> vals) {
    LinkedHashMap<String, Integer> c = new LinkedHashMap<>();
    for (String v : vals) {
      if (v == null) continue;
      c.put(v, c.containsKey(v) ? c.get(v) + 1 : 1);
    }
    String best = null;
    int bn = 0;
    for (Map.Entry<String, Integer> e : c.entrySet()) {
      if (e.getValue() > bn) {
        best = e.getKey();
        bn = e.getValue();
      }
    }
    return best;
  }

  private static double mean(List<Double> v) {
    double s = 0;
    for (double x : v) s += x;
    return s / v.size();
  }

  /** Formation Effect (spec 2.4): {attack, defence, nKnown}. Neutral 1.0 when the games do not say. */
  private static double[] formationEffect(List<Row> w, String fOwn, String gOpp, double[] weights, double k) {
    List<Row> known = new ArrayList<>();
    for (Row r : w) {
      if (r.formOwn != null && r.formOpp != null) known.add(r);
    }
    if (known.size() < 4 || fOwn == null || gOpp == null) return new double[] {1.0, 1.0, known.size()};
    List<Row> own = new ArrayList<>();
    List<Row> vs = new ArrayList<>();
    for (Row r : known) {
      if (r.formOwn.equals(fOwn)) own.add(r);
      if (r.formOpp.equals(gOpp)) vs.add(r);
    }
    double aOwn = fcomp(known, own, 0, weights, k);
    double aVs = fcomp(known, vs, 0, weights, k);
    double dOwn = fcomp(known, own, 1, weights, k);
    double dVs = fcomp(known, vs, 1, weights, k);
    return new double[] {Math.sqrt(aOwn * aVs), Math.sqrt(dOwn * dVs), known.size()};
  }

  private static double fcomp(List<Row> known, List<Row> sub, int idx, double[] weights, double k) {
    double sw = 0;
    double acc = 0;
    for (int s = 0; s < 5; s++) {
      double wt = weights[s];
      if (wt <= 0) continue;
      List<Double> base = new ArrayList<>();
      for (Row r : known) {
        double v = idx == 0 ? r.sf[s] : r.sa[s];
        if (!Double.isNaN(v)) base.add(v);
      }
      List<Double> sv = new ArrayList<>();
      for (Row r : sub) {
        double v = idx == 0 ? r.sf[s] : r.sa[s];
        if (!Double.isNaN(v)) sv.add(v);
      }
      if (base.size() < 4 || sv.isEmpty() || mean(base) <= 0) continue;
      sw += wt;
      acc += wt * shrink(mean(sv) / mean(base), sv.size(), k);
    }
    return sw == 0 ? 1.0 : acc / sw;
  }

  /** Home advantage worked out from all the distinct past games of the run. */
  public static double homeAdvantage(List<FMatch> all, Params p) {
    Set<String> seen = new HashSet<>();
    double sh = 0;
    double sa = 0;
    int n = 0;
    for (FMatch fm : all) {
      for (int side = 0; side < 2; side++) {
        for (Game g : side == 0 ? fm.lastHome : fm.lastAway) {
          if (g.date == null || g.hg < 0 || g.ag < 0 || g.home.isEmpty()) continue;
          if (!seen.add(g.key())) continue;
          sh += g.hg;
          sa += g.ag;
          n++;
        }
      }
    }
    if (n < p.hfaMinGames || sa <= 0) return p.hfaDefault;
    return sh / sa;
  }

  /** Runs the whole model for one fixture and fills the outcome chances of its SportyBet markets. */
  public static Fixture simulate(FMatch fm, SMatch sm, Params p, double hfa, long seed) {
    Fixture fx = new Fixture();
    fx.fm = fm;
    fx.sm = sm;
    fx.no = sm.no;
    fx.hfa = hfa;
    try {
      run(fx, p, hfa, seed);
      fx.ok = true;
    } catch (RuntimeException e) {
      fx.ok = false;
      fx.error = String.valueOf(e);
    }
    return fx;
  }

  private static void run(Fixture fx, Params p, double hfa, long seed) {
    FMatch fm = fx.fm;
    SMatch sm = fx.sm;
    int[] dh = new int[1];
    int[] da = new int[1];
    String[] nt = {""};
    List<Row> wh = window(fm, true, dh, nt);
    List<Row> wa = window(fm, false, da, nt);
    fx.droppedH = dh[0];
    fx.droppedA = da[0];
    fx.nH = wh.size();
    fx.nA = wa.size();
    if (!nt[0].isEmpty()) fx.notes.add(nt[0].trim());
    if (wh.size() < 3 || wa.size() < 3) {
      throw new IllegalStateException("Not enough past games (home " + wh.size() + ", away " + wa.size() + ").");
    }
    List<Row> all = new ArrayList<>(wh);
    all.addAll(wa);

    // group: full data (xG there) or basic stats
    int xh = 0;
    int xa = 0;
    for (Row r : wh) if (!Double.isNaN(r.sf[XG])) xh++;
    for (Row r : wa) if (!Double.isNaN(r.sf[XG])) xa++;
    boolean full = xh >= 4 && xa >= 4;
    fx.group = full ? "Full data (xG)" : "Basic stats (no xG)";
    double[] weights = full ? W_G1 : W_G2;

    double[] base = new double[5];
    for (int s = 0; s < 5; s++) base[s] = baseline(all, s);
    double ppgAll = 0;
    for (Row r : all) ppgAll += r.res == 'W' ? 3 : (r.res == 'D' ? 1 : 0);
    ppgAll /= all.size();

    SideStat h = sideStat(wh);
    SideStat a = sideStat(wa);
    double mu = base[G];
    double[] ah = apdx(h, base, weights, p.kShrink);
    double[] aa = apdx(a, base, weights, p.kShrink);
    double[] ph = pressure(h, base, p.kShrink);
    double[] pa = pressure(a, base, p.kShrink);
    double ch = classDom(h, ppgAll, p.kShrink);
    double ca = classDom(a, ppgAll, p.kShrink);
    double pfH = Math.pow(ph[0] * pa[1], p.beta);
    double pfA = Math.pow(pa[0] * ph[1], p.beta);
    double cfH = Math.pow(ch / ca, p.gamma);
    double cfA = Math.pow(ca / ch, p.gamma);

    // formations: the lineup of the match when the page has it, else the most used one of the last 10
    String fH;
    String fA;
    if (fm.mainFormHome != null && fm.mainFormAway != null) {
      fH = fm.mainFormHome;
      fA = fm.mainFormAway;
      fx.formSource = "lineup of the match";
    } else {
      List<String> oh = new ArrayList<>();
      List<String> oa = new ArrayList<>();
      for (Row r : wh) oh.add(r.formOwn);
      for (Row r : wa) oa.add(r.formOwn);
      fH = modal(oh);
      fA = modal(oa);
      fx.formSource = (fH != null || fA != null) ? "usual formation (most used in last 10)" : "none found";
    }
    fx.formH = fH == null ? "" : fH;
    fx.formA = fA == null ? "" : fA;
    double[] feH = formationEffect(wh, fH, fA, weights, p.kShrink);
    double[] feA = formationEffect(wa, fA, fH, weights, p.kShrink);
    double ffH = Math.pow(feH[0] * feA[1], p.zeta);
    double ffA = Math.pow(feA[0] * feH[1], p.zeta);

    double sq = Math.sqrt(hfa);
    double lamH = mu * ah[0] * aa[1] * sq * pfH * cfH * ffH;
    double lamA = mu * aa[0] * ah[1] / sq * pfA * cfA * ffA;
    fx.lamH = lamH;
    fx.lamA = lamA;
    fx.mu = mu;
    fx.eTotal = lamH + lamA;
    fx.e1h = fx.eTotal * p.halfShare;
    fx.comp.put("Attack Potency home", ah[0]);
    fx.comp.put("Attack Potency away", aa[0]);
    fx.comp.put("Defensive Solidity home", 1 / ah[1]);
    fx.comp.put("Defensive Solidity away", 1 / aa[1]);
    fx.comp.put("Pressure & Wing home", ph[0]);
    fx.comp.put("Pressure & Wing away", pa[0]);
    fx.comp.put("Class & Dominance home", ch);
    fx.comp.put("Class & Dominance away", ca);
    fx.comp.put("Formation Effect home", ffH);
    fx.comp.put("Formation Effect away", ffA);
    fx.comp.put("Opponent Strength", 1.0);
    if (fx.droppedH + fx.droppedA > 0) {
      fx.notes.add((fx.droppedH + fx.droppedA) + " game(s) dated on or after kick-off were left out.");
    }
    fx.notes.add("Opponent Strength: no league tables in the data, so it is neutral (1.00).");

    // ---- simulate
    Rng rng = new Rng(seed);
    int n = p.nSim;
    long[] keys = new long[n];
    double[] tt = new double[64];
    int[] ll = new int[64];
    final int CLIP = 12;
    for (int s = 0; s < n; s++) {
      int h1 = Math.min(poisson(rng, lamH * p.halfShare), CLIP);
      int a1 = Math.min(poisson(rng, lamA * p.halfShare), CLIP);
      int h2 = Math.min(poisson(rng, lamH * (1 - p.halfShare)), CLIP);
      int a2 = Math.min(poisson(rng, lamA * (1 - p.halfShare)), CLIP);
      int n1 = h1 + a1;
      int cnt = 0;
      for (int k = 0; k < h1; k++) {
        tt[cnt] = rng.nextDouble();
        ll[cnt++] = 1;
      }
      for (int k = 0; k < a1; k++) {
        tt[cnt] = rng.nextDouble();
        ll[cnt++] = 2;
      }
      sortSmall(tt, ll, 0, n1);
      for (int k = 0; k < h2; k++) {
        tt[cnt] = rng.nextDouble();
        ll[cnt++] = 1;
      }
      for (int k = 0; k < a2; k++) {
        tt[cnt] = rng.nextDouble();
        ll[cnt++] = 2;
      }
      sortSmall(tt, ll, n1, cnt);
      int first = 0, first1 = 0, first2 = 0, last = 0, last1 = 0, last2 = 0;
      int d = 0, maxD = 0, minD = 0, curT = 0, curL = 0, runH = 0, runA = 0;
      for (int k = 0; k < cnt; k++) {
        int lab = ll[k];
        boolean f1 = k < n1;
        if (first == 0) first = lab;
        last = lab;
        if (f1) {
          if (first1 == 0) first1 = lab;
          last1 = lab;
        } else {
          if (first2 == 0) first2 = lab;
          last2 = lab;
        }
        d += lab == 1 ? 1 : -1;
        if (d > maxD) maxD = d;
        if (d < minD) minD = d;
        if (lab == curT) {
          curL++;
        } else {
          curL = 1;
          curT = lab;
        }
        if (lab == 1) runH = Math.max(runH, curL);
        else runA = Math.max(runA, curL);
      }
      long key = h1;
      key = (key << 4) | a1;
      key = (key << 4) | h2;
      key = (key << 4) | a2;
      key = (key << 2) | first;
      key = (key << 2) | first1;
      key = (key << 2) | first2;
      key = (key << 2) | last;
      key = (key << 2) | last1;
      key = (key << 2) | last2;
      key = (key << 5) | maxD;
      key = (key << 5) | (-minD);
      key = (key << 5) | runH;
      key = (key << 5) | runA;
      keys[s] = key;
    }
    Arrays.sort(keys);
    List<Feat> feats = new ArrayList<>();
    int i = 0;
    while (i < n) {
      int j = i;
      while (j < n && keys[j] == keys[i]) j++;
      feats.add(decode(keys[i], j - i));
      i = j;
    }

    // ---- headline numbers and likely scores
    double pH = 0, pD = 0, pAw = 0, p25 = 0, pB = 0;
    Map<String, Long> sc = new HashMap<>();
    for (Feat f : feats) {
      if (f.hg > f.ag) pH += f.count;
      else if (f.hg == f.ag) pD += f.count;
      else pAw += f.count;
      if (f.hg + f.ag > 2.5) p25 += f.count;
      if (f.hg > 0 && f.ag > 0) pB += f.count;
      String k = f.hg + "-" + f.ag;
      sc.put(k, (sc.containsKey(k) ? sc.get(k) : 0L) + f.count);
    }
    fx.pHome = pH / n;
    fx.pDraw = pD / n;
    fx.pAway = pAw / n;
    fx.pOver25 = p25 / n;
    fx.pBtts = pB / n;
    List<Map.Entry<String, Long>> es = new ArrayList<>(sc.entrySet());
    es.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
    for (int k = 0; k < Math.min(6, es.size()); k++) {
      fx.scores.add(es.get(k).getKey() + "  " + String.format(Locale.US, "%.1f%%", 100.0 * es.get(k).getValue() / n));
    }

    // ---- every market of the SportyBet match
    String H = sm.home;
    String A = sm.away;
    for (Market mk : sm.markets) {
      boolean allOk = mk.outs.size() >= 2;
      double sumQ = 0;
      double sumInv = 0;
      for (Outcome o : mk.outs) {
        sumInv += 1.0 / o.odds;
        Box bx = Engine.box(mk, o, H, A);
        if (bx == null) {
          if (o.why.isEmpty()) o.why = "no model for this market yet";
          o.q = Double.NaN;
          allOk = false;
          fx.nUnsupported++;
          continue;
        }
        long win = 0;
        long vd = 0;
        for (Feat f : feats) {
          int r = bx.eval(f);
          if (r == 1) win += f.count;
          else if (r == 2) vd += f.count;
        }
        o.pvoid = (double) vd / n;
        o.q = o.pvoid < 1 ? ((double) win / n) / (1 - o.pvoid) : Double.NaN;
        sumQ += Double.isNaN(o.q) ? 0 : o.q;
        fx.nSupported++;
      }
      // the bookmaker's chance: the odds' shares of one, scaled to the total mass of the market
      if (allOk && sumInv > 0) {
        for (Outcome o : mk.outs) o.book = (1.0 / o.odds) / sumInv * sumQ;
      }
      Outcome best = null;
      for (Outcome o : mk.outs) {
        if (!Double.isNaN(o.q) && (best == null || o.q > best.q)) best = o;
      }
      if (best != null && allOk) best.pick = true;
    }
  }

  private static void sortSmall(double[] t, int[] l, int from, int to) {
    for (int i = from + 1; i < to; i++) {
      double tv = t[i];
      int lv = l[i];
      int j = i - 1;
      while (j >= from && t[j] > tv) {
        t[j + 1] = t[j];
        l[j + 1] = l[j];
        j--;
      }
      t[j + 1] = tv;
      l[j + 1] = lv;
    }
  }

  private static Feat decode(long key, int count) {
    Feat f = new Feat();
    f.count = count;
    long k = key;
    f.runA = (int) (k & 31);
    k >>>= 5;
    f.runH = (int) (k & 31);
    k >>>= 5;
    f.leadA = (int) (k & 31);
    k >>>= 5;
    f.leadH = (int) (k & 31);
    k >>>= 5;
    f.last2 = (int) (k & 3);
    k >>>= 2;
    f.last1 = (int) (k & 3);
    k >>>= 2;
    f.last = (int) (k & 3);
    k >>>= 2;
    f.first2 = (int) (k & 3);
    k >>>= 2;
    f.first1 = (int) (k & 3);
    k >>>= 2;
    f.first = (int) (k & 3);
    k >>>= 2;
    f.a2 = (int) (k & 15);
    k >>>= 4;
    f.h2 = (int) (k & 15);
    k >>>= 4;
    f.a1 = (int) (k & 15);
    k >>>= 4;
    f.h1 = (int) (k & 15);
    f.hg = f.h1 + f.h2;
    f.ag = f.a1 + f.a2;
    return f;
  }

  // ================================================================ joining Flashscore and SportyBet

  public static final class Pair {
    public FMatch fm;
    public SMatch sm;       // null: no SportyBet match found
    public String how = "";
  }

  /**
   * Flashscore favorite number i goes with SportyBet line i (the line number is the first key); the
   * team names are the second check. When the names do not agree, the matching SportyBet match is
   * looked for by name. A match with no partner is returned with sm = null.
   */
  public static List<Pair> join(List<FMatch> fms, List<SMatch> sms) {
    List<Pair> out = new ArrayList<>();
    boolean[] used = new boolean[sms.size()];
    for (int i = 0; i < fms.size(); i++) {
      FMatch fm = fms.get(i);
      Pair p = new Pair();
      p.fm = fm;
      int at = fm.favLine > 0 ? fm.favLine - 1 : i;
      if (at >= 0 && at < sms.size() && !used[at] && fits(fm, sms.get(at)) >= Names.OK) {
        p.sm = sms.get(at);
        used[at] = true;
        p.how = "line " + (at + 1) + (bothAgree(fm, sms.get(at)) ? " + both team names agree" : " + one team name agrees (the other is written differently)");
      } else {
        int bestIdx = -1;
        double best = 0;
        for (int k = 0; k < sms.size(); k++) {
          if (used[k]) continue;
          double sc = fits(fm, sms.get(k));
          if (sc > best) {
            best = sc;
            bestIdx = k;
          }
        }
        if (bestIdx >= 0 && best >= Names.OK) {
          p.sm = sms.get(bestIdx);
          used[bestIdx] = true;
          p.how = "by team names (SportyBet line " + (bestIdx + 1) + ")" + (bothAgree(fm, sms.get(bestIdx)) ? "" : ", one team name differs");
        } else {
          p.how = "no SportyBet match with these team names";
        }
      }
      out.add(p);
    }
    return out;
  }

  private static double fits(FMatch fm, SMatch sm) {
    String fh = fm.home;
    String fa = fm.away;
    if ((fh.isEmpty() || fa.isEmpty()) && fm.favName.contains(" - ")) {
      String[] t = fm.favName.split(" - ", 2);
      fh = t[0];
      fa = t[1];
    }
    double sh = Names.sim(fh, sm.home);
    double sa = Names.sim(fa, sm.away);
    if (Math.min(sh, sa) >= Names.OK) return Math.min(sh, sa);
    // one team agrees clearly and the other is written differently (CT United / Connecticut FC):
    // a team plays once in a list, so this is accepted, and said so
    if (Math.max(sh, sa) >= 0.85) return 0.72;
    return Math.min(sh, sa);
  }

  private static boolean bothAgree(FMatch fm, SMatch sm) {
    return Names.pair(fm.home, fm.away, sm.home, sm.away) >= Names.OK;
  }

  /**
   * One fixture on its own, as soon as its match is scraped: the home advantage is worked out from
   * the matches scraped so far (seen). The pipeline runs the finished ones again at the end with the
   * home advantage of the whole run (rerun), so the result equals a run of all matches at once.
   */
  public static Fixture runOne(Pair x, List<FMatch> seen, Params p) {
    double hfa = Double.isNaN(p.hfa) ? homeAdvantage(seen, p) : p.hfa;
    Fixture fx = simulate(x.fm, x.sm, p, hfa, 1000 + x.sm.no);
    fx.matchedBy = x.how;
    return fx;
  }

  /** The home advantage of a whole run. */
  public static double runHfa(List<FMatch> all, Params p) {
    return Double.isNaN(p.hfa) ? homeAdvantage(all, p) : p.hfa;
  }

  /** The same fixture again with another home advantage. */
  public static Fixture rerun(Fixture old, Params p, double hfa) {
    Fixture fx = simulate(old.fm, old.sm, p, hfa, 1000 + old.sm.no);
    fx.matchedBy = old.matchedBy;
    return fx;
  }

  /** Runs every pair that has a SportyBet match. */
  public interface Progress {
    void on(int done, int total, String text);
  }

  public static List<Fixture> runAll(List<Pair> pairs, Params p, Progress prog) {
    List<FMatch> all = new ArrayList<>();
    for (Pair x : pairs) all.add(x.fm);
    double hfa = Double.isNaN(p.hfa) ? homeAdvantage(all, p) : p.hfa;
    List<Fixture> out = new ArrayList<>();
    int total = 0;
    for (Pair x : pairs) if (x.sm != null) total++;
    int done = 0;
    for (Pair x : pairs) {
      if (x.sm == null) continue;
      if (prog != null) prog.on(done, total, x.sm.home + " v " + x.sm.away);
      Fixture fx = simulate(x.fm, x.sm, p, hfa, 1000 + x.sm.no);
      fx.matchedBy = x.how;
      out.add(fx);
      done++;
      if (prog != null) prog.on(done, total, x.sm.home + " v " + x.sm.away);
    }
    return out;
  }

  // ================================================================ export

  public static String resultsJson(List<Fixture> fxs, Params p) {
    List<Object> ms = new ArrayList<>();
    for (Fixture fx : fxs) {
      LinkedHashMap<String, Object> m = new LinkedHashMap<>();
      m.put("line", fx.no);
      m.put("home", fx.sm.home);
      m.put("away", fx.sm.away);
      m.put("league", fx.sm.league);
      m.put("date", fx.sm.date);
      m.put("time", fx.sm.time);
      m.put("matched", fx.matchedBy);
      m.put("ok", fx.ok);
      if (!fx.ok) {
        m.put("error", fx.error);
        ms.add(m);
        continue;
      }
      m.put("group", fx.group);
      m.put("lambda_home", fx.lamH);
      m.put("lambda_away", fx.lamA);
      m.put("expected_total", fx.eTotal);
      m.put("expected_first_half", fx.e1h);
      m.put("home_advantage", fx.hfa);
      m.put("formations", fx.formH + " v " + fx.formA + " (" + fx.formSource + ")");
      LinkedHashMap<String, Object> c = new LinkedHashMap<>(fx.comp);
      m.put("components", c);
      m.put("likely_scores", new ArrayList<Object>(fx.scores));
      List<Object> mks = new ArrayList<>();
      for (Market mk : fx.sm.markets) {
        for (Outcome o : mk.outs) {
          LinkedHashMap<String, Object> r = new LinkedHashMap<>();
          r.put("market", mk.name);
          if (mk.line != null) r.put("line", mk.line);
          r.put("outcome", o.label);
          r.put("odds", o.odds);
          r.put("model", o.q);
          r.put("book", o.book);
          r.put("pick", o.pick);
          if (!o.why.isEmpty()) r.put("note", o.why);
          mks.add(r);
        }
      }
      m.put("markets", mks);
      ms.add(m);
    }
    LinkedHashMap<String, Object> root = new LinkedHashMap<>();
    root.put("version", VERSION);
    root.put("simulations_per_match", p.nSim);
    root.put("matches", ms);
    return Json.write(root);
  }
}

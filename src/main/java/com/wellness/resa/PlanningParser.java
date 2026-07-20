package com.wellness.resa;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrait les séances du graphe d'objets JavaScript renvoyé par
 * OnlineRemote.initializePlanning. Logique portée depuis la version Python,
 * validée contre la réponse réelle de la capture HAR.
 */
public final class PlanningParser {

    private static final Pattern ID_TO_VAR   = Pattern.compile("s0\\['(\\d+)'\\]=s(\\d+);");
    private static final Pattern BEGIN_DATE  = Pattern.compile("s(\\d+)\\.beginDate=new Date\\((\\d+)\\);");
    private static final Pattern ACT_VAR     = Pattern.compile("s(\\d+)\\.activityIdList=s(\\d+);");
    private static final Pattern ARR_INLINE  = Pattern.compile("var s(\\d+)=\\[(\\d[\\d,]*)\\];");
    private static final Pattern ARR_INDEXED = Pattern.compile("s(\\d+)\\[0\\]=(\\d+);");

    /**
     * Objet "activité" : sur une même ligne, {@code s<v>.id=<activityId>} puis {@code s<v>.name="<nom>"},
     * l'ancre {@code templatefamilyId} garantissant qu'il s'agit bien d'une activité (et non d'une
     * séance, d'un coach ou d'une salle, qui n'ont pas ce champ). C'est cette table qui permet de
     * retrouver le NOM du cours à partir de l'activityId — et donc de réserver par libellé.
     */
    private static final Pattern ACTIVITY_DEF = Pattern.compile(
            "s(\\d+)\\.id=(\\d+);.*?s\\1\\.name=\"((?:[^\"\\\\]|\\\\.)*)\";.*?s\\1\\.templatefamilyId=");

    private PlanningParser() {}

    public static Map<Long, SessionInfo> parse(String txt) {
        Map<String, String> idToVar = new HashMap<>();
        Matcher m = ID_TO_VAR.matcher(txt);
        while (m.find()) idToVar.put(m.group(1), m.group(2));

        Map<String, Long> begin = new HashMap<>();
        m = BEGIN_DATE.matcher(txt);
        while (m.find()) begin.put(m.group(1), Long.parseLong(m.group(2)));

        Map<String, String> actVar = new HashMap<>();
        m = ACT_VAR.matcher(txt);
        while (m.find()) actVar.put(m.group(1), m.group(2));

        Map<String, String> arrInline = new HashMap<>();
        m = ARR_INLINE.matcher(txt);
        while (m.find()) arrInline.put(m.group(1), m.group(2));

        Map<String, String> arrIndexed = new HashMap<>();
        m = ARR_INDEXED.matcher(txt);
        while (m.find()) arrIndexed.putIfAbsent(m.group(1), m.group(2));

        // Table activityId -> nom du cours (ex. 10484 -> "Aquabiking Gambetta").
        Map<Integer, String> nameByActivityId = new HashMap<>();
        m = ACTIVITY_DEF.matcher(txt);
        while (m.find()) {
            nameByActivityId.putIfAbsent(Integer.parseInt(m.group(2)), unescapeJs(m.group(3)));
        }

        Map<Long, SessionInfo> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : idToVar.entrySet()) {
            String var = e.getValue();
            Long beginMs = begin.get(var);
            Integer activityId = null;
            if (actVar.containsKey(var)) {
                String arr = actVar.get(var);
                if (arrInline.containsKey(arr)) {
                    activityId = Integer.parseInt(arrInline.get(arr).split(",")[0]);
                } else if (arrIndexed.containsKey(arr)) {
                    activityId = Integer.parseInt(arrIndexed.get(arr));
                }
            }
            String activityName = activityId == null ? null : nameByActivityId.get(activityId);
            out.put(Long.parseLong(e.getKey()), new SessionInfo(beginMs, activityId, activityName));
        }
        return out;
    }

    /** Décode les échappements JavaScript d'une chaîne (\\uXXXX, \\', \\", ...). */
    static String unescapeJs(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 'u' && i + 5 < s.length()) {
                    b.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    i += 5;
                } else {
                    switch (n) {
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        default:  b.append(n);
                    }
                    i++;
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}

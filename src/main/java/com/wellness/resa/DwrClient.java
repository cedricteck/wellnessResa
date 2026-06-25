package com.wellness.resa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client bas niveau de l'interface DWR (onlineV2) de Resamania.
 * Une instance = une session (cookie JSESSIONID + scriptSessionId).
 * On en crée une neuve à chaque run de réservation.
 */
public class DwrClient {

    private static final Logger log = LoggerFactory.getLogger(DwrClient.class);
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final String PLANNING_PAGE = "/onlineV2/index.html";

    private final ResaProperties props;
    private final HttpClient http;
    private final CookieManager cookies;
    private final String scriptSessionId;
    private String httpSessionId;
    private int batch = 0;

    public DwrClient(ResaProperties props) {
        this.props = props;
        this.cookies = new CookieManager();
        this.cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        this.http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                // Respecte le proxy système (-Dhttps.proxyHost / -Dhttps.proxyPort,
                // ou les variables d'environnement). Sans cette ligne, le client
                // HTTP du JDK ignore tout proxy, ce qui provoque un timeout de
                // connexion sur les réseaux d'entreprise.
                .proxy(ProxySelector.getDefault())
                .build();
        this.scriptSessionId = newScriptSessionId();
    }

    /**
     * Génère un scriptSessionId au format DWR (32 hex + identifiant de page).
     * Pour les appels 'plaincall', le serveur ne le lie en général pas à la
     * sécurité (c'est JSESSIONID qui authentifie). Si la connexion est refusée
     * malgré des identifiants corrects, c'est le premier endroit à inspecter.
     */
    static String newScriptSessionId() {
        StringBuilder sb = new StringBuilder();
        String hex = "0123456789ABCDEF";
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 32; i++) sb.append(hex.charAt(r.nextInt(16)));
        sb.append(100 + r.nextInt(900));
        return sb.toString();
    }

    /** Charge la page de login pour obtenir le cookie de session JSESSIONID. */
    public void bootstrap() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/login/"))
                .header("User-Agent", UA)
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        http.send(req, HttpResponse.BodyHandlers.ofString());
        for (HttpCookie c : cookies.getCookieStore().getCookies()) {
            if ("JSESSIONID".equals(c.getName())) {
                httpSessionId = c.getValue();
            }
        }
        if (httpSessionId == null) {
            throw new DwrException("Cookie JSESSIONID introuvable au démarrage.");
        }
        log.info("Session initialisée.");
    }

    // ------------------------------------------------------------------
    // Appel DWR générique
    // ------------------------------------------------------------------
    private String call(String script, String method, List<String> bodyLines, String page) throws Exception {
        batch++;
        StringBuilder b = new StringBuilder();
        for (String l : List.of(
                "callCount=1",
                "page=" + page,
                "httpSessionId=" + httpSessionId,
                "scriptSessionId=" + scriptSessionId,
                "c0-scriptName=" + script,
                "c0-methodName=" + method,
                "c0-id=0")) {
            b.append(l).append('\n');
        }
        for (String l : bodyLines) b.append(l).append('\n');
        b.append("batchId=").append(batch).append("\n\n");

        String url = props.baseUrl() + "/dwr/call/plaincall/" + script + "." + method + ".dwr";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "text/plain; charset=UTF-8")
                .header("User-Agent", UA)
                .header("Referer", props.baseUrl() + page)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(b.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        String txt = resp.body();
        if (txt.contains("_remoteHandleException") || !txt.contains("//#DWR-REPLY")) {
            throw new DwrException("Réponse DWR anormale pour " + script + "." + method
                    + " : " + txt.substring(0, Math.min(300, txt.length())));
        }
        return txt;
    }

    // ------------------------------------------------------------------
    // Actions métier
    // ------------------------------------------------------------------
    public void authenticate() throws Exception {
        String pw = encodeURIComponent(props.password());
        String mail = encodeURIComponent(props.email());
        List<String> body = List.of(
                "c0-param0=boolean:false",
                "c0-e1=string:AuthenticationRequest",
                "c0-e2=null:null",
                "c0-e3=string:" + pw,
                "c0-e4=number:" + props.supplierId(),
                "c0-e5=null:null",
                "c0-e6=string:" + mail,
                "c0-e7=null:null",
                "c0-e8=boolean:false",
                "c0-e9=null:null",
                "c0-e10=boolean:false",
                "c0-param0=Object_AuthenticationRequest:{%24className:reference:c0-e1, "
                        + "authTypeId:reference:c0-e2, password:reference:c0-e3, "
                        + "forcedSupplierId:reference:c0-e4, supplierId:reference:c0-e5, "
                        + "mail:reference:c0-e6, ssoKey:reference:c0-e7, "
                        + "memorize:reference:c0-e8, tag:reference:c0-e9, "
                        + "autoLog:reference:c0-e10}",
                "c0-param1=boolean:false",
                "c0-param2=boolean:false",
                "c0-param3=boolean:false");
        String txt = call("RightRemote", "authenticate", body, "/login/");
        if (!txt.contains("state:true")) {
            throw new DwrException("Connexion refusée (state:true absent). "
                    + "Vérifie email/mot de passe, ou le scriptSessionId.");
        }
        log.info("Connexion réussie.");
    }

    public Map<Long, SessionInfo> loadPlanning(long dayMidnightMs) throws Exception {
        List<String> body = List.of(
                "c0-param0=boolean:false",
                "c0-e1=string:FilterValue",
                "c0-e4=string:FilterValueEntry",
                "c0-e5=string:roomSelector",
                "c0-e6=boolean:true",
                "c0-e7=number:-1",
                "c0-e8=null:null",
                "c0-e3=Object_FilterValueEntry:{%24className:reference:c0-e4, "
                        + "typeOfSelection:reference:c0-e5, limited:reference:c0-e6, "
                        + "selected:reference:c0-e7, hasToBeReload:reference:c0-e8}",
                "c0-e10=string:FilterValueEntry",
                "c0-e11=string:activitySelector",
                "c0-e12=boolean:false",
                "c0-e13=number:-1",
                "c0-e14=null:null",
                "c0-e9=Object_FilterValueEntry:{%24className:reference:c0-e10, "
                        + "typeOfSelection:reference:c0-e11, limited:reference:c0-e12, "
                        + "selected:reference:c0-e13, hasToBeReload:reference:c0-e14}",
                "c0-e16=string:FilterValueEntry",
                "c0-e17=string:coachSelector",
                "c0-e18=boolean:false",
                "c0-e19=number:-1",
                "c0-e20=null:null",
                "c0-e15=Object_FilterValueEntry:{%24className:reference:c0-e16, "
                        + "typeOfSelection:reference:c0-e17, limited:reference:c0-e18, "
                        + "selected:reference:c0-e19, hasToBeReload:reference:c0-e20}",
                "c0-e2=Array:[reference:c0-e3,reference:c0-e9,reference:c0-e15]",
                "c0-e21=number:" + props.supplierId(),
                "c0-e22=number:" + props.supplierId(),
                "c0-e24=boolean:false",
                "c0-e25=Date:" + dayMidnightMs,
                "c0-e26=Date:" + dayMidnightMs,
                "c0-e23=Object_Object:{isWeek:reference:c0-e24, "
                        + "firstDay:reference:c0-e25, lastDay:reference:c0-e26}",
                "c0-e27=number:" + props.dataPoolId(),
                "c0-e28=number:" + props.supplierId(),
                "c0-param1=Object_FilterValue:{%24className:reference:c0-e1, "
                        + "entries:reference:c0-e2, activitySupplierId:reference:c0-e21, "
                        + "roomSupplierId:reference:c0-e22, calendarEntry:reference:c0-e23, "
                        + "dataPoolId:reference:c0-e27, coachSupplierId:reference:c0-e28}",
                "c0-param2=number:" + props.customerId());
        String txt = call("OnlineRemote", "initializePlanning", body, PLANNING_PAGE);
        return PlanningParser.parse(txt);
    }

    public boolean checkAbo(long sessionId) throws Exception {
        List<String> body = List.of(
                "c0-param0=boolean:false",
                "c0-param1=number:" + props.customerId(),
                "c0-param2=number:" + sessionId,
                "c0-param3=number:1");
        String txt = call("OnlineRemote", "checkAboForBooking", body, PLANNING_PAGE);
        return txt.contains("',true);") || txt.trim().endsWith(",true);");
    }

    public boolean book(long sessionId) throws Exception {
        List<String> body = List.of(
                "c0-param0=boolean:false",
                "c0-param1=number:" + sessionId,
                "c0-param2=number:" + props.customerId(),
                "c0-param3=number:1");
        call("OnlineRemote", "bookForCustomer", body, PLANNING_PAGE);
        return true;
    }

    public boolean unbook(long sessionId) throws Exception {
        List<String> body = List.of(
                "c0-param0=boolean:false",
                "c0-param1=number:" + sessionId,
                "c0-param2=number:" + props.customerId());
        call("OnlineRemote", "unbookForCustomer", body, PLANNING_PAGE);
        return true;
    }

    /** Réplique le comportement de encodeURIComponent() (le ! n'est pas encodé, le @ devient %40). */
    static String encodeURIComponent(String s) {
        StringBuilder o = new StringBuilder();
        for (byte raw : s.getBytes(StandardCharsets.UTF_8)) {
            int ch = raw & 0xFF;
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || "-_.!~*'()".indexOf(ch) >= 0) {
                o.append((char) ch);
            } else {
                o.append('%').append(String.format("%02X", ch));
            }
        }
        return o.toString();
    }
}
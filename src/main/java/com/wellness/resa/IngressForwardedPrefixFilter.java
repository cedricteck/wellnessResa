package com.wellness.resa;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Rend l'IHM utilisable derrière l'ingress Home Assistant.
 *
 * <p>L'ingress sert l'add-on sous un préfixe du type {@code /api/hassio_ingress/<token>},
 * qu'il RETIRE de l'URL avant de proxifier : les mappings de l'application fonctionnent
 * donc inchangés, mais les URL qu'elle GÉNÈRE (liens {@code @{/...}} des templates,
 * redirections {@code redirect:/}) partiraient vers la racine de Home Assistant.
 *
 * <p>Home Assistant communique le préfixe réel dans l'en-tête {@code X-Ingress-Path},
 * que Spring ne connaît pas. On le recopie donc en {@code X-Forwarded-Prefix} — son
 * équivalent standard — puis on délègue à {@link ForwardedHeaderFilter}, qui enveloppe
 * la requête de façon COHÉRENTE (contextPath ET requestURI préfixés ensemble). Se
 * contenter de surcharger {@code getContextPath()} échouerait : Spring exige que l'URI
 * commence par le contextPath, sinon {@code Invalid contextPath ... must match the start
 * of requestPath}.
 *
 * <p>Le filtre est délégué explicitement plutôt qu'activé via
 * {@code server.forward-headers-strategy}, afin de ne pas dépendre de l'ordre
 * d'enregistrement des filtres : l'en-tête doit être posé AVANT sa lecture.
 *
 * <p>Hors ingress (accès direct en développement), l'en-tête est absent et le filtre est
 * transparent. Comme il ne fait que préfixer des URL générées, une valeur forgée par un
 * client ne casserait que ses propres liens.
 */
@Component
public class IngressForwardedPrefixFilter extends OncePerRequestFilter {

    private static final String INGRESS_PATH_HEADER = "X-Ingress-Path";
    private static final String FORWARDED_PREFIX_HEADER = "X-Forwarded-Prefix";

    private final ForwardedHeaderFilter forwardedHeaderFilter = relativeRedirectFilter();

    /**
     * Par défaut, {@link ForwardedHeaderFilter} transforme les redirections en URL ABSOLUES
     * reconstruites depuis les en-têtes {@code X-Forwarded-*}. Quand Home Assistant n'envoie
     * que {@code X-Ingress-Path}, il ne reste que le host réel du conteneur et le navigateur
     * serait renvoyé vers {@code http://172.30.33.x:8080/...} — injoignable, et en clair
     * derrière le proxy SSL. On force donc des redirections RELATIVES : le préfixe d'ingress
     * suffit, quels que soient les en-têtes transmis.
     *
     * <p>Nécessite {@code server.tomcat.use-relative-redirects: true} côté conteneur.
     */
    private static ForwardedHeaderFilter relativeRedirectFilter() {
        ForwardedHeaderFilter filter = new ForwardedHeaderFilter();
        filter.setRelativeRedirects(true);
        return filter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String prefix = normalize(request.getHeader(INGRESS_PATH_HEADER));
        if (prefix == null) {
            chain.doFilter(request, response);
            return;
        }
        forwardedHeaderFilter.doFilter(new ForwardedPrefixRequest(request, prefix), response, chain);
    }

    /** Préfixe exploitable ("/api/hassio_ingress/xxx", sans slash final), ou {@code null}. */
    private static String normalize(String header) {
        if (header == null || header.isBlank() || !header.startsWith("/")) {
            return null;
        }
        String prefix = header.trim();
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix.isEmpty() ? null : prefix;
    }

    /** Requête vue comme portant l'en-tête {@code X-Forwarded-Prefix} attendu par Spring. */
    private static final class ForwardedPrefixRequest extends HttpServletRequestWrapper {

        private final String prefix;

        ForwardedPrefixRequest(HttpServletRequest request, String prefix) {
            super(request);
            this.prefix = prefix;
        }

        @Override
        public String getHeader(String name) {
            return FORWARDED_PREFIX_HEADER.equalsIgnoreCase(name) ? prefix : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return FORWARDED_PREFIX_HEADER.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(prefix))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
            if (names.stream().noneMatch(FORWARDED_PREFIX_HEADER::equalsIgnoreCase)) {
                names.add(FORWARDED_PREFIX_HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}

package org.openl.rules.ruleservice.servlet;

import java.io.IOException;
import java.net.FileNameMap;
import java.util.Arrays;
import java.util.UUID;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.Environment;

import org.openl.info.OpenLInfoLogger;
import org.openl.rules.ruleservice.api.AuthorizationChecker;
import org.openl.rules.ruleservice.core.RuleServiceRedeployLock;
import org.openl.util.StringUtils;

/**
 * An uber filter which process different parts of the request logic.
 * <p>
 * The reasons why it was done in one place:
 * 1. To fix the order of the processing parts. It could be defined in web.xml file, but it required additional steps
 * to support it in the SpringBoot.
 * 2. Reduce complexity by removing common parts.
 *
 * @author Yury Molchan
 */
@WebFilter("/*")
public class RuleServicesFilter implements Filter {

    private final Logger log = LoggerFactory.getLogger(RuleServicesFilter.class);

    // Mapping from the file extension to the MIME type.
    private FileNameMap mimeMap;

    // MDC
    public static final String REQUEST_ID_KEY = "requestId";
    private String requestIdHeaderKey;

    // Security
    private AuthorizationChecker[] authorizationCheckers;

    // CORS
    private String[] allowedOrigins;
    private boolean allowedAny;
    private String allowedMethods;
    private String allowedHeaders;
    private String maxAge;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        var servletContext = filterConfig.getServletContext();
        mimeMap = servletContext::getMimeType;

        ApplicationContext appContext = SpringInitializer.getApplicationContext(servletContext);
        Environment env = appContext.getEnvironment();

        // MDC
        requestIdHeaderKey = StringUtils.trimToNull(env.getProperty("log.request-id.header"));

        // Security
        var checkers = appContext.getBeansOfType(AuthorizationChecker.class).values().toArray(new AuthorizationChecker[0]);
        Arrays.sort(checkers, AnnotationAwareOrderComparator.INSTANCE);
        log.info("Available Authorization checkers: {}", Arrays.toString(checkers));
        authorizationCheckers = checkers;

        //CORS
        var allowed = env.getProperty("cors.allowed.origins");
        allowedAny = "*".equals(allowed);
        allowedOrigins = StringUtils.split(allowed, ',');
        allowedMethods = env.getProperty("cors.allowed.methods");
        allowedHeaders = env.getProperty("cors.allowed.headers");
        maxAge = env.getProperty("cors.preflight.maxage");
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException {

        var request = (HttpServletRequest) req;
        var response = (HttpServletResponse) resp;

        // MDC. Insert generated id in MDC for each request.
        if (requestIdHeaderKey != null) {
            var requestId = request.getHeader(requestIdHeaderKey);
            if (StringUtils.isBlank(requestId)) {
                requestId = UUID.randomUUID().toString();
            }
            response.setHeader(requestIdHeaderKey, requestId);
            MDC.put(REQUEST_ID_KEY, requestId);
            try {
                process(request, response, chain);
            } finally {
                MDC.remove(REQUEST_ID_KEY);
            }
        } else {
            process(request, response, chain);
        }
    }

    private void process(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {

        var method = request.getMethod();
        var path = request.getPathInfo();
        if (path == null || path.equals("/")) {
            path = "/index.html";
        }

        // UTF-8
        if (request.getCharacterEncoding() == null) {
            // In case if charset was not set in the request.
            // UTF-8 is used as default instead of ISO-8859-1
            request.setCharacterEncoding("UTF-8");
        }

        // Static content
        if (method.equals("GET")) {
            try (var resourceStream = getClass().getClassLoader().getResourceAsStream("static" + path)) {
                if (resourceStream != null) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    var mimeType = mimeMap.getContentTypeFor(path);

                    response.setContentType(mimeType);
                    resourceStream.transferTo(response.getOutputStream());
                    return;
                }
            }
        }

        // Security
        if (authorizationCheckers.length > 0 && !skipAuthorization(path)) {
            var authorized = false;
            try {
                for (AuthorizationChecker validator : authorizationCheckers) {
                    if (validator.authorize(request)) {
                        log.debug("Authorized: {} {}; by: {}", method, request.getRequestURL(), validator);
                        authorized = true;
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Authorization failure.", e);
            }
            if (!authorized) {
                log.info("Unauthorized: {} {};", method, request.getRequestURL());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }

        // CORS
        var requestOrigin = request.getHeader("Origin");
        if (isAllowedOrigin(requestOrigin)) {
            response.addHeader("Access-Control-Allow-Origin", requestOrigin);
            response.addHeader("Access-Control-Max-Age", maxAge);
            response.addHeader("Access-Control-Allow-Methods", allowedMethods);
            response.addHeader("Access-Control-Allow-Headers", allowedHeaders);
            if ("OPTIONS".equals(method)) {
                response.setStatus(HttpServletResponse.SC_ACCEPTED);
                return;
            }
        }

        // Lock RuleServices until all services are available
        // TODO: Replace locking of all requests by locking per OpenL service in RuleService
        if (path.startsWith("/admin/")) { // Do not block admin functionality
            chain.doFilter(request, response);
            return;
        }
        var lock = RuleServiceRedeployLock.getInstance().getReadLock();
        try {
            lock.lock();
            chain.doFilter(request, response);
        } finally {
            lock.unlock();
        }

    }

    private boolean skipAuthorization(String path) {
        // Skip authorization for informational and healthcheck urls
        return path.startsWith("/admin/healthcheck/")
                || path.startsWith("/admin/info/")
                || path.startsWith("/admin/config/");
    }

    private boolean isAllowedOrigin(String requestOrigin) {
        if (requestOrigin == null || allowedOrigins == null) {
            return false;
        }
        if (allowedAny) {
            return true;
        }
        for (String allowed : allowedOrigins) {
            if (allowed.equalsIgnoreCase(requestOrigin)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        OpenLInfoLogger.memStat();
        authorizationCheckers = null;
        mimeMap = null;
    }
}

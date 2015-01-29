package net.dean.jraw.http;

import com.google.common.util.concurrent.RateLimiter;
import com.squareup.okhttp.Headers;
import net.dean.jraw.JrawUtils;

import java.io.IOException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This class provides a high-level API to send REST-oriented HTTP requests with.
 */
public abstract class RestClient implements HttpClient {
    /** The HttpAdapter used to send HTTP requests */
    protected final HttpAdapter httpAdapter;
    private final String defaultHost;
    /** The CookieStore that will contain all the cookies saved by {@link #httpAdapter} */
    protected final HttpLogger logger;
    /** A list of Requests sent in the past */
    protected final LinkedHashMap<RestResponse, Date> history;
    private RateLimiter rateLimiter;
    private boolean useHttpsDefault;
    private boolean enforceRatelimit;
    private boolean saveResponseHistory;
    private boolean requestLogging;

    /**
     * Instantiates a new RestClient
     *
     * @param defaultHost The host that will be applied to every {@link HttpRequest.Builder} returned by
     *                    {@link #request()}
     * @param userAgent The default value of the User-Agent header
     * @param requestsPerMinute The amount of HTTP requests that can be sent in one minute. A value greater than 0 will
     *                          enable rate limiting, one less than or equal to 0 will disable it.
     */
    public RestClient(HttpAdapter httpAdapter, String defaultHost, String userAgent, int requestsPerMinute) {
        this.httpAdapter = httpAdapter;
        this.defaultHost = defaultHost;
        this.saveResponseHistory = false;
        this.logger = new HttpLogger(JrawUtils.logger());
        this.requestLogging = false;
        this.history = new LinkedHashMap<>();
        this.useHttpsDefault = false;
        setUserAgent(userAgent);
        setEnforceRatelimit(requestsPerMinute);
    }

    /**
     * Gets the host that will be used by default when creating new RestRequest.Builders.
     * @return The default host
     */
    public String getDefaultHost() {
        return defaultHost;
    }

    /**
     * Whether to automatically manage the execution of HTTP requests based on time (enabled by default). If there has
     * been more than a certain amount of requests in the last minute (30 for normal API, 60 for OAuth), this class will
     * wait to execute the next request in order to minimize the chance of Reddit IP banning this client or simply
     * returning a 403 Forbidden.
     *
     * @param requestsPerMinute The amount of HTTP requests that can be sent in one minute. A value greater than 0 will
     *                          enable rate limit enforcing, one less than or equal to 0 will disable it.
     */
    public void setEnforceRatelimit(int requestsPerMinute) {
        this.enforceRatelimit = requestsPerMinute > 0;
        this.rateLimiter = enforceRatelimit ? RateLimiter.create((double) requestsPerMinute / 60) : null;
    }

    /**
     * Checks if the rate limit is being enforced. If true, then thread that {@link #execute(HttpRequest)} is called on
     * will block until enough time has passed
     * @return If the rate limit is being enforced.
     */
    public boolean isEnforcingRatelimit() {
        return enforceRatelimit;
    }

    @Override
    public boolean isHttpsDefault() {
        return useHttpsDefault;
    }

    @Override
    public void setHttpsDefault(boolean useHttpsDefault) {
        this.useHttpsDefault = useHttpsDefault;
    }

    @Override
    public HttpRequest.Builder request() {
        HttpRequest.Builder builder = new HttpRequest.Builder()
                .host(defaultHost)
                .https(useHttpsDefault);
        for (Map.Entry<String, String> entry: httpAdapter.getDefaultHeaders().entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        return builder;
    }

    @Override
    public RestResponse execute(HttpRequest request) throws NetworkException {
        if (request.isUsingBasicAuth()) {
            httpAdapter.authenticate(request.getBasicAuthData());
        }

        Headers.Builder builder = request.getHeaders().newBuilder();
        for (Map.Entry<String, String> defaultHeader : httpAdapter.getDefaultHeaders().entrySet()) {
            builder.add(defaultHeader.getKey(), defaultHeader.getValue());
        }

        if (enforceRatelimit) {
            // Try to get a ticket without waiting
            if (!rateLimiter.tryAcquire()) {
                // Could not get a ticket immediately, block until we can
                double time = rateLimiter.acquire();
                if (requestLogging) {
                    JrawUtils.logger().info("Slept for {} seconds", time);
                }
            }
        }

        try {
            if (requestLogging)
                logger.log(request);

            RestResponse response = httpAdapter.execute(request);
            if (requestLogging)
                logger.log(response);

            if (!JrawUtils.isEqual(response.getType(), request.getExpectedType())) {
                throw new NetworkException(String.format("Expected Content-Type ('%s/%s') did not match actual Content-Type ('%s/%s')",
                        request.getExpectedType().type(), request.getExpectedType().subtype(),
                        response.getType().type(), response.getType().subtype()));
            }

            if (saveResponseHistory)
                history.put(response, new Date());
            return response;
        } catch (IOException e) {
            throw new NetworkException("Could not execute the request: " + request, e);
        } finally {
            httpAdapter.deauthenticate();
        }
    }

    @Override
    public String getUserAgent() {
        return httpAdapter.getDefaultHeader("User-Agent");
    }

    @Override
    public void setUserAgent(String userAgent) {
        httpAdapter.setDefaultHeader("User-Agent", userAgent);
    }

    @Override
    public boolean isSavingResponseHistory() {
        return saveResponseHistory;
    }

    @Override
    public void setSaveResponseHistory(boolean saveResponseHistory) {
        this.saveResponseHistory = saveResponseHistory;
    }

    @Override
    public boolean isLoggingActivity() {
        return requestLogging;
    }

    @Override
    public void enableLogging(boolean flag) {
        this.requestLogging = flag;
    }

    @Override
    public LinkedHashMap<RestResponse, Date> getHistory() {
        return history;
    }

    @Override
    public HttpLogger getHttpLogger() {
        return logger;
    }

    @Override
    public HttpAdapter getHttpAdapter() {
        return httpAdapter;
    }
}

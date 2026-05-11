package activityreport.client;

import io.quarkus.logging.Log;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import org.jboss.resteasy.reactive.client.api.ClientLogger;

/**
 * Custom REST client logger that logs only essential information at TRACE level.
 * Logs URL, query parameters, and response status without request/response bodies.
 */
public class TraceClientLogger implements ClientLogger {

    @Override
    public void setBodySize(int bodySize) {
        // Not used
    }

    @Override
    public void logRequest(HttpClientRequest request, Buffer body, boolean omitBody) {
        if (!Log.isTraceEnabled()) {
            return;
        }

        String method = request.getMethod().name();
        String url = request.absoluteURI();

        Log.tracef("REST Request: %s %s", method, url);
    }

    @Override
    public void logResponse(HttpClientResponse response, boolean redirect) {
        if (!Log.isTraceEnabled()) {
            return;
        }

        int status = response.statusCode();
        Log.tracef("REST Response: %d", status);
    }
}

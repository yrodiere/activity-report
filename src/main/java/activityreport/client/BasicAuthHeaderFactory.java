package activityreport.client;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.util.Base64;

/**
 * Client request filter that adds Basic Authentication header.
 * Credentials are set via the "credentials" property on the request context.
 */
@Provider
public class BasicAuthHeaderFactory implements ClientRequestFilter {

    public static final String CREDENTIALS_PROPERTY = "basicauth.credentials";

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        Object credentials = requestContext.getProperty(CREDENTIALS_PROPERTY);
        if (credentials instanceof String credentialsStr) {
            String encoded = Base64.getEncoder().encodeToString(credentialsStr.getBytes());
            requestContext.getHeaders().add("Authorization", "Basic " + encoded);
        }
    }
}

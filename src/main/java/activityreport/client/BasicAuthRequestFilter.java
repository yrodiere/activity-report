package activityreport.client;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;

import java.io.IOException;
import java.util.Base64;

/**
 * Client request filter that adds Basic Authentication header with configured credentials.
 */
public class BasicAuthRequestFilter implements ClientRequestFilter {

    private final String credentials;

    public BasicAuthRequestFilter(String username, String password) {
        this.credentials = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
    }

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        requestContext.getHeaders().add("Authorization", "Basic " + credentials);
    }
}

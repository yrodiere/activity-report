package activityreport.client;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Quarkus REST client for Zulip API.
 */
@RegisterRestClient(configKey = "zulip")
@RegisterProvider(BasicAuthHeaderFactory.class)
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface ZulipRestClient {

    @GET
    @Path("/users/me")
    JsonNode getCurrentUser();

    @GET
    @Path("/messages")
    JsonNode getMessages(@QueryParam("anchor") String anchor,
                        @QueryParam("num_before") int numBefore,
                        @QueryParam("num_after") int numAfter,
                        @QueryParam("narrow") String narrow);
}

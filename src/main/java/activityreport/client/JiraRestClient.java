package activityreport.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Quarkus REST client for JIRA API.
 */
@RegisterRestClient(configKey = "jira")
@RegisterProvider(BasicAuthHeaderFactory.class)
@Path("/rest/api/3")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface JiraRestClient {

    @POST
    @Path("/search/jql")
    JsonNode search(ObjectNode request);
}

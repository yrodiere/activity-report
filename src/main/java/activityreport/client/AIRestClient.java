package activityreport.client;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.Map;

/**
 * Quarkus REST client for OpenAI-compatible AI API.
 */
@RegisterRestClient(configKey = "ai")
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface AIRestClient {

    @GET
    @Path("/models")
    JsonNode listModels();

    @POST
    @Path("/chat/completions")
    JsonNode chatCompletion(Map<String, Object> request);
}

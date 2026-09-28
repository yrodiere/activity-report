package activityreport.client;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/rest/dev-status/latest/issue")
@Produces(MediaType.APPLICATION_JSON)
public interface JiraDevStatusClient {

    @GET
    @Path("/detail")
    JsonNode detail(@QueryParam("issueId") String issueId,
                    @QueryParam("applicationType") String applicationType,
                    @QueryParam("dataType") String dataType);
}

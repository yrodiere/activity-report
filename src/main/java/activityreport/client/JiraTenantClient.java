package activityreport.client;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/_edge")
@Produces(MediaType.APPLICATION_JSON)
public interface JiraTenantClient {

    @GET
    @Path("/tenant_info")
    JsonNode tenantInfo();
}

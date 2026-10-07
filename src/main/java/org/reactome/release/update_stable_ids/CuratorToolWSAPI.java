package org.reactome.release.update_stable_ids;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.user.model.User;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;

/**
 * @author Joel Weiser (joel.weiser@oicr.on.ca)
 * Created 9/18/2025
 */
public class CuratorToolWSAPI {
    // Treat the token as expired this many seconds early so a request can not be in flight when it lapses
    private static final long EXPIRY_SKEW_SECONDS = 60L;

    private String hostURL;
    private String userName;
    private String password;

    private String jwtToken;
    private Instant jwtExpiry;

    public CuratorToolWSAPI(String hostURL, String userName, String password) {
        this.hostURL = hostURL;
        this.userName = userName;
        this.password = password;
        refreshJwtToken();
    }

    public SimpleInstance commit(SimpleInstance simpleInstance) {
        ObjectMapper mapper = new ObjectMapper();
        //	mapper.addMixIn(org.reactome.curation.model.SimpleInstance.class, DatabaseObjectMixin.class);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        try {
            HttpPost post = new HttpPost(getCommitURL());
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(mapper.writeValueAsString(simpleInstance)));

            ExecutionResponse executionResponse = executeAuthenticated(post);
            if (executionResponse.getResponseCode() != 200) {
                throw new RuntimeException("Failed : HTTP error code : " + executionResponse.getResponseCode());
            }
            return mapper.readValue(executionResponse.getResponseEntity(), SimpleInstance.class);
        } catch (IOException e) {
            throw new RuntimeException("Error committing simple instance " + simpleInstance + " to API", e);
        }
    }

    public SimpleInstance findByDbId(long dbId) throws InstanceNotFoundException {
        try {
            HttpGet request = new HttpGet(getFindByDbIdURL() + dbId);
            request.setHeader("Accept", "application/json");

            ExecutionResponse executionResponse = executeAuthenticated(request);
            int responseCode = executionResponse.getResponseCode();
            if (responseCode == 404) {
                throw new InstanceNotFoundException("Unable to find an instance in graph database for " + dbId);
            } else if (responseCode != 200) {
                throw new RuntimeException("Failed : HTTP error code : " + responseCode);
            }

            String json = executionResponse.getResponseEntity();
            if (json == null || json.isEmpty()) {
                return null;
            }
            return new ObjectMapper().readValue(json, SimpleInstance.class);
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException("Error fetching SimpleInstance from API", e);
        }
    }

    public static class InstanceNotFoundException extends Exception {
        public InstanceNotFoundException(String message) {
            super(message);
        }
    }

    private static class ExecutionResponse {
        private final String responseEntity;
        private final int responseCode;

        public ExecutionResponse(String responseEntity, int responseCode) {
            this.responseEntity = responseEntity;
            this.responseCode = responseCode;
        }

        public String getResponseEntity() {
            return this.responseEntity;
        }

        public int getResponseCode() {
            return this.responseCode;
        }
    }

    /**
     * Executes the request with a valid bearer token, returning the response body.  The token is refreshed up front
     * when it is at or near its expiry, and again if the server rejects it anyway (e.g. the server restarted with a
     * new signing key, or its clock differs from ours), in which case the request is retried once.
     */
    private ExecutionResponse executeAuthenticated(HttpRequestBase request) {
        for (int attempt = 0; ; attempt++) {
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                request.setHeader("Authorization", "Bearer " + getJwtToken());

                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int statusCode = response.getStatusLine().getStatusCode();
                    if (isAuthFailure(statusCode) && attempt == 0) {
                        EntityUtils.consumeQuietly(response.getEntity());
                        refreshJwtToken();
                        continue;
                    }

                    return new ExecutionResponse(EntityUtils.toString(response.getEntity()),statusCode);
                }
            } catch (IOException e) {
                throw new RuntimeException("Error executing request to " + request.getURI(), e);
            }
        }
    }

    private boolean isAuthFailure(int statusCode) {
        return statusCode == 401 || statusCode == 403;
    }

    private synchronized String getJwtToken() {
        if (this.jwtToken == null || isJwtTokenExpiring()) {
            refreshJwtToken();
        }
        return this.jwtToken;
    }

    private synchronized boolean isJwtTokenExpiring() {
        // A token whose expiry could not be determined is left to the retry in executeAuthenticated
        return this.jwtExpiry != null && Instant.now().plusSeconds(EXPIRY_SKEW_SECONDS).isAfter(this.jwtExpiry);
    }

    private synchronized void refreshJwtToken() {
        this.jwtToken = fetchJwtToken(getUserName(), getPassword());
        this.jwtExpiry = getExpiry(this.jwtToken);
    }

    private String fetchJwtToken(String username, String password) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(getAuthURL());
            post.setHeader("Content-Type", "application/json");
            ObjectMapper mapper = new ObjectMapper();
            String jsonObj = mapper.writeValueAsString(new User(username, password));
            post.setEntity(new StringEntity(jsonObj));
            HttpResponse response = httpClient.execute(post);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new RuntimeException("Failed : HTTP error code : " + statusCode);
            }
            String jwt = EntityUtils.toString(response.getEntity());
            if (jwt.startsWith("\"") && jwt.endsWith("\"")) {
                jwt = jwt.substring(1, jwt.length() - 1);
            }
            return jwt;
        } catch (Exception e) {
            throw new RuntimeException("Error fetching JWT token from API", e);
        }
    }

    /**
     * Reads the "exp" claim from the token's payload.  The signature is not checked - the claim is used only to
     * decide when to ask for a new token, never to decide that this token is trustworthy.
     *
     * @return the instant the token expires, or null if the token carries no readable expiry
     */
    private Instant getExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            JsonNode expiry = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[1])).get("exp");
            return expiry != null ? Instant.ofEpochSecond(expiry.asLong()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String getAuthURL() {
        return getHostURL() + "auth/login";
    }

    private String getFindByDbIdURL() {
        return getHostURL() + "curation/findByDbId/";
    }

    private String getCommitURL() {
        return getHostURL() + "curation/commit";
    }

    private String getHostURL() {
        return this.hostURL;
    }

    private String getUserName() {
        return this.userName;
    }

    private String getPassword() {
        return this.password;
    }
}

package org.reactome.release.update_stable_ids;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.user.model.User;

import java.io.IOException;

/**
 * @author Joel Weiser (joel.weiser@oicr.on.ca)
 * Created 9/18/2025
 */
public class CuratorToolWSAPI {
    private String hostURL;

    private String jwtToken;

    public CuratorToolWSAPI(String hostURL, String userName, String password) {
        this.hostURL = hostURL;
        this.jwtToken = this.fetchJwtToken(userName, password);
    }

    public SimpleInstance commit(SimpleInstance simpleInstance) {
        ObjectMapper mapper = new ObjectMapper();
        //	mapper.addMixIn(org.reactome.curation.model.SimpleInstance.class, DatabaseObjectMixin.class);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(getCommitURL());
            post.setHeader("Content-Type", "application/json");
            post.setHeader("Authorization", "Bearer " + getJwtToken());

            String simpleInstanceJSON = mapper.writeValueAsString(simpleInstance);

            post.setEntity(new StringEntity(simpleInstanceJSON));
            HttpResponse response = httpClient.execute(post);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new RuntimeException("Failed : HTTP error code : " + statusCode);
            }

            return mapper.readValue(EntityUtils.toString(response.getEntity()), SimpleInstance.class);
        } catch (IOException e) {
            throw new RuntimeException("Error committing simple instance " + simpleInstance + " to API", e);
        }
    }

    public SimpleInstance findByDbId(long dbId) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(getFindByDbIdURL() + dbId);
            request.setHeader("Accept", "application/json");
            request.setHeader("Authorization", "Bearer " + getJwtToken());
            HttpResponse response = httpClient.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new RuntimeException("Failed : HTTP error code : " + statusCode);
            }
            String json = EntityUtils.toString(response.getEntity());
            if (json == null || json.isEmpty()) {
                return null;
            }
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(json, SimpleInstance.class);
        }
        catch (Exception e) {
            throw new RuntimeException("Error fetching SimpleInstance from API", e);
        }
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
            this.jwtToken = jwt;
            return jwt;
        } catch (Exception e) {
            throw new RuntimeException("Error fetching JWT token from API", e);
        }
    }

    private String getJwtToken() {
        return this.jwtToken;
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
}
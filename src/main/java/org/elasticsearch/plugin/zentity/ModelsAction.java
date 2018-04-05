package org.elasticsearch.plugin.zentity;

import io.zentity.model.Model;
import io.zentity.model.ValidationException;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequestBuilder;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequestBuilder;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.rest.*;

import static org.elasticsearch.rest.RestRequest.Method;
import static org.elasticsearch.rest.RestRequest.Method.*;

public class ModelsAction extends BaseRestHandler {

    public static final String INDEX = ".zentity-models";
    public static final String INDEX_MAPPING = "{\n" +
            "  \"doc\": {\n" +
            "    \"dynamic\": \"strict\",\n" +
            "    \"properties\": {\n" +
            "      \"attributes\": {\n" +
            "        \"type\": \"object\",\n" +
            "        \"enabled\": false\n" +
            "      },\n" +
            "      \"resolvers\": {\n" +
            "        \"type\": \"object\",\n" +
            "        \"enabled\": false\n" +
            "      },\n" +
            "      \"matchers\": {\n" +
            "        \"type\": \"object\",\n" +
            "        \"enabled\": false\n" +
            "      },\n" +
            "      \"indices\": {\n" +
            "        \"type\": \"object\",\n" +
            "        \"enabled\": false\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

    @Inject
    public ModelsAction(Settings settings, RestController controller) {
        super(settings);
        controller.registerHandler(GET, "_zentity/models", this);
        controller.registerHandler(GET, "_zentity/models/{entity_type}", this);
        controller.registerHandler(POST, "_zentity/models/{entity_type}", this);
        controller.registerHandler(PUT, "_zentity/models/{entity_type}", this);
        controller.registerHandler(DELETE, "_zentity/models/{entity_type}", this);
    }

    public static void createIndex(NodeClient client) {
        client.admin().indices().prepareCreate(INDEX)
                .setSettings(Settings.builder()
                        .put("index.number_of_shards", 1)
                        .put("index.number_of_replicas", 1)
                )
                .addMapping("doc", INDEX_MAPPING, XContentType.JSON)
                .get();
    }

    /**
     * Check if the .zentity-models index exists, and if it doesn't, then create it.
     *
     * @param client The client that will communicate with Elasticsearch.
     */
    public static void ensureIndex(NodeClient client) {
        IndicesExistsRequestBuilder request = client.admin().indices().prepareExists(INDEX);
        IndicesExistsResponse response = request.get();
        if (!response.isExists())
            createIndex(client);
    }

    /**
     * Retrieve all entity models.
     *
     * @param client The client that will communicate with Elasticsearch.
     * @return The response from Elasticsearch.
     */
    public static SearchResponse getEntityModels(NodeClient client) {
        SearchRequestBuilder request = client.prepareSearch(INDEX);
        request.setSize(10000);
        try {
            return request.get();
        } catch (IndexNotFoundException e) {
            createIndex(client);
            return request.get();
        }
    }

    /**
     * Retrieve one entity model by its type.
     *
     * @param entityType The entity type.
     * @param client     The client that will communicate with Elasticsearch.
     * @return The response from Elasticsearch.
     */
    public static GetResponse getEntityModel(String entityType, NodeClient client) {
        GetRequestBuilder request = client.prepareGet(INDEX, "doc", entityType);
        try {
            return request.get();
        } catch (IndexNotFoundException e) {
            createIndex(client);
            return request.get();
        }
    }

    /**
     * Index one entity model by its type. Return error if an entity model already exists for that entity type.
     *
     * @param entityType  The entity type.
     * @param requestBody The request body.
     * @param client      The client that will communicate with Elasticsearch.
     * @return The response from Elasticsearch.
     */
    public static IndexResponse indexEntityModel(String entityType, String requestBody, NodeClient client) {
        ensureIndex(client);
        IndexRequestBuilder request = client.prepareIndex(INDEX, "doc", entityType);
        request.setSource(requestBody, XContentType.JSON).setCreate(true).setRefreshPolicy("wait_for");
        return request.get();
    }

    /**
     * Update one entity model by its type. Does not support partial updates.
     *
     * @param entityType  The entity type.
     * @param requestBody The request body.
     * @param client      The client that will communicate with Elasticsearch.
     * @return The response from Elasticsearch.
     */
    public static IndexResponse updateEntityModel(String entityType, String requestBody, NodeClient client) {
        ensureIndex(client);
        IndexRequestBuilder request = client.prepareIndex(INDEX, "doc", entityType);
        request.setSource(requestBody, XContentType.JSON).setCreate(false).setRefreshPolicy("wait_for");
        return request.get();
    }

    /**
     * Delete one entity model by its type.
     *
     * @param entityType The entity type.
     * @param client     The client that will communicate with Elasticsearch.
     * @return The response from Elasticsearch.
     */
    private static DeleteResponse deleteEntityModel(String entityType, NodeClient client) {
        DeleteRequestBuilder request = client.prepareDelete(INDEX, "doc", entityType);
        request.setRefreshPolicy("wait_for");
        try {
            return request.get();
        } catch (IndexNotFoundException e) {
            createIndex(client);
            return request.get();
        }
    }

    @Override
    public String getName() {
        return "zentity_models_action";
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) {

        // Parse request
        String entityType = restRequest.param("entity_type");
        Boolean pretty = restRequest.paramAsBoolean("pretty", false);
        Method method = restRequest.method();
        String requestBody = restRequest.content().utf8ToString();

        return channel -> {
            try {

                // Validate input
                if (method == POST || method == PUT) {

                    // Parse the request body.
                    if (requestBody == null || requestBody.equals(""))
                        throw new BadRequestException("Request body is missing.");

                    // Parse and validate the entity model.
                    new Model(requestBody);
                }

                // Handle request
                if (method == GET && (entityType == null || entityType.equals(""))) {
                    // GET _zentity/models
                    SearchResponse response = getEntityModels(client);
                    XContentBuilder content = XContentFactory.jsonBuilder();
                    if (pretty)
                        content.prettyPrint();
                    content = response.toXContent(content, ToXContent.EMPTY_PARAMS);
                    channel.sendResponse(new BytesRestResponse(RestStatus.OK, content));

                } else if (method == GET && !entityType.equals("")) {
                    // GET _zentity/models/{entity_type}
                    GetResponse response = getEntityModel(entityType, client);
                    XContentBuilder content = XContentFactory.jsonBuilder();
                    if (pretty)
                        content.prettyPrint();
                    content = response.toXContent(content, ToXContent.EMPTY_PARAMS);
                    channel.sendResponse(new BytesRestResponse(RestStatus.OK, content));

                } else if (method == POST && !entityType.equals("")) {
                    // POST _zentity/models/{entity_type}
                    if (requestBody.equals(""))
                        throw new BadRequestException("Request body cannot be empty when indexing an entity model.");
                    IndexResponse response = indexEntityModel(entityType, requestBody, client);
                    XContentBuilder content = XContentFactory.jsonBuilder();
                    if (pretty)
                        content.prettyPrint();
                    content = response.toXContent(content, ToXContent.EMPTY_PARAMS);
                    channel.sendResponse(new BytesRestResponse(RestStatus.OK, content));

                } else if (method == PUT && !entityType.equals("")) {
                    // PUT _zentity/models/{entity_type}
                    if (requestBody.equals(""))
                        throw new BadRequestException("Request body cannot be empty when updating an entity model.");
                    IndexResponse response = updateEntityModel(entityType, requestBody, client);
                    XContentBuilder content = XContentFactory.jsonBuilder();
                    if (pretty)
                        content.prettyPrint();
                    content = response.toXContent(content, ToXContent.EMPTY_PARAMS);
                    channel.sendResponse(new BytesRestResponse(RestStatus.OK, content));

                } else if (method == DELETE && !entityType.equals("")) {
                    // DELETE _zentity/models/{entity_type}
                    DeleteResponse response = deleteEntityModel(entityType, client);
                    XContentBuilder content = XContentFactory.jsonBuilder();
                    if (pretty)
                        content.prettyPrint();
                    content = response.toXContent(content, ToXContent.EMPTY_PARAMS);
                    channel.sendResponse(new BytesRestResponse(RestStatus.OK, content));

                } else {
                    throw new NotImplementedException("Method and endpoint not implemented.");
                }

            } catch (BadRequestException | ValidationException e) {
                channel.sendResponse(new BytesRestResponse(channel, RestStatus.BAD_REQUEST, e));
            } catch (NotImplementedException e) {
                channel.sendResponse(new BytesRestResponse(channel, RestStatus.NOT_IMPLEMENTED, e));
            }
        };
    }
}

package com.kovanlabs.logcontroller.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.PutIndexTemplateRequest;
import co.elastic.clients.elasticsearch.indices.PutIndexTemplateResponse;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

/**
 * Runs once at startup to:
 *   1. Verify Elasticsearch connectivity.
 *   2. Apply the app-logs index template so all future indices get explicit
 *      field mappings — preventing type conflicts (e.g. responseTime as long
 *      vs double) that cause silent document rejection.
 *
 * Failures are logged but never crash the application — the pipeline degrades
 * gracefully if ES is temporarily unavailable at startup.
 */
@Component
public class ElasticStartupValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticStartupValidator.class);
    private static final String TEMPLATE_NAME = "app-logs-template";
    private static final String TEMPLATE_RESOURCE = "es-index-template.json";

    private final ElasticsearchClient client;

    public ElasticStartupValidator(ElasticsearchClient client) {
        this.client = client;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        verifyConnectivity();
        applyIndexTemplate();
    }

    private void verifyConnectivity() {
        try {
            boolean alive = client.ping().value();
            if (alive) {
                LOGGER.info("ES STARTUP — Elasticsearch connectivity OK");
            } else {
                LOGGER.warn("ES STARTUP — Elasticsearch ping returned false. Check cluster health.");
            }
        } catch (Exception e) {
            LOGGER.error("ES STARTUP — Cannot reach Elasticsearch: {}. " +
                    "Logs will be dropped until connectivity is restored.", e.getMessage());
        }
    }

    private void applyIndexTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource(TEMPLATE_RESOURCE);
            if (!resource.exists()) {
                LOGGER.warn("ES STARTUP — Index template file '{}' not found on classpath. " +
                        "Skipping template application — mapping conflicts may occur.", TEMPLATE_RESOURCE);
                return;
            }

            String templateJson;
            try (InputStream is = resource.getInputStream()) {
                templateJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            // Parse the template JSON to extract the inner "template" object
            // and pass it to the ES index template API
            try (JsonReader reader = Json.createReader(
                    new java.io.StringReader(templateJson))) {
                JsonObject root = reader.readObject();

                PutIndexTemplateRequest request = PutIndexTemplateRequest.of(t -> t
                        .name(TEMPLATE_NAME)
                        .withJson(new java.io.StringReader(templateJson))
                );

                PutIndexTemplateResponse response = client.indices().putIndexTemplate(request);
                if (Boolean.TRUE.equals(response.acknowledged())) {
                    LOGGER.info("ES STARTUP — Index template '{}' applied successfully. " +
                            "All app-logs-* indices will use explicit field mappings.", TEMPLATE_NAME);
                } else {
                    LOGGER.warn("ES STARTUP — Index template '{}' PUT was not acknowledged by ES.", TEMPLATE_NAME);
                }
            }

        } catch (Exception e) {
            LOGGER.error("ES STARTUP — Failed to apply index template '{}': {}. " +
                    "Existing indices are unaffected but new indices may have mapping conflicts.",
                    TEMPLATE_NAME, e.getMessage(), e);
        }
    }
}

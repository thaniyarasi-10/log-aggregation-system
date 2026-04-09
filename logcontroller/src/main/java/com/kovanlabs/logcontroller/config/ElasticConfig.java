package com.kovanlabs.logcontroller.config;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.ssl.TrustStrategy;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;

@Configuration
@EnableConfigurationProperties(ElasticProperties.class)
public class ElasticConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticConfig.class);

    private final ElasticProperties properties;
    private final ResourceLoader resourceLoader;
    private final Environment environment;

    public ElasticConfig(ElasticProperties properties, ResourceLoader resourceLoader, Environment environment) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.environment = environment;
    }

    @Bean
    public ElasticsearchClient elasticsearchClient() throws Exception {
        BasicCredentialsProvider creds = new BasicCredentialsProvider();
        if (StringUtils.hasText(properties.getUsername())) {
            creds.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(properties.getUsername(), properties.getPassword())
            );
            LOGGER.info("Elasticsearch auth: basic authentication enabled for configured username");
        } else {
            LOGGER.info("Elasticsearch auth: no username configured, using anonymous client");
        }

        SSLContext sslContext = null;
        if ("https".equalsIgnoreCase(properties.getScheme())) {
            sslContext = buildSslContext();
        }
        final SSLContext finalSslContext = sslContext;

        RestClient restClient = RestClient
                .builder(new HttpHost(properties.getHost(), properties.getPort(), properties.getScheme()))
                .setRequestConfigCallback(rc -> rc
                        .setConnectTimeout(properties.getConnectTimeoutMs())
                        .setConnectionRequestTimeout(properties.getConnectionRequestTimeoutMs())
                        .setSocketTimeout(properties.getSocketTimeoutMs())
                )
                .setHttpClientConfigCallback(hc -> {
                    hc.setDefaultCredentialsProvider(creds);
                    if (finalSslContext != null) {
                        hc.setSSLContext(finalSslContext);
                        if (properties.isInsecureSkipVerify()) {
                            hc.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                        }
                    }
                    return hc;
                })
                .build();

        ElasticsearchTransport transport =
                new RestClientTransport(restClient, new JacksonJsonpMapper());

        return new ElasticsearchClient(transport);
    }

    private SSLContext buildSslContext() throws Exception {
        enforceSecureModeInProd();

        if (properties.isInsecureSkipVerify()) {
            LOGGER.warn("Elasticsearch SSL: insecure-skip-verify is enabled. Certificate and hostname checks are disabled.");
            return SSLContexts.custom()
                    .loadTrustMaterial(null, (TrustStrategy) (chain, authType) -> true)
                    .build();
        }

        if (StringUtils.hasText(properties.getTruststorePath())) {
            LOGGER.info("Elasticsearch SSL: using custom truststore [{}]", properties.getTruststorePath());
            return buildTruststoreSslContext();
        }

        if (StringUtils.hasText(properties.getCaCertPath())) {
            LOGGER.info("Elasticsearch SSL: using CA certificate(s) from [{}]", properties.getCaCertPath());
            return buildCaCertSslContext();
        }

        // Fall back to JVM default truststore (cacerts) when no custom trust material is configured.
        LOGGER.info("Elasticsearch SSL: using JVM default truststore (cacerts)");
        return SSLContexts.createSystemDefault();
    }

    private SSLContext buildTruststoreSslContext() throws Exception {

        String truststoreType = StringUtils.hasText(properties.getTruststoreType())
                ? properties.getTruststoreType()
                : KeyStore.getDefaultType();

        KeyStore trustStore = KeyStore.getInstance(truststoreType);
        Resource truststoreResource = resourceLoader.getResource(properties.getTruststorePath());

        if (!truststoreResource.exists()) {
            throw new IllegalStateException(
                "Configured elasticsearch.truststore-path does not exist: " + properties.getTruststorePath()
            );
        }

        try (InputStream inputStream = truststoreResource.getInputStream()) {
            char[] truststorePassword = properties.getTruststorePassword() != null
                    ? properties.getTruststorePassword().toCharArray()
                    : null;
            trustStore.load(inputStream, truststorePassword);
        }

        SSLContextBuilder builder = SSLContexts.custom()
            .loadTrustMaterial(trustStore, null);

        return applyClientKeyMaterial(builder).build();
    }

    private SSLContext buildCaCertSslContext() throws Exception {
        Resource caCertResource = resourceLoader.getResource(properties.getCaCertPath());

        if (!caCertResource.exists()) {
            throw new IllegalStateException(
                "Configured elasticsearch.ca-cert-path does not exist: " + properties.getCaCertPath()
            );
        }

        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certificates;
        try (InputStream inputStream = caCertResource.getInputStream()) {
            certificates = certificateFactory.generateCertificates(inputStream);
        }

        if (certificates == null || certificates.isEmpty()) {
            throw new IllegalStateException(
                "No X.509 certificate found in elasticsearch.ca-cert-path: " + properties.getCaCertPath()
            );
        }

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);

        int i = 0;
        for (Certificate certificate : certificates) {
            trustStore.setCertificateEntry("elasticsearch-ca-" + i, certificate);
            i++;
        }

        LOGGER.info("Elasticsearch SSL: loaded {} certificate(s) from {}", i, properties.getCaCertPath());

        SSLContextBuilder builder = SSLContexts.custom()
            .loadTrustMaterial(trustStore, null);

        return applyClientKeyMaterial(builder).build();
    }

    private void enforceSecureModeInProd() {
        if (!properties.isInsecureSkipVerify()) {
            return;
        }

        String[] activeProfiles = environment.getActiveProfiles();
        for (String profile : activeProfiles) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                throw new IllegalStateException(
                    "elasticsearch.insecure-skip-verify=true is not allowed in production profiles"
                );
            }
        }
    }

    private KeyStore loadClientKeyStore() throws Exception {
        if (!StringUtils.hasText(properties.getKeystorePath())) {
            return null;
        }

        String keystoreType = StringUtils.hasText(properties.getKeystoreType())
                ? properties.getKeystoreType()
                : KeyStore.getDefaultType();

        KeyStore keyStore = KeyStore.getInstance(keystoreType);
        Resource keystoreResource = resourceLoader.getResource(properties.getKeystorePath());

        if (!keystoreResource.exists()) {
            throw new IllegalStateException(
                "Configured elasticsearch.keystore-path does not exist: " + properties.getKeystorePath()
            );
        }

        try (InputStream inputStream = keystoreResource.getInputStream()) {
            keyStore.load(inputStream, getKeystorePassword());
        }

        LOGGER.info("Elasticsearch SSL: loaded client keystore [{}]", properties.getKeystorePath());
        return keyStore;
    }

    private char[] getKeystorePassword() {
        return properties.getKeystorePassword() != null
                ? properties.getKeystorePassword().toCharArray()
                : null;
    }

    private SSLContextBuilder applyClientKeyMaterial(SSLContextBuilder builder) throws Exception {
        KeyStore keyStore = loadClientKeyStore();
        if (keyStore != null) {
            builder.loadKeyMaterial(keyStore, getKeystorePassword());
        }
        return builder;
    }
}
package com.kovanlabs.logcontroller.service;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class LogCleanupService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String ELASTIC_URL = "http://localhost:9200";

    @Scheduled(cron = "0 0 2 * * ?") // Runs daily at 2 AM
    public void deleteOldLogs() {

        String url = ELASTIC_URL + "/app-logs-*/_delete_by_query";

        String body = """
        {
          "query": {
            "range": {
              "timestamp": {
                "lt": "now-15d/d"
              }
            }
          }
        }
        """;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<String> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    request,
                    String.class
            );

            System.out.println("Log cleanup response: " + response.getBody());

        } catch (Exception e) {
            System.err.println("Error deleting logs: " + e.getMessage());
        }
    }
}
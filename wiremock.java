import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.MatchResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Configuration
public class WireMockService {

    private List<Map<String, Object>> mockMappings;

    public WireMockService() {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> yamlData = mapper.readValue(
                    new ClassPathResource("wiremock-config.yml").getInputStream(), Map.class
            );
            this.mockMappings = (List<Map<String, Object>>) yamlData.get("wiremock.mappings");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load WireMock configuration", e);
        }
    }

    @Bean
    public WireMockServer wireMockServer() {
        WireMockServer wireMockServer = new WireMockServer(WireMockConfiguration.options().port(8080));
        wireMockServer.start();
        WireMock.configureFor("localhost", 8080);

        for (Map<String, Object> mapping : mockMappings) {
            String urlPattern = (String) mapping.get("uri");
            String method = ((String) mapping.get("method")).toUpperCase();
            int status = (int) mapping.get("status");
            String responseTemplate = (String) mapping.get("responseTemplate");
            Map<String, Object> predicates = (Map<String, Object>) mapping.get("predicates");
            Map<String, Object> dataConfig = (Map<String, Object>) mapping.get("data");

            WireMock.stubFor(WireMock.request(method, WireMock.urlMatching(urlPattern))
                    .willReturn(WireMock.aResponse()
                            .withStatus(status)
                            .withHeader("Content-Type", "application/json")
                            .withBody(loadResponseTemplate(responseTemplate, dataConfig)))
                    .withPostServeAction("log-matcher", (request, response) -> {
                        System.out.println("Received request: " + request.getUrl());
                        if (predicates != null) {
                            // Path Matching
                            List<Map<String, String>> pathMatches = (List<Map<String, String>>) predicates.get("matches");
                            if (pathMatches != null) {
                                for (Map<String, String> match : pathMatches) {
                                    String pathRegex = match.get("path");
                                    if (!Pattern.compile(pathRegex).matcher(request.getUrl()).matches()) {
                                        System.out.println("No match found for request: " + request.getUrl());
                                        return MatchResult.noMatch();
                                    }
                                }
                            }
                        }
                        System.out.println("Matched request: " + request.getUrl());
                        return MatchResult.exactMatch();
                    })
            );
        }
        return wireMockServer;
    }

    private String loadResponseTemplate(String responseTemplate, Map<String, Object> dataConfig) {
        try {
            String responseBody = new String(Files.readAllBytes(Paths.get(new ClassPathResource("responses/" + responseTemplate).getURI())));
            if (dataConfig != null) {
                return populateDataFromCSV(responseBody, dataConfig);
            }
            return responseBody;
        } catch (IOException e) {
            throw new RuntimeException("Error loading response file: " + responseTemplate, e);
        }
    }

    private String populateDataFromCSV(String responseBody, Map<String, Object> dataConfig) {
        try {
            Map<String, Object> keyConfig = (Map<String, Object>) dataConfig.get("key");
            String csvPath = (String) ((Map<String, Object>) dataConfig.get("fromDataSource")).get("csv").get("path");
            String keyColumn = (String) ((Map<String, Object>) dataConfig.get("fromDataSource")).get("csv").get("keyColumn");
            String delimiter = (String) ((Map<String, Object>) dataConfig.get("fromDataSource")).get("csv").get("delimiter");
            
            List<String> lines = Files.readAllLines(Paths.get(new ClassPathResource(csvPath).getURI()));
            Map<String, String> csvData = lines.stream().skip(1) // Skip header row
                    .map(line -> line.split(Pattern.quote(delimiter)))
                    .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));

            String key = keyConfig.get("index").toString();
            if (csvData.containsKey(key)) {
                System.out.println("Replacing placeholder with CSV data for key: " + key);
                return responseBody.replace("${application}", csvData.get(key));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return responseBody;
    }
}

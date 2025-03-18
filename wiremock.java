import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Request;
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
                            .withBody(loadResponseTemplate(responseTemplate, dataConfig))));
        }

        wireMockServer.addMockServiceRequestListener((request, response) -> {
            logRequest(request);
        });
        return wireMockServer;
    }

    private void logRequest(Request request) {
        String requestUrl = request.getAbsoluteUrl();
        System.out.println("Received request: " + requestUrl);
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
            Map<String, Object> fromDataSource = (Map<String, Object>) dataConfig.get("fromDataSource");
            Map<String, Object> csvConfig = (Map<String, Object>) fromDataSource.get("csv");

            String csvPath = (String) csvConfig.get("path");
            String keyColumn = (String) csvConfig.get("keyColumn");
            String delimiter = (String) csvConfig.get("delimiter");

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

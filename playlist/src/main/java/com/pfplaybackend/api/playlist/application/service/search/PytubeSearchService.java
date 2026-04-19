package com.pfplaybackend.api.playlist.application.service.search;

import com.pfplaybackend.api.playlist.application.dto.search.SearchResultDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class PytubeSearchService implements YoutubeSearchService {

    @Value("${service-api.pytube.uri}")
    private String baseUri;
    @Value("${service-api.pytube.api-key}")
    private String apiKey;
    @Value("${service-api.pytube.api-secret}")
    private String apiSecret;

    private final RestTemplate restTemplate;

    @Override
    public SearchResultDto searchByWord(String query, int rows) {
        String prefix = "/api/v1/video/search";
        String url = baseUri + prefix;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("query", query)
                .queryParam("rows", rows)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", apiKey);
        headers.set("X-Api-Secret", apiSecret);

        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<SearchResultDto> response = restTemplate.exchange(
                uri, HttpMethod.GET, entity, SearchResultDto.class);

        SearchResultDto result = response.getBody();
        if (result != null && result.data() != null) {
            result = new SearchResultDto(
                    result.message(),
                    result.data().stream()
                            .filter(music -> music.running_time() != null
                                    && !music.running_time().isBlank()
                                    && !music.running_time().equals("0"))
                            .toList()
            );
        }
        return result;
    }
}

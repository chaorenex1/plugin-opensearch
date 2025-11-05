package run.halo.opensearch;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.OpenSearchException;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.indices.GetIndexRequest;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerErrorException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.plugin.ReactiveSettingFetcher;
import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpensearchConsoleEndpoint implements CustomEndpoint {

    private final ReactiveSettingFetcher reactiveSettingFetcher;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "OpensearchConsoleV1alpha1";
        return SpringdocRouteBuilder.route()
            .GET("/stats", this::getStats, builder -> {
                builder.operationId("GetOpensearchStats")
                    .description("Get Opensearch index statistics")
                    .tag(tag)
                    .response(responseBuilder()
                        .implementation(boolean.class));
            })
            .build();
    }

    private Mono<ServerResponse> getStats(ServerRequest request) {
        return reactiveSettingFetcher.fetch("basic", OpensearchProperties.class)
            .flatMap(properties -> {
                var host = properties.getHost();
                var indexName = properties.getIndexName();

                if (host == null || host.isEmpty() || indexName == null || indexName.isEmpty()) {
                    return Mono.error(new ServerWebInputException("Opensearch host or index name is not configured"));
                }

                return Mono.fromCallable(() -> getOpensearchStats(properties))
                    .flatMap(stats -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(stats))
                    .onErrorResume(OpenSearchException.class, e -> Mono.error(new ServerErrorException("Failed to get Opensearch stats", e)))
                    .onErrorResume(Exception.class, e -> Mono.error(new ServerErrorException("Unexpected error: " + e.getMessage(), e)));
            })
            .onErrorResume(e -> Mono.error(new ServerWebInputException("Failed to fetch Opensearch configuration")));
    }

    private boolean getOpensearchStats(OpensearchProperties properties)
        throws OpenSearchException, IOException {
        var host = properties.getHost();
        var port = properties.getPort();
        var indexName = properties.getIndexName();
        var username = properties.getUsername();
        var password = properties.getPassword();

        var client = OpensearchClient.getInstance(host, port, username, password,false);

        GetIndexRequest getIndexRequest = new GetIndexRequest(indexName);
        return client.indices().exists(getIndexRequest, RequestOptions.DEFAULT);
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.opensearch.halo.run", "v1alpha1");
    }
}

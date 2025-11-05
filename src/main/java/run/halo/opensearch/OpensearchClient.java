package run.halo.opensearch;

import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.transport.client.OpenSearchClient;
import java.util.Objects;

public final class OpensearchClient {
    private static volatile RestHighLevelClient INSTANCE;
    /**
     * 初始化单例（只应调用一次）。如果多次调用，后续调用将返回已存在实例。
     */
    public static RestHighLevelClient getInstance(String host, int port, String username, String password, boolean sslEnabled) {
        if (Objects.isNull(INSTANCE)) {
            synchronized (OpenSearchClient.class) {
                if (Objects.isNull(INSTANCE)) {
                    final HttpHost httpHost;
                    if (!sslEnabled) {
                        httpHost = new HttpHost("http", host, port);
                    } else {
                        httpHost = new HttpHost("https", host, port);
                    }
                    final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                    //Only for demo purposes. Don't specify your credentials in code.
                    RestClientBuilder
                        builder = RestClient.builder(httpHost)
                        .setHttpClientConfigCallback(
                            httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));

                    INSTANCE = new RestHighLevelClient(builder);
                }
            }
        }
        return INSTANCE;
    }
}

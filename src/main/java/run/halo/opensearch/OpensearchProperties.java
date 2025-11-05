package run.halo.opensearch;

import lombok.Data;

@Data
public class OpensearchProperties {

    private String host;

    private int port;

    private String username;

    private String password;

    private String indexName;
}
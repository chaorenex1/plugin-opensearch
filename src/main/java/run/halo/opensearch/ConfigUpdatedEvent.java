package run.halo.opensearch;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class ConfigUpdatedEvent extends ApplicationEvent {

    private final OpensearchProperties opensearchProperties;

    public ConfigUpdatedEvent(Object source, OpensearchProperties opensearchProperties) {
        super(source);
        this.opensearchProperties = opensearchProperties;
    }

}
package co.edu.uco.messageucolab.client.spring;

import co.edu.uco.messageucolab.client.Messages;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Registra el adaptador de {@link Messages} en cuanto el starter está en el classpath. Un equipo
 * puede poner su propio bean de {@link Messages} y este se aparta.
 */
@AutoConfiguration
@ConditionalOnClass(WebClient.class)
@EnableConfigurationProperties(MessageUcoLabProperties.class)
public class MessageUcoLabAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(Messages.class)
    public MessageUcoLabMessages messageUcoLabMessages(MessageUcoLabProperties properties) {
        // WebClient.builder() y no el bean WebClient.Builder: en Spring Boot 4 ese bean vive en otro módulo
        WebClient webClient = WebClient.builder().baseUrl(properties.getBaseUrl()).build();
        MessageUcoLabMessages messages = new MessageUcoLabMessages(properties, webClient);
        messages.start();
        return messages;
    }
}

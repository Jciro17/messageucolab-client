package co.edu.uco.messageucolab.client;

import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Los textos de una aplicación en MessageUcoLab. La implementación la registra
 * messageucolab-client-spring-boot-starter, y la usa el adaptador de mensajes de cada backend.
 *
 * <p>Ninguno de los métodos lanza excepción: si un código no está disponible, el mensaje es
 * {@link Message#unavailable(String)}.
 */
public interface Messages {

    /**
     * El mensaje con ese código, desde memoria y sin esperar, como un enum. Un código que todavía no
     * está en memoria sale como no disponible esta vez; para esos casos, {@link #find(String)}.
     */
    Message get(String code);

    /** El mensaje con sus variables reemplazadas: {@code get("MSG_PRODSAVED", Map.of("nombre", "Mouse"))}. */
    default Message get(String code, Map<String, ?> variables) {
        return get(code).withVariables(variables);
    }

    /**
     * El mensaje con ese código. Si no está en memoria, por ejemplo porque se acaba de crear en
     * MessageUcoLab, lo pide en ese momento sin bloquear.
     */
    Mono<Message> find(String code);

    /** {@link #find(String)} con las variables reemplazadas. */
    default Mono<Message> find(String code, Map<String, ?> variables) {
        return find(code).map(message -> message.withVariables(variables));
    }
}

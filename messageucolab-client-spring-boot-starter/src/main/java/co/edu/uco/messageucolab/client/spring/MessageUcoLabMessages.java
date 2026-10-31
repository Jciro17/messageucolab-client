package co.edu.uco.messageucolab.client.spring;

import co.edu.uco.messageucolab.client.Message;
import co.edu.uco.messageucolab.client.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptador del puerto {@link Messages} contra la ruta pública de MessageUcoLab.
 *
 * <ul>
 *   <li>Al arrancar descarga todos los mensajes de la aplicación y el entorno, y los guarda en memoria:
 *       {@link #get(String)} no hace llamadas HTTP.</li>
 *   <li>Los vuelve a descargar cada {@code messageucolab.refresh-interval}. {@link #find(String)} pide
 *       en el momento un código que no tiene, así un mensaje recién creado aparece en la siguiente petición.</li>
 *   <li>Guarda la última descarga en disco; si MessageUcoLab no responde al arrancar, usa esa copia.</li>
 *   <li>Nunca lanza excepción a quien pide un mensaje.</li>
 * </ul>
 */
public class MessageUcoLabMessages implements Messages, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MessageUcoLabMessages.class);

    private static final String MESSAGES_PATH = "/messageucolab/v1/public/messages";
    private static final long MISSING_REFRESH_GAP_MS = Duration.ofSeconds(10).toMillis();
    private static final String[] FIELDS = {"title", "content", "type", "category", "functionality"};

    private final MessageUcoLabProperties properties;
    private final WebClient webClient;
    private final Path snapshotFile;
    private final AtomicLong lastMissingRefresh = new AtomicLong();

    private volatile Map<String, Message> messages = Map.of();
    private volatile boolean reachable = true;
    private Disposable schedule;

    public MessageUcoLabMessages(MessageUcoLabProperties properties, WebClient webClient) {
        this.properties = properties;
        this.webClient = webClient;
        this.snapshotFile = resolveSnapshotFile(properties);
    }

    @Override
    public Message get(String code) {
        Message message = messages.get(code);
        if (message != null) {
            return message;
        }
        refreshForMissing(code);
        return Message.unavailable(code);
    }

    @Override
    public Mono<Message> find(String code) {
        Message message = messages.get(code);
        if (message != null) {
            return Mono.just(message);
        }
        if (!configured()) {
            return Mono.just(Message.unavailable(code));
        }
        // no está en memoria: se pide solo ese código, para que un mensaje recién creado aparezca ya
        return webClient.get()
                .uri(MESSAGES_PATH + "/{code}", code)
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Application-Id", properties.getApplicationId())
                .header("X-Environment-Id", properties.getEnvironmentId())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() { })
                .timeout(properties.getTimeout())
                .map(fields -> toMessage(code, fields))
                .doOnNext(this::remember)
                .onErrorResume(error -> {
                    if (error instanceof WebClientResponseException.NotFound) {
                        log.debug("[MessageUcoLab] {} no existe en MessageUcoLab", code);
                    } else {
                        warnUnreachable(error);
                    }
                    return Mono.just(Message.unavailable(code));
                });
    }

    /** Cuántos mensajes hay en memoria. */
    public int size() {
        return messages.size();
    }

    /** Descarga todos los mensajes ahora, por ejemplo después de editarlos. Emite cuántos quedaron. */
    public Mono<Integer> refresh() {
        if (!configured()) {
            return Mono.error(new IllegalStateException(
                    "Faltan messageucolab.application-id o messageucolab.environment-id"));
        }
        return webClient.get()
                .uri(MESSAGES_PATH)
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Application-Id", properties.getApplicationId())
                .header("X-Environment-Id", properties.getEnvironmentId())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() { })
                .timeout(properties.getTimeout())
                .map(MessageUcoLabMessages::toMessages)
                .doOnNext(loaded -> {
                    messages = loaded;
                    saveSnapshot(loaded);
                    if (!reachable) {
                        log.info("[MessageUcoLab] volvió a responder: {} mensajes", loaded.size());
                    }
                    reachable = true;
                })
                .map(Map::size);
    }

    /** Carga la copia en disco, hace la primera descarga y programa las siguientes. */
    void start() {
        loadSnapshot();
        if (!configured()) {
            log.warn("[MessageUcoLab] faltan messageucolab.application-id o messageucolab.environment-id: "
                    + "todos los mensajes saldrán como no disponibles");
            return;
        }
        try {
            Integer loaded = refresh().block(properties.getTimeout().plusSeconds(1));
            log.info("[MessageUcoLab] {} mensajes cargados de {}", loaded, properties.getBaseUrl());
        } catch (RuntimeException error) {
            reachable = false;
            log.warn("[MessageUcoLab] no respondió al arrancar ({}); se usan {} mensajes de la copia en disco",
                    describe(error), messages.size());
        }
        Duration interval = properties.getRefreshInterval();
        schedule = Flux.interval(interval, interval)
                .onBackpressureDrop()
                .concatMap(tick -> refresh().onErrorResume(error -> {
                    warnUnreachable(error);
                    return Mono.empty();
                }))
                .subscribe();
    }

    @Override
    public void close() {
        if (schedule != null) {
            schedule.dispose();
        }
    }

    private boolean configured() {
        return !isBlank(properties.getApplicationId()) && !isBlank(properties.getEnvironmentId());
    }

    // un código nuevo aparece en cuanto se crea, sin esperar al próximo minuto; como mucho una descarga cada 10 s
    private void refreshForMissing(String code) {
        long now = System.currentTimeMillis();
        long last = lastMissingRefresh.get();
        if (!configured() || now - last < MISSING_REFRESH_GAP_MS || !lastMissingRefresh.compareAndSet(last, now)) {
            return;
        }
        log.debug("[MessageUcoLab] {} no está en memoria: se descargan de nuevo los mensajes", code);
        refresh().subscribe(loaded -> { }, this::warnUnreachable);
    }

    private void warnUnreachable(Throwable error) {
        if (reachable) {
            log.warn("[MessageUcoLab] no responde ({}); se siguen usando los últimos {} mensajes",
                    describe(error), messages.size());
        }
        reachable = false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Message> toMessages(Map<String, Object> body) {
        Object list = body.get("messages");
        Map<String, Message> loaded = new HashMap<>();
        if (list instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> fields && fields.get("code") != null) {
                    Message message = toMessage(String.valueOf(fields.get("code")), (Map<String, Object>) fields);
                    loaded.put(message.code(), message);
                }
            }
        }
        return Map.copyOf(loaded);
    }

    private static Message toMessage(String code, Map<String, Object> fields) {
        return new Message(code, text(fields, "title"), text(fields, "content"), text(fields, "type"),
                text(fields, "category"), text(fields, "functionality"), Message.SOURCE_API);
    }

    // agrega un mensaje pedido suelto; la próxima descarga completa lo incluye también en la copia en disco
    private void remember(Message message) {
        Map<String, Message> updated = new HashMap<>(messages);
        updated.put(message.code(), message);
        messages = Map.copyOf(updated);
    }

    private static String text(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private void loadSnapshot() {
        if (snapshotFile == null || !Files.isRegularFile(snapshotFile)) {
            return;
        }
        Properties stored = new Properties();
        try (Reader reader = Files.newBufferedReader(snapshotFile, StandardCharsets.UTF_8)) {
            stored.load(reader);
        } catch (IOException error) {
            log.warn("[MessageUcoLab] no se pudo leer la copia {}: {}", snapshotFile, error.getMessage());
            return;
        }
        Map<String, Message> loaded = new HashMap<>();
        for (String key : stored.stringPropertyNames()) {
            if (key.endsWith(".title")) {
                String code = key.substring(0, key.length() - ".title".length());
                loaded.put(code, new Message(code, stored.getProperty(code + ".title"),
                        stored.getProperty(code + ".content"), stored.getProperty(code + ".type"),
                        stored.getProperty(code + ".category"), stored.getProperty(code + ".functionality"),
                        Message.SOURCE_SNAPSHOT));
            }
        }
        messages = Map.copyOf(loaded);
    }

    private void saveSnapshot(Map<String, Message> loaded) {
        if (snapshotFile == null) {
            return;
        }
        Properties stored = new Properties();
        for (Message message : loaded.values()) {
            String[] values = {message.title(), message.content(), message.type(), message.category(),
                    message.functionality()};
            for (int i = 0; i < FIELDS.length; i++) {
                if (values[i] != null) {
                    stored.setProperty(message.code() + "." + FIELDS[i], values[i]);
                }
            }
        }
        try {
            Files.createDirectories(snapshotFile.toAbsolutePath().getParent());
            Path temporary = snapshotFile.resolveSibling(snapshotFile.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                stored.store(writer, "Copia automática de MessageUcoLab. No la edites: se reemplaza en cada descarga.");
            }
            Files.move(temporary, snapshotFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            log.warn("[MessageUcoLab] no se pudo guardar la copia {}: {}", snapshotFile, error.getMessage());
        }
    }

    private static Path resolveSnapshotFile(MessageUcoLabProperties properties) {
        if (!properties.isSnapshotEnabled()) {
            return null;
        }
        if (!isBlank(properties.getSnapshotFile())) {
            return Path.of(properties.getSnapshotFile());
        }
        String name = properties.getApplicationId() + "-" + properties.getEnvironmentId() + ".properties";
        return Path.of(System.getProperty("java.io.tmpdir"), "messageucolab", name.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    private static String describe(Throwable error) {
        if (error instanceof WebClientResponseException response) {
            String body = response.getResponseBodyAsString().replaceAll("\\s+", " ").trim();
            return "HTTP " + response.getStatusCode().value()
                    + (body.isBlank() ? "" : ": " + body.substring(0, Math.min(body.length(), 200)));
        }
        Throwable cause = error.getCause() != null && error.getMessage() == null ? error.getCause() : error;
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

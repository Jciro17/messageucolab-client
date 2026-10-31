package co.edu.uco.messageucolab.client.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Dónde está MessageUcoLab y qué aplicación y entorno leer. Ningún texto de mensaje va aquí: todos
 * salen de MessageUcoLab.
 */
@ConfigurationProperties(prefix = "messageucolab")
public class MessageUcoLabProperties {

    /** Dirección de Kong delante de MessageUcoLab. */
    private String baseUrl = "http://localhost:8000";
    /** applicationId copiado de la pantalla de MessageUcoLab. */
    private String applicationId = "";
    /** environmentId copiado de la pantalla de MessageUcoLab. */
    private String environmentId = "";
    /** Cada cuánto se vuelven a descargar los mensajes, para ver los cambios sin reiniciar. */
    private Duration refreshInterval = Duration.ofMinutes(1);
    /** Cuánto se espera a MessageUcoLab en cada descarga. */
    private Duration timeout = Duration.ofSeconds(5);
    /** Guardar en disco la última descarga, para arrancar con textos aunque MessageUcoLab esté apagado. */
    private boolean snapshotEnabled = true;
    /** Archivo de la copia. Vacío: {@code <temporal>/messageucolab/<applicationId>-<environmentId>.properties}. */
    private String snapshotFile = "";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public Duration getRefreshInterval() { return refreshInterval; }
    public void setRefreshInterval(Duration refreshInterval) { this.refreshInterval = refreshInterval; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public boolean isSnapshotEnabled() { return snapshotEnabled; }
    public void setSnapshotEnabled(boolean snapshotEnabled) { this.snapshotEnabled = snapshotEnabled; }
    public String getSnapshotFile() { return snapshotFile; }
    public void setSnapshotFile(String snapshotFile) { this.snapshotFile = snapshotFile; }
}

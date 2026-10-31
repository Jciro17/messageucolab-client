package co.edu.uco.messageucolab.client;

import java.util.Map;

/**
 * Un mensaje de MessageUcoLab. La mayoría de las aplicaciones solo usa {@link #title()} y
 * {@link #content()}; el resto describe el mensaje tal como se registró en MessageUcoLab.
 *
 * @param source de dónde salió: {@link #SOURCE_API}, {@link #SOURCE_SNAPSHOT} o {@link #SOURCE_FALLBACK}
 */
public record Message(String code, String title, String content, String type, String category,
                      String functionality, String source) {

    /** Descargado de MessageUcoLab mientras la aplicación corre. */
    public static final String SOURCE_API = "api";
    /** Tomado de la copia en disco porque MessageUcoLab no respondió desde que arrancó la aplicación. */
    public static final String SOURCE_SNAPSHOT = "snapshot";
    /** El código no está en MessageUcoLab ni en la copia: el título es el código. */
    public static final String SOURCE_FALLBACK = "fallback";

    static final String UNAVAILABLE_CONTENT = "Este mensaje no está disponible en este momento.";

    /** El mensaje que se devuelve cuando el código no se encuentra en ninguna parte. */
    public static Message unavailable(String code) {
        return new Message(code, code, UNAVAILABLE_CONTENT, null, null, null, SOURCE_FALLBACK);
    }

    /** {@code true} si el texto viene de MessageUcoLab (en vivo o de la copia en disco). */
    public boolean available() {
        return !SOURCE_FALLBACK.equals(source);
    }

    /** Una copia con cada {@code {nombre}} del título y del contenido reemplazado por su valor. */
    public Message withVariables(Map<String, ?> variables) {
        if (variables == null || variables.isEmpty()) {
            return this;
        }
        return new Message(code, replace(title, variables), replace(content, variables), type, category,
                functionality, source);
    }

    private static String replace(String text, Map<String, ?> variables) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (Map.Entry<String, ?> variable : variables.entrySet()) {
            result = result.replace("{" + variable.getKey() + "}", String.valueOf(variable.getValue()));
        }
        return result;
    }
}

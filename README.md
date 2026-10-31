# messageucolab-client

Librería para que un backend del semillero tome sus mensajes de **MessageUcoLab** en lugar de un enum.
Trae la conexión, la memoria, la actualización y la copia en disco; el backend solo escribe su puerto y
un adaptador pequeño, como con cualquier otra integración en puertos y adaptadores.

| Módulo | Qué trae |
|---|---|
| `messageucolab-client-spring-boot-starter` | El cliente `MessageUcoLabMessages`, registrado solo como bean de `Messages`. **Es la única dependencia que se agrega** |
| `messageucolab-client-core` | `Messages` y `Message`, en Java puro. Llega con el starter |

Funciona con Java 17 o superior y Spring Boot 3.2 o superior (probado con 3.2.5 y 4.1.1).

## Uso

**1. `pom.xml`**

```xml
<repositories>
    <!-- mismo id que el del componente de seguridad: usa el mismo token de GitHub -->
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/Jciro17/messageucolab-client</url>
    </repository>
</repositories>

<dependency>
    <groupId>co.edu.uco</groupId>
    <artifactId>messageucolab-client-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

GitHub Packages pide un token aunque el paquete sea público. Es el mismo `<server>` con id `github` del
`settings.xml` que ya usan para el componente de seguridad; el token necesita `read:packages`.

**2. `application.yml`**: solo dónde está MessageUcoLab. Ningún texto va aquí.

```yaml
messageucolab:
  base-url: http://localhost:8000
  application-id: ${MESSAGEUCOLAB_APPLICATION_ID}
  environment-id: ${MESSAGEUCOLAB_ENVIRONMENT_ID}
```

**3. Puerto secundario** (en `application/secondaryports/message`), que reemplaza al enum:

```java
public interface CatalogMessagePort {
    Mono<Mensaje> get(String codigo);
    Mono<Mensaje> get(String codigo, Map<String, ?> variables);
}
```

**4. Adaptador secundario** (en `infrastructure/secondaryadapters/message`), que delega en la librería:

```java
@Component
public class MessageUcoLabMessageAdapter implements CatalogMessagePort {

    private final Messages messageUcoLab;   // lo registra el starter

    public MessageUcoLabMessageAdapter(Messages messageUcoLab) { this.messageUcoLab = messageUcoLab; }

    @Override
    public Mono<Mensaje> get(String codigo) {
        return messageUcoLab.find(codigo).map(MessageUcoLabMessageAdapter::toMensaje);
    }

    @Override
    public Mono<Mensaje> get(String codigo, Map<String, ?> variables) {
        return messageUcoLab.find(codigo, variables).map(MessageUcoLabMessageAdapter::toMensaje);
    }

    private static Mensaje toMensaje(Message m) { return new Mensaje(m.code(), m.title(), m.content(), m.source()); }
}
```

**5. Reemplazar el enum** en los casos de uso, que reciben el puerto:

```java
// antes
throw new ReglaNegocioException(MensajeEnum.MSG_001.getTitulo(), MensajeEnum.MSG_001.getContenido());

// después: el caso de uso reactivo devuelve el mensaje
return mensajes.get("MSG_001");

// o, si necesita el texto para lanzar una excepción
return mensajes.get("MSG_001").flatMap(m -> Mono.error(new ReglaNegocioException(m.title(), m.content())));
```

`Message` trae `code()`, `title()`, `content()`, `type()`, `category()`, `functionality()` y `source()`,
con los mismos nombres que la ruta pública y que el minifront; cada equipo pasa a su `Mensaje` lo que
use. [InventarioWebFlux](https://github.com/Jciro17/InventarioWebFlux) es el ejemplo completo.

## Cómo se comporta

| Situación | Qué devuelven `find` y `get` | `source()` |
|---|---|---|
| MessageUcoLab responde | El texto de MessageUcoLab, desde memoria (no hace una llamada por mensaje) | `api` |
| MessageUcoLab se apaga mientras la aplicación corre | Los últimos textos descargados | `api` |
| MessageUcoLab está apagado cuando la aplicación arranca | La copia que se guardó en disco en la última descarga | `snapshot` |
| El código no estaba en memoria (por ejemplo, se acaba de crear) | `find` lo pide a MessageUcoLab en ese momento; `get` lo da por no disponible esa vez | `api` / `fallback` |
| El código no existe en MessageUcoLab, o no hay ninguna copia | El código como título y un texto genérico | `fallback` |

- Al arrancar descarga **todos** los mensajes de la aplicación y el entorno (`GET /messageucolab/v1/public/messages`).
- Los vuelve a descargar cada minuto (`messageucolab.refresh-interval`), así los cambios hechos en la
  pantalla de MessageUcoLab se ven sin reiniciar.
- `find(código)` (reactivo, el que usa el adaptador) pide en el momento un código que no tiene en memoria:
  un mensaje recién creado en MessageUcoLab aparece en la siguiente petición.
- `get(código)` (síncrono) responde solo desde memoria, como un enum.
- Ninguno lanza excepción, y la aplicación arranca aunque MessageUcoLab esté apagado.
- Para forzar una descarga, inyecta `MessageUcoLabMessages` y llama `refresh()`.

| Propiedad | Por defecto |
|---|---|
| `messageucolab.base-url` | `http://localhost:8000` |
| `messageucolab.application-id` / `environment-id` | Obligatorias |
| `messageucolab.refresh-interval` | `1m` |
| `messageucolab.timeout` | `5s` |
| `messageucolab.snapshot-enabled` | `true` |
| `messageucolab.snapshot-file` | `<temporal>/messageucolab/<applicationId>-<environmentId>.properties` |

## Publicar una versión

1. Cambia la versión en los tres `pom.xml` (por ejemplo `0.2.0`).
2. Haz commit y sube la etiqueta `v0.2.0`. El workflow **Publicar** la publica en GitHub Packages.

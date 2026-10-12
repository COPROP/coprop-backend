package bo.coprop.condominium.internal;

import bo.coprop.condominium.CondominiumConfigChanged;
import bo.coprop.condominium.CondominiumRegistered;
import bo.coprop.condominium.CondominiumView;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CondominiumsService implements Condominiums {

    /** El MVP factura solo en bolivianos (analisis 2.1). */
    private static final String MONEDA_DEL_MVP = "BOB";

    private final CondominiumRepository condominios;
    private final CondominiumConfigRepository configuraciones;
    private final ApplicationEventPublisher eventos;
    private final Clock reloj;

    CondominiumsService(
            CondominiumRepository condominios,
            CondominiumConfigRepository configuraciones,
            ApplicationEventPublisher eventos,
            Clock reloj) {
        this.condominios = condominios;
        this.configuraciones = configuraciones;
        this.eventos = eventos;
        this.reloj = reloj;
    }

    @Override
    @Transactional
    public CondominiumView register(NewCondominium nuevo, String actor) {
        // El NIT identifica legalmente al condominio: dos con el mismo serian el mismo, y a partir
        // de ahi los pagos de uno podrian aplicarse al otro.
        if (condominios.existsByNit(nuevo.nit())) {
            throw new ReglaDeNegocioViolada("Ya existe un condominio con el NIT %s.".formatted(nuevo.nit()));
        }

        Instant ahora = reloj.instant();
        Condominium condominio = condominios.save(
                new Condominium(nuevo.name(), nuevo.nit(), nuevo.type(), nuevo.timeZone(), MONEDA_DEL_MVP, ahora));
        CondominiumConfig config = configuraciones.save(
                new CondominiumConfig(condominio.getId(), nuevo.issueDay(), nuevo.dueDay(), ahora));

        eventos.publishEvent(new CondominiumRegistered(condominio.getId(), condominio.getName(), actor, ahora));

        return vista(condominio, config);
    }

    @Override
    @Transactional
    public CondominiumView changeConfig(UUID condominiumId, int issueDay, int dueDay, String actor) {
        Condominium condominio = buscar(condominiumId);
        Instant ahora = reloj.instant();

        // Cerrar la vigente y abrir otra, en vez de actualizar. La fila anterior es historia: es
        // lo que permite saber con que configuracion se emitio cada cosa.
        //
        // Se exige que exista: todo condominio nace con una y nunca se cierra sin abrir la
        // siguiente, asi que no haberla es un estado roto, no un caso que tolerar. Ademas es de
        // donde salen los valores de "antes" del evento.
        CondominiumConfig vigente = configuracionVigenteDe(condominiumId);
        vigente.close(ahora);

        // El flush no es decorativo. Hibernate ordena todos los INSERT antes que los UPDATE dentro
        // de un mismo flush, asi que sin esto la fila nueva entra mientras la vieja sigue abierta y
        // el indice parcial de "una sola vigente por condominio" la rechaza. El indice esta
        // haciendo su trabajo; lo que hay que arreglar es el orden.
        configuraciones.flush();

        CondominiumConfig nueva = configuraciones.save(new CondominiumConfig(condominiumId, issueDay, dueDay, ahora));

        eventos.publishEvent(new CondominiumConfigChanged(
                condominiumId, vigente.getIssueDay(), vigente.getDueDay(), issueDay, dueDay, actor, ahora));

        return vista(condominio, nueva);
    }

    @Override
    @Transactional(readOnly = true)
    public CondominiumView find(UUID condominiumId) {
        Condominium condominio = buscar(condominiumId);
        return vista(condominio, configuracionVigenteDe(condominiumId));
    }

    /** La unica fila de configuracion abierta del condominio. No haberla es un estado roto. */
    private CondominiumConfig configuracionVigenteDe(UUID condominiumId) {
        return configuraciones
                .findByCondominiumIdAndValidToIsNull(condominiumId)
                .orElseThrow(() -> new IllegalStateException(
                        "El condominio %s no tiene configuracion vigente.".formatted(condominiumId)));
    }

    private Condominium buscar(UUID condominiumId) {
        return condominios
                .findById(condominiumId)
                .orElseThrow(() -> RecursoNoEncontrado.de("el condominio", condominiumId));
    }

    private static CondominiumView vista(Condominium condominio, CondominiumConfig config) {
        return new CondominiumView(
                condominio.getId(),
                condominio.getName(),
                condominio.getNit(),
                condominio.getType(),
                condominio.getTimeZone(),
                condominio.getCurrency(),
                condominio.getStatus(),
                config.getIssueDay(),
                config.getDueDay());
    }
}

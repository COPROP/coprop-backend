package bo.coprop.condominium.internal;

import bo.coprop.condominium.CondominiumConfigChanged;
import bo.coprop.condominium.CondominiumEdited;
import bo.coprop.condominium.CondominiumRegistered;
import bo.coprop.condominium.CondominiumView;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CondominiumsService implements Condominiums {

    /** El MVP factura solo en bolivianos (analisis 2.1). */
    private static final String MONEDA_DEL_MVP = "BOB";

    private final CondominiumRepository condominiums;
    private final CondominiumConfigRepository configs;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    CondominiumsService(
            CondominiumRepository condominiums,
            CondominiumConfigRepository configs,
            ApplicationEventPublisher eventos,
            Clock clock) {
        this.condominiums = condominiums;
        this.configs = configs;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CondominiumView register(NewCondominium body, String actor) {
        // El NIT identifica legalmente al condominio: dos con el mismo serian el mismo, y a partir
        // de ahi los pagos de uno podrian aplicarse al otro.
        if (condominiums.existsByNit(body.nit())) {
            throw new BusinessRuleViolated("Ya existe un condominio con el NIT %s.".formatted(body.nit()));
        }

        Instant ahora = clock.instant();
        Condominium condominium = condominiums.save(
                new Condominium(body.name(), body.nit(), body.type(), body.timeZone(), MONEDA_DEL_MVP, ahora));
        CondominiumConfig config =
                configs.save(new CondominiumConfig(condominium.getId(), body.issueDay(), body.dueDay(), ahora));

        eventos.publishEvent(new CondominiumRegistered(condominium.getId(), condominium.getName(), actor, ahora));

        return view(condominium, config);
    }

    @Override
    @Transactional
    public CondominiumView changeConfig(UUID condominiumId, int issueDay, int dueDay, String actor) {
        Condominium condominium = requireById(condominiumId);
        Instant ahora = clock.instant();

        // Cerrar la vigente y abrir otra, en vez de actualizar. La fila anterior es historia: es
        // lo que permite saber con que configuracion se emitio cada cosa.
        //
        // Se exige que exista: todo condominio nace con una y nunca se cierra sin abrir la
        // siguiente, asi que no haberla es un estado roto, no un caso que tolerar. Ademas es de
        // donde salen los valores de antes del evento.
        CondominiumConfig current = currentConfigOf(condominiumId);
        current.close(ahora);

        // El flush no es decorativo. Hibernate ordena todos los INSERT antes que los UPDATE dentro
        // de un mismo flush, asi que sin esto la fila nueva entra mientras la vieja sigue abierta y
        // el indice parcial de "una sola vigente por condominio" la rechaza. El indice esta
        // haciendo su trabajo; lo que hay que arreglar es el orden.
        configs.flush();

        CondominiumConfig body = configs.save(new CondominiumConfig(condominiumId, issueDay, dueDay, ahora));

        eventos.publishEvent(new CondominiumConfigChanged(
                condominiumId, current.getIssueDay(), current.getDueDay(), issueDay, dueDay, actor, ahora));

        return view(condominium, body);
    }

    @Override
    @Transactional
    public CondominiumView edit(UUID condominiumId, String name, String nit, @Nullable String address, String actor) {
        Condominium condominium = requireById(condominiumId);

        // Con IdNot y no con existsByNit: guardar el NIT que ya tenia no es un choque, y sin esto
        // corregir solo el nombre seria imposible.
        if (condominiums.existsByNitAndIdNot(nit, condominiumId)) {
            throw new BusinessRuleViolated("Ya existe un condominio con el NIT %s.".formatted(nit));
        }

        // Los valores de antes se leen ANTES de editar. Con la entidad gestionada, leerlos despues
        // devolveria los nuevos y el evento diria que nada cambio.
        String previousName = condominium.getName();
        String nitAnterior = condominium.getNit();
        String direccionAnterior = condominium.getAddress();

        condominium.edit(name, nit, address);

        eventos.publishEvent(new CondominiumEdited(
                condominiumId,
                previousName,
                nitAnterior,
                direccionAnterior,
                name,
                nit,
                address,
                actor,
                clock.instant()));

        return view(condominium, currentConfigOf(condominiumId));
    }

    @Override
    @Transactional(readOnly = true)
    public CondominiumView find(UUID condominiumId) {
        Condominium condominium = requireById(condominiumId);
        return view(condominium, currentConfigOf(condominiumId));
    }

    /** La unica fila de configuracion abierta del condominio. No haberla es un estado roto. */
    private CondominiumConfig currentConfigOf(UUID condominiumId) {
        return configs.findByCondominiumIdAndValidToIsNull(condominiumId)
                .orElseThrow(() -> new IllegalStateException(
                        "El condominio %s no tiene configuracion vigente.".formatted(condominiumId)));
    }

    private Condominium requireById(UUID condominiumId) {
        return condominiums
                .findById(condominiumId)
                .orElseThrow(() -> ResourceNotFound.de("el condominio", condominiumId));
    }

    private static CondominiumView view(Condominium condominium, CondominiumConfig config) {
        return new CondominiumView(
                condominium.getId(),
                condominium.getName(),
                condominium.getNit(),
                condominium.getAddress(),
                condominium.getType(),
                condominium.getTimeZone(),
                condominium.getCurrency(),
                condominium.getStatus(),
                config.getIssueDay(),
                config.getDueDay());
    }
}

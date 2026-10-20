package bo.coprop.billing.internal;

import bo.coprop.billing.ChargeConceptRegistered;
import bo.coprop.billing.ChargeConceptView;
import bo.coprop.billing.ChargeConcepts;
import bo.coprop.billing.NewChargeConcept;
import bo.coprop.condominium.Condominiums;
import bo.coprop.shared.StateConflict;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChargeConceptsService implements ChargeConcepts {

    private final ChargeConceptRepository concepts;
    private final Condominiums condominiums;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    ChargeConceptsService(
            ChargeConceptRepository concepts,
            Condominiums condominiums,
            ApplicationEventPublisher eventos,
            Clock clock) {
        this.concepts = concepts;
        this.condominiums = condominiums;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ChargeConceptView register(UUID condominiumId, NewChargeConcept body, String actor) {
        requireCondominium(condominiumId);

        // Se comprueba antes de tocar la base aunque la restriccion unica tambien lo impida: asi el
        // cliente recibe un 409 con el codigo repetido escrito, y no un choque de integridad que
        // acabaria en 500.
        if (concepts.existsByCondominiumIdAndCode(condominiumId, body.code())) {
            throw new StateConflict(
                    "El condominio ya tiene un concepto de cobro con el codigo %s.".formatted(body.code()));
        }

        ChargeConcept created = concepts.save(
                new ChargeConcept(condominiumId, body.code(), body.name(), body.type(), body.paymentGroup()));

        eventos.publishEvent(new ChargeConceptRegistered(
                condominiumId,
                created.getId(),
                created.getCode(),
                created.getName(),
                created.getType(),
                created.getPaymentGroup(),
                actor,
                clock.instant()));

        return view(created);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChargeConceptView> list(UUID condominiumId) {
        requireCondominium(condominiumId);
        return concepts.findByCondominiumIdOrderByCode(condominiumId).stream()
                .map(ChargeConceptsService::view)
                .toList();
    }

    /**
     * El condominio tiene que existir.
     *
     * <p>Se pregunta por {@link Condominiums}, la interfaz publica de ese modulo --la dependencia la
     * declara el {@code package-info} de este-- y no por su repositorio, que es interno. Es una
     * lectura, asi que no hay dos modulos escribiendo en la misma transaccion, que es lo que la
     * regla 5 de ARCHITECTURE.md prohibe.
     *
     * <p>Podria parecer redundante en HTTP, donde el filtro del #17 ya respondio 404 a quien no es
     * miembro: no lo es, porque un condominio inexistente no tiene membresias que mirar y porque la
     * operacion del dominio tiene que valer igual llamada desde un worker.
     */
    private void requireCondominium(UUID condominiumId) {
        condominiums.find(condominiumId);
    }

    private static ChargeConceptView view(ChargeConcept concept) {
        return new ChargeConceptView(
                concept.getId(),
                concept.getCondominiumId(),
                concept.getCode(),
                concept.getName(),
                concept.getType(),
                concept.getPaymentGroup());
    }
}

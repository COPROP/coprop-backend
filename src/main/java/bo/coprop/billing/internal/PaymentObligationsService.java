package bo.coprop.billing.internal;

import bo.coprop.billing.NewPaymentObligation;
import bo.coprop.billing.PaymentObligationCancelled;
import bo.coprop.billing.PaymentObligationIssued;
import bo.coprop.billing.PaymentObligationPaid;
import bo.coprop.billing.PaymentObligationStatus;
import bo.coprop.billing.PaymentObligationUnderAgreement;
import bo.coprop.billing.PaymentObligationView;
import bo.coprop.billing.PaymentObligations;
import bo.coprop.condominium.CondominiumView;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.Units;
import bo.coprop.identity.People;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentObligationsService implements PaymentObligations {

    private final PaymentObligationRepository obligations;
    private final ChargeConceptRepository concepts;
    private final BillingPeriodRepository periods;
    private final Condominiums condominiums;
    private final Units units;
    private final People people;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    /** La composicion del numero legible, compartida con la emision de expensas del #24. */
    private final ObligationNumbers numbers;

    PaymentObligationsService(
            PaymentObligationRepository obligations,
            ChargeConceptRepository concepts,
            BillingPeriodRepository periods,
            Condominiums condominiums,
            Units units,
            People people,
            ApplicationEventPublisher eventos,
            Clock clock) {
        this.obligations = obligations;
        this.concepts = concepts;
        this.periods = periods;
        this.condominiums = condominiums;
        this.units = units;
        this.people = people;
        this.eventos = eventos;
        this.clock = clock;
        this.numbers = new ObligationNumbers(obligations);
    }

    @Override
    @Transactional
    public PaymentObligationView register(UUID condominiumId, NewPaymentObligation body, String actor) {
        CondominiumView condominium = condominiums.find(condominiumId);

        // El origen va entero o no va. Se comprueba aqui y en la base: aqui para decir que falta, y
        // alli porque el dominio tambien se llama desde un worker.
        if ((body.sourceType() == null) != (body.sourceId() == null)) {
            throw new BusinessRuleViolated(
                    "El origen de la obligacion necesita el tipo y el identificador, o ninguno de los dos.");
        }

        // Las comprobaciones de pertenencia. Las hace el servicio para responder 404 con el recurso
        // señalado; las claves foraneas compuestas de la V13 las repiten en la base, que es lo que de
        // verdad impide mezclar dos condominios.
        String unitCode = units.find(condominiumId, body.unitId()).code();
        people.find(body.responsiblePersonId());
        ChargeConcept concept = concepts.findByIdAndCondominiumId(body.conceptId(), condominiumId)
                .orElseThrow(() -> ResourceNotFound.de("el concepto de cobro", body.conceptId()));
        BillingPeriod period = periodoDe(condominiumId, body.periodId());
        requireParent(condominiumId, body.parentObligationId());

        String number = numbers.free(condominiumId, concept.getCode(), period, body.dueDate(), unitCode);

        PaymentObligation created = obligations.save(new PaymentObligation(
                condominiumId,
                body.unitId(),
                body.responsiblePersonId(),
                body.conceptId(),
                body.periodId(),
                number,
                body.amount(),
                condominium.currency(),
                body.dueDate(),
                body.parentObligationId(),
                body.sourceType(),
                body.sourceId()));

        // El alta NO se audita, y es una decision: un borrador no es deuda de nadie --el propietario
        // no lo ve-- y auditar el calculo mensual entero llenaria la bitacora de filas que no son
        // acciones de nadie. Lo que si queda es la emision, que es cuando la deuda existe.
        return view(created, hoyEn(condominium));
    }

    @Override
    @Transactional
    public PaymentObligationView issue(UUID condominiumId, UUID obligationId, String actor) {
        PaymentObligation obligation = require(condominiumId, obligationId);
        obligation.issue(clock.instant());

        eventos.publishEvent(new PaymentObligationIssued(
                condominiumId,
                obligation.getId(),
                obligation.getUnitId(),
                obligation.getResponsiblePersonId(),
                obligation.getNumber(),
                obligation.getAmount(),
                obligation.getCurrency(),
                obligation.getDueDate(),
                actor,
                clock.instant()));

        return view(obligation, hoyEn(condominiums.find(condominiumId)));
    }

    @Override
    @Transactional
    public PaymentObligationView pay(UUID condominiumId, UUID obligationId, String actor) {
        PaymentObligation obligation = require(condominiumId, obligationId);
        obligation.pay();

        eventos.publishEvent(new PaymentObligationPaid(
                condominiumId,
                obligation.getId(),
                obligation.getUnitId(),
                obligation.getNumber(),
                obligation.getAmount(),
                obligation.getCurrency(),
                actor,
                clock.instant()));

        return view(obligation, hoyEn(condominiums.find(condominiumId)));
    }

    @Override
    @Transactional
    public PaymentObligationView cancel(UUID condominiumId, UUID obligationId, String reason, String actor) {
        PaymentObligation obligation = require(condominiumId, obligationId);
        PaymentObligationStatus previo = obligation.getStatus();
        obligation.cancel();

        eventos.publishEvent(new PaymentObligationCancelled(
                condominiumId,
                obligation.getId(),
                obligation.getUnitId(),
                obligation.getNumber(),
                previo,
                reason,
                actor,
                clock.instant()));

        return view(obligation, hoyEn(condominiums.find(condominiumId)));
    }

    @Override
    @Transactional
    public PaymentObligationView putUnderAgreement(UUID condominiumId, UUID obligationId, String actor) {
        PaymentObligation obligation = require(condominiumId, obligationId);
        obligation.putUnderAgreement();

        eventos.publishEvent(new PaymentObligationUnderAgreement(
                condominiumId,
                obligation.getId(),
                obligation.getUnitId(),
                obligation.getNumber(),
                actor,
                clock.instant()));

        return view(obligation, hoyEn(condominiums.find(condominiumId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentObligationView> ofUnit(UUID condominiumId, UUID unitId) {
        CondominiumView condominium = condominiums.find(condominiumId);
        units.find(condominiumId, unitId);
        LocalDate hoy = hoyEn(condominium);
        return obligations.findByCondominiumIdAndUnitIdOrderByDueDateDesc(condominiumId, unitId).stream()
                .map(obligation -> view(obligation, hoy))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentObligationView> ofPeriod(UUID condominiumId, UUID periodId) {
        CondominiumView condominium = condominiums.find(condominiumId);
        periodoDe(condominiumId, periodId);
        LocalDate hoy = hoyEn(condominium);
        return obligations.findByCondominiumIdAndPeriodIdOrderByNumber(condominiumId, periodId).stream()
                .map(obligation -> view(obligation, hoy))
                .toList();
    }

    private PaymentObligation require(UUID condominiumId, UUID obligationId) {
        return obligations
                .findByIdAndCondominiumId(obligationId, condominiumId)
                .orElseThrow(() -> ResourceNotFound.de("la obligacion de pago", obligationId));
    }

    /**
     * El periodo, si viene uno.
     *
     * <p>Nulo es legitimo: una multa o una reserva no pertenecen a ningun mes. Lo que no se admite es
     * un periodo que no existe o que es de otro condominio.
     */
    private @Nullable BillingPeriod periodoDe(UUID condominiumId, @Nullable UUID periodId) {
        if (periodId == null) {
            return null;
        }
        return periods.findByIdAndCondominiumId(periodId, condominiumId)
                .orElseThrow(() -> ResourceNotFound.de("el periodo de facturacion", periodId));
    }

    /**
     * La obligacion padre, si viene una.
     *
     * <p>Se comprueba que exista y sea del mismo condominio. <strong>No se comprueba que el padre sea
     * cobrable ni que esto sea mora</strong>: quien decide que una obligacion genera recargo es la
     * politica del #27, y adelantarlo aqui seria escribir su regla sin su ticket.
     */
    private void requireParent(UUID condominiumId, @Nullable UUID parentObligationId) {
        if (parentObligationId != null) {
            require(condominiumId, parentObligationId);
        }
    }

    /**
     * El dia de hoy en la zona del condominio.
     *
     * <p>No {@code LocalDate.now()}: una obligacion vence el dia en que vence <em>alli</em>, y el
     * analisis 12.2 pide que los calculos de vencimiento se hagan en la zona del condominio. En un
     * servidor en UTC, a las 21:00 de La Paz ya es manaña, y una deuda apareceria vencida un dia
     * antes.
     */
    private LocalDate hoyEn(CondominiumView condominium) {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of(condominium.timeZone()));
    }

    private static PaymentObligationView view(PaymentObligation obligation, LocalDate hoy) {
        return new PaymentObligationView(
                obligation.getId(),
                obligation.getCondominiumId(),
                obligation.getUnitId(),
                obligation.getResponsiblePersonId(),
                obligation.getConceptId(),
                obligation.getPeriodId(),
                obligation.getNumber(),
                obligation.getAmount(),
                obligation.getCurrency(),
                obligation.getIssuedAt(),
                obligation.getDueDate(),
                obligation.getStatus(),
                obligation.isOverdue(hoy),
                obligation.getParentObligationId(),
                obligation.getSourceType(),
                obligation.getSourceId());
    }
}

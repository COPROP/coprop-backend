package bo.coprop.billing.internal;

import bo.coprop.billing.BillingPeriodOpened;
import bo.coprop.billing.BillingPeriodStatus;
import bo.coprop.billing.BillingPeriodStatusChanged;
import bo.coprop.billing.BillingPeriodView;
import bo.coprop.billing.BillingPeriods;
import bo.coprop.billing.NewBillingPeriod;
import bo.coprop.condominium.Condominiums;
import bo.coprop.shared.ResourceNotFound;
import bo.coprop.shared.StateConflict;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BillingPeriodsService implements BillingPeriods {

    private final BillingPeriodRepository periods;
    private final Condominiums condominiums;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    BillingPeriodsService(
            BillingPeriodRepository periods,
            Condominiums condominiums,
            ApplicationEventPublisher eventos,
            Clock clock) {
        this.periods = periods;
        this.condominiums = condominiums;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Override
    @Transactional
    public BillingPeriodView open(UUID condominiumId, NewBillingPeriod body, String actor) {
        requireCondominium(condominiumId);

        // Se comprueba antes de tocar la base aunque la restriccion unica tambien lo impida: asi el
        // cliente recibe un 409 con el mes escrito, y no un choque de integridad que acabaria en
        // 500. Es el mismo reparto que en el alta de un concepto de cobro.
        if (periods.existsByCondominiumIdAndYearAndMonth(condominiumId, body.year(), body.month())) {
            throw new StateConflict(
                    "El condominio ya tiene abierto el periodo %d-%02d.".formatted(body.year(), body.month()));
        }

        BillingPeriod opened = periods.save(new BillingPeriod(condominiumId, body.year(), body.month()));

        eventos.publishEvent(new BillingPeriodOpened(
                condominiumId, opened.getId(), opened.getYear(), opened.getMonth(), actor, clock.instant()));

        return view(opened);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillingPeriodView> list(UUID condominiumId) {
        requireCondominium(condominiumId);
        return periods.findByCondominiumIdOrderByYearDescMonthDesc(condominiumId).stream()
                .map(BillingPeriodsService::view)
                .toList();
    }

    @Override
    @Transactional
    public BillingPeriodView issue(UUID condominiumId, UUID periodId, String actor) {
        BillingPeriod period = require(condominiumId, periodId);
        BillingPeriodStatus from = period.getStatus();

        // La transicion la decide la entidad. Si la rechaza, la excepcion sale antes de que se
        // publique nada y la transaccion no deja fila tocada: es el criterio de que pasar de CLOSED
        // a ISSUED "no toca la base".
        period.issue();

        publishChange(period, from, actor);
        return view(period);
    }

    @Override
    @Transactional
    public BillingPeriodView close(UUID condominiumId, UUID periodId, String actor) {
        BillingPeriod period = require(condominiumId, periodId);
        BillingPeriodStatus from = period.getStatus();

        // Falta aqui la comprobacion de que no queden obligaciones en DRAFT, que el ticket pide y
        // que no se puede escribir todavia: payment_obligation nace con el #23. Entra con ese
        // issue, en este mismo metodo y con una sola consulta, porque la obligacion es de este
        // modulo. Hasta entonces el #101 queda abierto.
        period.close();

        publishChange(period, from, actor);
        return view(period);
    }

    /**
     * El periodo, o 404.
     *
     * <p>No se pregunta primero si el condominio existe: la consulta lleva el condominio dentro, asi
     * que un periodo de otro ya no se encuentra, y el mensaje es el mismo en los dos casos. Es lo
     * contrario que en el alta y el listado, donde no hay periodo que buscar y el 404 del condominio
     * es la unica forma de no inventarse uno.
     */
    private BillingPeriod require(UUID condominiumId, UUID periodId) {
        return periods.findByIdAndCondominiumId(periodId, condominiumId)
                .orElseThrow(() -> ResourceNotFound.de("el periodo de facturacion", periodId));
    }

    /**
     * El condominio tiene que existir.
     *
     * <p>Por {@link Condominiums}, la interfaz publica de ese modulo, igual que en el catalogo de
     * conceptos: es una lectura, asi que no hay dos modulos escribiendo en la misma transaccion.
     */
    private void requireCondominium(UUID condominiumId) {
        condominiums.find(condominiumId);
    }

    private void publishChange(BillingPeriod period, BillingPeriodStatus from, String actor) {
        eventos.publishEvent(new BillingPeriodStatusChanged(
                period.getCondominiumId(),
                period.getId(),
                period.getYear(),
                period.getMonth(),
                from,
                period.getStatus(),
                actor,
                clock.instant()));
    }

    private static BillingPeriodView view(BillingPeriod period) {
        return new BillingPeriodView(
                period.getId(), period.getCondominiumId(), period.getYear(), period.getMonth(), period.getStatus());
    }
}

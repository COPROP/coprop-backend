package bo.coprop.billing.internal;

import bo.coprop.billing.BillingPeriodStatus;
import bo.coprop.shared.StateConflict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * El periodo de facturacion de un mes: a que mes pertenece cada obligacion (analisis 5.2).
 *
 * <p><strong>Aqui esta la maquina de estados, y no en el servicio.</strong> Es la diferencia con la
 * suspension de una membresia --que vive en {@code MembershipsService} porque arrastra reglas sobre
 * el ultimo administrador y sobre las sesiones-- y es deliberada: la del periodo es una maquina
 * cerrada, no depende de nada mas que del estado actual, y el ticket pide que se fuerce en el
 * dominio. Puesta aqui vale igual llamada desde el worker del #28 que desde una peticion, y no hay
 * forma de cambiar el estado sin pasar por ella.
 */
@Entity
@Table(name = "billing_period")
class BillingPeriod {

    @Id
    private UUID id;

    /** Nunca nulo: un periodo sin condominio no existe. Lo garantiza ademas la clave foranea. */
    @Column(name = "condominium_id", nullable = false)
    private UUID condominiumId;

    /**
     * El año, en una columna propia.
     *
     * <p>Dos numeros y no una fecha: un periodo es un mes entero. El porque esta en la V12 y se
     * resume en que una columna {@code DATE} obligaria a elegir un dia que no significa nada.
     */
    @Column(nullable = false)
    private int year;

    /** El mes, de 1 a 12. Lo acotan la validacion del cuerpo y la restriccion de la base. */
    @Column(nullable = false)
    private int month;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillingPeriodStatus status;

    protected BillingPeriod() {}

    BillingPeriod(UUID condominiumId, int year, int month) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.year = year;
        this.month = month;
        // El estado no es un parametro: un periodo nace siempre en DRAFT. El porque esta en
        // BillingPeriodStatus.
        this.status = BillingPeriodStatus.DRAFT;
    }

    /**
     * Lo saca a cobro.
     *
     * <p>Emitir dos veces es un conflicto y no una operacion idempotente, a proposito: lo que el #28
     * necesita idempotente es <em>abrir</em> el periodo, no emitirlo. Un segundo emitir vendria de
     * un worker que ya corrio, y responderle que todo bien dejaria creer que acaba de emitir unas
     * obligaciones que ya estaban. El #24 pide justamente eso: "emitir un periodo ya emitido no crea
     * nada y lo informa".
     *
     * @throws StateConflict si ya estaba emitido o si estaba cerrado
     */
    void issue() {
        if (status != BillingPeriodStatus.DRAFT) {
            throw new StateConflict(
                    "El periodo %d-%02d esta en %s y solo se emite uno en DRAFT.".formatted(year, month, status));
        }
        this.status = BillingPeriodStatus.ISSUED;
    }

    /**
     * Cierra el mes.
     *
     * <p>Solo desde {@code ISSUED}: cerrar un mes que nunca emitio no es un cierre contable, es un
     * periodo que sobraba --y uno que sobra se queda en {@code DRAFT} sin molestar a nadie--. Desde
     * {@code CLOSED} tampoco, que es el criterio de que la maquina no vuelve atras.
     *
     * @throws StateConflict si todavia esta en {@code DRAFT} o si ya estaba cerrado
     */
    void close() {
        if (status != BillingPeriodStatus.ISSUED) {
            throw new StateConflict(
                    "El periodo %d-%02d esta en %s y solo se cierra uno en ISSUED.".formatted(year, month, status));
        }
        this.status = BillingPeriodStatus.CLOSED;
    }

    UUID getId() {
        return id;
    }

    UUID getCondominiumId() {
        return condominiumId;
    }

    int getYear() {
        return year;
    }

    int getMonth() {
        return month;
    }

    BillingPeriodStatus getStatus() {
        return status;
    }
}

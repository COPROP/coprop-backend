package bo.coprop.billing.internal;

import bo.coprop.billing.BillingPeriodStatus;
import bo.coprop.billing.BillingPeriodView;
import bo.coprop.billing.BillingPeriods;
import bo.coprop.billing.ChargeConceptType;
import bo.coprop.billing.ExpenseLineView;
import bo.coprop.billing.ExpenseMethod;
import bo.coprop.billing.ExpenseRunView;
import bo.coprop.billing.ExpenseSettingChanged;
import bo.coprop.billing.ExpenseSettingView;
import bo.coprop.billing.Expenses;
import bo.coprop.billing.ExpensesIssued;
import bo.coprop.billing.NewExpenseSetting;
import bo.coprop.billing.PaymentObligationIssued;
import bo.coprop.billing.PaymentObligationStatus;
import bo.coprop.condominium.BillingResponsibilityView;
import bo.coprop.condominium.CondominiumView;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.UnitRelations;
import bo.coprop.condominium.UnitView;
import bo.coprop.condominium.Units;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
import bo.coprop.shared.StateConflict;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExpensesService implements Expenses {

    /**
     * Lo que cuenta como "ya a cobro" al decidir si un mes se emitio.
     *
     * <p>Una anulada no esta aqui: un mes que se emitio y se anulo entero se puede volver a calcular
     * y a emitir, que es lo que corresponde cuando la emision estuvo mal.
     */
    private static final Set<PaymentObligationStatus> A_COBRO = EnumSet.of(
            PaymentObligationStatus.ISSUED, PaymentObligationStatus.PAID, PaymentObligationStatus.IN_AGREEMENT);

    private static final BigDecimal CIEN = new BigDecimal("100");

    /**
     * Con cuantos decimales se calcula la cuota exacta de cada unidad antes de redondear.
     *
     * <p>Ocho bastarian --el total va en centavos, entero, y la alicuota tiene seis decimales-- y
     * con diez el calculo intermedio es <strong>exacto</strong>, sin un solo redondeo. Es lo que
     * permite que el reparto del resto sea el de los restos mayores de verdad y no el de unos restos
     * ya desfigurados.
     */
    private static final int DECIMALES_DEL_CALCULO = 10;

    /** Cuantos codigos de unidad caben en un mensaje de error antes de resumirlos. */
    private static final int CODIGOS_EN_EL_MENSAJE = 10;

    private final ExpenseSettingRepository settings;
    private final ChargeConceptRepository concepts;
    private final PaymentObligationRepository obligations;
    private final BillingPeriods periods;
    private final Condominiums condominiums;
    private final Units units;
    private final UnitRelations relations;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    /** La composicion del numero legible, la misma que usa el alta de una obligacion sola. */
    private final ObligationNumbers numbers;

    ExpensesService(
            ExpenseSettingRepository settings,
            ChargeConceptRepository concepts,
            PaymentObligationRepository obligations,
            BillingPeriods periods,
            Condominiums condominiums,
            Units units,
            UnitRelations relations,
            ApplicationEventPublisher eventos,
            Clock clock) {
        this.settings = settings;
        this.concepts = concepts;
        this.obligations = obligations;
        this.periods = periods;
        this.condominiums = condominiums;
        this.units = units;
        this.relations = relations;
        this.eventos = eventos;
        this.clock = clock;
        this.numbers = new ObligationNumbers(obligations);
    }

    @Override
    @Transactional
    public ExpenseSettingView configure(UUID condominiumId, NewExpenseSetting body, String actor) {
        CondominiumView condominium = condominiums.find(condominiumId);
        ChargeConcept concept = requireConcept(condominiumId, body.conceptId());

        // Solo los conceptos de expensas. El agua se calcula con una tarifa y una lectura (#25, #26)
        // y la mora con una politica (#27), cada una con su tabla y sus parametros; dejar configurar
        // "expensas" sobre un concepto de agua guardaria un monto que su emision no va a mirar.
        if (concept.getType() != ChargeConceptType.DUES) {
            throw new BusinessRuleViolated(
                    ("El concepto %s es de tipo %s y la configuracion de expensas es solo para los de" + " tipo DUES.")
                            .formatted(concept.getCode(), concept.getType()));
        }

        Instant ahora = clock.instant();

        // Cerrar la vigente y abrir otra, nunca actualizar: la fila anterior es la que dice con que
        // monto se emitio cada mes, y cada obligacion la nombra en su sourceId. Es el mismo patron
        // que CondominiumConfig.
        ExpenseSetting previa = settings.findByCondominiumIdAndConceptIdAndValidToIsNull(
                        condominiumId, body.conceptId())
                .orElse(null);
        if (previa != null) {
            previa.close(ahora);
            // El flush no es decorativo. Hibernate ordena todos los INSERT antes que los UPDATE
            // dentro de un mismo flush, asi que sin esto la fila nueva entra mientras la vieja sigue
            // abierta y el indice parcial de "una sola vigente" la rechaza. Misma trampa, mismo
            // sitio, que en CondominiumsService.changeConfig.
            settings.flush();
        }

        ExpenseSetting creada = settings.save(new ExpenseSetting(
                condominiumId, body.conceptId(), body.method(), body.amount(), condominium.currency(), ahora));

        eventos.publishEvent(new ExpenseSettingChanged(
                condominiumId,
                creada.getId(),
                creada.getConceptId(),
                previa != null ? previa.getMethod() : null,
                previa != null ? previa.getAmount() : null,
                creada.getMethod(),
                creada.getAmount(),
                creada.getCurrency(),
                actor,
                ahora));

        return view(creada);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseSettingView> current(UUID condominiumId) {
        condominiums.find(condominiumId);
        return settings.findByCondominiumIdAndValidToIsNullOrderByValidFromDescConceptIdAsc(condominiumId).stream()
                .map(ExpensesService::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseSettingView> history(UUID condominiumId) {
        condominiums.find(condominiumId);
        return settings.historyOf(condominiumId).stream()
                .map(ExpensesService::view)
                .toList();
    }

    @Override
    @Transactional
    public ExpenseRunView calculate(UUID condominiumId, UUID conceptId, int year, int month, String actor) {
        CondominiumView condominium = condominiums.find(condominiumId);
        ChargeConcept concept = requireConcept(condominiumId, conceptId);
        ExpenseSetting setting = requireCurrentSetting(condominiumId, concept);

        // El periodo se abre si falta, de forma idempotente: es lo que el #101 dejo escrito que
        // necesitaba este ticket, para que el worker del #28 no falle en un mes que nadie preparo.
        BillingPeriodView period = periods.openIfAbsent(condominiumId, year, month, actor);
        requireOpen(period);
        requireNotIssued(period, concept);

        List<UnitView> active = units.activeOf(condominiumId);
        if (active.isEmpty()) {
            throw new BusinessRuleViolated(
                    "El condominio no tiene unidades activas: no hay a quien emitirle expensas.");
        }

        List<BigDecimal> reparto = reparto(setting, active);
        Map<UUID, UUID> responsables = responsablesDe(condominiumId, concept, active, reparto);

        // Los borradores anteriores se BORRAN, no se anulan: el porque esta en Expenses#calculate, y
        // se resume en que un borrador reemplazado antes de que nadie lo viera ocuparia su numero
        // legible. El flush, por lo mismo que al cerrar una configuracion: Hibernate ordena los
        // INSERT antes que los DELETE del mismo flush, asi que sin esto el numero seguiria tomado y
        // el calculo nuevo saldria con correlativo.
        List<PaymentObligation> previos = obligations.findByCondominiumIdAndPeriodIdAndConceptIdAndStatusOrderByNumber(
                condominiumId, period.id(), conceptId, PaymentObligationStatus.DRAFT);
        if (!previos.isEmpty()) {
            obligations.deleteAll(previos);
            obligations.flush();
        }

        LocalDate dueDate = vencimientoDe(condominium, year, month);
        List<ExpenseLineView> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (int i = 0; i < active.size(); i++) {
            UnitView unit = active.get(i);
            BigDecimal amount = reparto.get(i);

            // Un cargo de cero no es una deuda, y lo que corresponde es no crear la obligacion
            // (criterio del #23). Pasa con una unidad de alicuota cero --un parqueo, tipicamente--
            // cuando se reparte un total.
            if (amount.signum() == 0) {
                continue;
            }

            String number = numbers.free(condominiumId, concept.getCode(), year, month, unit.code());
            PaymentObligation created = obligations.save(new PaymentObligation(
                    condominiumId,
                    unit.id(),
                    // Esta: responsablesDe habria fallado antes si a esta unidad le faltara uno, y
                    // recorre la misma lista con el mismo reparto.
                    Objects.requireNonNull(responsables.get(unit.id())),
                    conceptId,
                    period.id(),
                    number,
                    amount,
                    condominium.currency(),
                    dueDate,
                    null,
                    ExpenseSetting.SOURCE_TYPE,
                    setting.getId()));

            lines.add(new ExpenseLineView(
                    created.getId(),
                    unit.id(),
                    unit.code(),
                    unit.aliquot(),
                    created.getResponsiblePersonId(),
                    number,
                    amount,
                    dueDate));
            total = total.add(amount);
        }

        // El calculo NO se audita, por lo mismo que el alta de una obligacion sola en el #23: un
        // borrador que el propietario no ve no es una accion sobre la deuda de nadie, y auditar cada
        // previsualizacion llenaria la bitacora de filas que nadie va a leer. Lo que si se audita es
        // la emision, que es cuando la deuda existe.
        return run(
                condominiumId,
                period,
                conceptId,
                setting.getId(),
                setting.getMethod(),
                PaymentObligationStatus.DRAFT,
                total,
                condominium.currency(),
                lines);
    }

    @Override
    @Transactional
    public ExpenseRunView issue(UUID condominiumId, UUID conceptId, int year, int month, String actor) {
        CondominiumView condominium = condominiums.find(condominiumId);
        ChargeConcept concept = requireConcept(condominiumId, conceptId);

        // Abre el periodo si falta, igual que el calculo. Si falta no habra borradores y esto acaba
        // en "nada que emitir", asi que el periodo vacio que se abriria se va con la transaccion.
        BillingPeriodView period = periods.openIfAbsent(condominiumId, year, month, actor);
        requireOpen(period);

        // EL ORDEN IMPORTA: primero si ya se emitio y despues si hay algo que emitir. Al reves, la
        // segunda llamada diria "no hay nada calculado" --porque los borradores ya pasaron a
        // emitidos-- en vez de "ya se emitio", que es el criterio del ticket.
        requireNotIssued(period, concept);

        List<PaymentObligation> drafts = obligations.findByCondominiumIdAndPeriodIdAndConceptIdAndStatusOrderByNumber(
                condominiumId, period.id(), conceptId, PaymentObligationStatus.DRAFT);
        if (drafts.isEmpty()) {
            throw new BusinessRuleViolated(
                    ("No hay expensas calculadas de %s para %d-%02d: calculalas" + " antes de emitirlas.")
                            .formatted(concept.getCode(), year, month));
        }

        // La configuracion con la que se calcularon los borradores, NO la vigente de ahora: si
        // alguien la cambio entre calcular y emitir, lo que se emite sigue siendo lo que se aprobo.
        // De ahi salen el identificador y el metodo que se informan, los dos de la misma fila.
        ExpenseSetting setting = settingDe(drafts, condominiumId, concept);

        Map<UUID, UnitView> unidades = unidadesDe(condominiumId, drafts);

        Instant ahora = clock.instant();
        List<ExpenseLineView> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (PaymentObligation obligation : drafts) {
            obligation.issue(ahora);

            // Uno por obligacion, y hace falta: es el que escuchan las notificaciones del #50 para
            // avisar a cada propietario de SU deuda. El resumen de la emision va aparte, abajo.
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
                    ahora));

            // Esta: unidadesDe busca una por una las que no esten entre las activas.
            UnitView unit = Objects.requireNonNull(unidades.get(obligation.getUnitId()));
            lines.add(new ExpenseLineView(
                    obligation.getId(),
                    obligation.getUnitId(),
                    unit.code(),
                    unit.aliquot(),
                    obligation.getResponsiblePersonId(),
                    obligation.getNumber(),
                    obligation.getAmount(),
                    obligation.getDueDate()));
            total = total.add(obligation.getAmount());
        }

        // El mes pasa a emitido una sola vez. Un condominio que cobra expensas y fondo de reserva por
        // separado emite dos conceptos en el mismo mes, y el segundo encuentra el periodo ya emitido:
        // volver a transitarlo seria un conflicto de estado por una operacion que es correcta.
        if (period.status() == BillingPeriodStatus.DRAFT) {
            periods.issue(condominiumId, period.id(), actor);
        }

        eventos.publishEvent(new ExpensesIssued(
                condominiumId,
                period.id(),
                conceptId,
                setting.getId(),
                year,
                month,
                lines.size(),
                total,
                condominium.currency(),
                actor,
                ahora));

        return run(
                condominiumId,
                period,
                conceptId,
                setting.getId(),
                setting.getMethod(),
                PaymentObligationStatus.ISSUED,
                total,
                condominium.currency(),
                lines);
    }

    private ChargeConcept requireConcept(UUID condominiumId, UUID conceptId) {
        return concepts.findByIdAndCondominiumId(conceptId, condominiumId)
                .orElseThrow(() -> ResourceNotFound.de("el concepto de cobro", conceptId));
    }

    /**
     * La configuracion vigente de ese concepto.
     *
     * <p>No haberla es un error de negocio y no un 404: el concepto existe, lo que falta es decir
     * cuanto se cobra por el, y el mensaje tiene que llevar a configurarlo.
     */
    private ExpenseSetting requireCurrentSetting(UUID condominiumId, ChargeConcept concept) {
        return settings.findByCondominiumIdAndConceptIdAndValidToIsNull(condominiumId, concept.getId())
                .orElseThrow(() -> new BusinessRuleViolated(
                        ("El concepto %s no tiene configuracion de expensas vigente: configurala antes" + " de emitir.")
                                .formatted(concept.getCode())));
    }

    /** Un mes cerrado no admite nada mas dentro: es el cierre contable. */
    private static void requireOpen(BillingPeriodView period) {
        if (period.status() == BillingPeriodStatus.CLOSED) {
            throw new StateConflict(
                    "El periodo %d-%02d esta cerrado y no admite emisiones.".formatted(period.year(), period.month()));
        }
    }

    /**
     * Ese concepto no puede tener ya obligaciones a cobro en ese mes.
     *
     * <p>Es el primer criterio del ticket --"emitir un periodo ya emitido no crea nada y lo
     * informa"--, y se pregunta <strong>por concepto y no por periodo</strong>: un condominio que
     * cobra expensas y fondo de reserva por separado emite dos veces el mismo mes, y eso es correcto.
     *
     * <p>Lo informa con un conflicto y no con una respuesta vacia porque emitir dos veces duplicaria
     * la deuda de todo el edificio: eso no puede pasar en silencio. Lo idempotente es abrir el
     * periodo, no emitirlo.
     */
    private void requireNotIssued(BillingPeriodView period, ChargeConcept concept) {
        long aCobro = obligations.countByCondominiumIdAndPeriodIdAndConceptIdAndStatusIn(
                period.condominiumId(), period.id(), concept.getId(), A_COBRO);
        if (aCobro > 0) {
            throw new StateConflict(("Las expensas de %s ya se emitieron en %d-%02d: hay %d"
                            + " obligaciones a cobro y no se crea ninguna mas.")
                    .formatted(concept.getCode(), period.year(), period.month(), aCobro));
        }
    }

    /**
     * El dia en que vencen las expensas de un mes.
     *
     * <p><strong>Vence el mes siguiente</strong>, no el del periodo: el analisis 7.1 escribe el
     * ejemplo --las expensas de 2026-09 vencen el 10/10-- y es como funciona un condominio, que
     * cobra el mes ya pasado. El dia sale de la configuracion del condominio, que lo limita a 28 por
     * la misma razon que el dia de emision: un vencimiento el 30 no existiria en febrero.
     */
    private static LocalDate vencimientoDe(CondominiumView condominium, int year, int month) {
        return LocalDate.of(year, month, 1).plusMonths(1).withDayOfMonth(condominium.dueDay());
    }

    /**
     * Lo que le toca a cada unidad, en el mismo orden que la lista de unidades.
     *
     * <p>Un cero en una posicion significa "a esta unidad no se le cobra nada", y quien emite no le
     * crea obligacion: pasa con una unidad de alicuota cero cuando se reparte un total.
     */
    private static List<BigDecimal> reparto(ExpenseSetting setting, List<UnitView> active) {
        if (setting.getMethod() == ExpenseMethod.FIXED_PER_UNIT) {
            return active.stream().map(unit -> setting.getAmount()).toList();
        }
        return porAlicuota(setting.getAmount(), active);
    }

    /**
     * El total repartido por alicuota, <strong>por restos mayores</strong> y en centavos.
     *
     * <p>El criterio del ticket es que "el total emitido cuadre con la suma de obligaciones
     * generadas", y redondear cada cuota por separado no lo cumple: con doscientas unidades sobran o
     * faltan centavos. Asi que se calcula la cuota exacta de cada una, se queda con los centavos
     * enteros, y los centavos que quedan sueltos se reparten de uno en uno entre las unidades con la
     * parte decimal mas grande. La suma es exactamente el total configurado, por construccion.
     *
     * <p><strong>No se le da el resto a la ultima</strong>, que seria mas corto: la ultima por codigo
     * es la misma todos los meses, y pagaria el redondeo de todo el edificio para siempre.
     *
     * <p>A igualdad de resto decide el codigo de la unidad, para que dos calculos iguales den el
     * mismo reparto: una previsualizacion que no se puede comparar con la siguiente no sirve de
     * previsualizacion.
     */
    private static List<BigDecimal> porAlicuota(BigDecimal total, List<UnitView> active) {
        BigDecimal suma = active.stream().map(UnitView::aliquot).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (suma.compareTo(CIEN) != 0) {
            throw new BusinessRuleViolated(("Las alicuotas de las %d unidades activas suman %s y para"
                            + " repartir un total tienen que sumar 100: corrigelas antes de emitir.")
                    .formatted(active.size(), suma.stripTrailingZeros().toPlainString()));
        }

        BigDecimal enCentavos = total.movePointRight(2);
        List<BigDecimal> exactos = active.stream()
                .map(unit ->
                        enCentavos.multiply(unit.aliquot()).divide(CIEN, DECIMALES_DEL_CALCULO, RoundingMode.HALF_UP))
                .toList();

        long[] centavos = new long[active.size()];
        long repartidos = 0;
        for (int i = 0; i < exactos.size(); i++) {
            centavos[i] = enteros(exactos.get(i));
            repartidos += centavos[i];
        }

        // Solo las unidades a las que les toca algo entran en el reparto del resto: una de alicuota
        // cero no debe nada, y darle un centavo le crearia una deuda que su alicuota no justifica.
        List<Integer> candidatos = new ArrayList<>();
        for (int i = 0; i < exactos.size(); i++) {
            if (exactos.get(i).signum() > 0) {
                candidatos.add(i);
            }
        }
        candidatos.sort(Comparator.comparing(
                        (Integer i) -> exactos.get(i).subtract(BigDecimal.valueOf(enteros(exactos.get(i)))))
                .reversed()
                .thenComparing(i -> active.get(i).code()));

        long resto = enCentavos.longValueExact() - repartidos;
        for (int n = 0; n < resto && n < candidatos.size(); n++) {
            centavos[candidatos.get(n)]++;
        }

        List<BigDecimal> montos = new ArrayList<>(centavos.length);
        for (long c : centavos) {
            montos.add(BigDecimal.valueOf(c, 2));
        }
        return montos;
    }

    private static long enteros(BigDecimal valor) {
        return valor.setScale(0, RoundingMode.DOWN).longValueExact();
    }

    /**
     * Quien responde por cada unidad a la que se le va a cobrar.
     *
     * <p>La precedencia es la que {@code UnitRelations.assignBilling} define: la responsabilidad de
     * ese concepto manda sobre la general --la de concepto nulo, que cubre todo lo que se cobre a la
     * unidad-- porque es la mas especifica. Se resuelve aqui y no del otro lado del puerto para
     * poder distinguir las dos formas de fallar, que se arreglan distinto.
     *
     * <p><strong>Si falta alguna, no se emite ninguna</strong> (analisis 7.1: la emision es
     * transaccional por lote). Y se informan todas juntas en vez de parar en la primera: el
     * administrador va a corregir datos, y una lista le ahorra tantas emisiones fallidas como
     * unidades mal.
     *
     * <p><strong>Varios responsables para el mismo concepto es un fallo y no una eleccion.</strong>
     * RF-08 admite que respondan "ambos", pero la obligacion tiene un responsable y adivinar cual
     * seria decidir a quien se le cobra sin que nadie lo haya dicho. El dia que "ambos" tenga que
     * repartir de verdad, es un ticket con su propio modelo.
     *
     * @param reparto lo que le toca a cada unidad, para no exigir responsable a las que no se les
     *     cobra nada
     */
    private Map<UUID, UUID> responsablesDe(
            UUID condominiumId, ChargeConcept concept, List<UnitView> active, List<BigDecimal> reparto) {
        Map<UUID, UUID> delConcepto = new HashMap<>();
        Set<UUID> variasDelConcepto = new HashSet<>();
        Map<UUID, UUID> generales = new HashMap<>();
        Set<UUID> variasGenerales = new HashSet<>();

        for (BillingResponsibilityView row : relations.billingResponsibilitiesFor(condominiumId, concept.getId())) {
            boolean especifica = row.conceptId() != null;
            Map<UUID, UUID> donde = especifica ? delConcepto : generales;
            Set<UUID> varias = especifica ? variasDelConcepto : variasGenerales;
            if (donde.put(row.unitId(), row.personId()) != null) {
                varias.add(row.unitId());
            }
        }

        Map<UUID, UUID> resuelto = new HashMap<>();
        List<String> sinResponsable = new ArrayList<>();
        List<String> conVarios = new ArrayList<>();

        for (int i = 0; i < active.size(); i++) {
            UnitView unit = active.get(i);
            if (reparto.get(i).signum() == 0) {
                continue;
            }
            if (delConcepto.containsKey(unit.id())) {
                if (variasDelConcepto.contains(unit.id())) {
                    conVarios.add(unit.code());
                } else {
                    resuelto.put(unit.id(), delConcepto.get(unit.id()));
                }
            } else if (generales.containsKey(unit.id())) {
                if (variasGenerales.contains(unit.id())) {
                    conVarios.add(unit.code());
                } else {
                    resuelto.put(unit.id(), generales.get(unit.id()));
                }
            } else {
                sinResponsable.add(unit.code());
            }
        }

        if (!sinResponsable.isEmpty() || !conVarios.isEmpty()) {
            throw new BusinessRuleViolated(problemaDeResponsables(sinResponsable, conVarios));
        }
        return resuelto;
    }

    private static String problemaDeResponsables(List<String> sinResponsable, List<String> conVarios) {
        StringBuilder mensaje = new StringBuilder("No se puede emitir: ");
        if (!sinResponsable.isEmpty()) {
            mensaje.append("%d unidades sin responsable de pago (%s)"
                    .formatted(sinResponsable.size(), resumen(sinResponsable)));
        }
        if (!sinResponsable.isEmpty() && !conVarios.isEmpty()) {
            mensaje.append(" y ");
        }
        if (!conVarios.isEmpty()) {
            mensaje.append("%d unidades con mas de un responsable para este concepto (%s)"
                    .formatted(conVarios.size(), resumen(conVarios)));
        }
        return mensaje.append(". Asigna uno por unidad antes de emitir.").toString();
    }

    /** Los primeros codigos y cuantos quedan: un mensaje con doscientos codigos no se lee. */
    private static String resumen(List<String> codigos) {
        if (codigos.size() <= CODIGOS_EN_EL_MENSAJE) {
            return String.join(", ", codigos);
        }
        return String.join(", ", codigos.subList(0, CODIGOS_EN_EL_MENSAJE))
                + " y %d mas".formatted(codigos.size() - CODIGOS_EN_EL_MENSAJE);
    }

    /**
     * La configuracion con la que se calcularon estos borradores.
     *
     * <p>Sale del {@code sourceId} que la obligacion guarda --el origen por referencia del analisis
     * 5.1-- y no de la vigente de hoy: entre calcular y emitir alguien pudo cambiar el monto, y lo
     * que se emite tiene que seguir siendo lo que se aprobo en la previsualizacion.
     *
     * <p>Cae en la vigente solo si ninguno de los borradores lleva origen, que es el caso de una
     * obligacion suelta creada a mano con {@code PaymentObligations.register} sobre ese concepto y
     * ese mes. Emitirla con el mes es lo correcto; lo que no se puede es decir con que configuracion
     * salio, porque no salio de ninguna.
     */
    private ExpenseSetting settingDe(List<PaymentObligation> drafts, UUID condominiumId, ChargeConcept concept) {
        return drafts.stream()
                .map(PaymentObligation::getSourceId)
                .filter(Objects::nonNull)
                .findFirst()
                .flatMap(settings::findById)
                .orElseGet(() -> requireCurrentSetting(condominiumId, concept));
    }

    /**
     * Las unidades de estos borradores, por identificador.
     *
     * <p>Se piden las activas de una vez --que son casi todas-- y solo se busca una por una la que
     * falte: una unidad puede haberse desactivado entre calcular y emitir, y su obligacion ya
     * calculada sigue siendo suya. Lo que no se puede hacer es una consulta por unidad en una
     * emision de doscientas.
     */
    private Map<UUID, UnitView> unidadesDe(UUID condominiumId, List<PaymentObligation> drafts) {
        Map<UUID, UnitView> porId = new HashMap<>();
        for (UnitView unit : units.activeOf(condominiumId)) {
            porId.put(unit.id(), unit);
        }
        for (PaymentObligation obligation : drafts) {
            porId.computeIfAbsent(obligation.getUnitId(), id -> units.find(condominiumId, id));
        }
        return porId;
    }

    private static ExpenseRunView run(
            UUID condominiumId,
            BillingPeriodView period,
            UUID conceptId,
            UUID settingId,
            ExpenseMethod method,
            PaymentObligationStatus status,
            BigDecimal total,
            String currency,
            List<ExpenseLineView> lines) {
        return new ExpenseRunView(
                condominiumId,
                period.id(),
                period.year(),
                period.month(),
                conceptId,
                settingId,
                method,
                status,
                total,
                currency,
                lines);
    }

    private static ExpenseSettingView view(ExpenseSetting setting) {
        return new ExpenseSettingView(
                setting.getId(),
                setting.getCondominiumId(),
                setting.getConceptId(),
                setting.getMethod(),
                setting.getAmount(),
                setting.getCurrency(),
                setting.getValidFrom(),
                setting.getValidTo());
    }
}

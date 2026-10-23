package bo.coprop.billing.internal;

import bo.coprop.billing.ExpenseRunView;
import bo.coprop.billing.ExpenseSettingView;
import bo.coprop.billing.Expenses;
import bo.coprop.billing.NewExpenseSetting;
import bo.coprop.shared.ApiController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las expensas de un condominio: como se calculan y como se emiten. Issue #24.
 *
 * <p>El condominio va en la ruta y nunca en el cuerpo, y el aislamiento lo pone el filtro del #17
 * antes de llegar aqui: quien no tiene membresia activa en ese condominio ya recibio 404.
 *
 * <p><strong>Dos permisos y no uno, y es la separacion de funciones del analisis.</strong> Poner el
 * monto es {@code CONFIGURE_CHARGES} --configurar lo que se cobra, igual que el catalogo de
 * conceptos y los periodos-- y emitir es {@code ISSUE_DUES}, que la matriz describe como "emitir las
 * expensas y el cobro de agua del periodo". Hoy las dos las tiene el mismo rol, el financiero; el
 * dia que un condominio quiera que el directorio apruebe el monto y la administracion lo emita, los
 * permisos ya estan separados y basta mover una celda de la matriz.
 *
 * <p><strong>La previsualizacion es un POST y escribe</strong>, aunque se lea como una consulta: deja
 * las obligaciones en {@code DRAFT}, que es el "estado BORRADOR" del analisis 7.1. El porque esta en
 * {@link Expenses}, y se resume en que lo que se emite tiene que ser exactamente lo que la persona
 * aprobo.
 */
@ApiController
@RestController
@RequestMapping("/condominiums/{condominiumId}")
class ExpenseController {

    private final Expenses expenses;

    ExpenseController(Expenses expenses) {
        this.expenses = expenses;
    }

    /**
     * Registra la configuracion de expensas de un concepto.
     *
     * <p>Devuelve 201 y <strong>sin cabecera {@code Location}</strong>, igual que el alta de un
     * concepto y la apertura de un periodo: no hay ruta que consulte una configuracion por su
     * identificador, y una {@code Location} que responde 404 es peor que ninguna.
     *
     * <p>Es un POST y no un PUT aunque reemplace a la anterior, porque crea una fila: la anterior no
     * se modifica, se cierra. {@code PUT /configuration} del condominio hace lo mismo por dentro y es
     * un PUT; aqui el recurso es la coleccion de vigencias, no un singleton.
     */
    @PreAuthorize("@authorization.can(authentication, 'CONFIGURE_CHARGES')")
    @PostMapping("/expense-settings")
    ResponseEntity<ExpenseSettingView> configure(
            @PathVariable UUID condominiumId, @Valid @RequestBody NewExpenseSetting body, Principal principal) {
        return ResponseEntity.status(201).body(expenses.configure(condominiumId, body, principal.getName()));
    }

    /** Las configuraciones vigentes, una por concepto. */
    @PreAuthorize("@authorization.can(authentication, 'CONFIGURE_CHARGES')")
    @GetMapping("/expense-settings")
    List<ExpenseSettingView> current(@PathVariable UUID condominiumId) {
        return expenses.current(condominiumId);
    }

    /**
     * El historial de vigencias, de la mas reciente a la mas antigua.
     *
     * <p>Ruta propia y no un parametro del listado: son dos preguntas distintas --lo que se va a
     * cobrar y con que se cobro-- y un parametro que cambia el significado de una respuesta obliga a
     * quien la lee a mirar la peticion para saber que esta viendo.
     */
    @PreAuthorize("@authorization.can(authentication, 'CONFIGURE_CHARGES')")
    @GetMapping("/expense-settings/history")
    List<ExpenseSettingView> history(@PathVariable UUID condominiumId) {
        return expenses.history(condominiumId);
    }

    /**
     * Calcula las expensas del mes y devuelve el reparto para revisarlo.
     *
     * <p>200 y no 201: lo que devuelve no es un recurso nuevo con su propia direccion, es el estado
     * calculado de un mes, y repetir la llamada da el mismo resultado en vez de crear otro.
     */
    @PreAuthorize("@authorization.can(authentication, 'ISSUE_DUES')")
    @PostMapping("/expense-runs")
    ExpenseRunView calculate(
            @PathVariable UUID condominiumId, @Valid @RequestBody ExpenseRun body, Principal principal) {
        return expenses.calculate(condominiumId, body.conceptId(), body.year(), body.month(), principal.getName());
    }

    /** Saca a cobro lo calculado. Un mes ya emitido responde 409 y no crea nada. */
    @PreAuthorize("@authorization.can(authentication, 'ISSUE_DUES')")
    @PostMapping("/expense-runs/issue")
    ExpenseRunView issue(@PathVariable UUID condominiumId, @Valid @RequestBody ExpenseRun body, Principal principal) {
        return expenses.issue(condominiumId, body.conceptId(), body.year(), body.month(), principal.getName());
    }

    /**
     * Que concepto y que mes.
     *
     * <p>Los mismos limites que {@code NewBillingPeriod}, y no por simetria: el periodo se abre desde
     * aqui cuando falta, asi que un mes que la base rechazaria tiene que fallar antes con un 422 que
     * dice cual es el rango.
     */
    record ExpenseRun(@NotNull UUID conceptId, @Min(2000) @Max(2100) int year, @Min(1) @Max(12) int month) {}
}

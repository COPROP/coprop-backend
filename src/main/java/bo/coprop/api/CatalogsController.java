package bo.coprop.api;

import bo.coprop.condominium.CondominiumStatus;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.OccupancyRole;
import bo.coprop.condominium.UnitRelation;
import bo.coprop.condominium.UnitStatus;
import bo.coprop.condominium.UnitType;
import bo.coprop.identity.AccountStatus;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipStatus;
import bo.coprop.identity.Permission;
import bo.coprop.identity.PermissionMatrix;
import bo.coprop.identity.Scope;
import bo.coprop.identity.SessionOrigin;
import bo.coprop.shared.ApiController;
import bo.coprop.shared.ErrorCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.TreeMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El catalogo de lo que no cambia: las enumeraciones del dominio y la matriz de permisos. Issue #89.
 *
 * <p>Es lo que lee un cliente justo despues de {@code GET /api/v1/me}, y existe para que no tenga que
 * copiar a mano unos valores que el backend ya conoce. Mientras el catalogo sea fijo --lo es en el
 * MVP: Seguridad 3 no deja crear roles-- esto es todo lo que hace falta.
 *
 * <p><strong>Vive en {@code api} y no en un modulo de dominio</strong> por la misma razon que
 * {@code MeController}: la respuesta cruza {@code condominium}, {@code identity} y {@code shared}, y
 * este es el unico modulo que ve los tres. Ponerlo en cualquiera de ellos exigiria que ese modulo
 * dependiera de los otros, que es lo que prohiben las reglas 2 y 6.
 *
 * <p><strong>Sin {@code @PreAuthorize}, y es una decision.</strong> No hay nada que autorizar: lo que
 * devuelve es la forma de la API, no datos de nadie. Una lista de tipos de unidad no dice cuantas
 * unidades hay ni de quien son, y la matriz esta publicada en un repositorio publico. Exigir un
 * permiso dejaria a porteria o al directorio sin poder pintar un formulario, y a quien acaba de
 * entrar sin membresia --el caso del superadministrador recien abierto-- sin poder pintar ninguno.
 * Autenticar si: lo hace la cadena, que deniega por defecto.
 *
 * <p><strong>La lista de enumeraciones se escribe a mano, y un test la vigila.</strong> Descubrirla
 * por reflexion en arranque haria que el contrato cambiara solo el dia que alguien añadiera un enum
 * publico sin pensar en esto. Escrita aqui, añadirlo es una decision, y olvidarse rompe el build:
 * {@code CatalogsTests} recorre los paquetes raiz de los tres modulos y exige que no falte
 * ninguno. Es el mismo reparto que usa la rejilla de autorizacion --se declara a mano y un test
 * cuenta las celdas--.
 */
@ApiController
@RestController
@RequestMapping("/parametricas")
class CatalogsController {

    /**
     * Las enumeraciones publicas de los paquetes raiz de {@code condominium}, {@code identity} y
     * {@code shared}, en orden alfabetico.
     *
     * <p>No entran las de {@code internal}: no son contrato, y exponerlas ataria la API a como este
     * guardado el dato. Hoy no hay ninguna, y el test lo comprueba para que el dia que la haya se
     * decida en vez de salir sola.
     */
    private static final List<Class<? extends Enum<?>>> ENUMERACIONES = List.of(
            AccountStatus.class,
            Scope.class,
            ErrorCode.class,
            CondominiumStatus.class,
            CondominiumType.class,
            MembershipRole.class,
            MembershipStatus.class,
            OccupancyRole.class,
            SessionOrigin.class,
            Permission.class,
            UnitRelation.class,
            UnitStatus.class,
            UnitType.class);

    @GetMapping
    Catalogs consultar() {
        var catalogo = new TreeMap<String, List<String>>();
        for (Class<? extends Enum<?>> enumeracion : ENUMERACIONES) {
            catalogo.put(enumeracion.getSimpleName(), valoresDe(enumeracion));
        }
        return new Catalogs(catalogo);
    }

    @GetMapping("/matriz-de-permisos")
    RolePermissionMatrix matriz() {
        var filas = new ArrayList<RolePermissionMatrix.Row>();
        for (Permission permission : Permission.values()) {
            filas.add(filaDe(permission));
        }
        return new RolePermissionMatrix(List.of(MembershipRole.values()), List.copyOf(filas));
    }

    private static RolePermissionMatrix.Row filaDe(Permission permission) {
        var scopes = new EnumMap<MembershipRole, Scope>(MembershipRole.class);
        for (MembershipRole rol : MembershipRole.values()) {
            scopes.put(rol, PermissionMatrix.scopeOf(rol, permission));
        }
        return new RolePermissionMatrix.Row(permission, PermissionMatrix.requiresSecondFactor(permission), scopes);
    }

    /**
     * Los nombres de los valores, en el orden en que se declararon.
     *
     * <p>El {@code getEnumConstants()} de una clase que no es un enum devuelve nulo, y NullAway no
     * deduce del parametro que aqui no puede serlo, asi que el nulo se trata en vez de anotarse como
     * imposible. La lista vacia no llega a ocurrir: la rama existe para el analizador.
     */
    private static List<String> valoresDe(Class<? extends Enum<?>> enumeracion) {
        Enum<?>[] valores = enumeracion.getEnumConstants();
        return valores == null
                ? List.of()
                : Arrays.stream(valores).map(Enum::name).toList();
    }
}

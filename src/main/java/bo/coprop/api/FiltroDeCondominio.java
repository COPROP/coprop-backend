package bo.coprop.api;

import bo.coprop.identity.MembershipStatus;
import bo.coprop.identity.MembershipView;
import bo.coprop.identity.Memberships;
import bo.coprop.shared.ClavesDeLog;
import bo.coprop.shared.CodigoDeError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Nadie entra al condominio de otro. El filtro de Seguridad 7.2 y el issue #17.
 *
 * <p>Lee el condominio de la ruta, carga la membresia vigente de quien pide y, si no la hay,
 * responde <strong>404 y no 403</strong>. Esa diferencia es el criterio heredado del issue #5:
 * distinguir "no existe" de "no puedes" le diria a cualquiera que preguntara que condominios hay en
 * la plataforma, y con eso quien tenga una cuenta puede enumerarlos probando identificadores.
 *
 * <p>Una membresia <strong>suspendida tampoco entra</strong>, y responde lo mismo que si no
 * existiera. Suspender y dar de baja son operaciones del issue #19; lo que decide aqui es solo si
 * la que hay esta activa.
 *
 * <p>Los roles de la membresia quedan como autoridades de la peticion, que es donde Spring los
 * busca. Hoy no los mira nadie --la matriz de permisos es el issue <strong>#16</strong>-- pero es
 * el sitio donde los va a buscar, y cargarlos aqui evita que esa matriz tenga que volver a
 * consultar la membresia en cada comprobacion.
 *
 * <p>Corre <strong>despues</strong> del filtro de identidad, que es quien resuelve la cuenta, y
 * antes de la autorizacion. Si todavia no hay identidad --una peticion sin token-- se aparta y deja
 * que la cadena responda el 401 que corresponde: un 404 aqui taparia la causa real.
 *
 * <p>Deja el condominio en el MDC ({@link ClavesDeLog#CONDOMINIO}), que era la ultima de las tres
 * claves que el issue #6 dejo declaradas sin rellenar.
 */
class FiltroDeCondominio extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltroDeCondominio.class);

    /**
     * Las rutas acotadas a un condominio, que son todas menos las globales.
     *
     * <p>Se mira la ruta y no un parametro resuelto porque este filtro corre antes de que exista un
     * controlador, y por tanto antes de que nadie haya convertido la ruta en argumentos. El
     * identificador tiene que tener forma de UUID: si no la tiene, no es una ruta de condominio y
     * el controlador la rechazara como corresponde.
     */
    private static final Pattern RUTA_DE_CONDOMINIO =
            Pattern.compile(Pattern.quote(ConfiguracionDeRutas.PREFIJO_DE_VERSION + "/condominios/")
                    + "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/.*)?");

    private final Memberships membresias;
    private final EscritorDeProblemas problemas;

    FiltroDeCondominio(Memberships membresias, EscritorDeProblemas problemas) {
        this.membresias = membresias;
        this.problemas = problemas;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        UUID condominio = condominioDe(peticion.getRequestURI());
        if (condominio == null) {
            // Rutas globales: personas, cuentas, el alta de un condominio. Aqui no hay condominio
            // que comprobar. Que el alta de condominios quede sin restriccion es del issue #16.
            cadena.doFilter(peticion, respuesta);
            return;
        }

        var identidad = identidadActual();
        if (identidad == null) {
            // Sin identidad todavia. No es cosa de este filtro: la cadena respondera 401.
            cadena.doFilter(peticion, respuesta);
            return;
        }

        var actor = (IdentidadAutenticada.Actor) identidad.getPrincipal();
        MembershipView membresia = membresias.of(condominio, actor.accountId());
        if (membresia == null || membresia.status() != MembershipStatus.ACTIVA) {
            log.info(
                    "La cuenta {} pidio el condominio {} sin membresia activa; se responde 404.",
                    actor.accountId(),
                    condominio);
            problemas.escribir(peticion, respuesta, CodigoDeError.NO_ENCONTRADO, "No se encontro el recurso.");
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(new IdentidadAutenticada(actor, autoridadesDe(membresia)));
        MDC.put(ClavesDeLog.CONDOMINIO, condominio.toString());
        try {
            cadena.doFilter(peticion, respuesta);
        } finally {
            MDC.remove(ClavesDeLog.CONDOMINIO);
        }
    }

    private static @Nullable UUID condominioDe(String ruta) {
        var encontrado = RUTA_DE_CONDOMINIO.matcher(ruta);
        return encontrado.matches() ? UUID.fromString(encontrado.group(1)) : null;
    }

    private static @Nullable IdentidadAutenticada identidadActual() {
        var autenticacion = SecurityContextHolder.getContext().getAuthentication();
        return autenticacion instanceof IdentidadAutenticada identidad ? identidad : null;
    }

    /** Con el prefijo {@code ROLE_}, que es el que Spring quita al comparar con {@code hasRole}. */
    private static List<SimpleGrantedAuthority> autoridadesDe(MembershipView membresia) {
        return membresia.roles().stream()
                .map(rol -> new SimpleGrantedAuthority("ROLE_" + rol.name()))
                .toList();
    }
}

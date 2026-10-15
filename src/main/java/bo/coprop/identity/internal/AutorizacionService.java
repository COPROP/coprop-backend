package bo.coprop.identity.internal;

import bo.coprop.identity.Alcance;
import bo.coprop.identity.Autorizacion;
import bo.coprop.identity.MatrizDePermisos;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.Permiso;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * El bean que responde a {@code @PreAuthorize("@autorizacion...")}.
 *
 * <p>El nombre importa: es el que aparece en las anotaciones, que son cadenas y no las revisa el
 * compilador. Renombrarlo rompe la autorizacion de todos los endpoints a la vez y en ejecucion, no
 * al compilar, asi que se cambia a la vez que las anotaciones o no se cambia.
 */
@Component("autorizacion")
class AutorizacionService implements Autorizacion {

    /** El prefijo que Spring pone a las autoridades que vienen de un rol, y quita al compararlas. */
    private static final String PREFIJO = "ROLE_";

    private final MembershipRepository membresias;

    AutorizacionService(MembershipRepository membresias) {
        this.membresias = membresias;
    }

    @Override
    public Alcance alcanceDe(Authentication quien, String permiso) {
        return MatrizDePermisos.alcanceDe(rolesDe(quien), permisoDe(permiso));
    }

    @Override
    public boolean puede(Authentication quien, String permiso) {
        return alcanceDe(quien, permiso) != Alcance.NINGUNO;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean puedeEnAlgunCondominio(Authentication quien, String nombre) {
        Permiso permiso = permisoDe(nombre);

        // El superadministrador no necesita membresia: su rol es de plataforma y viaja en las
        // autoridades de cualquier peticion, tenga condominio o no.
        if (MatrizDePermisos.alcanceDe(rolesDe(quien), permiso) != Alcance.NINGUNO) {
            return true;
        }

        // Se pregunta al reves de lo obvio --que roles dan este permiso, y tiene alguno-- en vez de
        // traer todas sus membresias y recorrerlas. Son los roles del catalogo, siete como mucho, y
        // asi la base responde con un EXISTS en vez de devolver filas.
        Set<MembershipRole> queLoDan = EnumSet.noneOf(MembershipRole.class);
        for (MembershipRole rol : MembershipRole.values()) {
            if (MatrizDePermisos.alcanceDe(rol, permiso) != Alcance.NINGUNO) {
                queLoDan.add(rol);
            }
        }
        return !queLoDan.isEmpty() && membresias.existeVigenteConAlgunRol(cuentaDe(quien), queLoDan);
    }

    @Override
    public boolean puedeConceder(Authentication quien, Set<MembershipRole> roles) {
        if (roles.isEmpty()) {
            return false;
        }
        Set<MembershipRole> suyos = rolesDe(quien);
        if (MatrizDePermisos.alcanceDe(suyos, Permiso.INVITAR_USUARIOS) != Alcance.NINGUNO) {
            return true;
        }
        if (MatrizDePermisos.alcanceDe(suyos, Permiso.INVITAR_SOLO_ADMINISTRADORES) == Alcance.NINGUNO) {
            return false;
        }
        return roles.stream().allMatch(MatrizDePermisos::esAdministrativo);
    }

    /**
     * Los roles que la membresia tiene hoy, y la pregunta de siempre sobre ellos.
     *
     * <p>La membresia se busca por su identificador <strong>y el del condominio de la ruta</strong>:
     * pedir la de otro condominio desde aqui responde lo mismo que pedir una que no existe
     * (Seguridad 7.1). La excepcion sale de dentro de la expresion de {@code @PreAuthorize}, que es
     * como el issue #15 consigue un 404 donde un {@code false} daria 403.
     *
     * <p>Funciona tambien con una membresia ya cerrada, y hace falta que sea asi: quien intente dar
     * de baja dos veces tiene que recibir el conflicto de estado que explica lo que pasa, no un 403
     * que le haga buscar un permiso que si tiene.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean puedeCambiar(Authentication quien, UUID condominiumId, UUID membershipId) {
        Membership membresia = membresias
                .findById(membershipId)
                .filter(encontrada -> encontrada.getCondominiumId().equals(condominiumId))
                .orElseThrow(() -> bo.coprop.shared.RecursoNoEncontrado.de("la membresia", membershipId));
        return puedeConceder(quien, membresia.getRoles());
    }

    /**
     * Los roles de la peticion, leidos de sus autoridades.
     *
     * <p>Las puso el filtro de condominio con la membresia vigente, y el de identidad añade la de
     * plataforma. Una autoridad que no corresponda a ningun rol del catalogo se ignora en vez de
     * reventar: no deberia existir, y si existe no es motivo para tumbar una peticion.
     */
    private static Set<MembershipRole> rolesDe(Authentication quien) {
        var roles = EnumSet.noneOf(MembershipRole.class);
        for (GrantedAuthority autoridad : quien.getAuthorities()) {
            String nombre = autoridad.getAuthority();
            if (nombre == null || !nombre.startsWith(PREFIJO)) {
                continue;
            }
            try {
                roles.add(MembershipRole.valueOf(nombre.substring(PREFIJO.length())));
            } catch (IllegalArgumentException noEsUnRol) {
                // Ignorada a proposito.
            }
        }
        return roles;
    }

    private static UUID cuentaDe(Authentication quien) {
        return UUID.fromString(quien.getName());
    }

    /**
     * Un permiso que no existe es un error de programacion --la anotacion lo escribe a mano-- y
     * tiene que reventar en el primer test que toque ese endpoint, no denegar en silencio.
     */
    private static Permiso permisoDe(String nombre) {
        return Permiso.valueOf(nombre);
    }
}

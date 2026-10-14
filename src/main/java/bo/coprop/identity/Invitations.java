package bo.coprop.identity;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Invitaciones a un condominio. <strong>El acceso nace siempre de una</strong> (Seguridad 6).
 *
 * <p>"No hay registro abierto: solo se entra por invitacion." La razon no es comodidad: si
 * cualquiera pudiera registrarse y declararse propietario del Dpto. 302, veria deudas ajenas. La
 * invitacion nace de un dato que el administrador ya valido --la persona y su relacion con la
 * unidad-- y es lo que ata una cuenta a un condominio con unos roles.
 *
 * <p><strong>Es una membresia diferida.</strong> La invitacion lleva los roles y la membresia no
 * existe hasta que alguien acepta. La via directa --{@link Memberships#grant}-- se queda para la
 * primera membresia de un condominio, que concede el superadministrador porque no hay nadie dentro
 * a quien invitar.
 *
 * <p><strong>El token no aparece en esta interfaz.</strong> Se genera al emitir, viaja al correo de
 * la persona invitada y se olvida; de la base solo queda su SHA-256 (Seguridad 6.1). Ningun metodo
 * lo devuelve, ni siquiera a quien lo emite: un administrador que pudiera leerlo podria entrar como
 * el invitado. Quien necesite uno nuevo, reenvia.
 *
 * <p>Las unidades no viajan aqui, aunque el alcance del issue #18 las nombre junto al correo y el
 * rol. Las relaciones con la unidad son del modulo {@code condominium} --la regla 2 prohibe que
 * {@code identity} dependa de el-- y se registran antes, con sus propios endpoints, que es tambien
 * el orden de 6.1.
 */
public interface Invitations {

    /**
     * Emite una invitacion y <strong>envia el correo con su enlace</strong>.
     *
     * <p>El envio ocurre aqui, dentro de la misma transaccion, y no reaccionando a
     * {@link InvitationIssued}: los eventos de este sistema se persisten, asi que el token tendria
     * que viajar en uno y quedaria legible en la base. Aqui es el unico momento en que existe en
     * memoria.
     *
     * <p>Si el correo falla, la emision entera se deshace. Es deliberado: una invitacion que nadie
     * recibio no sirve de nada, y es mejor que el administrador vea el error que una fila viva con
     * un enlace que no llego. Queda una ventana --correo enviado y transaccion que no confirma-- y
     * su arreglo es {@link #resend}.
     *
     * <p>Publica {@link InvitationIssued}.
     *
     * @param roles al menos uno, y ninguno puede ser {@link MembershipRole#PLATFORM_ADMIN}: su
     *     alcance es la plataforma y no se concede como membresia
     * @param actor la cuenta que invita
     * @throws bo.coprop.shared.RecursoNoEncontrado si la cuenta invitada no existe
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si no llega ningun rol, si llega
     *     {@code PLATFORM_ADMIN}, si esa cuenta ya tiene membresia vigente en ese condominio --ya
     *     esta dentro, no hay a que invitarla-- o si ya tiene una invitacion viva, que es lo que
     *     {@link #resend} resuelve
     */
    InvitationView issue(UUID condominiumId, UUID accountId, Set<MembershipRole> roles, String actor);

    /**
     * Revoca la invitacion y emite otra con los mismos roles, para la misma cuenta.
     *
     * <p>Es el "EXPIRADO --> INVITADO: administrador reenvia" del diagrama de Seguridad 6, y vale
     * tanto para una vencida como para una viva cuyo correo se perdio. <strong>No prolonga la
     * vigencia de la anterior</strong>: emite un token nuevo y deja el viejo sin valor, porque un
     * enlace que sigue sirviendo despues de reenviar son dos accesos vivos y solo uno de los dos se
     * controla.
     *
     * <p>Publica {@link InvitationRevoked} por la anterior y {@link InvitationIssued} por la nueva.
     *
     * @throws bo.coprop.shared.RecursoNoEncontrado si no existe o no es de ese condominio
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si ya se acepto: ahi la membresia existe y
     *     reenviar no significa nada
     */
    InvitationView resend(UUID condominiumId, UUID invitationId, String actor);

    /**
     * Deja el enlace sin valor.
     *
     * <p>Publica {@link InvitationRevoked}.
     *
     * @throws bo.coprop.shared.RecursoNoEncontrado si no existe o no es de ese condominio
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si ya se acepto
     */
    InvitationView revoke(UUID condominiumId, UUID invitationId, String actor);

    /** Las invitaciones de un condominio, las vivas y las cerradas, de la mas reciente a la mas vieja. */
    List<InvitationView> ofCondominium(UUID condominiumId);

    /**
     * @throws bo.coprop.shared.RecursoNoEncontrado si no existe o no es de ese condominio
     */
    InvitationView find(UUID condominiumId, UUID invitationId);

    /**
     * Abre el enlace de una invitacion: comprueba que sirve y deja a la persona en condiciones de
     * iniciar sesion.
     *
     * <p>Es lo que se llama <strong>sin autenticar</strong>, porque quien acaba de recibir la
     * invitacion todavia no tiene con que entrar. Lo que la autoriza es el token.
     *
     * <p>Resuelve los dos casos de 6.1: si ese correo no tiene credencial en el proveedor de
     * identidad, se le crea el usuario y se le pide al proveedor que le envie la accion para poner
     * su contraseña --{@link Apertura#credencialEnviada()} en cierto--; si ya participa en otro
     * condominio, no se toca nada y solo tiene que iniciar sesion. En ninguno de los dos se crea
     * una segunda cuenta.
     *
     * <p>No consume el token: eso es {@link #accept}. Abrir el enlace dos veces no rompe nada.
     *
     * @throws bo.coprop.shared.RecursoNoEncontrado si el token no existe, ya se uso, se revoco o
     *     vencio. Los cuatro responden igual a proposito: distinguirlos le diria a quien prueba
     *     tokens cuales existieron
     */
    Apertura open(String token);

    /**
     * Lo que la pantalla de la invitacion necesita saber antes de mandar a nadie al login.
     *
     * @param credencialEnviada si hubo que pedirle al proveedor de identidad que le envie a la
     *     persona la accion para poner su contraseña. En falso, ya puede iniciar sesion.
     * @param condominiumId a que condominio la invitan, para poder decir su nombre
     */
    record Apertura(UUID condominiumId, boolean credencialEnviada) {}

    /**
     * Acepta la invitacion: la sella y concede la membresia.
     *
     * <p>Se llama <strong>ya autenticado</strong>, despues de que la persona haya iniciado sesion.
     * Es el "la invitacion solo agrega la nueva membresia" de 6.1, y lo que hace que entrar con
     * contraseña y entrar con Google caigan en la misma cuenta: para cuando se llega aqui, el
     * filtro de identidad del #13 ya vinculo el sujeto del proveedor con la cuenta de COPROP por el
     * correo verificado.
     *
     * <p>Publica {@link InvitationAccepted} y, por la via de {@link Memberships#grant},
     * {@link MembershipGranted}.
     *
     * @param accountId la cuenta que esta autenticada
     * @throws bo.coprop.shared.RecursoNoEncontrado si el token no sirve, por cualquiera de las
     *     cuatro razones, <strong>o si la invitacion no es para esa cuenta</strong>: con el enlace
     *     de otro en la mano, la respuesta tiene que ser la misma que con un token inventado
     */
    InvitationView accept(String token, UUID accountId);
}

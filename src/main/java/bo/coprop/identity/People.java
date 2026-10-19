package bo.coprop.identity;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Personas y sus cuentas de acceso. Las dos cosas son globales y no cuelgan de un condominio.
 *
 * <p>Es la excepcion declarada a la regla de que el condominio es el primer argumento de toda
 * operacion del dominio: aqui no hay condominio que pasar. RF-03 exige que la persona se comparta
 * entre edificios, y la cuenta con la que entra tampoco es de ninguno (Seguridad 2: "global, sin
 * condominio"). Lo que esta acotado por condominio es {@link Memberships} y
 * {@code UnitRelations}.
 */
public interface People {

    /**
     * Da de alta una persona, o devuelve la que ya hay.
     *
     * <p><strong>Responde igual exista o no.</strong> Si llega un carnet que ya esta registrado se
     * reutiliza la fila y se devuelve su vista, sin error y sin señal de que ya estaba. Es
     * deliberado: la persona es global, asi que un alta que distinguiera los dos casos le diria al
     * administrador de un condominio que esa persona existe en otro. Es el mismo criterio que el
     * 404-no-403 que arrastra el issue #17.
     *
     * <p>Sin carnet no hay con que reconocerla, asi que siempre crea una nueva: dos personas
     * pueden llamarse igual.
     *
     * <p>Publica {@link PersonRegistered} <strong>solo cuando crea la fila</strong>.
     *
     * @param actor quien la da de alta, para la bitacora
     */
    PersonView register(NewPerson body, String actor);

    /**
     * @throws bo.coprop.shared.ResourceNotFound si no existe
     */
    PersonView find(UUID personId);

    /**
     * Abre la cuenta con la que una persona entrara a la plataforma.
     *
     * <p>Nace en {@link AccountStatus#INVITED}: existe y nadie ha entrado con ella. El envio de
     * la invitacion es el issue <strong>#18</strong> y el primer login el <strong>#13</strong>;
     * esto es solo la fila.
     *
     * <p>Publica {@link AccountOpened}.
     *
     * @param phone opcional
     * @param actor quien la abre, para la bitacora
     * @throws bo.coprop.shared.ResourceNotFound si la persona no existe
     * @throws bo.coprop.shared.BusinessRuleViolated si la persona ya tiene cuenta --una por
     *     persona, que es el titulo del issue #11-- o si el correo ya lo usa otra
     */
    AccountView openAccount(UUID personId, String email, @Nullable String phone, String actor);

    /**
     * @throws bo.coprop.shared.ResourceNotFound si no existe
     */
    AccountView findAccount(UUID accountId);
}

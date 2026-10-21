package bo.coprop.notifications;

/**
 * Enviar un correo. El unico canal del MVP (analisis RF-38, Seguridad 10.1).
 *
 * <p><strong>Es el primer puerto de este modulo, y rompe a proposito su regla de "nadie lo llama
 * directamente".</strong> El modulo se diseño para reaccionar solo a eventos, y para casi todo
 * sigue siendo lo correcto: añadir un aviso no deberia obligar a tocar a quien lo origina. La
 * invitacion del issue #18 es la excepcion y tiene un motivo concreto: <strong>su correo lleva el
 * token</strong>, los eventos de este sistema se persisten --el registro de la migracion V2-- y
 * Seguridad 6.1 exige que de ese token solo se guarde el hash. Un evento con el token dentro lo
 * dejaria legible en la base, que es justo lo que el hash evita.
 *
 * <p>Asi que quien tiene el token en memoria --la emision, en {@code identity}-- envia el correo en
 * el acto y por este puerto. Lo que si viaja en evento es {@code InvitationIssued}, sin token.
 *
 * <p>Una interfaz por canal: sumar WhatsApp o SMS en fase posterior no toca a quien dispara el
 * aviso. Lo que no entra aqui es el contenido: cada quien escribe su mensaje, porque el asunto y el
 * cuerpo son del caso de uso y no del canal.
 */
public interface Mail {

    /**
     * Envia un correo de texto plano.
     *
     * <p>Texto y no HTML en el MVP. Un cuerpo HTML obliga a una plantilla, a una version de texto
     * para quien no lo renderiza y a cuidar que un cliente de correo no destroce el enlace; nada de
     * eso aporta a que una invitacion llegue. Cuando haya varios avisos con formato, lo que toca es
     * una plantilla aqui, no HTML pegado en cada llamada.
     *
     * @param recipient una sola direccion; no hay aviso del MVP que vaya a dos
     * @throws FalloDeEnvio si no se pudo entregar al servidor de correo. No se traga: quien envia
     *     decide que hacer, y en la invitacion lo que se hace es deshacer la emision.
     */
    void enviar(String recipient, String asunto, String cuerpo);

    /** No se pudo entregar el correo al servidor. */
    class FalloDeEnvio extends RuntimeException {

        public FalloDeEnvio(String message, Throwable causa) {
            super(message, causa);
        }
    }
}

/**
 * Personas, membresias por condominio, roles e invitaciones.
 *
 * <p>Una persona tiene una sola cuenta en la plataforma y una membresia por condominio, con rol
 * propio en cada uno (Seguridad, seccion 2). Los roles por condominio viven aqui, nunca en el
 * token de Keycloak.
 *
 * <p>Referencia al condominio por identificador, no por entidad: este modulo no depende de
 * {@code condominium}.
 *
 * <p>Depende de {@code notifications} desde el issue #18, y de nada mas. El correo de una
 * invitacion lleva su token, que solo existe en memoria durante la emision: Seguridad 6.1 exige que
 * de la base solo quede su hash, y los eventos de este sistema se persisten, asi que no puede
 * viajar en uno. Lo envia quien lo tiene, por el puerto {@code Mail}. La direccion es la que cabe
 * esperar --quien decide que hay que avisar llama a quien sabe enviar-- y no invierte nada:
 * {@code notifications} sigue sin depender de este modulo.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "notifications"},
        displayName = "Identity")
package bo.coprop.identity;

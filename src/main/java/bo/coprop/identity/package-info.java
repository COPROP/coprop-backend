/**
 * Personas, membresias por condominio, roles e invitaciones.
 *
 * <p>Una persona tiene una sola cuenta en la plataforma y una membresia por condominio, con rol
 * propio en cada uno (Seguridad, seccion 2). Los roles por condominio viven aqui, nunca en el
 * token de Keycloak.
 *
 * <p>Referencia al condominio por identificador, no por entidad: este modulo no depende de
 * {@code condominium}.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"},
        displayName = "Identity")
package bo.coprop.identity;

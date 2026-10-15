/**
 * Bitacora inmutable de cambios y acciones, con retencion de un ano (Seguridad, secciones 8 y
 * 10.1).
 *
 * <p>Tabla append-only de verdad, no por convencion: un disparador en la base (migracion V9)
 * rechaza cualquier {@code UPDATE} o {@code DELETE} sobre ella, venga de la aplicacion o de un
 * {@code psql}. El unico camino por el que una fila desaparece es la purga del año, que es una
 * funcion declarada para eso.
 *
 * <p><strong>No depende de ningun modulo, y eso costo una decision.</strong> La idea de "solo
 * reacciona a eventos" choca con que para escuchar un evento hace falta su tipo: escuchar
 * {@code MembershipGranted} obligaria a depender de {@code identity}, y despues de
 * {@code condominium}, y de cada modulo que naciera. La salida es que lo que se escucha sea una
 * <strong>interfaz de {@code shared}</strong>, {@code EventoAuditable}: los dos lados miran a
 * {@code shared} y ninguno al otro.
 *
 * <p>Con eso, <strong>implementar la interfaz es la declaracion de que un evento es auditable</strong>.
 * No hay lista de eventos en este modulo, asi que sumar una accion a la bitacora no obliga a tocarlo
 * --que es lo que este {@code package-info} prometia desde el principio-- y quitarla tampoco.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"},
        displayName = "Audit")
package bo.coprop.audit;

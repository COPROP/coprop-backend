/**
 * Intents, QR, inbox de eventos del proveedor, registro y aplicacion de pagos, conciliacion.
 *
 * <p>Es el modulo que el analisis contempla extraer como servicio propio si hace falta (seccion
 * 9.1), asi que sus limites son los mas estrictos: hacia {@code billing} se comunica por eventos
 * y por puertos declarados, nunca tocando sus entidades.
 *
 * <p>Los adaptadores por proveedor viven en {@code bo.coprop.payments.providers}, interno a este
 * modulo: el resto de la aplicacion solo ve el puerto {@code PaymentProvider} y su
 * {@code ProviderCapabilities}. No hay un solo {@code if (banco == X)} fuera de ahi (seccion
 * 10.1).
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "billing", "condominium"},
        displayName = "Payments")
package bo.coprop.payments;

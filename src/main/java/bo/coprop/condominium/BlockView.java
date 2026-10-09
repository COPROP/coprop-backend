package bo.coprop.condominium;

import java.util.UUID;

/** Una torre o bloque. Es opcional: un condominio pequeño puede no tener ninguno. */
public record BlockView(UUID id, UUID condominiumId, String name) {}

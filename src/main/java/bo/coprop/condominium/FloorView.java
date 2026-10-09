package bo.coprop.condominium;

import java.util.UUID;

/**
 * Un piso, que cuelga de un bloque.
 *
 * <p>Cuelga del bloque y no del condominio porque asi lo modela el analisis 5.2. Tiene una
 * consecuencia que conviene conocer: para usar pisos hay que crear al menos un bloque, aunque sea
 * uno solo llamado "Torre unica".
 */
public record FloorView(UUID id, UUID blockId, int number) {}

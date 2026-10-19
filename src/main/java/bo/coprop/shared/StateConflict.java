package bo.coprop.shared;

/**
 * La operacion choca con el estado actual: una transicion que la maquina de estados no permite, o
 * una modificacion concurrente que perdio la carrera.
 */
public class StateConflict extends DomainError {

    public StateConflict(String mensaje) {
        super(ErrorCode.CONFLICTO, mensaje);
    }
}
